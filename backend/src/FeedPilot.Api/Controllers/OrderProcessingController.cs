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
/// Work distribution for worker devices fulfilling app orders.
///
/// The contract is deliberately narrow: a device claims a batch, which flips those orders to
/// <see cref="AppOrderStatus.Processing"/> so nobody else sees them, then reports progress.
/// An order returns to Pending when it still owes actions, or settles as Completed once the
/// target count is reached.
/// </summary>
[ApiController]
[Authorize]
[Route("api/orders/processing")]
public class OrderProcessingController : ControllerBase
{
    private readonly AppDbContext _db;
    private readonly IWalletService _wallets;
    private readonly ISubscriptionService _subscriptions;
    private readonly IAppOrderService _orders;
    private readonly OrderPricingSettings _settings;
    private readonly ILogger<OrderProcessingController> _logger;

    /// <summary>
    /// Serialises claims within this process so two concurrent requests cannot read the same
    /// candidate rows before either writes — the fallback path for whatever [ClaimBatchAsync]
    /// can't run [ClaimAtomicallyAsync] against (SQLite/in-memory dev, or a Postgres claim that
    /// itself threw). A single in-process gate is only correct for a single-process host, which
    /// is what dev is; production claims should always be taking the atomic path instead.
    /// </summary>
    private static readonly SemaphoreSlim ClaimGate = new(1, 1);

    public OrderProcessingController(
        AppDbContext db,
        IWalletService wallets,
        ISubscriptionService subscriptions,
        IAppOrderService orders,
        IOptions<OrderPricingSettings> settings,
        ILogger<OrderProcessingController> logger)
    {
        _db = db;
        _wallets = wallets;
        _subscriptions = subscriptions;
        _orders = orders;
        _settings = settings.Value;
        _logger = logger;
    }

    [HttpGet("has-pending")]
    public async Task<ActionResult<HasPendingOrdersResponse>> HasPending(
        [FromQuery] string? taskTypes, CancellationToken ct)
    {
        var identity = this.ClientIdentity();
        var deviceId = identity.DeviceId;
        var staleBefore = await ClaimStaleBeforeAsync(ct);

        List<TaskType>? allowedTypes = null;
        if (!string.IsNullOrWhiteSpace(taskTypes))
        {
            allowedTypes = taskTypes.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
                .Select(t => Enum.TryParse<TaskType>(t, true, out var tt) ? tt : (TaskType?)null)
                .Where(t => t.HasValue)
                .Select(t => t!.Value)
                .Distinct()
                .ToList();
            if (allowedTypes.Count == 0) allowedTypes = null;
        }

        var count = await ClaimableOrders(deviceId, staleBefore, excludeOrderIds: null, allowedTypes)
            .CountAsync(ct);

        return Ok(new HasPendingOrdersResponse(count > 0, count));
    }

    [HttpPost("claim")]
    public async Task<ActionResult<IEnumerable<ClaimedOrderDto>>> Claim(
        [FromBody] ClaimOrdersRequest body, CancellationToken ct)
    {
        var batchSize = body.BatchSize > 0 ? body.BatchSize : _settings.DefaultBatchSize;
        batchSize = Math.Clamp(batchSize, 1, 200);
        var identity = this.ClientIdentity();
        var deviceId = string.IsNullOrWhiteSpace(body.DeviceId)
            ? identity.DeviceId
            : body.DeviceId.Trim();
        if (string.IsNullOrWhiteSpace(deviceId) || deviceId == ClientIdentityDefaults.Unknown)
            return BadRequest(new ApiError("A device id is required to claim orders.", "missing_device_id"));

        var staleBefore = await ClaimStaleBeforeAsync(ct);
        var excludeOrderIds = body.ExcludeOrderIds?.Where(id => id != Guid.Empty).Distinct().ToList();

        // Parse the allowed task types the account's mode sent. Null/empty = no restriction (Random mode).
        List<TaskType>? allowedTypes = null;
        if (body.TaskTypes is { Count: > 0 })
        {
            allowedTypes = body.TaskTypes
                .Select(t => Enum.TryParse<TaskType>(t, true, out var tt) ? tt : (TaskType?)null)
                .Where(t => t.HasValue)
                .Select(t => t!.Value)
                .Distinct()
                .ToList();
            if (allowedTypes.Count == 0) allowedTypes = null;
        }

        var candidates = await ClaimBatchAsync(deviceId, staleBefore, batchSize, excludeOrderIds, allowedTypes, ct);

        return Ok(candidates.Select(ToClaimedDto).ToList());
    }

    /// <summary>
    /// Claims one batch. On Postgres this is [ClaimAtomicallyAsync] — a single `UPDATE … FOR
    /// UPDATE SKIP LOCKED … RETURNING` with no app-level lock at all, so concurrent claims (two
    /// accounts on one clone, or two entirely different devices) can never see or take the same
    /// row, and unrelated devices no longer queue behind each other the way the semaphore below
    /// makes them. Everywhere else — SQLite/in-memory dev, which don't support that syntax, or a
    /// Postgres attempt that itself threw — falls back to [ClaimWithSemaphoreAsync], the original
    /// select-then-save path serialised behind an in-process lock.
    /// </summary>
    private async Task<List<AppOrder>> ClaimBatchAsync(
        string deviceId, DateTime staleBefore, int batchSize, List<Guid>? excludeOrderIds,
        List<TaskType>? allowedTypes, CancellationToken ct)
    {
        if (_db.Database.IsNpgsql())
        {
            try
            {
                return await ClaimAtomicallyAsync(deviceId, staleBefore, batchSize, excludeOrderIds, allowedTypes, ct);
            }
            catch (Exception ex)
            {
                _logger.LogWarning(ex,
                    "Atomic Postgres claim failed for device {DeviceId} — falling back to the semaphore-guarded claim",
                    deviceId);
            }
        }

        return await ClaimWithSemaphoreAsync(deviceId, staleBefore, batchSize, excludeOrderIds, allowedTypes, ct);
    }

    /// <summary>
    /// Claims up to [batchSize] rows in one atomic statement: the inner `SELECT … FOR UPDATE SKIP
    /// LOCKED` locks exactly the rows it picks within Postgres's own row-lock machinery, and any
    /// other transaction running this same query concurrently skips whatever's already locked
    /// instead of blocking on it or double-claiming it — so this needs no <see cref="ClaimGate"/>
    /// equivalent, and, unlike that in-process semaphore, is correct even if this API ever runs as
    /// more than one instance. The stale-claim reclaim that [ReleaseOrphanedProcessingOrdersAsync]
    /// does as a separate pass for the semaphore path is folded into the same WHERE clause here —
    /// a stale Processing row is just another claimable candidate, re-locked to whichever device's
    /// claim reaches it first.
    /// </summary>
    private async Task<List<AppOrder>> ClaimAtomicallyAsync(
        string deviceId, DateTime staleBefore, int batchSize, List<Guid>? excludeOrderIds,
        List<TaskType>? allowedTypes, CancellationToken ct)
    {
        var now = DateTime.UtcNow;
        var processing = (int)AppOrderStatus.Processing;
        var openStatuses = new[]
        {
            (int)AppOrderStatus.Pending,
            (int)AppOrderStatus.Approved,
            (int)AppOrderStatus.Submitted,
            (int)AppOrderStatus.InProgress
        };

        var excludes = excludeOrderIds ?? new List<Guid>();

        // Build the optional task-type filter clause. An empty allowedTypes means all types are fine.
        var typeFilter = allowedTypes is { Count: > 0 }
            ? allowedTypes.Select(t => (int)t).ToArray()
            : Array.Empty<int>();
        var applyTypeFilter = typeFilter.Length > 0;

        var ids = applyTypeFilter
            ? await _db.Database.SqlQuery<Guid>($"""
                UPDATE "AppOrders"
                SET "Status" = {processing},
                    "ProcessingDeviceId" = {deviceId},
                    "ProcessingStartedAt" = {now},
                    "UpdatedAt" = {now}
                WHERE "Id" IN (
                    SELECT "Id" FROM "AppOrders"
                    WHERE "CompletedCount" < "Quantity"
                      AND "IsExternal" = FALSE
                      AND NOT ("Id" = ANY({excludes.ToArray()}))
                      AND "OrderType" = ANY({typeFilter})
                      AND (
                        "Status" = ANY({openStatuses}) OR
                        ("Status" = {processing} AND (
                            "ProcessingDeviceId" = {deviceId} OR
                            "ProcessingDeviceId" IS NULL OR
                            "ProcessingDeviceId" = '' OR
                            "ProcessingStartedAt" IS NULL OR
                            "ProcessingStartedAt" < {staleBefore}
                        ))
                      )
                    ORDER BY "Priority" DESC, "CreatedAt" ASC
                    LIMIT {batchSize}
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING "Id"
                """).ToListAsync(ct)
            : await _db.Database.SqlQuery<Guid>($"""
                UPDATE "AppOrders"
                SET "Status" = {processing},
                    "ProcessingDeviceId" = {deviceId},
                    "ProcessingStartedAt" = {now},
                    "UpdatedAt" = {now}
                WHERE "Id" IN (
                    SELECT "Id" FROM "AppOrders"
                    WHERE "CompletedCount" < "Quantity"
                      AND "IsExternal" = FALSE
                      AND NOT ("Id" = ANY({excludes.ToArray()}))
                      AND (
                        "Status" = ANY({openStatuses}) OR
                        ("Status" = {processing} AND (
                            "ProcessingDeviceId" = {deviceId} OR
                            "ProcessingDeviceId" IS NULL OR
                            "ProcessingDeviceId" = '' OR
                            "ProcessingStartedAt" IS NULL OR
                            "ProcessingStartedAt" < {staleBefore}
                        ))
                      )
                    ORDER BY "Priority" DESC, "CreatedAt" ASC
                    LIMIT {batchSize}
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING "Id"
                """).ToListAsync(ct);

        if (ids.Count == 0) return new List<AppOrder>();

        // A fresh, untracked read of the rows the UPDATE above already committed — this only
        // shapes the response the same way the semaphore path's `candidates` list does, it does
        // not itself claim anything. Re-sorted by CreatedAt since Postgres does not guarantee a
        // multi-row RETURNING preserves the subquery's ORDER BY.
        return await _db.AppOrders.AsNoTracking()
            .Where(o => ids.Contains(o.Id))
            .OrderBy(o => o.CreatedAt)
            .ToListAsync(ct);
    }

    /// <summary>
    /// The original select-then-save claim, serialised behind <see cref="ClaimGate"/> so two
    /// concurrent callers on this same process can't read the same candidate rows before either
    /// writes — see [ClaimBatchAsync] for when this runs instead of the atomic Postgres path.
    /// </summary>
    private async Task<List<AppOrder>> ClaimWithSemaphoreAsync(
        string deviceId, DateTime staleBefore, int batchSize, List<Guid>? excludeOrderIds,
        List<TaskType>? allowedTypes, CancellationToken ct)
    {
        await ClaimGate.WaitAsync(ct);
        try
        {
            await ReleaseOrphanedProcessingOrdersAsync(staleBefore, ct);
            var candidates = await ClaimableOrders(deviceId, staleBefore, excludeOrderIds, allowedTypes)
                .Take(batchSize)
                .ToListAsync(ct);

            if (candidates.Count == 0) return candidates;

            var now = DateTime.UtcNow;
            foreach (var order in candidates)
            {
                order.Status = AppOrderStatus.Processing;
                order.ProcessingDeviceId = deviceId;
                order.ProcessingStartedAt = now;
                order.UpdatedAt = now;
            }
            await _db.SaveChangesAsync(ct);

            return candidates;
        }
        finally
        {
            ClaimGate.Release();
        }
    }

    private IQueryable<AppOrder> ClaimableOrders(
        string deviceId, DateTime staleBefore, List<Guid>? excludeOrderIds,
        List<TaskType>? allowedTypes = null)
    {
        var query = _db.AppOrders
            .Where(o =>
                o.CompletedCount < o.Quantity &&
                !o.IsExternal &&
                (o.Status == AppOrderStatus.Pending ||
                 o.Status == AppOrderStatus.Approved ||
                 o.Status == AppOrderStatus.Submitted ||
                 o.Status == AppOrderStatus.InProgress ||
                 (o.Status == AppOrderStatus.Processing &&
                  (o.ProcessingDeviceId == deviceId ||
                   o.ProcessingDeviceId == null ||
                   o.ProcessingDeviceId == string.Empty ||
                   o.ProcessingStartedAt == null ||
                   o.ProcessingStartedAt < staleBefore))));

        if (excludeOrderIds != null && excludeOrderIds.Count > 0)
            query = query.Where(o => !excludeOrderIds.Contains(o.Id));

        // Only return order types the account's selected mode can process.
        if (allowedTypes is { Count: > 0 })
            query = query.Where(o => allowedTypes.Contains(o.OrderType));

        return query.OrderByDescending(o => o.Priority).ThenBy(o => o.CreatedAt);
    }

    private async Task ReleaseOrphanedProcessingOrdersAsync(DateTime staleBefore, CancellationToken ct)
    {
        var now = DateTime.UtcNow;
        var orphaned = await _db.AppOrders
            .Where(o =>
                o.Status == AppOrderStatus.Processing &&
                o.CompletedCount < o.Quantity &&
                (o.ProcessingDeviceId == null ||
                 o.ProcessingDeviceId == string.Empty ||
                 o.ProcessingStartedAt == null ||
                 o.ProcessingStartedAt < staleBefore))
            .ToListAsync(ct);

        foreach (var order in orphaned)
        {
            order.Status = AppOrderStatus.Pending;
            order.ProcessingDeviceId = null;
            order.ProcessingStartedAt = null;
            order.UpdatedAt = now;
        }

        if (orphaned.Count > 0)
        {
            await _db.SaveChangesAsync(ct);
        }
    }

    /// <summary>
    /// Refreshes the claim lock on every order this device currently holds, not just the one a
    /// Progress/ProgressBatch call is reporting against. A claimed batch shares one
    /// ProcessingStartedAt stamped once at claim time; without this, only the specific row being
    /// reported got its clock reset, so the tail of a larger batch worked through at realistic
    /// pacing (streak cooldowns, per-action delay) could go stale — and be reclaimed by another
    /// device — before this device ever got around to touching it. Cheap: one indexed UPDATE
    /// (Status, ProcessingStartedAt) scoped to this device's own rows.
    /// </summary>
    private async Task ExtendClaimAsync(string? deviceId, CancellationToken ct)
    {
        if (string.IsNullOrWhiteSpace(deviceId)) return;
        await _db.AppOrders
            .Where(o => o.Status == AppOrderStatus.Processing && o.ProcessingDeviceId == deviceId)
            .ExecuteUpdateAsync(s => s.SetProperty(o => o.ProcessingStartedAt, DateTime.UtcNow), ct);
    }

    /// <summary>
    /// Dashboard-configured claim staleness cutoff (see RunnerSettings.ClaimTimeoutMinutes),
    /// not the static "Orders" config value, so operators can tune it without redeploying.
    /// </summary>
    private async Task<DateTime> ClaimStaleBeforeAsync(CancellationToken ct)
    {
        var runnerSettings = await RunnerSettingsStore.GetOrCreateAsync(_db, ct);
        return DateTime.UtcNow.AddMinutes(-runnerSettings.ClaimTimeoutMinutes);
    }

    /// <summary>
    /// Refunds the outstanding, prorated portion of an order that just landed in <see
    /// cref="AppOrderStatus.Failed"/> or <see cref="AppOrderStatus.NotFound"/> — a worker
    /// device discovering the target is private or gone, through no fault of the user who paid
    /// for it. Neither status was previously reachable from any refund path: only an admin
    /// explicitly setting Rejected/Canceled (<see cref="AdminOrdersController.Update"/>) or the
    /// user's own Cancel (<see cref="OrdersController.Cancel"/>) ever credited coins back, so an
    /// order that failed here on its own sat unrefunded forever unless an admin happened to
    /// notice and manually re-flag it. Uses the same RefundIssued-guarded, exactly-once
    /// conditional claim as those two paths, so a worker report can never double-refund
    /// alongside a concurrent admin/user action on the same order. <see
    /// cref="AppOrderStatus.Rejected"/> is deliberately excluded here — it now only ever means
    /// an admin decision (see the unified failure mapping below), which already owns its own
    /// refund path.
    /// </summary>
    private async Task RefundUnfulfillableAsync(AppOrder order, CancellationToken ct)
    {
        if (order.Status is not (AppOrderStatus.Failed or AppOrderStatus.NotFound) || order.CoinsSpent <= 0)
            return;

        await using var tx = await _db.Database.BeginTransactionAsync(ct);
        var claimed = await _db.AppOrders
            .Where(o => o.Id == order.Id && !o.RefundIssued)
            .ExecuteUpdateAsync(s => s.SetProperty(o => o.RefundIssued, true), ct);

        if (claimed == 1)
        {
            var refund = _orders.ProratedRefund(order);
            if (refund > 0)
                await _wallets.CreditAsync(order.UserId, refund, WalletTransactionType.Refund, $"order:{order.Id}", ct);
        }

        await tx.CommitAsync(ct);
    }

    /// <summary>
    /// Records progress and settles the order: Completed once the target count is met,
    /// otherwise released back to Pending so any device can pick up the remainder.
    /// </summary>
    [HttpPost("{id:guid}/progress")]
    public async Task<ActionResult<AppOrderDto>> Progress(
        Guid id, [FromBody] ReportProgressRequest body, CancellationToken ct)
    {
        var order = await _db.AppOrders.FirstOrDefaultAsync(o => o.Id == id, ct);
        if (order is null) return NotFound(new ApiError("Order not found."));

        // A device may only report against a claim it still holds. If the claim went stale
        // and was taken by someone else, the late report must not corrupt their progress.
        if (order.Status == AppOrderStatus.Processing &&
            !string.IsNullOrEmpty(order.ProcessingDeviceId) &&
            order.ProcessingDeviceId != body.DeviceId)
        {
            return Conflict(new ApiError(
                "This order is claimed by another device.", "CLAIM_LOST"));
        }

        await ExtendClaimAsync(body.DeviceId, ct);

        // The very first time a worker actually resolves this target's public count, capture it
        // as the baseline: followers for Follow, likes for Like/post/reel. External orders arrive with
        // StartCount 0 — the panel's own pulled start_count is never populated — so without
        // this, ObservedCompleted below stayed permanently disabled (it refuses to run against
        // an unset baseline) and the 0 we've always had here is exactly what
        // SmmPanelSyncService.PushOrderStatusUpdatesAsync then pushed straight back to the panel
        // as start_count. This resolves it from whichever device/account reports progress first,
        // same as an app-placed order's StartCount is resolved once at placement time.
        if ((order.OrderType == TaskType.Follow || order.OrderType == TaskType.Like) &&
            order.StartCount < 0 &&
            body.ObservedCount is { } firstObservedCount && firstObservedCount >= 0)
        {
            order.StartCount = firstObservedCount;
        }

        var failedReport = !string.IsNullOrWhiteSpace(body.ErrorMessage) ||
                           !string.IsNullOrWhiteSpace(body.FailureCode);

        // Completed is an absolute total, so a duplicate report is a no-op rather than
        // an over-count. Never let it move backwards or exceed the target. For follow/like orders,
        // also accept the public count observed by the worker and derive progress from
        // StartCount; that updates delivery even when the visible count is the source
        // of truth. Worker rewards still use only body.Completed below, not observed growth.
        // Failed reports are release/terminal signals only: they must not advance progress or
        // award coins even if an old/bad client includes a higher Completed or ObservedCount.
        var before = order.CompletedCount;
        var reportedCompleted = Math.Clamp(body.Completed, 0, order.Quantity);
        if (!failedReport)
        {
            var observedCompleted = ObservedCompleted(order, body);
            order.CompletedCount = Math.Clamp(
                Math.Max(order.CompletedCount, Math.Max(reportedCompleted, observedCompleted)), 0, order.Quantity);
        }

        // Pay the worker for units genuinely completed on this report. Fulfilment used to
        // earn nothing: coins were only credited through /api/tasks/result, which belongs to
        // the legacy EngagementTask queue and knows nothing about app orders.
        var newlyDone = failedReport
            ? 0
            : Math.Max(0, Math.Min(reportedCompleted, order.CompletedCount) - before);
        var workerCoinsAwarded = 0;
        if (newlyDone > 0)
        {
            var userId = User.GetUserId();
            // A paid plan multiplies what each completed action is worth (2× / 3× / 5×).
            var multiplier = await _subscriptions.GetCoinMultiplierAsync(userId, ct);
            Account? workerAccount = null;
            if (body.AccountId is { } accountId)
            {
                workerAccount = await _db.Accounts
                    .FirstOrDefaultAsync(a => a.Id == accountId && a.UserId == userId, ct);
            }

            var runnerSettings = await RunnerSettingsStore.GetOrCreateAsync(_db, ct);
            bool isUpgraded = workerAccount?.UpgradedAt is { } upgradedAt && DateTime.UtcNow - upgradedAt < TimeSpan.FromHours(24);
            int baseActionReward = RunnerSettingsStore.GetActionCoinReward(order.OrderType, isUpgraded, runnerSettings);
            workerCoinsAwarded = newlyDone * baseActionReward * multiplier;

            await _wallets.CreditAsync(
                userId, workerCoinsAwarded, WalletTransactionType.Earn, $"order:{order.Id}", ct);

            // Same crediting the wallet just got, but on the specific Account's own tally —
            // the number the app's account card shows. Without this the wallet total climbed
            // correctly but every account card stayed frozen at whatever it was when the account
            // was first linked, because nothing else in this (now primary) claim/progress flow
            // ever touched Account.CoinsEarned.
            if (workerAccount is not null)
            {
                workerAccount.CoinsEarned += workerCoinsAwarded;
                workerAccount.LastActive = DateTime.UtcNow;

                for (int i = 0; i < newlyDone; i++)
                {
                    _db.Tasks.Add(new EngagementTask
                    {
                        OrderId = order.Id,
                        AccountId = workerAccount.Id,
                        TaskType = order.OrderType,
                        TargetId = order.TargetUsername ?? order.TargetUrl,
                        Status = FeedPilot.Api.Domain.TaskStatus.Completed,
                        RewardCoins = baseActionReward * multiplier,
                        CreatedAt = DateTime.UtcNow,
                        CompletedAt = DateTime.UtcNow
                    });
                }
            }
        }

        if (!string.IsNullOrWhiteSpace(body.ErrorMessage))
            order.ErrorMessage = body.ErrorMessage;

        if (IsTargetNotFound(body))
        {
            order.Status = AppOrderStatus.NotFound;
            order.ErrorMessage = string.IsNullOrWhiteSpace(body.ErrorMessage)
                ? "Target Instagram user not found."
                : body.ErrorMessage;
            order.ProcessingDeviceId = null;
            order.ProcessingStartedAt = null;
        }
        else if (IsPrivateAccount(body))
        {
            order.Status = AppOrderStatus.Failed;
            order.ErrorMessage = "Can't process order on private account.";
            order.ProcessingDeviceId = null;
            order.ProcessingStartedAt = null;
        }
        else if (order.CompletedCount >= order.Quantity)
        {
            order.Status = AppOrderStatus.Completed;
            order.CompletedAt ??= DateTime.UtcNow;
            order.ProcessingDeviceId = null;
            order.ProcessingStartedAt = null;
        }
        else if (body.Release)
        {
            // Still owed work — free it so another device can continue.
            order.Status = AppOrderStatus.Pending;
            order.ProcessingDeviceId = null;
            order.ProcessingStartedAt = null;
        }
        else
        {
            // Keep the claim alive for another pass by this same device.
            order.ProcessingStartedAt = DateTime.UtcNow;
        }

        order.UpdatedAt = DateTime.UtcNow;
        await _db.SaveChangesAsync(ct);

        await RefundUnfulfillableAsync(order, ct);

        return Ok(OrdersController.ToDto(order, workerCoinsAwarded));
    }


    /// <summary>Drops a claim without reporting progress, e.g. when a runner is stopped.</summary>
    [HttpPost("{id:guid}/release")]
    public async Task<ActionResult<AppOrderDto>> Release(
        Guid id, [FromBody] ClaimOrdersRequest body, CancellationToken ct)
    {
        var order = await _db.AppOrders.FirstOrDefaultAsync(o => o.Id == id, ct);
        if (order is null) return NotFound(new ApiError("Order not found."));

        if (order.Status == AppOrderStatus.Processing && order.ProcessingDeviceId == body.DeviceId)
        {
            order.Status = order.CompletedCount >= order.Quantity
                ? AppOrderStatus.Completed
                : AppOrderStatus.Pending;
            order.ProcessingDeviceId = null;
            order.ProcessingStartedAt = null;
            order.UpdatedAt = DateTime.UtcNow;
            await _db.SaveChangesAsync(ct);
        }

        return Ok(OrdersController.ToDto(order));
    }

    private static ClaimedOrderDto ToClaimedDto(AppOrder o) => new(
        o.Id, o.OrderType, o.TargetUrl, o.TargetUsername, o.Quantity, o.CompletedCount, o.StartCount,
        Math.Max(0, o.Quantity - o.CompletedCount), o.CommentText, o.ProviderServiceId);

    private static int ObservedCompleted(AppOrder order, ReportProgressRequest body)
    {
        if (order.OrderType is not (TaskType.Follow or TaskType.Like) ||
            order.StartCount < 0 ||
            body.ObservedCount is null)
            return 0;

        return Math.Clamp(body.ObservedCount.Value - order.StartCount, 0, order.Quantity);
    }

    private static bool IsTargetNotFound(ReportProgressRequest body) =>
        IsTargetNotFound(body.FailureCode, body.ErrorMessage);

    private static bool IsPrivateAccount(ReportProgressRequest body) =>
        IsPrivateAccount(body.FailureCode, body.ErrorMessage);

    /// <summary>
    /// Shared by both <see cref="Progress"/> and <see cref="ProgressBatch"/> so the two report
    /// paths can never again classify the identical failure into two different terminal
    /// statuses the way they used to — a private-account report through ProgressBatch used to
    /// land on Rejected while the exact same failure through Progress landed on Failed.
    /// </summary>
    private static bool IsTargetNotFound(string? failureCode, string? message)
    {
        if (failureCode?.Equals("TARGET_NOT_FOUND", StringComparison.OrdinalIgnoreCase) == true)
            return true;

        return !string.IsNullOrWhiteSpace(message) &&
               (message.Contains("user not found", StringComparison.OrdinalIgnoreCase) ||
                message.Contains("account not found", StringComparison.OrdinalIgnoreCase) ||
                message.Contains("could not resolve", StringComparison.OrdinalIgnoreCase) ||
                message.Contains("post not found", StringComparison.OrdinalIgnoreCase) ||
                message.Contains("media not found", StringComparison.OrdinalIgnoreCase) ||
                message.Contains("unavailable", StringComparison.OrdinalIgnoreCase) ||
                message.Contains("404", StringComparison.OrdinalIgnoreCase));
    }

    private static bool IsPrivateAccount(string? failureCode, string? message)
    {
        if (failureCode?.Equals("TARGET_PRIVATE", StringComparison.OrdinalIgnoreCase) == true)
            return true;

        return !string.IsNullOrWhiteSpace(message) &&
               message.Contains("private account", StringComparison.OrdinalIgnoreCase);
    }

    [HttpPost("progress-batch")]
    public async Task<ActionResult<BatchReportProgressResponse>> ProgressBatch(
        [FromBody] BatchReportProgressRequest body, CancellationToken ct)
    {
        if (body.Reports == null || body.Reports.Count == 0)
            return Ok(new BatchReportProgressResponse(new List<BatchReportProgressResult>(), 0));

        await ExtendClaimAsync(body.DeviceId, ct);

        var results = new List<BatchReportProgressResult>();
        var userId = User.GetUserId();
        var multiplier = await _subscriptions.GetCoinMultiplierAsync(userId, ct);
        int totalCoinsAwarded = 0;
        // Refund checks are deferred until after the batch's single SaveChangesAsync below
        // commits every order's new Status — running the RefundIssued claim mid-loop, before
        // that save, could credit a refund for a terminal state that then never actually landed
        // if something later in the loop threw.
        var unfulfillableOrders = new List<AppOrder>();

        var orderIds = body.Reports.Select(r => r.OrderId).Distinct().ToList();
        var ordersMap = await _db.AppOrders
            .Where(o => orderIds.Contains(o.Id))
            .ToDictionaryAsync(o => o.Id, ct);

        var accountIds = body.Reports.Select(r => r.AccountId).Where(id => id.HasValue).Select(id => id!.Value).Distinct().ToList();
        var accountsMap = accountIds.Count > 0
            ? await _db.Accounts.Where(a => accountIds.Contains(a.Id) && a.UserId == userId).ToDictionaryAsync(a => a.Id, ct)
            : new Dictionary<Guid, Account>();

        foreach (var item in body.Reports)
        {
            if (!ordersMap.TryGetValue(item.OrderId, out var order))
            {
                results.Add(new BatchReportProgressResult(item.OrderId, false, 0, AppOrderStatus.Failed, "Order not found."));
                continue;
            }

            if (order.Status == AppOrderStatus.Processing &&
                !string.IsNullOrEmpty(order.ProcessingDeviceId) &&
                order.ProcessingDeviceId != body.DeviceId)
            {
                results.Add(new BatchReportProgressResult(item.OrderId, false, order.CompletedCount, order.Status, "CLAIM_LOST"));
                continue;
            }

            if ((order.OrderType == TaskType.Follow || order.OrderType == TaskType.Like) &&
                order.StartCount < 0 &&
                item.ObservedCount is { } firstObservedCount && firstObservedCount >= 0)
            {
                order.StartCount = firstObservedCount;
            }

            var failedReport = !string.IsNullOrWhiteSpace(item.ErrorMessage) ||
                               !string.IsNullOrWhiteSpace(item.FailureCode);

            var before = order.CompletedCount;
            var reportedCompleted = Math.Clamp(item.Completed, 0, order.Quantity);
            if (!failedReport)
            {
                var obsComp = (order.OrderType is TaskType.Follow or TaskType.Like && order.StartCount >= 0 && item.ObservedCount.HasValue)
                    ? Math.Clamp(item.ObservedCount.Value - order.StartCount, 0, order.Quantity)
                    : 0;
                order.CompletedCount = Math.Clamp(
                    Math.Max(order.CompletedCount, Math.Max(reportedCompleted, obsComp)), 0, order.Quantity);
            }

            var newlyDone = failedReport ? 0 : Math.Max(0, Math.Min(reportedCompleted, order.CompletedCount) - before);
            if (newlyDone > 0)
            {
            var runnerSettings = await RunnerSettingsStore.GetOrCreateAsync(_db, ct);
            Account? workerAccount = item.AccountId.HasValue && accountsMap.TryGetValue(item.AccountId.Value, out var acc) ? acc : null;
            bool isUpgraded = workerAccount?.UpgradedAt is { } upgradedAt && DateTime.UtcNow - upgradedAt < TimeSpan.FromHours(24);
            int baseActionReward = RunnerSettingsStore.GetActionCoinReward(order.OrderType, isUpgraded, runnerSettings);
            var coins = newlyDone * baseActionReward * multiplier;
            totalCoinsAwarded += coins;

                if (workerAccount is not null)
                {
                    workerAccount.CoinsEarned += coins;
                    workerAccount.LastActive = DateTime.UtcNow;

                    for (int i = 0; i < newlyDone; i++)
                    {
                        _db.Tasks.Add(new EngagementTask
                        {
                            OrderId = order.Id,
                            AccountId = workerAccount.Id,
                            TaskType = order.OrderType,
                            TargetId = order.TargetUsername ?? order.TargetUrl,
                            Status = FeedPilot.Api.Domain.TaskStatus.Completed,
                            RewardCoins = baseActionReward * multiplier,
                            CreatedAt = DateTime.UtcNow,
                            CompletedAt = DateTime.UtcNow
                        });
                    }
                }
            }

            if (!string.IsNullOrWhiteSpace(item.ErrorMessage))
                order.ErrorMessage = item.ErrorMessage;

            // Mirrors Progress's classification exactly (via the shared IsTargetNotFound/
            // IsPrivateAccount helpers) so the same failure can never again land on a different
            // terminal status depending on which of the two report endpoints saw it first.
            if (IsTargetNotFound(item.FailureCode, item.ErrorMessage))
            {
                order.Status = AppOrderStatus.NotFound;
                order.ProcessingDeviceId = null;
                order.ProcessingStartedAt = null;
                unfulfillableOrders.Add(order);
            }
            else if (IsPrivateAccount(item.FailureCode, item.ErrorMessage))
            {
                order.Status = AppOrderStatus.Failed;
                order.ProcessingDeviceId = null;
                order.ProcessingStartedAt = null;
                unfulfillableOrders.Add(order);
            }
            else if (item.Release || order.CompletedCount >= order.Quantity)
            {
                order.Status = order.CompletedCount >= order.Quantity
                    ? AppOrderStatus.Completed
                    : (failedReport ? AppOrderStatus.Failed : AppOrderStatus.InProgress);
                order.ProcessingDeviceId = null;
                order.ProcessingStartedAt = null;
                if (order.Status == AppOrderStatus.Failed) unfulfillableOrders.Add(order);
            }

            order.UpdatedAt = DateTime.UtcNow;
            results.Add(new BatchReportProgressResult(order.Id, true, order.CompletedCount, order.Status));
        }

        if (totalCoinsAwarded > 0)
        {
            await _wallets.CreditAsync(userId, totalCoinsAwarded, WalletTransactionType.Earn, "order:batch", ct);
        }

        await _db.SaveChangesAsync(ct);

        foreach (var order in unfulfillableOrders)
            await RefundUnfulfillableAsync(order, ct);

        return Ok(new BatchReportProgressResponse(results, totalCoinsAwarded));
    }
}
