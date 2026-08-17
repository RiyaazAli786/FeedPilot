using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using FeedPilot.Api.Data;
using FeedPilot.Api.Domain;
using FeedPilot.Api.Dtos;
using FeedPilot.Api.Services;

namespace FeedPilot.Api.Controllers;

[ApiController]
[Route("api/picked-usernames")]
public class PickedUsernameController : ControllerBase
{
    private readonly AppDbContext _db;

    public PickedUsernameController(AppDbContext db)
    {
        _db = db;
    }

    [HttpPost("pick")]
    public async Task<ActionResult<PickUsernameResponse>> PickUsername(
        [FromBody] PickUsernameRequest body, CancellationToken ct)
    {
        if (string.IsNullOrWhiteSpace(body.Username))
            return BadRequest(new ApiError("Username is required.", "MISSING_USERNAME"));

        var cleanUsername = body.Username.Trim().TrimStart('@').ToLowerInvariant();
        var deviceId = body.DeviceId?.Trim() ?? string.Empty;
        var appId = string.IsNullOrWhiteSpace(body.AppId) ? "unknown" : body.AppId.Trim();

        Guid? userId = null;
        if (User.Identity?.IsAuthenticated == true)
        {
            try { userId = User.GetUserId(); } catch { }
        }

        // Store every picked username for the (device, clone) pair without deleting previous
        // ones. AppId is part of the key so two clones on the same physical device (same
        // DeviceId/hardware id) each get their own pick, not a shared one.
        var existing = await _db.PickedUsernames.FirstOrDefaultAsync(
            p => p.Username == cleanUsername && p.DeviceId == deviceId && p.AppId == appId, ct);

        if (existing is null)
        {
            _db.PickedUsernames.Add(new PickedUsername
            {
                Username = cleanUsername,
                DeviceId = deviceId,
                AppId = appId,
                UserId = userId,
                PickedAt = DateTime.UtcNow
            });
            await _db.SaveChangesAsync(ct);
        }

        return Ok(new PickUsernameResponse(true, $"Username '@{cleanUsername}' successfully picked and reserved!"));
    }

    [HttpGet("check")]
    public async Task<ActionResult<CheckPickedUsernameResponse>> CheckPicked(
        [FromQuery] string username, [FromQuery] string? deviceId, CancellationToken ct)
    {
        if (string.IsNullOrWhiteSpace(username))
            return Ok(new CheckPickedUsernameResponse(false));

        var cleanUsername = username.Trim().TrimStart('@').ToLowerInvariant();

        var isPicked = await _db.PickedUsernames.AnyAsync(
            p => p.Username == cleanUsername, ct);

        return Ok(new CheckPickedUsernameResponse(isPicked));
    }

    [HttpGet]
    public async Task<ActionResult<PagedResponse<PickedUsernameDto>>> GetPicked(
        [FromQuery] string deviceId, [FromQuery] string? appId,
        [FromQuery] int page = 1, [FromQuery] int pageSize = 10, CancellationToken ct = default)
    {
        if (string.IsNullOrWhiteSpace(deviceId))
            return BadRequest(new ApiError("Device ID is required.", "MISSING_DEVICE_ID"));

        var cleanAppId = string.IsNullOrWhiteSpace(appId) ? "unknown" : appId.Trim();
        var query = _db.PickedUsernames
            .Where(p => p.DeviceId == deviceId && p.AppId == cleanAppId)
            .OrderByDescending(p => p.PickedAt);

        var totalCount = await query.CountAsync(ct);
        var items = await query
            .Skip((page - 1) * pageSize)
            .Take(pageSize)
            .Select(p => new PickedUsernameDto(p.Username, p.PickedAt))
            .ToListAsync(ct);

        return Ok(new PagedResponse<PickedUsernameDto>(items, totalCount, page, pageSize));
    }

    [HttpDelete("{username}")]
    public async Task<ActionResult> DeletePicked(
        [FromRoute] string username, [FromQuery] string deviceId, [FromQuery] string? appId, CancellationToken ct)
    {
        if (string.IsNullOrWhiteSpace(username) || string.IsNullOrWhiteSpace(deviceId))
            return BadRequest(new ApiError("Username and Device ID are required.", "INVALID_INPUT"));

        var cleanUsername = username.Trim().TrimStart('@').ToLowerInvariant();
        var cleanAppId = string.IsNullOrWhiteSpace(appId) ? "unknown" : appId.Trim();
        var existing = await _db.PickedUsernames.FirstOrDefaultAsync(
            p => p.Username == cleanUsername && p.DeviceId == deviceId && p.AppId == cleanAppId, ct);

        if (existing != null)
        {
            _db.PickedUsernames.Remove(existing);
            await _db.SaveChangesAsync(ct);
        }
        return Ok();
    }
}
