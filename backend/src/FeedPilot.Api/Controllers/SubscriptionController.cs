using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using FeedPilot.Api.Data;
using FeedPilot.Api.Domain;
using FeedPilot.Api.Dtos;
using FeedPilot.Api.Services;

namespace FeedPilot.Api.Controllers;

/// <summary>
/// Subscription tiers and purchases. A paid tier is NOT activated on request: the user pays the
/// configured UPI id out of band, submits the payment's UTR, and an admin verifies it on the
/// dashboard. Approval is what activates the plan (and raises the coin-earn multiplier — see
/// <see cref="PlanCatalog"/>).
/// </summary>
[ApiController]
[Authorize]
[Route("api/subscription")]
public class SubscriptionController : ControllerBase
{
    private readonly AppDbContext _db;
    private readonly PaymentSettings _payments;

    public SubscriptionController(AppDbContext db, IOptions<PaymentSettings> payments)
    {
        _db = db;
        _payments = payments.Value;
    }

    /// <summary>Catalogue of tiers with prices, coin rates and features. Drives the upgrade screen.</summary>
    [AllowAnonymous]
    [HttpGet("plans")]
    public ActionResult<IEnumerable<PlanDto>> Plans() =>
        Ok(PlanCatalog.All.Select(p => new PlanDto(p.Plan, p.Name, p.PriceInr, p.CoinMultiplier, p.Features)));

    /// <summary>Where to pay. The app shows this UPI id in the payment popup.</summary>
    [HttpGet("payment")]
    public ActionResult<PaymentInfoDto> Payment() =>
        Ok(new PaymentInfoDto(_payments.UpiId, _payments.PayeeName, !string.IsNullOrWhiteSpace(_payments.UpiId)));

    /// <summary>The caller's current plan plus any purchase awaiting admin verification.</summary>
    [HttpGet]
    public async Task<ActionResult<SubscriptionDto>> Current(CancellationToken ct)
    {
        var user = await _db.Users.FindAsync([User.GetUserId()], ct);
        if (user is null) return NotFound(new ApiError("User not found."));

        var pending = await _db.SubscriptionRequests
            .Where(r => r.UserId == user.Id && r.Status == SubscriptionRequestStatus.Pending)
            .OrderByDescending(r => r.CreatedAt)
            .FirstOrDefaultAsync(ct);

        return Ok(ToDto(user, pending));
    }

    /// <summary>
    /// Records a UPI payment for a tier and queues it for admin verification. Does not activate
    /// anything — that happens only when an admin approves it on the dashboard.
    /// </summary>
    [HttpPost("request")]
    public async Task<ActionResult<SubscriptionDto>> RequestPurchase(SubscriptionPurchaseRequest body, CancellationToken ct)
    {
        if (body.Plan == SubscriptionPlan.Free)
            return BadRequest(new ApiError("Free is not a purchasable plan.", "invalid_plan"));
        if (string.IsNullOrWhiteSpace(_payments.UpiId))
            return BadRequest(new ApiError("Payments are not configured. Try again later.", "payments_disabled"));
        if (string.IsNullOrWhiteSpace(body.Utr))
            return BadRequest(new ApiError("A payment reference (UTR) is required.", "utr_required"));

        var userId = User.GetUserId();
        var user = await _db.Users.FindAsync([userId], ct);
        if (user is null) return NotFound(new ApiError("User not found."));

        // One open request at a time keeps the admin queue and the user's status unambiguous.
        var hasPending = await _db.SubscriptionRequests
            .AnyAsync(r => r.UserId == userId && r.Status == SubscriptionRequestStatus.Pending, ct);
        if (hasPending)
            return Conflict(new ApiError("You already have a purchase awaiting approval.", "already_pending"));

        var plan = PlanCatalog.Get(body.Plan);
        var identity = this.ClientIdentity();
        _db.SubscriptionRequests.Add(new SubscriptionRequest
        {
            UserId = userId,
            Plan = body.Plan,
            PriceInr = plan.PriceInr,
            Upi = _payments.UpiId,
            Utr = body.Utr.Trim(),
            Status = SubscriptionRequestStatus.Pending,
            AppId = identity.AppId,
            DeviceId = identity.DeviceId
        });
        await _db.SaveChangesAsync(ct);

        return Ok(ToDto(user, await _db.SubscriptionRequests
            .Where(r => r.UserId == userId && r.Status == SubscriptionRequestStatus.Pending)
            .OrderByDescending(r => r.CreatedAt).FirstAsync(ct)));
    }

    private static SubscriptionDto ToDto(User user, SubscriptionRequest? pending)
    {
        var multiplier = PlanCatalog.MultiplierFor(user.Plan, user.PlanExpiresAt);
        var active = user.Plan != SubscriptionPlan.Free && multiplier > 1;
        return new SubscriptionDto(
            user.Plan, PlanCatalog.Get(user.Plan).Name, multiplier, user.PlanExpiresAt, active,
            pending?.Plan, pending?.Status);
    }
}
