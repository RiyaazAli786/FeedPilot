using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using FeedPilot.Api.Data;
using FeedPilot.Api.Domain;
using FeedPilot.Api.Dtos;
using FeedPilot.Api.Services;

namespace FeedPilot.Api.Controllers;

[ApiController]
[AdminSession]
[Route("api/admin/instagram-sessions")]
public class AdminInstagramSessionsController : ControllerBase
{
    private static readonly HashSet<string> BrowserCookieAllowList = new(StringComparer.OrdinalIgnoreCase)
    {
        "csrftoken",
        "datr",
        "ds_user_id",
        "ig_did",
        "mid",
        "rur",
        "sessionid",
        "shbid",
        "shbts",
        "wd"
    };

    private readonly AppDbContext _db;
    private readonly IInstagramFeedService _instagram;

    public AdminInstagramSessionsController(AppDbContext db, IInstagramFeedService instagram)
    {
        _db = db;
        _instagram = instagram;
    }

    [HttpGet]
    public async Task<ActionResult<PagedResult<AdminInstagramSessionDto>>> List(
        [FromQuery] string? search,
        [FromQuery] string? appId,
        [FromQuery] string? deviceId,
        [FromQuery] int page = 1,
        [FromQuery] int pageSize = 24,
        CancellationToken ct = default)
    {
        page = Math.Max(1, page);
        pageSize = Math.Clamp(pageSize, 1, 100);

        var query = _db.Accounts
            .AsNoTracking()
            .Include(a => a.User)
            .Where(a => a.SessionData != null && a.SessionData != "");

        if (!string.IsNullOrWhiteSpace(appId)) query = query.Where(a => a.AppId == appId);
        if (!string.IsNullOrWhiteSpace(deviceId)) query = query.Where(a => a.DeviceId == deviceId);
        if (!string.IsNullOrWhiteSpace(search))
        {
            var term = search.Trim();
            query = query.Where(a =>
                a.Username.Contains(term) ||
                (a.DeviceId != null && a.DeviceId.Contains(term)) ||
                (a.User != null && a.User.Email.Contains(term)));
        }

        var total = await query.CountAsync(ct);
        var accounts = await query
            .OrderByDescending(a => a.LastActive ?? a.LastLogin ?? a.CreatedAt)
            .Skip((page - 1) * pageSize)
            .Take(pageSize)
            .ToListAsync(ct);

        var items = new List<AdminInstagramSessionDto>();
        foreach (var account in accounts)
        {
            InstagramFeedProfile? profile = null;
            try
            {
                profile = await _instagram.FetchProfileSnapshotAsync(account.Username, account.SessionData ?? "", ct);
            }
            catch when (!ct.IsCancellationRequested)
            {
                // Keep the dashboard usable even when one saved Instagram session has expired.
            }

            var username = profile?.Username?.Trim('@') ?? account.Username;
            items.Add(new AdminInstagramSessionDto(
                account.Id,
                username,
                profile?.FullName ?? "",
                profile?.ProfilePicUrl ?? account.ProfilePictureUrl,
                profile?.FollowerCount ?? 0,
                profile?.FollowingCount ?? 0,
                profile?.MediaCount ?? 0,
                profile?.IsPrivate ?? false,
                profile?.IsVerified ?? false,
                account.AppId,
                account.DeviceId,
                account.User?.Email ?? "",
                account.LastLogin,
                account.LastActive,
                profile is not null
            ));
        }

        return Ok(new PagedResult<AdminInstagramSessionDto>(
            items,
            total,
            page,
            pageSize));
    }

    [HttpGet("{id:guid}/browser-cookies")]
    public async Task<ActionResult<AdminInstagramBrowserSessionDto>> BrowserCookies(Guid id, CancellationToken ct)
    {
        var account = await _db.Accounts.AsNoTracking().FirstOrDefaultAsync(a => a.Id == id, ct);
        if (account is null)
            return NotFound(new ApiError("Account not found."));

        if (string.IsNullOrWhiteSpace(account.SessionData))
            return BadRequest(new ApiError("This account has no saved session."));

        var cookies = ParseBrowserCookies(account.SessionData);
        if (cookies.Count == 0)
            return BadRequest(new ApiError("No Instagram browser cookies could be parsed from this session."));

        var username = account.Username.Trim().TrimStart('@');
        return Ok(new AdminInstagramBrowserSessionDto(
            account.Id,
            username,
            $"https://www.instagram.com/{Uri.EscapeDataString(username)}/",
            cookies));
    }

    private static List<AdminInstagramBrowserCookieDto> ParseBrowserCookies(string sessionData)
    {
        var expires = DateTimeOffset.UtcNow.AddDays(180).ToUnixTimeSeconds();
        var cookies = new List<AdminInstagramBrowserCookieDto>();

        foreach (var part in sessionData.Split(';', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
        {
            var index = part.IndexOf('=');
            if (index <= 0) continue;

            var name = part[..index].Trim();
            var value = part[(index + 1)..].Trim();
            if (string.IsNullOrWhiteSpace(name) || !BrowserCookieAllowList.Contains(name))
                continue;

            cookies.Add(new AdminInstagramBrowserCookieDto(
                name,
                value,
                ".instagram.com",
                "/",
                true,
                name.Equals("sessionid", StringComparison.OrdinalIgnoreCase),
                "no_restriction",
                expires));
        }

        return cookies
            .GroupBy(c => c.Name, StringComparer.OrdinalIgnoreCase)
            .Select(g => g.Last())
            .ToList();
    }
}
