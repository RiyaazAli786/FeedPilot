using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using FeedPilot.Api.Data;
using FeedPilot.Api.Domain;
using FeedPilot.Api.Dtos;
using FeedPilot.Api.Services;

namespace FeedPilot.Api.Controllers;

/// <summary>
/// Orders placed from the mobile app. The app posts here instead of calling an SMM panel
/// directly, so pricing, the coin ledger, and fulfilment all stay under backend control.
/// </summary>
[ApiController]
[Authorize]
[Route("api/orders")]
public class OrdersController : ControllerBase
{
    private readonly AppDbContext _db;
    private readonly IWalletService _wallets;
    private readonly IAppOrderService _orders;
    private readonly ISubscriptionService _subscriptions;
    private readonly OrderPricingSettings _pricing;

    public OrdersController(
        AppDbContext db,
        IWalletService wallets,
        IAppOrderService orders,
        ISubscriptionService subscriptions,
        Microsoft.Extensions.Options.IOptions<OrderPricingSettings> pricing)
    {
        _db = db;
        _wallets = wallets;
        _orders = orders;
        _subscriptions = subscriptions;
        _pricing = pricing.Value;
    }

    /// <summary>Quotes an order without placing it, so the app can show a price up front.</summary>
    [HttpGet("quote")]
    public async Task<ActionResult<object>> Quote([FromQuery] TaskType orderType, [FromQuery] int quantity, CancellationToken ct)
    {
        var minQty = orderType == TaskType.Comment ? 1 : _pricing.MinQuantity;
        if (quantity < minQty || quantity > _pricing.MaxQuantity)
            return BadRequest(new ApiError(
                $"Quantity must be between {minQty} and {_pricing.MaxQuantity}.",
                "QUANTITY_OUT_OF_RANGE"));

        var settings = await RunnerSettingsStore.GetOrCreateAsync(_db, ct);
        var rate = orderType switch
        {
            TaskType.Follow => settings.PricePerFollow,
            TaskType.Like => settings.PricePerLike,
            TaskType.Comment => settings.PricePerComment,
            TaskType.Repost => settings.PricePerRepost,
            TaskType.SavePost => settings.PricePerSavePost,
            TaskType.StoryView => settings.PricePerStoryView,
            _ => settings.PricePerLike
        };
        var coins = rate * quantity;

        return Ok(new
        {
            orderType,
            quantity,
            coins,
            minQuantity = minQty,
            maxQuantity = _pricing.MaxQuantity
        });
    }

    [HttpPost]
    public async Task<ActionResult<PlaceAppOrderResponse>> Place(
        [FromBody] PlaceAppOrderRequest body, CancellationToken ct)
    {
        var minQty = (body.OrderType == TaskType.Comment || (body.Comments != null && body.Comments.Count > 0)) ? 1 : _pricing.MinQuantity;
        if (body.Quantity < minQty || body.Quantity > _pricing.MaxQuantity)
            return BadRequest(new ApiError(
                $"Quantity must be between {minQty} and {_pricing.MaxQuantity}.",
                "QUANTITY_OUT_OF_RANGE"));

        if (string.IsNullOrWhiteSpace(body.TargetUrl))
            return BadRequest(new ApiError("A target is required.", "TARGET_REQUIRED"));

        var cost = _orders.QuoteCoins(body.OrderType, body.Quantity);
        var wallet = await _wallets.GetOrCreateAsync(User.GetUserId(), ct);

        if (wallet.Coins < cost)
            return BadRequest(new ApiError(
                $"Not enough coins. This order costs {cost}, your balance is {wallet.Coins}.",
                "INSUFFICIENT_COINS"));

        var commentText = body.Comments != null && body.Comments.Count > 0
            ? string.Join("\n", body.Comments.Where(c => !string.IsNullOrWhiteSpace(c)).Select(c => c.Trim()))
            : null;

        var identity = this.ClientIdentity();
        // Paid tiers already earn more per action via GetCoinMultiplierAsync (2x/3x/5x); reusing
        // that same multiplier as claim priority gives subscribers a second, more visible perk —
        // their own orders get fulfilled sooner — without introducing a separate tier lookup.
        // Fixed at placement time, same as CoinsSpent, so a later plan change/expiry doesn't
        // retroactively reorder an order already sitting in the queue.
        var priority = await _subscriptions.GetCoinMultiplierAsync(User.GetUserId(), ct);
        var order = new AppOrder
        {
            UserId = User.GetUserId(),
            AppId = identity.AppId,
            DeviceId = identity.DeviceId,
            OrderType = body.OrderType,
            TargetUrl = body.TargetUrl.Trim(),
            TargetUsername = body.TargetUsername?.Trim(),
            Quantity = body.Quantity,
            CoinsSpent = cost,
            Status = AppOrderStatus.Pending,
            StartCount = body.StartCount.HasValue ? Math.Max(0, body.StartCount.Value) : -1,
            CommentText = commentText,
            Priority = priority
        };

        // Debit only after the order row exists, so the transaction can reference it. Both
        // writes share one DB transaction: if the debit loses a balance race (another request
        // spent the coins first), the order insert rolls back too instead of leaving a "paid"
        // order that was never actually paid for.
        await using var tx = await _db.Database.BeginTransactionAsync(ct);
        _db.AppOrders.Add(order);
        await _db.SaveChangesAsync(ct);

        var debit = await _wallets.CreditAsync(User.GetUserId(), -cost, WalletTransactionType.Spend, $"order:{order.Id}", ct);
        if (!debit.Success)
        {
            await tx.RollbackAsync(ct);
            return BadRequest(new ApiError(
                $"Not enough coins. This order costs {cost}, your balance is {wallet.Coins}.",
                "INSUFFICIENT_COINS"));
        }
        await tx.CommitAsync(ct);

        return Ok(new PlaceAppOrderResponse(ToDto(order), debit.Balance));
    }

    /// <summary>Every order the signed-in user has placed, newest first, paginated.</summary>
    [HttpGet]
    public async Task<ActionResult<PagedOrdersDto>> Mine(
        [FromQuery] int page = 1, [FromQuery] int pageSize = 50, CancellationToken ct = default)
    {
        page = Math.Max(1, page);
        pageSize = Math.Clamp(pageSize, 1, 200);
        var userId = User.GetUserId();

        var query = _db.AppOrders.Where(o => o.UserId == userId);
        var totalCount = await query.CountAsync(ct);
        var items = await query
            .OrderByDescending(o => o.CreatedAt)
            .Skip((page - 1) * pageSize)
            .Take(pageSize)
            .ToListAsync(ct);

        return Ok(new PagedOrdersDto(
            items.Select(o => ToDto(o)).ToList(), page, pageSize, totalCount,
            (int)Math.Ceiling(totalCount / (double)pageSize)));
    }

    [HttpGet("{id:guid}")]
    public async Task<ActionResult<AppOrderDto>> GetOne(Guid id, CancellationToken ct)
    {
        var userId = User.GetUserId();
        var order = await _db.AppOrders.FirstOrDefaultAsync(o => o.Id == id && o.UserId == userId, ct);
        return order is null ? NotFound(new ApiError("Order not found.")) : Ok(ToDto(order));
    }

    /// <summary>Lets a user withdraw an order that has not been sent to a provider yet.</summary>
    [HttpPost("{id:guid}/cancel")]
    public async Task<ActionResult<AppOrderDto>> Cancel(Guid id, CancellationToken ct)
    {
        var userId = User.GetUserId();
        var order = await _db.AppOrders.FirstOrDefaultAsync(o => o.Id == id && o.UserId == userId, ct);
        if (order is null) return NotFound(new ApiError("Order not found."));

        if (!_orders.IsRefundable(order.Status))
            return BadRequest(new ApiError(
                $"An order that is {order.Status} can no longer be canceled.", "NOT_CANCELABLE"));

        // Computed before Status flips — ProratedRefund only reads Quantity/CompletedCount/
        // CoinsSpent, but reading it against the pre-cancel order keeps the intent clear.
        var refund = _orders.ProratedRefund(order);

        // The status flip and the RefundIssued claim happen in one conditional UPDATE, and the
        // wallet credit shares the same DB transaction. That closes two gaps the old two-step
        // (save status, then separately credit) code had: two concurrent cancel calls could
        // both see the order as refundable and both credit it, and a crash/timeout between the
        // two separate SaveChanges calls could flip the order to Canceled while silently never
        // crediting the refund at all, with no way to retry.
        await using var tx = await _db.Database.BeginTransactionAsync(ct);
        var claimed = await _db.AppOrders
            .Where(o => o.Id == id && o.UserId == userId && !o.RefundIssued &&
                        (o.Status == AppOrderStatus.Pending || o.Status == AppOrderStatus.Approved))
            .ExecuteUpdateAsync(s => s
                .SetProperty(o => o.Status, AppOrderStatus.Canceled)
                .SetProperty(o => o.RefundIssued, true)
                .SetProperty(o => o.UpdatedAt, DateTime.UtcNow), ct);

        if (claimed == 0)
        {
            await tx.RollbackAsync(ct);
            return BadRequest(new ApiError(
                $"An order that is {order.Status} can no longer be canceled.", "NOT_CANCELABLE"));
        }

        if (refund > 0)
            await _wallets.CreditAsync(userId, refund, WalletTransactionType.Refund, $"order:{order.Id}", ct);

        await tx.CommitAsync(ct);

        order.Status = AppOrderStatus.Canceled;
        return Ok(ToDto(order));
    }

    /// <summary>Deletes a terminal order from the user's backend history.</summary>
    [HttpDelete("{id:guid}")]
    public async Task<IActionResult> Delete(Guid id, CancellationToken ct)
    {
        var userId = User.GetUserId();
        var order = await _db.AppOrders.FirstOrDefaultAsync(o => o.Id == id && o.UserId == userId, ct);
        if (order is null) return NotFound(new ApiError("Order not found."));

        if (order.Status is AppOrderStatus.Pending or AppOrderStatus.Approved
            or AppOrderStatus.Submitted or AppOrderStatus.InProgress)
        {
            return BadRequest(new ApiError(
                "Cancel this order before deleting it from history.",
                "ORDER_STILL_ACTIVE"));
        }

        await using var tx = await _db.Database.BeginTransactionAsync(ct);

        await _db.Tasks
            .Where(t => t.OrderId == id)
            .ExecuteDeleteAsync(ct);

        _db.AppOrders.Remove(order);
        await _db.SaveChangesAsync(ct);
        await tx.CommitAsync(ct);

        return NoContent();
    }

    /// <summary>
    /// Swaps status of an order that has not been sent to a provider yet.
    /// </summary>
    [AllowAnonymous]
    [HttpPost("fetch-external")]
    public ActionResult<object> FetchExternal() =>
        StatusCode(StatusCodes.Status410Gone,
            new ApiError("External SMM order fetching is disabled. FeedPilot processes app-placed orders only.", "EXTERNAL_ORDERS_DISABLED"));

    /// <summary>
    /// Every order imported from the smmorigin.com panel, newest first, paginated.
    /// </summary>
    [AllowAnonymous]
    [HttpGet("external")]
    public async Task<ActionResult<PagedOrdersDto>> GetExternalOrders(
        [FromQuery] int page = 1, [FromQuery] int pageSize = 50, CancellationToken ct = default)
    {
        page = Math.Max(1, page);
        pageSize = Math.Clamp(pageSize, 1, 200);

        var query = _db.AppOrders.Where(o => o.IsExternal);
        var totalCount = await query.CountAsync(ct);
        var items = await query
            .OrderByDescending(o => o.CreatedAt)
            .Skip((page - 1) * pageSize)
            .Take(pageSize)
            .ToListAsync(ct);

        return Ok(new PagedOrdersDto(
            items.Select(o => ToDto(o)).ToList(), page, pageSize, totalCount,
            (int)Math.Ceiling(totalCount / (double)pageSize)));
    }

    internal static AppOrderDto ToDto(
        AppOrder o,
        int workerCoinsAwarded = 0,
        long? workerWalletBalance = null,
        long? workerAccountCoinsEarned = null) => new(
        o.Id, o.OrderType, o.TargetUrl, o.TargetUsername, o.Quantity, o.CompletedCount,
        o.StartCount, o.CoinsSpent, o.Status, o.ProviderName, o.ProviderOrderId, o.ErrorMessage,
        o.CreatedAt, o.CompletedAt, workerCoinsAwarded, workerWalletBalance, workerAccountCoinsEarned);
}
