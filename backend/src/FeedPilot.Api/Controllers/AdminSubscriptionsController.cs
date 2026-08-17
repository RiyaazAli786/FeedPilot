using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using FeedPilot.Api.Data;
using FeedPilot.Api.Domain;
using FeedPilot.Api.Dtos;
using FeedPilot.Api.Services;

namespace FeedPilot.Api.Controllers;

/// <summary>
/// Subscription-purchase moderation for the dashboard. An admin checks that the submitted UTR
/// matches a real UPI payment, then approves — which is the only thing that activates a plan.
/// </summary>
[ApiController]
[AdminSession]
[Route("api/admin/subscriptions")]
public class AdminSubscriptionsController : ControllerBase
{
    private readonly AppDbContext _db;

    public AdminSubscriptionsController(AppDbContext db) => _db = db;

    [HttpGet]
    public async Task<ActionResult<PagedResult<AdminSubscriptionDto>>> List(
        [FromQuery] SubscriptionRequestStatus? status,
        [FromQuery] string? appId,
        [FromQuery] string? deviceId,
        [FromQuery] int page = 1,
        [FromQuery] int pageSize = 25,
        CancellationToken ct = default)
    {
        page = Math.Max(1, page);
        pageSize = Math.Clamp(pageSize, 1, 200);

        var query = _db.SubscriptionRequests.Include(r => r.User).AsQueryable();
        if (status.HasValue) query = query.Where(r => r.Status == status.Value);
        if (!string.IsNullOrWhiteSpace(appId)) query = query.Where(r => r.AppId == appId);
        if (!string.IsNullOrWhiteSpace(deviceId)) query = query.Where(r => r.DeviceId == deviceId);

        var total = await query.CountAsync(ct);
        var items = await query
            .OrderByDescending(r => r.CreatedAt)
            .Skip((page - 1) * pageSize)
            .Take(pageSize)
            .ToListAsync(ct);

        return Ok(new PagedResult<AdminSubscriptionDto>(
            items.Select(ToAdminDto).ToList(), total, page, pageSize));
    }

    [HttpGet("stats")]
    public async Task<ActionResult<object>> Stats(CancellationToken ct)
    {
        var all = await _db.SubscriptionRequests
            .Select(r => new { r.Status, r.PriceInr, r.AppId })
            .ToListAsync(ct);

        return Ok(new
        {
            total = all.Count,
            pending = all.Count(r => r.Status == SubscriptionRequestStatus.Pending),
            approved = all.Count(r => r.Status == SubscriptionRequestStatus.Approved),
            rejected = all.Count(r => r.Status == SubscriptionRequestStatus.Rejected),
            revenueInr = all.Where(r => r.Status == SubscriptionRequestStatus.Approved).Sum(r => r.PriceInr),
            apps = all.Select(r => r.AppId).Distinct().OrderBy(a => a).ToList()
        });
    }

    /// <summary>
    /// Approve or reject a purchase. Approving activates the plan on the user's account for
    /// <see cref="PlanCatalog.PlanDurationDays"/> days (extending an unexpired plan rather than
    /// truncating it). Only the first approval activates — a repeat PATCH is a no-op.
    /// </summary>
    [HttpPatch("{id:guid}")]
    public async Task<ActionResult<AdminSubscriptionDto>> Update(
        Guid id, [FromBody] UpdateSubscriptionRequest body, CancellationToken ct)
    {
        var req = await _db.SubscriptionRequests.Include(r => r.User).FirstOrDefaultAsync(r => r.Id == id, ct);
        if (req is null) return NotFound(new ApiError("Request not found."));

        if (body.AdminNote is not null) req.AdminNote = body.AdminNote;

        if (body.Status.HasValue && body.Status.Value != req.Status)
        {
            var next = body.Status.Value;

            if (next == SubscriptionRequestStatus.Approved && req.Status != SubscriptionRequestStatus.Approved)
            {
                var user = req.User ?? await _db.Users.FindAsync([req.UserId], ct);
                if (user is not null)
                {
                    user.Plan = req.Plan;
                    var from = user.PlanExpiresAt is { } exp && exp > DateTime.UtcNow ? exp : DateTime.UtcNow;
                    user.PlanExpiresAt = from.AddDays(PlanCatalog.PlanDurationDays);
                }
                req.ProcessedAt = DateTime.UtcNow;
            }
            else if (next == SubscriptionRequestStatus.Rejected)
            {
                req.ProcessedAt = DateTime.UtcNow;
            }

            req.Status = next;
        }

        await _db.SaveChangesAsync(ct);
        return Ok(ToAdminDto(req));
    }

    private static AdminSubscriptionDto ToAdminDto(SubscriptionRequest r) => new(
        r.Id, r.UserId, r.User?.Email ?? string.Empty, r.AppId, r.DeviceId,
        r.Plan, r.PriceInr, r.Upi, r.Utr, r.Status, r.AdminNote, r.CreatedAt, r.ProcessedAt);
}
