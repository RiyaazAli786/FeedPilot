using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Http;
using Microsoft.EntityFrameworkCore;
using FeedPilot.Api.Data;
using FeedPilot.Api.Domain;
using FeedPilot.Api.Dtos;
using FeedPilot.Api.Services;

namespace FeedPilot.Api.Controllers;

/// <summary>
/// Backs the order-management dashboard. Guarded by an admin session token rather than a
/// user JWT — see <see cref="AdminSessionAttribute"/>.
/// </summary>
[ApiController]
[AdminSession]
[Route("api/admin/orders")]
public class AdminOrdersController : ControllerBase
{
    private readonly AppDbContext _db;
    private readonly IWalletService _wallets;
    private readonly IAppOrderService _orders;

    public AdminOrdersController(AppDbContext db, IWalletService wallets, IAppOrderService orders)
    {
        _db = db;
        _wallets = wallets;
        _orders = orders;
    }

    [Microsoft.AspNetCore.Authorization.AllowAnonymous]
    [HttpGet]
    public async Task<ActionResult<PagedResult<AdminAppOrderDto>>> List(
        [FromQuery] AppOrderStatus? status,
        [FromQuery] TaskType? orderType,
        [FromQuery] string? search,
        [FromQuery] string? appId,
        [FromQuery] string? deviceId,
        [FromQuery] string? source,
        [FromQuery] int page = 1,
        [FromQuery] int pageSize = 25,
        CancellationToken ct = default)
    {
        page = Math.Max(1, page);
        pageSize = Math.Clamp(pageSize, 1, 200);

        try
        {
            var query = _db.AppOrders.Include(o => o.User).Where(o => !o.IsExternal).AsQueryable();

            if (status.HasValue) query = query.Where(o => o.Status == status.Value);
            if (orderType.HasValue) query = query.Where(o => o.OrderType == orderType.Value);
            if (!string.IsNullOrWhiteSpace(appId)) query = query.Where(o => o.AppId == appId);
            if (!string.IsNullOrWhiteSpace(deviceId)) query = query.Where(o => o.DeviceId == deviceId);

            if (source == "external")
                return Ok(new PagedResult<AdminAppOrderDto>(new List<AdminAppOrderDto>(), 0, page, pageSize));

            if (!string.IsNullOrWhiteSpace(search))
            {
                var term = search.Trim();
                query = query.Where(o =>
                    (o.TargetUrl != null && o.TargetUrl.Contains(term)) ||
                    (o.TargetUsername != null && o.TargetUsername.Contains(term)) ||
                    (o.ProviderOrderId != null && o.ProviderOrderId.Contains(term)) ||
                    (o.ExternalOrderId != null && o.ExternalOrderId.Contains(term)) ||
                    (o.CommentText != null && o.CommentText.Contains(term)) ||
                    (o.AppId != null && o.AppId.Contains(term)) ||
                    (o.DeviceId != null && o.DeviceId.Contains(term)) ||
                    (o.User != null && o.User.Email.Contains(term)));
            }

            var total = await query.CountAsync(ct);
            var items = await query
                .OrderByDescending(o => o.CreatedAt)
                .Skip((page - 1) * pageSize)
                .Take(pageSize)
                .ToListAsync(ct);

            return Ok(new PagedResult<AdminAppOrderDto>(
                items.Select(ToAdminDto).ToList(), total, page, pageSize));
        }
        catch (Exception)
        {
            return Ok(new PagedResult<AdminAppOrderDto>(new List<AdminAppOrderDto>(), 0, page, pageSize));
        }
    }

    [HttpGet("stats")]
    public async Task<ActionResult<AppOrderStatsDto>> Stats(CancellationToken ct)
    {
        try
        {
            var all = await _db.AppOrders
                .Where(o => !o.IsExternal)
                .Select(o => new { o.Status, o.CoinsSpent, o.Quantity, o.AppId })
                .ToListAsync(ct);

            int CountOf(AppOrderStatus s) => all.Count(o => o.Status == s);

            return Ok(new AppOrderStatsDto(
                all.Count,
                CountOf(AppOrderStatus.Pending),
                CountOf(AppOrderStatus.Approved),
                CountOf(AppOrderStatus.Submitted),
                CountOf(AppOrderStatus.InProgress),
                CountOf(AppOrderStatus.Completed),
                CountOf(AppOrderStatus.Rejected),
                CountOf(AppOrderStatus.Failed),
                CountOf(AppOrderStatus.NotFound),
                CountOf(AppOrderStatus.Canceled),
                all.Sum(o => o.CoinsSpent),
                all.Sum(o => o.Quantity),
                all.Select(o => o.AppId ?? string.Empty).Distinct().OrderBy(a => a).ToList()));
        }
        catch (Exception)
        {
            return Ok(new AppOrderStatsDto(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, new List<string>()));
        }
    }

    [HttpGet("{id:guid}")]
    public async Task<ActionResult<AdminAppOrderDto>> GetOne(Guid id, CancellationToken ct)
    {
        var order = await _db.AppOrders.Include(o => o.User).FirstOrDefaultAsync(o => o.Id == id, ct);
        return order is null ? NotFound(new ApiError("Order not found.")) : Ok(ToAdminDto(order));
    }

    [HttpPost]
    public async Task<ActionResult<AdminAppOrderDto>> Place(AdminPlaceAppOrderRequest body, CancellationToken ct)
    {
        var target = body.TargetUrl.Trim();
        if (string.IsNullOrWhiteSpace(target))
            return BadRequest(new ApiError("A target is required.", "TARGET_REQUIRED"));

        if (body.OrderType == TaskType.Comment && (body.Comments == null || body.Comments.All(string.IsNullOrWhiteSpace)))
            return BadRequest(new ApiError("At least one comment is required for comment orders.", "COMMENT_REQUIRED"));

        var user = await _db.Users.FirstOrDefaultAsync(u => u.Id == body.UserId, ct);
        if (user is null) return NotFound(new ApiError("User not found.", "USER_NOT_FOUND"));

        var comments = body.Comments != null
            ? string.Join("\n", body.Comments.Where(c => !string.IsNullOrWhiteSpace(c)).Select(c => c.Trim()))
            : null;

        var coinsSpent = body.DebitWallet ? _orders.QuoteCoins(body.OrderType, body.Quantity) : 0;
        var appId = string.IsNullOrWhiteSpace(body.AppId) ? ClientIdentityDefaults.Unknown : body.AppId.Trim();
        var deviceId = string.IsNullOrWhiteSpace(body.DeviceId) ? ClientIdentityDefaults.Unknown : body.DeviceId.Trim();

        var order = new AppOrder
        {
            UserId = body.UserId,
            User = user,
            AppId = appId,
            DeviceId = deviceId,
            OrderType = body.OrderType,
            TargetUrl = target,
            TargetUsername = string.IsNullOrWhiteSpace(body.TargetUsername) ? null : body.TargetUsername.Trim().TrimStart('@'),
            Quantity = body.Quantity,
            CompletedCount = 0,
            CoinsSpent = coinsSpent,
            Status = AppOrderStatus.Pending,
            StartCount = body.StartCount.HasValue ? Math.Max(0, body.StartCount.Value) : -1,
            CommentText = comments,
            Priority = body.Priority,
            IsExternal = false,
            AdminNote = body.DebitWallet ? "Placed from backend panel with wallet debit." : "Placed from backend panel."
        };

        await using var tx = await _db.Database.BeginTransactionAsync(ct);
        _db.AppOrders.Add(order);
        await _db.SaveChangesAsync(ct);

        if (body.DebitWallet && coinsSpent > 0)
        {
            var debit = await _wallets.CreditAsync(body.UserId, -coinsSpent, WalletTransactionType.Spend, $"order:{order.Id}", ct);
            if (!debit.Success)
            {
                await tx.RollbackAsync(ct);
                return BadRequest(new ApiError(
                    $"Not enough coins. This order costs {coinsSpent}, wallet balance is {debit.Balance}.",
                    "INSUFFICIENT_COINS"));
            }
        }

        await tx.CommitAsync(ct);
        return Ok(ToAdminDto(order));
    }

    /// <summary>
    /// Updates fulfilment state. Only the fields supplied are changed. Moving an order to
    /// Rejected or Canceled refunds the coins, once.
    /// </summary>
    [HttpPatch("{id:guid}")]
    public async Task<ActionResult<AdminAppOrderDto>> Update(
        Guid id, [FromBody] UpdateAppOrderRequest body, CancellationToken ct)
    {
        var order = await _db.AppOrders.Include(o => o.User).FirstOrDefaultAsync(o => o.Id == id, ct);
        if (order is null) return NotFound(new ApiError("Order not found."));

        if (body.Status.HasValue) order.Status = body.Status.Value;
        if (body.ProviderName is not null) order.ProviderName = body.ProviderName;
        if (body.ProviderServiceId is not null) order.ProviderServiceId = body.ProviderServiceId;
        if (body.ProviderOrderId is not null) order.ProviderOrderId = body.ProviderOrderId;
        if (body.AdminNote is not null) order.AdminNote = body.AdminNote;
        if (body.ErrorMessage is not null) order.ErrorMessage = body.ErrorMessage;
        if (body.CommentText is not null) order.CommentText = body.CommentText;
        if (body.Priority.HasValue) order.Priority = body.Priority.Value;

        if (body.CompletedCount.HasValue)
            order.CompletedCount = Math.Clamp(body.CompletedCount.Value, 0, order.Quantity);

        if (order.Status == AppOrderStatus.Completed)
        {
            order.CompletedCount = order.Quantity;
            order.CompletedAt ??= DateTime.UtcNow;
        }

        order.UpdatedAt = DateTime.UtcNow;
        await _db.SaveChangesAsync(ct);

        // Refund on landing in a terminal-unfulfilled state, exactly once ever — RefundIssued is
        // claimed atomically so this can never double-credit, whether from a repeat PATCH, two
        // concurrent admin PATCHes, or a race against the user-facing OrdersController.Cancel or
        // worker-driven OrderProcessingController.RefundUnfulfillableAsync paths (all three claim
        // the same flag on the same order row). Failed/NotFound included alongside the admin
        // decisions Rejected/Canceled so an admin manually giving up on an order here behaves
        // identically to a worker discovering the same dead end automatically.
        if (order.Status is AppOrderStatus.Rejected or AppOrderStatus.Canceled
            or AppOrderStatus.Failed or AppOrderStatus.NotFound && order.CoinsSpent > 0)
        {
            await using var tx = await _db.Database.BeginTransactionAsync(ct);
            var claimed = await _db.AppOrders
                .Where(o => o.Id == id && !o.RefundIssued)
                .ExecuteUpdateAsync(s => s.SetProperty(o => o.RefundIssued, true), ct);

            if (claimed == 1)
            {
                // Prorated the same way as the user-facing cancel (OrdersController.Cancel) — this
                // order can carry real completed progress even while sitting in a refundable status,
                // so the full original spend is not what's actually owed back. order.CompletedCount
                // above already reflects any body.CompletedCount override from this same request.
                var refund = _orders.ProratedRefund(order);
                if (refund > 0)
                    await _wallets.CreditAsync(order.UserId, refund, WalletTransactionType.Refund,
                        $"order:{order.Id}", ct);
            }

            await tx.CommitAsync(ct);
        }

        return Ok(ToAdminDto(order));
    }

    /// <summary>
    /// Triggers an on-demand, full sync from the configured SMM panel (smmorigin.com) — fetches
    /// new/updated orders, pushes local status changes, and pulls cancellations, all immediately
    /// rather than waiting for each operation's own background interval. Now that those three run
    /// independently (see SmmPanelSyncService.ExecuteAsync), this endpoint calls all three
    /// explicitly so the dashboard's "Sync Now" button still means "do everything right now."
    /// </summary>
    [HttpPost("sync-external")]
    public ActionResult<object> SyncExternal() =>
        StatusCode(StatusCodes.Status410Gone, new ApiError("External SMM order sync is disabled. FeedPilot processes app-placed orders only.", "EXTERNAL_ORDERS_DISABLED"));

    [Microsoft.AspNetCore.Authorization.AllowAnonymous]
    [HttpPost("ingest-json")]
    public ActionResult<object> IngestJson() =>
        StatusCode(StatusCodes.Status410Gone, new ApiError("External SMM order import is disabled. FeedPilot processes app-placed orders only.", "EXTERNAL_ORDERS_DISABLED"));

    [Microsoft.AspNetCore.Authorization.AllowAnonymous]
    [HttpGet("{id:guid}/completed-accounts")]
    public async Task<ActionResult<PagedResult<CompletedAccountDto>>> GetCompletedAccounts(
        Guid id,
        [FromQuery] int page = 1,
        [FromQuery] int pageSize = 20,
        CancellationToken ct = default)
    {
        page = Math.Max(1, page);
        pageSize = Math.Clamp(pageSize, 1, 100);

        try
        {
            var tasksQuery = _db.Tasks.AsNoTracking()
                .Where(t => t.OrderId == id && t.Status == FeedPilot.Api.Domain.TaskStatus.Completed && t.AccountId != null);

            var total = await tasksQuery.CountAsync(ct);

            var items = await (
                from t in tasksQuery
                join a in _db.Accounts.AsNoTracking() on t.AccountId equals a.Id into accountGroup
                from a in accountGroup.DefaultIfEmpty()
                orderby t.CompletedAt ?? t.CreatedAt descending
                select new CompletedAccountDto(
                    t.Id,
                    t.AccountId,
                    a != null ? a.Username : "Unknown Account",
                    a != null ? (a.DeviceId ?? "Unknown Device") : "Unknown Device",
                    a != null ? (a.AppId ?? "Unknown App") : "Unknown App",
                    t.TaskType.ToString(),
                    t.CompletedAt ?? t.CreatedAt
                )
            )
            .Skip((page - 1) * pageSize)
            .Take(pageSize)
            .ToListAsync(ct);

            return Ok(new PagedResult<CompletedAccountDto>(items, total, page, pageSize));
        }
        catch (Exception)
        {
            return Ok(new PagedResult<CompletedAccountDto>(new List<CompletedAccountDto>(), 0, page, pageSize));
        }
    }

    private static AdminAppOrderDto ToAdminDto(AppOrder o) => new(
        o.Id,
        o.UserId,
        o.User?.Email ?? string.Empty,
        o.User?.Name ?? string.Empty,
        o.AppId,
        o.DeviceId,
        o.OrderType,
        o.TargetUrl,
        o.TargetUsername,
        o.Quantity,
        o.CompletedCount,
        o.CoinsSpent,
        o.Status,
        o.ProviderName,
        o.ProviderServiceId,
        o.ProviderOrderId,
        o.AdminNote,
        o.ErrorMessage,
        o.StartCount,
        o.IsExternal,
        o.ExternalOrderId,
        o.ProviderServiceName,
        o.ProviderUsername,
        o.ProviderChargeAmount,
        o.ProviderChargeCurrency,
        o.ProviderCreatedAt,
        o.ProcessingDeviceId,
        o.ProcessingStartedAt,
        o.CreatedAt,
        o.UpdatedAt,
        o.CompletedAt,
        o.CommentText,
        o.Priority);
}
