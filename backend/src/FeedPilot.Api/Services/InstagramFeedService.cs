using System.Globalization;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;
using Microsoft.EntityFrameworkCore;
using FeedPilot.Api.Data;
using FeedPilot.Api.Domain;

namespace FeedPilot.Api.Services;

public interface IInstagramFeedService
{
    Task<InstagramJsonFeedResponse> FetchJsonFeedAsync(string username, int limit, CancellationToken ct);
    Task<InstagramFeedProfile?> FetchProfileSnapshotAsync(string username, string sessionCookies, CancellationToken ct);
}

public class InstagramFeedService : IInstagramFeedService
{
    private const string InstagramAppId = "936619743392459";
    private const string AsbdId = "359341";
    private const string UserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private const string MobileUserAgent =
        "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.135 Mobile Safari/537.36";
    private const string InstagramAppUserAgent =
        "Instagram 350.0.0.0.0 Android (34/14; 420dpi; 1080x2340; samsung; SM-S918B; dm3q; qcom; en_US; 600000000)";

    private readonly AppDbContext _db;
    private readonly IHttpClientFactory _http;
    private readonly ILogger<InstagramFeedService> _logger;

    public InstagramFeedService(AppDbContext db, IHttpClientFactory http, ILogger<InstagramFeedService> logger)
    {
        _db = db;
        _http = http;
        _logger = logger;
    }

    public async Task<InstagramJsonFeedResponse> FetchJsonFeedAsync(string username, int limit, CancellationToken ct)
    {
        var cleanUsername = NormalizeUsername(username);
        if (string.IsNullOrWhiteSpace(cleanUsername))
            throw new ArgumentException("Instagram username is required.", nameof(username));

        limit = Math.Clamp(limit, 1, 50);
        var session = await PickRandomSessionAsync(ct)
            ?? throw new InvalidOperationException("No active Instagram account session is available.");

        var profile = await FetchProfileAsync(cleanUsername, session, ct)
            ?? throw new InvalidOperationException($"Instagram profile @{cleanUsername} could not be resolved.");

        var posts = await FetchPostsAsync(profile.Id, profile.Username, session, limit, ct);
        if (posts.Count == 0)
        {
            posts = await FetchGraphqlTimelinePostsAsync(profile.Username, session, limit, ct);
        }
        return BuildJsonFeed(profile, posts);
    }

    public async Task<InstagramFeedProfile?> FetchProfileSnapshotAsync(
        string username,
        string sessionCookies,
        CancellationToken ct)
    {
        var cleanUsername = NormalizeUsername(username);
        if (string.IsNullOrWhiteSpace(cleanUsername) || string.IsNullOrWhiteSpace(sessionCookies))
            return null;

        var userId = ExtractCookie(sessionCookies, "ds_user_id");
        if (!string.IsNullOrWhiteSpace(userId))
        {
            var profileById = await FetchProfileByUserIdAsync(userId, cleanUsername, sessionCookies, ct);
            if (HasProfileSnapshot(profileById))
                return profileById;
        }

        var profileByUsername = await FetchProfileAsync(cleanUsername, sessionCookies, ct);
        return HasProfileSnapshot(profileByUsername) ? profileByUsername : null;
    }

    private async Task<string?> PickRandomSessionAsync(CancellationToken ct)
    {
        var sessions = await _db.Accounts
            .AsNoTracking()
            .Where(a => a.Status == AccountStatus.Active && a.SessionData != null && a.SessionData != "")
            .Select(a => a.SessionData!)
            .ToListAsync(ct);

        return sessions.Count == 0 ? null : sessions[Random.Shared.Next(sessions.Count)];
    }

    private sealed record ProfilePageSnapshot(
        string? ProfileId,
        string? Lsd,
        string? FbDtsg,
        string? ActorId,
        long OgFollowers,
        long OgFollowing,
        long OgPosts);

    private async Task<InstagramFeedProfile?> FetchProfileAsync(string username, string sessionCookies, CancellationToken ct)
    {
        var client = _http.CreateClient("Instagram");

        var searchUrl =
            $"https://www.instagram.com/api/v1/fbsearch/topsearch_flat/?query={Uri.EscapeDataString(username)}&context=blended";
        var searchProfile = await FetchProfileFromRequestAsync(
            client,
            CreateBlendedSearchGet(searchUrl, sessionCookies),
            username,
            ct);

        if (searchProfile is not null && (searchProfile.FollowerCount > 0 || searchProfile.FollowingCount > 0))
            return searchProfile;

        var pageSnapshot = await FetchProfilePageSnapshotAsync(client, username, sessionCookies, ct);
        var resolvedId = !string.IsNullOrWhiteSpace(searchProfile?.Id)
            ? searchProfile.Id
            : pageSnapshot?.ProfileId;

        if (!string.IsNullOrWhiteSpace(resolvedId))
        {
            var hoverProfile = await FetchHoverCardProfileAsync(client, resolvedId, username, sessionCookies, pageSnapshot, ct);
            if (hoverProfile is not null && (hoverProfile.FollowerCount > 0 || hoverProfile.FollowingCount > 0))
                return MergeProfile(hoverProfile, searchProfile);

            var fullProfile = await FetchProfileByUserIdAsync(resolvedId, username, sessionCookies, ct);
            if (fullProfile is not null)
                return MergeProfile(fullProfile, searchProfile);
        }

        if (searchProfile is not null || pageSnapshot is not null)
        {
            var baseProfile = searchProfile ?? new InstagramFeedProfile(
                Id: resolvedId ?? string.Empty,
                Username: username,
                FullName: string.Empty,
                Biography: string.Empty,
                ProfilePicUrl: string.Empty,
                FollowerCount: 0,
                FollowingCount: 0,
                MediaCount: 0,
                IsPrivate: false,
                IsVerified: false);

            if (pageSnapshot is not null &&
                (pageSnapshot.OgFollowers > 0 || pageSnapshot.OgFollowing > 0 || pageSnapshot.OgPosts > 0))
            {
                return baseProfile with
                {
                    FollowerCount = baseProfile.FollowerCount > 0 ? baseProfile.FollowerCount : pageSnapshot.OgFollowers,
                    FollowingCount = baseProfile.FollowingCount > 0 ? baseProfile.FollowingCount : pageSnapshot.OgFollowing,
                    MediaCount = baseProfile.MediaCount > 0 ? baseProfile.MediaCount : pageSnapshot.OgPosts
                };
            }

            if (searchProfile is not null) return searchProfile;
        }

        return null;
    }

    private static InstagramFeedProfile MergeProfile(InstagramFeedProfile primary, InstagramFeedProfile? fallback)
    {
        if (fallback is null) return primary;
        return primary with
        {
            Id = string.IsNullOrWhiteSpace(primary.Id) ? fallback.Id : primary.Id,
            Username = string.IsNullOrWhiteSpace(primary.Username) ? fallback.Username : primary.Username,
            FullName = string.IsNullOrWhiteSpace(primary.FullName) ? fallback.FullName : primary.FullName,
            Biography = string.IsNullOrWhiteSpace(primary.Biography) ? fallback.Biography : primary.Biography,
            ProfilePicUrl = string.IsNullOrWhiteSpace(primary.ProfilePicUrl) ? fallback.ProfilePicUrl : primary.ProfilePicUrl
        };
    }

    private async Task<ProfilePageSnapshot?> FetchProfilePageSnapshotAsync(
        HttpClient client,
        string username,
        string sessionCookies,
        CancellationToken ct)
    {
        try
        {
            var url = $"https://www.instagram.com/{Uri.EscapeDataString(username)}/";
            var (_, isSuccess, html) = await SendWithUserAgentFallbackAsync(
                client,
                ua => CreateDocumentGet(url, sessionCookies, ua),
                ct);
            if (!isSuccess || string.IsNullOrWhiteSpace(html)) return null;

            var profileId = ExtractFirstGroup(html, @"""profile_owner""\s*:\s*\{\s*""id""\s*:\s*""(\d+)""")
                ?? ExtractFirstGroup(html, @"""target_user_id""\s*:\s*""(\d+)""")
                ?? ExtractFirstGroup(html, @"""profile_id""\s*:\s*""([1-9]\d+)""");
            var lsd = ExtractFirstGroup(html, @"""LSD""\s*,\s*\[.*?\]\s*,\s*\{\s*""token""\s*:\s*""([^""]+)""");
            var fbDtsg = ExtractFirstGroup(html, @"""DTSGInitialData""\s*,\s*\[.*?\]\s*,\s*\{\s*""token""\s*:\s*""([^""]+)""");
            var actorId = ExtractFirstGroup(html, @"""actorID""\s*:\s*""(\d+)""");

            var (ogFollowers, ogFollowing, ogPosts) = ParseOgProfileStats(html);

            return new ProfilePageSnapshot(profileId, lsd, fbDtsg, actorId, ogFollowers, ogFollowing, ogPosts);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Profile page snapshot failed for {Username}", username);
            return null;
        }
    }

    private async Task<InstagramFeedProfile?> FetchHoverCardProfileAsync(
        HttpClient client,
        string userId,
        string username,
        string sessionCookies,
        ProfilePageSnapshot? page,
        CancellationToken ct)
    {
        if (string.IsNullOrWhiteSpace(userId) || !userId.All(char.IsDigit))
            return null;

        try
        {
            var formData = new Dictionary<string, string>
            {
                ["__d"] = "www",
                ["__user"] = "0",
                ["__a"] = "1",
                ["__comet_req"] = "7",
                ["__crn"] = "comet.igweb.PolarisProfilePostsTabRoute",
                ["fb_api_caller_class"] = "RelayModern",
                ["fb_api_req_friendly_name"] = "PolarisUserHoverCardContentV2Query",
                ["server_timestamps"] = "true",
                ["doc_id"] = "27756568060663620",
                ["variables"] = JsonSerializer.Serialize(new { userID = userId })
            };

            if (!string.IsNullOrWhiteSpace(page?.ActorId))
                formData["av"] = page.ActorId;
            if (!string.IsNullOrWhiteSpace(page?.Lsd))
                formData["lsd"] = page.Lsd;
            if (!string.IsNullOrWhiteSpace(page?.FbDtsg))
            {
                formData["fb_dtsg"] = page.FbDtsg;
                formData["jazoest"] = CreateJazoest(page.FbDtsg);
            }
            else
            {
                formData["jazoest"] = CreateJazoest(userId);
            }

            var (statusCode, isSuccess, body) = await SendWithUserAgentFallbackAsync(
                client,
                ua => CreateGraphqlPost(
                    "https://www.instagram.com/api/graphql",
                    username,
                    sessionCookies,
                    "PolarisUserHoverCardContentV2Query",
                    page?.Lsd,
                    null,
                    formData,
                    ua),
                ct);

            if (!isSuccess || string.IsNullOrWhiteSpace(body) || body.TrimStart().StartsWith("<"))
            {
                _logger.LogWarning("HoverCard request failed for {Username}/{UserId}: HTTP {StatusCode}", username, userId, statusCode);
                return null;
            }

            using var doc = JsonDocument.Parse(StripJsonPrefix(body));
            var userDict = TryGet(doc.RootElement, "data", "xig_user_by_igid_v2", "user_dict");
            return userDict is null ? null : ParseProfile(userDict.Value, username);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "HoverCard lookup failed for {Username}/{UserId}", username, userId);
            return null;
        }
    }

    private async Task<InstagramFeedProfile?> FetchProfileByUserIdAsync(
        string userId,
        string fallbackUsername,
        string sessionCookies,
        CancellationToken ct)
    {
        var client = _http.CreateClient("Instagram");
        var escapedUserId = Uri.EscapeDataString(userId);
        var urls = new[]
        {
            $"https://www.instagram.com/api/v1/users/{escapedUserId}/info/",
            $"https://i.instagram.com/api/v1/users/{escapedUserId}/info/"
        };

        foreach (var url in urls)
        {
            var profile = await FetchProfileFromUrlAsync(client, url, fallbackUsername, sessionCookies, ct);
            if (profile is not null) return profile;
        }

        return null;
    }

    private async Task<InstagramFeedProfile?> FetchProfileFromUrlAsync(
        HttpClient client,
        string url,
        string username,
        string sessionCookies,
        CancellationToken ct)
    {
        var (statusCode, isSuccess, body) = await SendWithUserAgentFallbackAsync(
            client,
            ua => CreateInstagramGet(url, sessionCookies, $"https://www.instagram.com/{username}/", ua),
            ct);

        return ParseProfileResponse(statusCode, isSuccess, body, username);
    }

    private async Task<InstagramFeedProfile?> FetchProfileFromRequestAsync(
        HttpClient client,
        HttpRequestMessage request,
        string username,
        CancellationToken ct)
    {
        using var req = request;
        using var response = await client.SendAsync(req, ct);
        var body = await ReadBodyAsync(response, ct);
        return ParseProfileResponse((int)response.StatusCode, response.IsSuccessStatusCode, body, username);
    }

    private InstagramFeedProfile? ParseProfileResponse(int statusCode, bool isSuccess, string body, string username)
    {
        if (!isSuccess || string.IsNullOrWhiteSpace(body) || body.TrimStart().StartsWith("<"))
        {
            _logger.LogWarning(
                "Instagram profile request failed for {Username}: HTTP {StatusCode}",
                username,
                statusCode);
            return null;
        }

        using var doc = JsonDocument.Parse(StripJsonPrefix(body));
        var root = doc.RootElement;
        var user = TryGet(root, "data", "user")
            ?? TryGet(root, "user")
            ?? TryFindMatchingUser(root, "users", username)
            ?? TryFindMatchingUser(root, "list", username)
            ?? TryGetFirstItem(root, "items")?.TryGetPropertyOrNull("user");

        return user is null ? null : ParseProfile(user.Value, username);
    }

    private static JsonElement? TryFindMatchingUser(JsonElement root, string propertyName, string username)
    {
        if (!root.TryGetProperty(propertyName, out var array) || array.ValueKind != JsonValueKind.Array)
            return null;

        foreach (var item in array.EnumerateArray())
        {
            var user = item.ValueKind == JsonValueKind.Object && item.TryGetProperty("user", out var u)
                ? u
                : item;

            if (user.ValueKind == JsonValueKind.Object &&
                user.TryGetProperty("username", out var uName) &&
                string.Equals(uName.GetString(), username, StringComparison.OrdinalIgnoreCase))
            {
                return user;
            }
        }

        return null;
    }

    private async Task<List<InstagramFeedPost>> FetchPostsAsync(
        string userId,
        string username,
        string sessionCookies,
        int limit,
        CancellationToken ct)
    {
        var client = _http.CreateClient("Instagram");
        var identifiers = new[] { userId, username }
            .Where(v => !string.IsNullOrWhiteSpace(v))
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToList();

        foreach (var identifier in identifiers)
        {
            var isNumeric = identifier.All(char.IsDigit);
            if (!isNumeric) continue;

            var url = $"https://www.instagram.com/api/v1/feed/user/{Uri.EscapeDataString(identifier)}/?count={limit}";
            var (statusCode, isSuccess, body) = await SendWithUserAgentFallbackAsync(
                client,
                ua => CreateInstagramGet(url, sessionCookies, $"https://www.instagram.com/{username}/", ua),
                ct);

            if (!isSuccess || string.IsNullOrWhiteSpace(body) || body.TrimStart().StartsWith("<"))
            {
                _logger.LogWarning(
                    "Instagram feed request failed for {Username}/{Identifier}: HTTP {StatusCode}",
                    username,
                    identifier,
                    statusCode);
                continue;
            }

            using var doc = JsonDocument.Parse(StripJsonPrefix(body));
            if (!doc.RootElement.TryGetProperty("items", out var items) || items.ValueKind != JsonValueKind.Array)
                continue;

            var posts = new List<InstagramFeedPost>();
            foreach (var item in items.EnumerateArray())
            {
                var post = ParsePost(item);
                if (!string.IsNullOrWhiteSpace(post.Id) || !string.IsNullOrWhiteSpace(post.Code))
                    posts.Add(post);
            }

            if (posts.Count > 0) return posts.OrderByDescending(p => p.TakenAt).Take(limit).ToList();
        }

        return new List<InstagramFeedPost>();
    }

    private async Task<List<InstagramFeedPost>> FetchGraphqlTimelinePostsAsync(
        string username,
        string sessionCookies,
        int limit,
        CancellationToken ct)
    {
        var client = _http.CreateClient("Instagram");
        var page = await FetchProfilePageSnapshotAsync(client, username, sessionCookies, ct);
        var variables = JsonSerializer.Serialize(new
        {
            after = (string?)null,
            before = (string?)null,
            data = new
            {
                count = Math.Clamp(limit, 1, 50),
                include_reel_media_seen_timestamp = true,
                include_relationship_info = true,
                latest_besties_reel_media = true,
                latest_reel_media = true
            },
            first = Math.Clamp(limit, 1, 50),
            include_multi_captions = true,
            last = (string?)null,
            username = username,
            __relay_internal__pv__PolarisMultiCaptionCarouselEnabledrelayprovider = true,
            __relay_internal__pv__PolarisShortDramaEnabledrelayprovider = true,
            __relay_internal__pv__PolarisReelsRecoDebugOverlayEnabledrelayprovider = false
        });

        var formData = new Dictionary<string, string>
        {
            ["__d"] = "www",
            ["__user"] = "0",
            ["__a"] = "1",
            ["__comet_req"] = "7",
            ["__crn"] = "comet.igweb.PolarisProfilePostsTabRoute",
            ["fb_api_caller_class"] = "RelayModern",
            ["fb_api_req_friendly_name"] = "PolarisProfilePostsTabContentQuery_connection",
            ["server_timestamps"] = "true",
            ["doc_id"] = "29240983615539641",
            ["variables"] = variables
        };

        if (!string.IsNullOrWhiteSpace(page?.ActorId))
            formData["av"] = page.ActorId;
        if (!string.IsNullOrWhiteSpace(page?.Lsd))
            formData["lsd"] = page.Lsd;
        if (!string.IsNullOrWhiteSpace(page?.FbDtsg))
        {
            formData["fb_dtsg"] = page.FbDtsg;
            formData["jazoest"] = CreateJazoest(page.FbDtsg);
        }

        var (statusCode, isSuccess, body) = await SendWithUserAgentFallbackAsync(
            client,
            ua => CreateGraphqlPost(
                "https://www.instagram.com/graphql/query",
                username,
                sessionCookies,
                "PolarisProfilePostsTabContentQuery_connection",
                page?.Lsd,
                "xdt_api__v1__feed__user_timeline_graphql_connection",
                formData,
                ua),
            ct);

        if (!isSuccess || string.IsNullOrWhiteSpace(body) || body.TrimStart().StartsWith("<"))
        {
            _logger.LogWarning(
                "Instagram GraphQL timeline request failed for {Username}: HTTP {StatusCode}",
                username,
                statusCode);
            return new List<InstagramFeedPost>();
        }

        using var doc = JsonDocument.Parse(StripJsonPrefix(body));
        var edges = TryGet(doc.RootElement, "data", "xdt_api__v1__feed__user_timeline_graphql_connection", "edges")
            ?? TryGet(doc.RootElement, "data", "user", "edge_owner_to_timeline_media", "edges");
        if (edges is null || edges.Value.ValueKind != JsonValueKind.Array)
            return new List<InstagramFeedPost>();

        var posts = new List<InstagramFeedPost>();
        foreach (var edge in edges.Value.EnumerateArray())
        {
            var node = TryGet(edge, "node");
            if (node is null) continue;
            var post = ParseTimelineNodePost(node.Value);
            if (!string.IsNullOrWhiteSpace(post.Id) || !string.IsNullOrWhiteSpace(post.Code))
                posts.Add(post);
        }

        return posts.OrderByDescending(p => p.TakenAt).Take(limit).ToList();
    }

    private async Task<(int StatusCode, bool IsSuccess, string Body)> SendWithUserAgentFallbackAsync(
        HttpClient client,
        Func<string, HttpRequestMessage> createRequest,
        CancellationToken ct)
    {
        using var desktopReq = createRequest(UserAgent);
        using var desktopRes = await client.SendAsync(desktopReq, ct);
        var body = await ReadBodyAsync(desktopRes, ct);

        // Android-captured sessions can be pinned to a mobile User-Agent and fail with 400 "useragent mismatch".
        if ((int)desktopRes.StatusCode == 400 &&
            body.Contains("useragent mismatch", StringComparison.OrdinalIgnoreCase))
        {
            using var mobileReq = createRequest(MobileUserAgent);
            using var mobileRes = await client.SendAsync(mobileReq, ct);
            var mobileBody = await ReadBodyAsync(mobileRes, ct);
            return ((int)mobileRes.StatusCode, mobileRes.IsSuccessStatusCode, mobileBody);
        }

        return ((int)desktopRes.StatusCode, desktopRes.IsSuccessStatusCode, body);
    }

    private static async Task<string> ReadBodyAsync(HttpResponseMessage response, CancellationToken ct)
    {
        var bytes = await response.Content.ReadAsByteArrayAsync(ct);
        return bytes.Length == 0 ? string.Empty : Encoding.UTF8.GetString(bytes);
    }

    private static HttpRequestMessage CreateBlendedSearchGet(string url, string sessionCookies)
    {
        var request = new HttpRequestMessage(HttpMethod.Get, url);
        request.Headers.TryAddWithoutValidation("User-Agent", InstagramAppUserAgent);
        request.Headers.TryAddWithoutValidation("Accept", "*/*");
        request.Headers.TryAddWithoutValidation("Accept-Language", "en-US,en;q=0.9");
        request.Headers.TryAddWithoutValidation("X-IG-App-ID", InstagramAppId);
        AddSessionHeaders(request, sessionCookies);
        return request;
    }

    private static HttpRequestMessage CreateDocumentGet(string url, string sessionCookies, string userAgent)
    {
        var request = new HttpRequestMessage(HttpMethod.Get, url);
        request.Headers.TryAddWithoutValidation("User-Agent", userAgent);
        request.Headers.TryAddWithoutValidation("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        request.Headers.TryAddWithoutValidation("Accept-Language", "en-US,en;q=0.9");
        request.Headers.TryAddWithoutValidation("Sec-Fetch-Dest", "document");
        request.Headers.TryAddWithoutValidation("Sec-Fetch-Mode", "navigate");
        request.Headers.TryAddWithoutValidation("Sec-Fetch-Site", "none");
        request.Headers.TryAddWithoutValidation("Sec-Fetch-User", "?1");
        request.Headers.TryAddWithoutValidation("Upgrade-Insecure-Requests", "1");
        if (!string.IsNullOrWhiteSpace(sessionCookies))
            request.Headers.TryAddWithoutValidation("Cookie", sessionCookies);
        return request;
    }

    private static HttpRequestMessage CreateGraphqlPost(
        string url,
        string username,
        string sessionCookies,
        string friendlyName,
        string? lsd,
        string? rootFieldName,
        Dictionary<string, string> formData,
        string userAgent)
    {
        var request = new HttpRequestMessage(HttpMethod.Post, url);
        request.Headers.TryAddWithoutValidation("User-Agent", userAgent);
        request.Headers.TryAddWithoutValidation("Accept", "*/*");
        request.Headers.TryAddWithoutValidation("Accept-Language", "en-US,en;q=0.9");
        request.Headers.TryAddWithoutValidation("X-IG-App-ID", InstagramAppId);
        request.Headers.TryAddWithoutValidation("X-ASBD-ID", AsbdId);
        request.Headers.TryAddWithoutValidation("X-FB-Friendly-Name", friendlyName);
        if (!string.IsNullOrWhiteSpace(rootFieldName))
            request.Headers.TryAddWithoutValidation("X-Root-Field-Name", rootFieldName);
        if (!string.IsNullOrWhiteSpace(lsd))
            request.Headers.TryAddWithoutValidation("X-FB-LSD", lsd);
        request.Headers.TryAddWithoutValidation("Origin", "https://www.instagram.com");
        request.Headers.TryAddWithoutValidation("Sec-Fetch-Site", "same-origin");
        request.Headers.TryAddWithoutValidation("Sec-Fetch-Mode", "cors");
        request.Headers.TryAddWithoutValidation("Sec-Fetch-Dest", "empty");
        request.Headers.Referrer = new Uri($"https://www.instagram.com/{username}/");
        AddSessionHeaders(request, sessionCookies);
        request.Content = new FormUrlEncodedContent(formData);
        return request;
    }

    private static HttpRequestMessage CreateInstagramGet(
        string url,
        string sessionCookies,
        string referer,
        string userAgent = UserAgent)
    {
        var request = new HttpRequestMessage(HttpMethod.Get, url);
        request.Headers.TryAddWithoutValidation("User-Agent", userAgent);
        request.Headers.TryAddWithoutValidation("Accept", "*/*");
        request.Headers.TryAddWithoutValidation("Accept-Language", "en-US,en;q=0.9");
        request.Headers.TryAddWithoutValidation("X-IG-App-ID", InstagramAppId);
        request.Headers.TryAddWithoutValidation("X-ASBD-ID", AsbdId);
        request.Headers.TryAddWithoutValidation("Sec-Fetch-Dest", "empty");
        request.Headers.TryAddWithoutValidation("Sec-Fetch-Mode", "cors");
        request.Headers.TryAddWithoutValidation("Sec-Fetch-Site", "same-origin");
        request.Headers.Referrer = new Uri(referer);
        AddSessionHeaders(request, sessionCookies);

        return request;
    }

    private static void AddSessionHeaders(HttpRequestMessage request, string sessionCookies)
    {
        if (string.IsNullOrWhiteSpace(sessionCookies)) return;
        request.Headers.TryAddWithoutValidation("Cookie", sessionCookies);
        request.Headers.TryAddWithoutValidation("X-IG-WWW-Claim", ExtractCookie(sessionCookies, "x-ig-www-claim") ?? "0");
        var csrf = ExtractCookie(sessionCookies, "csrftoken");
        if (!string.IsNullOrWhiteSpace(csrf))
            request.Headers.TryAddWithoutValidation("X-CSRFToken", csrf);
    }

    private static InstagramFeedProfile ParseProfile(JsonElement user, string fallbackUsername)
    {
        var username = GetString(user, "username") ?? fallbackUsername;
        return new InstagramFeedProfile(
            Id: GetString(user, "pk") ?? GetString(user, "pk_id") ?? GetString(user, "id") ?? string.Empty,
            Username: username,
            FullName: GetString(user, "full_name") ?? string.Empty,
            Biography: GetString(user, "biography") ?? string.Empty,
            ProfilePicUrl: GetString(user, "profile_pic_url_hd")
                ?? GetString(user, "profile_pic_url")
                ?? GetString(TryGet(user, "hd_profile_pic_url_info"), "url")
                ?? string.Empty,
            FollowerCount: GetCount(user, "edge_followed_by", "follower_count", "followers_count"),
            FollowingCount: GetCount(user, "edge_follow", "following_count", "follows_count"),
            MediaCount: GetCount(user, "edge_owner_to_timeline_media", "media_count"),
            IsPrivate: GetBool(user, "is_private"),
            IsVerified: GetBool(user, "is_verified"));
    }

    private static InstagramFeedPost ParsePost(JsonElement item)
    {
        var caption = TryGet(item, "caption") is { } captionObj
            ? GetString(captionObj, "text")
            : null;

        return new InstagramFeedPost(
            Id: GetString(item, "id") ?? string.Empty,
            Code: GetString(item, "code") ?? string.Empty,
            Caption: caption,
            MediaType: GetInt(item, "media_type", 1),
            DisplayUrl: TryGetFirstItem(TryGet(item, "image_versions2"), "candidates") is { } image
                ? GetString(image, "url")
                : null,
            VideoUrl: TryGetFirstItem(item, "video_versions") is { } video
                ? GetString(video, "url")
                : null,
            LikeCount: GetLong(item, "like_count"),
            CommentCount: GetLong(item, "comment_count"),
            RepostCount: GetLong(item, "media_repost_count", GetLong(item, "reshare_count", GetLong(item, "repost_count"))),
            TakenAt: GetLong(item, "taken_at"),
            MediaId: GetString(item, "pk") ?? (GetString(item, "id") ?? string.Empty).Split('_')[0],
            IsPinned: (TryGet(item, "timeline_pinned_user_ids") is { } pItem && pItem.ValueKind == JsonValueKind.Array && pItem.GetArrayLength() > 0) || GetBool(item, "is_pinned"));
    }

    private static InstagramFeedPost ParseTimelineNodePost(JsonElement node)
    {
        var caption = GetString(TryGet(node, "caption"), "text")
            ?? (TryGetFirstItem(TryGet(node, "edge_media_to_caption"), "edges") is { } captionEdge
                ? GetString(TryGet(captionEdge, "node"), "text")
                : null);

        var mediaType = GetInt(node, "media_type", 0);
        if (mediaType <= 0)
        {
            var typeName = GetString(node, "__typename") ?? string.Empty;
            mediaType = typeName switch
            {
                "GraphVideo" => 2,
                "GraphSidecar" => 8,
                _ when node.TryGetProperty("carousel_media", out _) => 8,
                _ when GetBool(node, "is_video") => 2,
                _ => 1
            };
        }

        var displayUrl = GetString(node, "display_url")
            ?? (TryGetFirstItem(TryGet(node, "image_versions2"), "candidates") is { } image ? GetString(image, "url") : null)
            ?? (TryGetFirstItem(node, "thumbnail_resources") is { } thumbnail ? GetString(thumbnail, "src") : null);

        var videoUrl = GetString(node, "video_url")
            ?? (TryGetFirstItem(node, "video_versions") is { } video ? GetString(video, "url") : null);

        var likeCount = GetLong(node, "like_count", -1);
        if (likeCount < 0)
            likeCount = TryGet(node, "edge_liked_by") is { } likes ? GetLong(likes, "count") : 0L;

        var commentCount = GetLong(node, "comment_count", -1);
        if (commentCount < 0)
            commentCount = TryGet(node, "edge_media_to_comment") is { } comments ? GetLong(comments, "count") : 0L;

        var takenAt = GetLong(node, "taken_at", GetLong(node, "taken_at_timestamp"));
        var id = GetString(node, "id") ?? string.Empty;
        var mediaId = GetString(node, "pk") ?? (string.IsNullOrWhiteSpace(id) ? string.Empty : id.Split('_')[0]);

        return new InstagramFeedPost(
            Id: id,
            Code: GetString(node, "code") ?? GetString(node, "shortcode") ?? string.Empty,
            Caption: caption,
            MediaType: mediaType,
            DisplayUrl: displayUrl,
            VideoUrl: videoUrl,
            LikeCount: likeCount,
            CommentCount: commentCount,
            RepostCount: GetLong(node, "media_repost_count", GetLong(node, "reshare_count", GetLong(node, "repost_count"))),
            TakenAt: takenAt,
            MediaId: mediaId,
            IsPinned: (TryGet(node, "timeline_pinned_user_ids") is { } p && p.ValueKind == JsonValueKind.Array && p.GetArrayLength() > 0) || GetBool(node, "is_pinned"));
    }

    private static string? ExtractFirstGroup(string input, string pattern)
    {
        var match = Regex.Match(input, pattern, RegexOptions.IgnoreCase);
        return match.Success && match.Groups.Count > 1 ? match.Groups[1].Value : null;
    }

    private static string CreateJazoest(string seed)
    {
        var sum = 0;
        foreach (var b in Encoding.ASCII.GetBytes(seed))
            sum += b;
        return $"2{sum}";
    }

    private static (long Followers, long Following, long Posts) ParseOgProfileStats(string html)
    {
        var ogDesc = ExtractFirstGroup(html, @"<meta[^>]+(?:property|name)=[""'](?:og:description|description)[""'][^>]+content=[""']([^""']+)[""']")
            ?? ExtractFirstGroup(html, @"<meta[^>]+content=[""']([^""']+)[""'][^>]+(?:property|name)=[""'](?:og:description|description)[""']");
        if (string.IsNullOrWhiteSpace(ogDesc)) return (0, 0, 0);

        var followers = ParseFormattedCount(ExtractFirstGroup(ogDesc, @"([0-9.,KMBm]+)\s+Followers?"));
        var following = ParseFormattedCount(ExtractFirstGroup(ogDesc, @"([0-9.,KMBm]+)\s+Following"));
        var posts = ParseFormattedCount(ExtractFirstGroup(ogDesc, @"([0-9.,KMBm]+)\s+Posts?"));
        return (followers, following, posts);
    }

    private static long ParseFormattedCount(string? value)
    {
        if (string.IsNullOrWhiteSpace(value)) return 0;
        var clean = value.Trim().ToUpperInvariant();
        var multiplier = 1.0;
        if (clean.EndsWith('B')) { multiplier = 1_000_000_000; clean = clean[..^1]; }
        else if (clean.EndsWith('M')) { multiplier = 1_000_000; clean = clean[..^1]; }
        else if (clean.EndsWith('K')) { multiplier = 1_000; clean = clean[..^1]; }
        else
        {
            var digits = clean.Replace(",", "").Replace(".", "");
            return long.TryParse(digits, NumberStyles.Integer, CultureInfo.InvariantCulture, out var exact) ? exact : 0;
        }

        clean = clean.Replace(",", "");
        return double.TryParse(clean, NumberStyles.Float, CultureInfo.InvariantCulture, out var scaled)
            ? (long)(scaled * multiplier)
            : 0;
    }

    private static InstagramJsonFeedResponse BuildJsonFeed(InstagramFeedProfile profile, List<InstagramFeedPost> posts)
    {
        var username = string.IsNullOrWhiteSpace(profile.Username) ? "instagram" : profile.Username;
        var homeUrl = $"https://www.instagram.com/{username}/";
        var title = string.IsNullOrWhiteSpace(profile.FullName) ? $"@{username}" : $"{profile.FullName} (@{username})";
        var description = $"{FormatFeedCount(profile.FollowerCount)} Followers" +
            (string.IsNullOrWhiteSpace(profile.Biography) ? string.Empty : $" - {profile.Biography.Trim()}");

        return new InstagramJsonFeedResponse(
            Version: "https://jsonfeed.org/version/1.1",
            Title: title,
            HomePageUrl: homeUrl,
            FeedUrl: homeUrl,
            Favicon: RewriteInstagramCdnHost(profile.ProfilePicUrl),
            Language: "en",
            Description: description,
            Items: posts.Select(post => BuildJsonFeedItem(post, username, homeUrl)).ToList());
    }

    private static InstagramJsonFeedItem BuildJsonFeedItem(InstagramFeedPost post, string username, string homeUrl)
    {
        var url = string.IsNullOrWhiteSpace(post.Code) ? homeUrl : $"https://www.instagram.com/p/{post.Code}/";
        var mediaUrl = post.DisplayUrl ?? post.VideoUrl;
        var content = !string.IsNullOrWhiteSpace(post.Caption)
            ? post.Caption
            : post.MediaType switch
            {
                2 => $"Instagram video by @{username}",
                8 => $"Instagram carousel by @{username}",
                _ => $"Instagram post by @{username}"
            };

        return new InstagramJsonFeedItem(
            Id: FirstNonBlank(post.MediaId, post.Id, post.Code, url),
            Url: url,
            Title: string.IsNullOrWhiteSpace(post.Code) ? "Post" : $"Post {post.Code}",
            ContentText: content,
            ContentHtml: string.IsNullOrWhiteSpace(mediaUrl) ? null : $"<div><img src=\"{mediaUrl}\" /></div>",
            Image: mediaUrl,
            DatePublished: post.TakenAt > 0
                ? DateTimeOffset.FromUnixTimeSeconds(post.TakenAt).UtcDateTime
                : DateTime.UtcNow,
            Authors: new List<InstagramJsonFeedAuthor> { new(username) },
            Attachments: string.IsNullOrWhiteSpace(mediaUrl)
                ? null
                : new List<InstagramJsonFeedAttachment> { new(mediaUrl) });
    }

    private static string NormalizeUsername(string username) =>
        username.Trim()
            .TrimStart('@')
            .Split('?', '#')[0]
            .Trim('/')
            .ToLowerInvariant();

    private static string StripJsonPrefix(string value) =>
        value.StartsWith("for (;;);", StringComparison.Ordinal) ? value["for (;;);".Length..] : value;

    private static JsonElement? TryGet(JsonElement? element, params string[] path)
    {
        if (element is null) return null;
        var current = element.Value;
        foreach (var name in path)
        {
            if (current.ValueKind != JsonValueKind.Object || !current.TryGetProperty(name, out current))
                return null;
        }
        return current;
    }

    private static JsonElement? TryGetFirstItem(JsonElement? element, string property)
    {
        if (element is null) return null;
        return TryGetFirstItem(element.Value, property);
    }

    private static JsonElement? TryGetFirstItem(JsonElement element, string property)
    {
        if (!element.TryGetProperty(property, out var array) || array.ValueKind != JsonValueKind.Array)
            return null;
        return array.GetArrayLength() > 0 ? array[0] : null;
    }

    private static string? GetString(JsonElement? element, string property) =>
        element is null ? null : GetString(element.Value, property);

    private static string? GetString(JsonElement element, string property)
    {
        if (!element.TryGetProperty(property, out var value)) return null;
        return value.ValueKind switch
        {
            JsonValueKind.String => value.GetString()?.Trim(),
            JsonValueKind.Number => value.GetRawText(),
            _ => null
        };
    }

    private static long GetCount(JsonElement user, string nestedCountProperty, params string[] directProperties)
    {
        foreach (var property in directProperties)
        {
            var value = GetLong(user, property, -1);
            if (value >= 0) return value;
        }

        var nested = TryGet(user, nestedCountProperty);
        return nested is null ? 0 : GetLong(nested.Value, "count");
    }

    private static long GetLong(JsonElement element, string property, long fallback = 0)
    {
        if (!element.TryGetProperty(property, out var value)) return fallback;
        return value.ValueKind switch
        {
            JsonValueKind.Number when value.TryGetInt64(out var number) => number,
            JsonValueKind.String when long.TryParse(value.GetString(), NumberStyles.Integer, CultureInfo.InvariantCulture, out var number) => number,
            _ => fallback
        };
    }

    private static int GetInt(JsonElement element, string property, int fallback = 0)
    {
        var value = GetLong(element, property, fallback);
        return value < int.MinValue || value > int.MaxValue ? fallback : (int)value;
    }

    private static bool GetBool(JsonElement element, string property) =>
        element.TryGetProperty(property, out var value) &&
        value.ValueKind == JsonValueKind.True ||
        element.TryGetProperty(property, out value) &&
        value.ValueKind == JsonValueKind.String &&
        bool.TryParse(value.GetString(), out var parsed) &&
        parsed;

    private static string? ExtractCookie(string cookies, string name)
    {
        foreach (var part in cookies.Split(';', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
        {
            var index = part.IndexOf('=');
            if (index <= 0) continue;
            if (part[..index].Equals(name, StringComparison.OrdinalIgnoreCase))
                return part[(index + 1)..];
        }
        return null;
    }

    private static string RewriteInstagramCdnHost(string? url) =>
        (url ?? string.Empty).Replace(
            "https://instagram.frpr5-1.fna.fbcdn.net",
            "https://scontent-mia5-1.cdninstagram.com",
            StringComparison.OrdinalIgnoreCase);

    private static string FormatFeedCount(long value) => value switch
    {
        >= 1_000_000 => (value / 1_000_000.0).ToString("0.#", CultureInfo.InvariantCulture) + "M",
        >= 1_000 => (value / 1_000.0).ToString("0.#", CultureInfo.InvariantCulture) + "K",
        _ => value.ToString(CultureInfo.InvariantCulture)
    };

    private static string FirstNonBlank(params string[] values) =>
        values.FirstOrDefault(v => !string.IsNullOrWhiteSpace(v)) ?? string.Empty;

    private static bool HasProfileSnapshot(InstagramFeedProfile? profile) =>
        profile is not null &&
        (!string.IsNullOrWhiteSpace(profile.ProfilePicUrl) ||
         profile.FollowerCount > 0 ||
         profile.FollowingCount > 0 ||
         profile.MediaCount > 0);
}

public sealed record InstagramFeedProfile(
    string Id,
    string Username,
    string FullName,
    string Biography,
    string ProfilePicUrl,
    long FollowerCount,
    long FollowingCount,
    long MediaCount,
    bool IsPrivate,
    bool IsVerified);

public sealed record InstagramFeedPost(
    string Id,
    string Code,
    string? Caption,
    int MediaType,
    string? DisplayUrl,
    string? VideoUrl,
    long LikeCount,
    long CommentCount,
    long RepostCount,
    long TakenAt,
    string MediaId,
    bool IsPinned = false);

public sealed record InstagramJsonFeedResponse(
    [property: JsonPropertyName("version")]
    string Version,
    [property: JsonPropertyName("title")]
    string Title,
    [property: JsonPropertyName("home_page_url")]
    string HomePageUrl,
    [property: JsonPropertyName("feed_url")]
    string FeedUrl,
    [property: JsonPropertyName("favicon")]
    string Favicon,
    [property: JsonPropertyName("language")]
    string Language,
    [property: JsonPropertyName("description")]
    string Description,
    [property: JsonPropertyName("items")]
    IReadOnlyList<InstagramJsonFeedItem> Items);

public sealed record InstagramJsonFeedItem(
    [property: JsonPropertyName("id")]
    string Id,
    [property: JsonPropertyName("url")]
    string Url,
    [property: JsonPropertyName("title")]
    string Title,
    [property: JsonPropertyName("content_text")]
    string ContentText,
    [property: JsonPropertyName("content_html")]
    string? ContentHtml,
    [property: JsonPropertyName("image")]
    string? Image,
    [property: JsonPropertyName("date_published")]
    DateTime DatePublished,
    [property: JsonPropertyName("authors")]
    IReadOnlyList<InstagramJsonFeedAuthor> Authors,
    [property: JsonPropertyName("attachments")]
    IReadOnlyList<InstagramJsonFeedAttachment>? Attachments);

public sealed record InstagramJsonFeedAuthor(
    [property: JsonPropertyName("name")]
    string Name);

public sealed record InstagramJsonFeedAttachment(
    [property: JsonPropertyName("url")]
    string Url);

internal static class JsonElementExtensions
{
    public static JsonElement? TryGetPropertyOrNull(this JsonElement element, string property) =>
        element.ValueKind == JsonValueKind.Object && element.TryGetProperty(property, out var value)
            ? value
            : null;
}
