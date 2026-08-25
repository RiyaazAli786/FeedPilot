using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using FeedPilot.Api.Data;
using FeedPilot.Api.Domain;
using FeedPilot.Api.Dtos;
using FeedPilot.Api.Services;

namespace FeedPilot.Api.Controllers;

/// <summary>
/// Admin-side management of <see cref="PickedUsername"/> reservations. Lets an admin pre-assign a
/// generated username to one specific device+clone (e.g. to hand-place a known-good identity)
/// without going through the app's own pick flow — guarded the same way the app's flow is:
/// rejected if the username is already picked anywhere, by any device or clone.
/// </summary>
[ApiController]
[AdminSession]
[Route("api/admin/picked-usernames")]
public class AdminPickedUsernamesController : ControllerBase
{
    private readonly AppDbContext _db;
    public AdminPickedUsernamesController(AppDbContext db) => _db = db;

    [HttpGet]
    public async Task<ActionResult<PagedResponse<AdminPickedUsernameDto>>> List(
        [FromQuery] string? search, [FromQuery] int page = 1, [FromQuery] int pageSize = 25, CancellationToken ct = default)
    {
        page = Math.Max(1, page);
        pageSize = Math.Clamp(pageSize, 1, 200);

        var query = _db.PickedUsernames.AsNoTracking().AsQueryable();
        if (!string.IsNullOrWhiteSpace(search))
        {
            var term = search.Trim();
            query = query.Where(p =>
                p.Username.Contains(term) || p.DeviceId.Contains(term) || p.AppId.Contains(term));
        }
        query = query.OrderByDescending(p => p.PickedAt);

        var total = await query.CountAsync(ct);
        var items = await query
            .Skip((page - 1) * pageSize)
            .Take(pageSize)
            .Select(p => new AdminPickedUsernameDto(p.Id, p.Username, p.DeviceId, p.AppId, p.PickedAt))
            .ToListAsync(ct);

        return Ok(new PagedResponse<AdminPickedUsernameDto>(items, total, page, pageSize));
    }

    /// <summary>
    /// Reserves a username for one device+clone. Fails if that exact username is already picked
    /// anywhere — same uniqueness rule the app's own "pick" flow relies on, so an admin can never
    /// hand out an identity that collides with one already in use.
    /// </summary>
    [HttpPost("assign")]
    public async Task<ActionResult<object>> Assign(AdminAssignPickedUsernameRequest req, CancellationToken ct)
    {
        var cleanUsername = req.Username.Trim().TrimStart('@').ToLowerInvariant();
        var deviceId = req.DeviceId.Trim();
        var appId = string.IsNullOrWhiteSpace(req.AppId) ? "unknown" : req.AppId.Trim();

        if (string.IsNullOrWhiteSpace(cleanUsername))
            return BadRequest(new ApiError("Username is required.", "MISSING_USERNAME"));
        if (string.IsNullOrWhiteSpace(deviceId))
            return BadRequest(new ApiError("Device ID is required.", "MISSING_DEVICE_ID"));

        var existingPicks = await _db.PickedUsernames
            .Where(p => p.Username == cleanUsername)
            .OrderByDescending(p => p.PickedAt)
            .ToListAsync(ct);

        var existingForTarget = existingPicks.FirstOrDefault(
            p => p.DeviceId == deviceId && p.AppId == appId);
        if (existingForTarget is not null)
        {
            return Ok(new
            {
                message = $"'@{cleanUsername}' is already assigned to this device/clone.",
                id = existingForTarget.Id
            });
        }

        if (existingPicks.Count > 0 && !req.ForceReassign)
        {
            var current = existingPicks[0];
            return Conflict(new
            {
                message = $"'@{cleanUsername}' is already assigned to another device/clone.",
                code = "USERNAME_ALREADY_ASSIGNED",
                username = cleanUsername,
                assignedDeviceId = current.DeviceId,
                assignedAppId = current.AppId,
                assignedPickedAt = current.PickedAt
            });
        }

        if (existingPicks.Count > 0)
        {
            _db.PickedUsernames.RemoveRange(existingPicks);
        }

        var picked = new PickedUsername
        {
            Username = cleanUsername,
            DeviceId = deviceId,
            AppId = appId,
            PickedAt = DateTime.UtcNow
        };
        _db.PickedUsernames.Add(picked);
        await _db.SaveChangesAsync(ct);

        return Ok(new
        {
            message = existingPicks.Count > 0
                ? $"'@{cleanUsername}' reassigned to this device/clone."
                : $"'@{cleanUsername}' assigned to this device/clone.",
            id = picked.Id
        });
    }

    [HttpDelete("{id:guid}")]
    public async Task<ActionResult> Delete(Guid id, CancellationToken ct)
    {
        var picked = await _db.PickedUsernames.FirstOrDefaultAsync(p => p.Id == id, ct);
        if (picked is null) return NotFound(new ApiError("Not found.", "not_found"));
        _db.PickedUsernames.Remove(picked);
        await _db.SaveChangesAsync(ct);
        return NoContent();
    }
}
