using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using FeedPilot.Api.Data;
using FeedPilot.Api.Domain;
using FeedPilot.Api.Dtos;
using FeedPilot.Api.Services;

namespace FeedPilot.Api.Controllers;

/// <summary>
/// Password reset queue for the dashboard. There is no mail delivery in this system, so an
/// admin sets the new password here and passes it to the user through whatever support
/// channel they already use.
/// </summary>
[ApiController]
[AdminSession]
[Route("api/admin/password-resets")]
public class AdminPasswordResetsController : ControllerBase
{
    private readonly AppDbContext _db;

    public AdminPasswordResetsController(AppDbContext db) => _db = db;

    [HttpGet]
    public async Task<ActionResult<PagedResult<PasswordResetRequestDto>>> List(
        [FromQuery] PasswordResetStatus? status,
        [FromQuery] string? appId,
        [FromQuery] string? deviceId,
        [FromQuery] int page = 1,
        [FromQuery] int pageSize = 25,
        CancellationToken ct = default)
    {
        page = Math.Max(1, page);
        pageSize = Math.Clamp(pageSize, 1, 200);

        var query = _db.PasswordResetRequests.AsQueryable();
        if (status.HasValue) query = query.Where(r => r.Status == status.Value);
        if (!string.IsNullOrWhiteSpace(appId)) query = query.Where(r => r.AppId == appId);
        if (!string.IsNullOrWhiteSpace(deviceId)) query = query.Where(r => r.DeviceId == deviceId);

        var total = await query.CountAsync(ct);
        var items = await query
            .OrderByDescending(r => r.RequestedAt)
            .Skip((page - 1) * pageSize)
            .Take(pageSize)
            .ToListAsync(ct);

        return Ok(new PagedResult<PasswordResetRequestDto>(
            items.Select(ToDto).ToList(), total, page, pageSize));
    }

    [HttpGet("stats")]
    public async Task<ActionResult<object>> Stats(CancellationToken ct)
    {
        var all = await _db.PasswordResetRequests.Select(r => new { r.Status, r.AppId }).ToListAsync(ct);
        return Ok(new
        {
            total = all.Count,
            pending = all.Count(r => r.Status == PasswordResetStatus.Pending),
            completed = all.Count(r => r.Status == PasswordResetStatus.Completed),
            rejected = all.Count(r => r.Status == PasswordResetStatus.Rejected),
            apps = all.Select(r => r.AppId).Distinct().OrderBy(a => a).ToList()
        });
    }

    /// <summary>
    /// Resolves a request. Passing a new password sets it and invalidates the refresh token,
    /// so any session still holding the old credentials cannot silently continue.
    /// </summary>
    [HttpPatch("{id:guid}")]
    public async Task<ActionResult<PasswordResetRequestDto>> Resolve(
        Guid id, [FromBody] ResolvePasswordResetRequest body, CancellationToken ct)
    {
        var request = await _db.PasswordResetRequests.FirstOrDefaultAsync(r => r.Id == id, ct);
        if (request is null) return NotFound(new ApiError("Reset request not found."));

        if (!string.IsNullOrWhiteSpace(body.NewPassword))
        {
            if (request.UserId is null)
                return BadRequest(new ApiError(
                    "No account exists for this address, so there is no password to set.",
                    "NO_SUCH_USER"));

            var user = await _db.Users.FirstOrDefaultAsync(u => u.Id == request.UserId, ct);
            if (user is null)
                return BadRequest(new ApiError("The account for this request no longer exists.", "NO_SUCH_USER"));

            user.PasswordHash = BCrypt.Net.BCrypt.HashPassword(body.NewPassword);
            user.RefreshToken = null;
            user.RefreshTokenExpiresAt = null;
        }
        else if (body.Status == PasswordResetStatus.Completed)
        {
            return BadRequest(new ApiError(
                "Provide the new password when completing a reset.", "PASSWORD_REQUIRED"));
        }

        if (body.AdminNote is not null) request.AdminNote = body.AdminNote;
        request.Status = body.Status;
        request.ProcessedAt = DateTime.UtcNow;

        await _db.SaveChangesAsync(ct);
        return Ok(ToDto(request));
    }

    private static PasswordResetRequestDto ToDto(PasswordResetRequest r) => new(
        r.Id, r.UserId, r.Email, r.AppId, r.DeviceId, r.Status, r.AdminNote,
        r.RequestedAt, r.ProcessedAt);
}
