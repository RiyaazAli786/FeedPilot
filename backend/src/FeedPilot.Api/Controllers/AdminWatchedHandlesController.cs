using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using FeedPilot.Api.Data;
using FeedPilot.Api.Dtos;
using FeedPilot.Api.Services;

namespace FeedPilot.Api.Controllers;

[ApiController]
[AdminSession]
[Route("api/admin/watched-handles")]
public class AdminWatchedHandlesController : ControllerBase
{
    private readonly AppDbContext _db;

    public AdminWatchedHandlesController(AppDbContext db) => _db = db;

    [HttpGet("devices")]
    public async Task<ActionResult<IReadOnlyList<AdminWatchedHandleDeviceDto>>> Devices(
        [FromQuery] string? appId,
        [FromQuery] string? deviceId,
        [FromQuery] string? search,
        CancellationToken ct)
    {
        var query = _db.WatchedInstagramHandles
            .AsNoTracking()
            .Include(h => h.Posts)
            .AsQueryable();

        if (!string.IsNullOrWhiteSpace(appId)) query = query.Where(h => h.AppId == appId);
        if (!string.IsNullOrWhiteSpace(deviceId)) query = query.Where(h => h.DeviceId == deviceId);
        if (!string.IsNullOrWhiteSpace(search))
        {
            var term = search.Trim();
            query = query.Where(h =>
                h.Username.Contains(term) ||
                h.AppId.Contains(term) ||
                h.DeviceId.Contains(term) ||
                (h.FullName != null && h.FullName.Contains(term)));
        }

        var handles = await query
            .OrderBy(h => h.AppId)
            .ThenBy(h => h.DeviceId)
            .ThenBy(h => h.Username)
            .ToListAsync(ct);

        var deviceKeys = handles
            .Select(h => new { h.AppId, h.DeviceId })
            .Distinct()
            .ToList();

        var devices = await _db.Devices
            .AsNoTracking()
            .Where(d => deviceKeys.Select(k => k.AppId).Contains(d.AppId) &&
                        deviceKeys.Select(k => k.DeviceId).Contains(d.DeviceId))
            .Select(d => new { d.AppId, d.DeviceId, d.LastSeenAt })
            .ToListAsync(ct);

        var result = handles
            .GroupBy(h => new { h.AppId, h.DeviceId })
            .Select(group =>
            {
                var lastSeen = devices
                    .Where(d => d.AppId == group.Key.AppId && d.DeviceId == group.Key.DeviceId)
                    .Select(d => d.LastSeenAt)
                    .OrderByDescending(x => x)
                    .FirstOrDefault();

                return new AdminWatchedHandleDeviceDto(
                    group.Key.AppId,
                    group.Key.DeviceId,
                    group.Count(),
                    group.Count(h => h.WatchEnabled),
                    group.Select(h => h.LastFetchedAt).Where(x => x != null).OrderByDescending(x => x).FirstOrDefault(),
                    lastSeen,
                    group.Select(h => new AdminWatchedHandleDto(
                        h.Id,
                        h.Username,
                        h.FullName,
                        h.WatchEnabled,
                        h.PollIntervalMinutes,
                        h.LastFetchedAt,
                        h.Posts.Count,
                        h.CreatedAt)).ToList());
            })
            .OrderByDescending(d => d.MonitoringCount)
            .ThenBy(d => d.AppId)
            .ThenBy(d => d.DeviceId)
            .ToList();

        return Ok(result);
    }
}
