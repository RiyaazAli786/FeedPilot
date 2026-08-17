using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using FeedPilot.Api.Data;
using FeedPilot.Api.Domain;
using FeedPilot.Api.Dtos;
using FeedPilot.Api.Services;

namespace FeedPilot.Api.Controllers;

[ApiController]
[Authorize]
[Route("api/watched-handles")]
public class WatchedHandlesController : ControllerBase
{
    private readonly AppDbContext _db;

    public WatchedHandlesController(AppDbContext db) => _db = db;

    [HttpGet]
    public async Task<ActionResult<IReadOnlyList<WatchedHandleDto>>> List(CancellationToken ct)
    {
        var userId = User.GetUserId();
        var identity = this.ClientIdentity();
        var handles = await _db.WatchedInstagramHandles
            .Where(h => h.UserId == userId && h.AppId == identity.AppId && h.DeviceId == identity.DeviceId)
            .OrderBy(h => h.Username)
            .Select(h => new WatchedHandleDto(
                h.Id,
                h.AppId,
                h.DeviceId,
                h.Username,
                h.ProfilePictureUrl,
                h.FullName,
                h.IsPrivate,
                h.FollowerCount,
                h.FollowingCount,
                h.MediaCount,
                h.WatchEnabled,
                h.PollIntervalMinutes,
                h.LastFetchedAt,
                h.CreatedAt,
                h.Posts.Count))
            .ToListAsync(ct);

        return Ok(handles);
    }

    [HttpPost]
    public async Task<ActionResult<WatchedHandleDto>> Create(CreateWatchedHandleRequest req, CancellationToken ct)
    {
        var userId = User.GetUserId();
        var identity = this.ClientIdentity();
        var username = NormalizeUsername(req.Username);
        if (string.IsNullOrWhiteSpace(username))
            return BadRequest(new ApiError("Instagram handle is required.", "INVALID_HANDLE"));

        var existing = await _db.WatchedInstagramHandles
            .Include(h => h.Posts)
            .FirstOrDefaultAsync(h =>
                h.UserId == userId &&
                h.AppId == identity.AppId &&
                h.DeviceId == identity.DeviceId &&
                h.Username.ToLower() == username, ct);

        if (existing is not null)
            return Ok(ToDto(existing));

        var handle = new WatchedInstagramHandle
        {
            UserId = userId,
            AppId = identity.AppId,
            DeviceId = identity.DeviceId,
            Username = username,
            PollIntervalMinutes = req.PollIntervalMinutes,
            WatchEnabled = req.WatchEnabled
        };

        _db.WatchedInstagramHandles.Add(handle);
        await _db.SaveChangesAsync(ct);

        return CreatedAtAction(nameof(Get), new { id = handle.Id }, ToDto(handle));
    }

    [HttpGet("{id:guid}")]
    public async Task<ActionResult<WatchedHandleDto>> Get(Guid id, CancellationToken ct)
    {
        var handle = await OwnHandle(id)
            .Include(h => h.Posts)
            .FirstOrDefaultAsync(ct);

        return handle is null ? NotFound() : Ok(ToDto(handle));
    }

    [HttpPatch("{id:guid}")]
    public async Task<ActionResult<WatchedHandleDto>> Update(Guid id, UpdateWatchedHandleRequest req, CancellationToken ct)
    {
        var handle = await OwnHandle(id)
            .Include(h => h.Posts)
            .FirstOrDefaultAsync(ct);
        if (handle is null) return NotFound();

        if (req.PollIntervalMinutes is { } interval) handle.PollIntervalMinutes = interval;
        if (req.WatchEnabled is { } enabled) handle.WatchEnabled = enabled;
        if (req.ProfilePictureUrl is not null) handle.ProfilePictureUrl = BlankToNull(req.ProfilePictureUrl);
        if (req.FullName is not null) handle.FullName = BlankToNull(req.FullName);
        if (req.IsPrivate is { } isPrivate) handle.IsPrivate = isPrivate;
        if (req.FollowerCount is { } followers) handle.FollowerCount = Math.Max(0, followers);
        if (req.FollowingCount is { } following) handle.FollowingCount = Math.Max(0, following);
        if (req.MediaCount is { } mediaCount) handle.MediaCount = Math.Max(0, mediaCount);
        handle.UpdatedAt = DateTime.UtcNow;

        await _db.SaveChangesAsync(ct);
        return Ok(ToDto(handle));
    }

    [HttpDelete("{id:guid}")]
    public async Task<IActionResult> Delete(Guid id, CancellationToken ct)
    {
        var handle = await OwnHandle(id).FirstOrDefaultAsync(ct);
        if (handle is null) return NotFound();

        _db.WatchedInstagramHandles.Remove(handle);
        await _db.SaveChangesAsync(ct);
        return NoContent();
    }

    [HttpGet("{id:guid}/posts")]
    public async Task<ActionResult<IReadOnlyList<WatchedPostDto>>> Posts(Guid id, [FromQuery] int limit = 50, CancellationToken ct = default)
    {
        var exists = await OwnHandle(id).AnyAsync(ct);
        if (!exists) return NotFound();

        var cappedLimit = Math.Clamp(limit, 1, 200);
        var posts = await _db.WatchedInstagramPosts
            .Where(p => p.WatchedInstagramHandleId == id)
            .OrderByDescending(p => p.TakenAt ?? p.FetchedAt)
            .Take(cappedLimit)
            .Select(p => new WatchedPostDto(
                p.Id,
                p.WatchedInstagramHandleId,
                p.PostId,
                p.Code,
                p.Caption,
                p.MediaUrl,
                p.Permalink,
                p.MediaType,
                p.LikeCount,
                p.CommentCount,
                p.TakenAt,
                p.FetchedAt))
            .ToListAsync(ct);

        return Ok(posts);
    }

    [HttpPost("{id:guid}/feed")]
    public async Task<ActionResult<IReadOnlyList<WatchedPostDto>>> SaveFeed(Guid id, SaveWatchedFeedRequest req, CancellationToken ct)
    {
        var handle = await OwnHandle(id)
            .Include(h => h.Posts)
            .FirstOrDefaultAsync(ct);
        if (handle is null) return NotFound();

        if (req.ProfilePictureUrl is not null) handle.ProfilePictureUrl = BlankToNull(req.ProfilePictureUrl);
        if (req.FullName is not null) handle.FullName = BlankToNull(req.FullName);
        if (req.IsPrivate is { } isPrivate) handle.IsPrivate = isPrivate;
        if (req.FollowerCount is { } followers) handle.FollowerCount = Math.Max(0, followers);
        if (req.FollowingCount is { } following) handle.FollowingCount = Math.Max(0, following);
        if (req.MediaCount is { } mediaCount) handle.MediaCount = Math.Max(0, mediaCount);

        var now = DateTime.UtcNow;
        handle.LastFetchedAt = now;
        handle.UpdatedAt = now;

        var incoming = req.Posts
            .Where(p => !string.IsNullOrWhiteSpace(p.PostId))
            .GroupBy(p => p.PostId.Trim(), StringComparer.OrdinalIgnoreCase)
            .Select(g => g.First())
            .ToList();

        var ids = incoming.Select(p => p.PostId.Trim()).ToList();
        var existing = await _db.WatchedInstagramPosts
            .Where(p => p.WatchedInstagramHandleId == id && ids.Contains(p.PostId))
            .ToDictionaryAsync(p => p.PostId, StringComparer.OrdinalIgnoreCase, ct);

        foreach (var item in incoming)
        {
            var postId = item.PostId.Trim();
            if (!existing.TryGetValue(postId, out var post))
            {
                post = new WatchedInstagramPost
                {
                    WatchedInstagramHandleId = id,
                    PostId = postId
                };
                _db.WatchedInstagramPosts.Add(post);
            }

            post.Code = BlankToNull(item.Code);
            post.Caption = BlankToNull(item.Caption);
            post.MediaUrl = BlankToNull(item.MediaUrl);
            post.Permalink = BlankToNull(item.Permalink) ?? BuildPermalink(item.Code);
            post.MediaType = item.MediaType;
            post.LikeCount = item.LikeCount;
            post.CommentCount = item.CommentCount;
            post.TakenAt = item.TakenAt;
            post.FetchedAt = now;
        }

        await _db.SaveChangesAsync(ct);

        var saved = await _db.WatchedInstagramPosts
            .Where(p => p.WatchedInstagramHandleId == id && ids.Contains(p.PostId))
            .OrderByDescending(p => p.TakenAt ?? p.FetchedAt)
            .Select(p => new WatchedPostDto(
                p.Id,
                p.WatchedInstagramHandleId,
                p.PostId,
                p.Code,
                p.Caption,
                p.MediaUrl,
                p.Permalink,
                p.MediaType,
                p.LikeCount,
                p.CommentCount,
                p.TakenAt,
                p.FetchedAt))
            .ToListAsync(ct);

        return Ok(saved);
    }

    private IQueryable<WatchedInstagramHandle> OwnHandle(Guid id)
    {
        var userId = User.GetUserId();
        var identity = this.ClientIdentity();
        return _db.WatchedInstagramHandles.Where(h =>
            h.Id == id &&
            h.UserId == userId &&
            h.AppId == identity.AppId &&
            h.DeviceId == identity.DeviceId);
    }

    private static WatchedHandleDto ToDto(WatchedInstagramHandle handle) =>
        new(
            handle.Id,
            handle.AppId,
            handle.DeviceId,
            handle.Username,
            handle.ProfilePictureUrl,
            handle.FullName,
            handle.IsPrivate,
            handle.FollowerCount,
            handle.FollowingCount,
            handle.MediaCount,
            handle.WatchEnabled,
            handle.PollIntervalMinutes,
            handle.LastFetchedAt,
            handle.CreatedAt,
            handle.Posts.Count);

    private static string NormalizeUsername(string username) =>
        username.Trim().TrimStart('@').ToLowerInvariant();

    private static string? BlankToNull(string? value) =>
        string.IsNullOrWhiteSpace(value) ? null : value.Trim();

    private static string? BuildPermalink(string? code) =>
        string.IsNullOrWhiteSpace(code) ? null : $"https://www.instagram.com/p/{code.Trim()}/";
}
