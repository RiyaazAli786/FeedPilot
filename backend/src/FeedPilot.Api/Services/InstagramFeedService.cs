using System.Globalization;
using System.Text.Json;
using System.Text.Json.Serialization;
using Microsoft.EntityFrameworkCore;
using FeedPilot.Api.Data;
using FeedPilot.Api.Domain;

namespace FeedPilot.Api.Services;

public interface IInstagramFeedService
{
    Task<InstagramJsonFeedResponse> FetchJsonFeedAsync(string username, int limit, CancellationToken ct);
}

public class InstagramFeedService : IInstagramFeedService
{
    private const string InstagramAppId = "936619743392459";
    private const string AsbdId = "129477";
    private const string UserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

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
            posts = await FetchWebProfileTimelinePostsAsync(profile.Username, session, limit, ct);
        }
        return BuildJsonFeed(profile, posts);
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

    private async Task<InstagramFeedProfile?> FetchProfileAsync(string username, string sessionCookies, CancellationToken ct)
    {
        var client = _http.CreateClient("Instagram");
        var webProfileUrl =
            $"https://www.instagram.com/api/v1/users/web_profile_info/?username={Uri.EscapeDataString(username)}";

        var profile = await FetchProfileFromUrlAsync(client, webProfileUrl, username, sessionCookies, ct);
        if (profile is not null) return profile;

        var feedProfileUrl =
            $"https://www.instagram.com/api/v1/feed/user/{Uri.EscapeDataString(username)}/username/?count=1";
        return await FetchProfileFromUrlAsync(client, feedProfileUrl, username, sessionCookies, ct);
    }

    private async Task<InstagramFeedProfile?> FetchProfileFromUrlAsync(
        HttpClient client,
        string url,
        string username,
        string sessionCookies,
        CancellationToken ct)
    {
        using var request = CreateInstagramGet(url, sessionCookies, $"https://www.instagram.com/{username}/");
        using var response = await client.SendAsync(request, ct);
        var body = await response.Content.ReadAsStringAsync(ct);

        if (!response.IsSuccessStatusCode || string.IsNullOrWhiteSpace(body) || body.TrimStart().StartsWith("<"))
        {
            _logger.LogWarning(
                "Instagram profile request failed for {Username}: HTTP {StatusCode}",
                username,
                (int)response.StatusCode);
            return null;
        }

        using var doc = JsonDocument.Parse(StripJsonPrefix(body));
        var root = doc.RootElement;
        var user = TryGet(root, "data", "user")
            ?? TryGet(root, "user")
            ?? TryGetFirstItem(root, "items")?.TryGetPropertyOrNull("user");

        return user is null ? null : ParseProfile(user.Value, username);
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
            var url = $"https://www.instagram.com/api/v1/feed/user/{Uri.EscapeDataString(identifier)}/username/?count={limit}";
            using var request = CreateInstagramGet(url, sessionCookies, $"https://www.instagram.com/{username}/");
            using var response = await client.SendAsync(request, ct);
            var body = await response.Content.ReadAsStringAsync(ct);

            if (!response.IsSuccessStatusCode || string.IsNullOrWhiteSpace(body) || body.TrimStart().StartsWith("<"))
            {
                _logger.LogWarning(
                    "Instagram feed request failed for {Username}/{Identifier}: HTTP {StatusCode}",
                    username,
                    identifier,
                    (int)response.StatusCode);
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

            if (posts.Count > 0) return posts;
        }

        return new List<InstagramFeedPost>();
    }

    private async Task<List<InstagramFeedPost>> FetchWebProfileTimelinePostsAsync(
        string username,
        string sessionCookies,
        int limit,
        CancellationToken ct)
    {
        var client = _http.CreateClient("Instagram");
        var url = $"https://www.instagram.com/api/v1/users/web_profile_info/?username={Uri.EscapeDataString(username)}";
        using var request = CreateInstagramGet(url, sessionCookies, $"https://www.instagram.com/{username}/");
        using var response = await client.SendAsync(request, ct);
        var body = await response.Content.ReadAsStringAsync(ct);

        if (!response.IsSuccessStatusCode || string.IsNullOrWhiteSpace(body) || body.TrimStart().StartsWith("<"))
        {
            _logger.LogWarning(
                "Instagram web profile timeline fallback failed for {Username}: HTTP {StatusCode}",
                username,
                (int)response.StatusCode);
            return new List<InstagramFeedPost>();
        }

        using var doc = JsonDocument.Parse(StripJsonPrefix(body));
        var edges = TryGet(doc.RootElement, "data", "user", "edge_owner_to_timeline_media", "edges");
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
            if (posts.Count >= limit) break;
        }

        return posts;
    }

    private static HttpRequestMessage CreateInstagramGet(string url, string sessionCookies, string referer)
    {
        var request = new HttpRequestMessage(HttpMethod.Get, url);
        request.Headers.TryAddWithoutValidation("User-Agent", UserAgent);
        request.Headers.TryAddWithoutValidation("Accept", "*/*");
        request.Headers.TryAddWithoutValidation("Accept-Language", "en-US,en;q=0.9");
        request.Headers.TryAddWithoutValidation("X-IG-App-ID", InstagramAppId);
        request.Headers.TryAddWithoutValidation("X-ASBD-ID", AsbdId);
        request.Headers.TryAddWithoutValidation("X-Requested-With", "XMLHttpRequest");
        request.Headers.TryAddWithoutValidation("Sec-Fetch-Dest", "empty");
        request.Headers.TryAddWithoutValidation("Sec-Fetch-Mode", "cors");
        request.Headers.TryAddWithoutValidation("Sec-Fetch-Site", "same-origin");
        request.Headers.Referrer = new Uri(referer);

        if (!string.IsNullOrWhiteSpace(sessionCookies))
        {
            request.Headers.TryAddWithoutValidation("Cookie", sessionCookies);
            request.Headers.TryAddWithoutValidation("X-IG-WWW-Claim", ExtractCookie(sessionCookies, "x-ig-www-claim") ?? "0");
            var csrf = ExtractCookie(sessionCookies, "csrftoken");
            if (!string.IsNullOrWhiteSpace(csrf))
                request.Headers.TryAddWithoutValidation("X-CSRFToken", csrf);
        }

        return request;
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
            MediaId: GetString(item, "pk") ?? (GetString(item, "id") ?? string.Empty).Split('_')[0]);
    }

    private static InstagramFeedPost ParseTimelineNodePost(JsonElement node)
    {
        var caption = TryGetFirstItem(TryGet(node, "edge_media_to_caption"), "edges") is { } captionEdge
            ? GetString(TryGet(captionEdge, "node"), "text")
            : null;

        var typeName = GetString(node, "__typename") ?? string.Empty;
        var mediaType = typeName switch
        {
            "GraphVideo" => 2,
            "GraphSidecar" => 8,
            _ => 1
        };

        return new InstagramFeedPost(
            Id: GetString(node, "id") ?? string.Empty,
            Code: GetString(node, "shortcode") ?? GetString(node, "code") ?? string.Empty,
            Caption: caption,
            MediaType: mediaType,
            DisplayUrl: GetString(node, "display_url")
                ?? (TryGetFirstItem(node, "thumbnail_resources") is { } thumbnail ? GetString(thumbnail, "src") : null),
            VideoUrl: GetString(node, "video_url"),
            LikeCount: TryGet(node, "edge_liked_by") is { } likes ? GetLong(likes, "count") : 0L,
            CommentCount: TryGet(node, "edge_media_to_comment") is { } comments ? GetLong(comments, "count") : 0L,
            RepostCount: 0L,
            TakenAt: GetLong(node, "taken_at_timestamp"),
            MediaId: GetString(node, "id") ?? string.Empty);
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
    string MediaId);

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
