using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using FeedPilot.Api.Data;
using FeedPilot.Api.Domain;
using FeedPilot.Api.Dtos;

namespace FeedPilot.Api.Controllers;

[ApiController]
[Authorize]
[Route("api/instagram/resolve-cache")]
public class InstagramCacheController : ControllerBase
{
    private readonly AppDbContext _db;

    public InstagramCacheController(AppDbContext db)
    {
        _db = db;
    }

    [HttpGet]
    public async Task<ActionResult<ResolveCacheResponse>> Get([FromQuery] string username, CancellationToken ct)
    {
        if (string.IsNullOrWhiteSpace(username))
        {
            return BadRequest(new ApiError("Username cannot be empty."));
        }

        var normalizedUsername = username.Trim().ToLowerInvariant();
        var entry = await _db.InstagramUserIdCaches
            .FirstOrDefaultAsync(c => c.Username == normalizedUsername, ct);

        if (entry is null)
        {
            return NotFound(new ApiError("Username not found in cache."));
        }

        return Ok(new ResolveCacheResponse(entry.Username, entry.UserId));
    }

    [HttpPost]
    public async Task<ActionResult<ResolveCacheResponse>> Save([FromBody] ResolveCacheRequest body, CancellationToken ct)
    {
        if (string.IsNullOrWhiteSpace(body.Username) || string.IsNullOrWhiteSpace(body.UserId))
        {
            return BadRequest(new ApiError("Username and UserId are required."));
        }

        var normalizedUsername = body.Username.Trim().ToLowerInvariant();
        var normalizedUserId = body.UserId.Trim();
        if (!normalizedUserId.All(char.IsDigit))
        {
            return BadRequest(new ApiError("UserId must be numeric."));
        }

        var entry = await _db.InstagramUserIdCaches
            .FirstOrDefaultAsync(c => c.Username == normalizedUsername, ct);

        if (entry is null)
        {
            entry = new InstagramUserIdCache
            {
                Username = normalizedUsername,
                UserId = normalizedUserId
            };
            _db.InstagramUserIdCaches.Add(entry);
        }
        else
        {
            entry.UserId = normalizedUserId;
            entry.CreatedAt = DateTime.UtcNow;
        }

        try
        {
            await _db.SaveChangesAsync(ct);
        }
        catch (DbUpdateException)
        {
            _db.ChangeTracker.Clear();
            var existing = await _db.InstagramUserIdCaches
                .AsNoTracking()
                .FirstOrDefaultAsync(c => c.Username == normalizedUsername, ct);

            if (existing != null)
            {
                return Ok(new ResolveCacheResponse(existing.Username, existing.UserId));
            }
            throw;
        }

        return Ok(new ResolveCacheResponse(entry.Username, entry.UserId));
    }
}
