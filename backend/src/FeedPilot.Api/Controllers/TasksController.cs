using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using FeedPilot.Api.Data;
using FeedPilot.Api.Domain;
using FeedPilot.Api.Dtos;
using FeedPilot.Api.Services;
using TaskStatus = FeedPilot.Api.Domain.TaskStatus;

namespace FeedPilot.Api.Controllers;

[ApiController]
[Authorize]
[Route("api/tasks")]
public class TasksController : ControllerBase
{
    private readonly AppDbContext _db;
    private readonly IWalletService _wallets;
    private readonly SmmPanelSyncService? _smmSync;
    private readonly TelegramRequestLogger? _telegram;

    /// <summary>
    /// Serialises the read-check-write on a single task's Status for whatever the Postgres
    /// `FOR UPDATE` row lock in <see cref="SubmitResult"/> can't cover (SQLite/in-memory dev).
    /// Same fallback role as OrderProcessingController.ProgressGate, for the same reason: a
    /// retried report for one task landing twice must serialise against itself, not the other
    /// task IDs unrelated callers are simultaneously reporting.
    /// </summary>
    private static readonly SemaphoreSlim ResultGate = new(1, 1);

    public TasksController(
        AppDbContext db,
        IWalletService wallets,
        SmmPanelSyncService? smmSync = null,
        TelegramRequestLogger? telegram = null)
    {
        _db = db;
        _wallets = wallets;
        _smmSync = smmSync;
        _telegram = telegram;
    }

    /// <summary>Downloads a batch of pending tasks and marks them assigned.</summary>
    [HttpGet]
    public async Task<ActionResult<IEnumerable<TaskDto>>> Pending([FromQuery] int limit = 20, CancellationToken ct = default)
    {
        limit = Math.Clamp(limit, 1, 100);
        var tasks = await _db.Tasks
            .Where(t => t.Status == TaskStatus.Pending)
            .OrderBy(t => t.CreatedAt)
            .Take(limit)
            .ToListAsync(ct);

        foreach (var t in tasks) t.Status = TaskStatus.Assigned;
        await _db.SaveChangesAsync(ct);

        return Ok(tasks.Select(t => new TaskDto(t.Id, t.OrderId, t.AccountId, t.TaskType, t.TargetId,
            t.Status, t.RetryCount, t.RewardCoins, t.CreatedAt)));
    }

    /// <summary>Uploads a task result. On success the reward is credited to the user's wallet.</summary>
    [HttpPost("result")]
    public async Task<ActionResult<TaskResultResponse>> SubmitResult(TaskResultRequest req, CancellationToken ct)
    {
        var userId = User.GetUserId();

        // Same claim-then-mutate shape as OrderProcessingController.Progress: a Postgres row lock
        // (or the in-process ResultGate where FOR UPDATE isn't available) makes the read-check-write
        // on task.Status atomic, so a retried report for a task this process already resolved can
        // never re-run the reward. TaskRepository.submitLegacyTaskResultWithRetry on the client
        // resends this exact call with backoff whenever a response is lost — including after the
        // first attempt already landed and paid out — and without this guard the resend silently
        // credited the wallet and advanced order progress a second time for work done once.
        var useTx = _db.Database.IsNpgsql();
        var tx = useTx ? await _db.Database.BeginTransactionAsync(ct) : null;
        if (!useTx) await ResultGate.WaitAsync(ct);
        var locked = true;
        try
        {
            var task = useTx
                ? await _db.Tasks.FromSqlInterpolated($"""SELECT * FROM "Tasks" WHERE "Id" = {req.TaskId} FOR UPDATE""").FirstOrDefaultAsync(ct)
                : await _db.Tasks.FirstOrDefaultAsync(t => t.Id == req.TaskId, ct);
            if (task is null) return NotFound(new ApiError("Task not found.", "not_found"));

            var account = await _db.Accounts.FirstOrDefaultAsync(a => a.Id == req.AccountId && a.UserId == userId, ct);
            if (account is null) return BadRequest(new ApiError("Account does not belong to user.", "invalid_account"));

            // Already resolved by an earlier attempt (or a concurrent one that landed first).
            // Report what actually happened instead of re-running the reward/order-progress logic.
            if (task.Status is TaskStatus.Completed or TaskStatus.Failed or TaskStatus.Skipped)
            {
                var priorAwarded = task.Status == TaskStatus.Completed
                    ? (int)await _db.WalletTransactions.AsNoTracking()
                        .Where(w => w.Reference == $"task:{task.Id}")
                        .Select(w => w.Coins).FirstOrDefaultAsync(ct)
                    : 0;
                var priorBalance = (await _wallets.GetOrCreateAsync(userId, ct)).Coins;
                return Ok(new TaskResultResponse(task.Id, task.Status, priorAwarded, priorBalance, account.CoinsEarned));
            }

            int awarded = 0;
            long walletBalance;

            AppOrder? order = null;
            if (req.Success)
            {
                task.Status = TaskStatus.Completed;
                task.CompletedAt = DateTime.UtcNow;
                task.AccountId = account.Id;
                var isUpgraded = account.UpgradedAt is { } upgradedAt && DateTime.UtcNow - upgradedAt < TimeSpan.FromHours(24);

                // Dashboard-configured per-action-type base reward (see AdminRunnerSettingsController's
                // "Action Coin Pricing" panel) rather than the per-task RewardCoins column, which is
                // never actually set anywhere and always defaults to 1. The Normal/Upgraded distinction
                // already lives in this lookup, and paid-plan multipliers do not change task awards.
                var runnerSettings = await RunnerSettingsStore.GetOrCreateAsync(_db, ct);
                var baseReward = RunnerSettingsStore.GetActionCoinReward(task.TaskType, isUpgraded, runnerSettings);
                awarded = baseReward;

                account.CoinsEarned += awarded;
                account.LastActive = DateTime.UtcNow;
                var credit = await _wallets.CreditAsync(userId, awarded, WalletTransactionType.Earn, $"task:{task.Id}", ct);
                walletBalance = credit.Balance;

                order = await _db.AppOrders.FirstOrDefaultAsync(o => o.Id == task.OrderId, ct);
                if (order is not null)
                {
                    order.CompletedCount = Math.Clamp(order.CompletedCount + 1, 0, order.Quantity);
                    if (order.CompletedCount >= order.Quantity)
                    {
                        order.Status = AppOrderStatus.Completed;
                        order.CompletedAt ??= DateTime.UtcNow;
                    }
                    else if (order.Status is AppOrderStatus.Pending or AppOrderStatus.Approved or AppOrderStatus.Submitted)
                    {
                        order.Status = AppOrderStatus.InProgress;
                    }
                    order.UpdatedAt = DateTime.UtcNow;
                }
            }
            else
            {
                task.RetryCount += 1;
                task.Status = task.RetryCount >= 3 ? TaskStatus.Failed : TaskStatus.Pending;
                walletBalance = (await _wallets.GetOrCreateAsync(userId, ct)).Coins;
            }

            await _db.SaveChangesAsync(ct);
            if (tx is not null) await tx.CommitAsync(ct);
            locked = false;
            if (!useTx) ResultGate.Release();

            if (req.Success && order is { IsExternal: true } && _smmSync is not null)
            {
                _ = Task.Run(() => _smmSync.PushOrderStatusUpdatesAsync(CancellationToken.None));
            }

            // Instagram's own response for this attempt — separate from the HTTP request log, which
            // only shows that POST /api/tasks/result itself returned 200.
            var identity = this.ClientIdentity();
            var deviceModel = Request.Headers["X-Device-Model"].ToString();
            _ = _telegram?.LogInstagramActivityAsync(
                task.TaskType.ToString(), task.TargetId, account.Username, req.Success, req.Message,
                identity.AppId, deviceModel, identity.DeviceId);

            return Ok(new TaskResultResponse(task.Id, task.Status, awarded, walletBalance, account.CoinsEarned));
        }
        finally
        {
            if (locked && !useTx) ResultGate.Release();
            if (tx is not null) await tx.DisposeAsync();
        }
    }

    /// <summary>
    /// Awards a dashboard-configured reward for a manually retried Action Log entry (the Android
    /// "Retry" button on a failed log row). There is no backend task/order behind an old log entry
    /// — the device re-ran the Instagram action itself and is reporting a fresh success — so
    /// unlike <see cref="SubmitResult"/> this has nothing to look up and instead creates the
    /// EngagementTask audit row from scratch. Routing this through the same wallet-crediting path
    /// as every other award replaces what used to be a permanent, client-only credit
    /// (WalletRepository.addCoins, with no backend record at all) that could never be verified or
    /// reconciled — see TasksViewModel.retryFailedActionLog.
    /// </summary>
    [HttpPost("manual-result")]
    public async Task<ActionResult<TaskResultResponse>> SubmitManualResult(ManualActionResultRequest req, CancellationToken ct)
    {
        var userId = User.GetUserId();
        var account = await _db.Accounts.FirstOrDefaultAsync(a => a.Id == req.AccountId && a.UserId == userId, ct);
        if (account is null) return BadRequest(new ApiError("Account does not belong to user.", "invalid_account"));

        // A client-supplied idempotency key ties every resend of the same "Retry" tap (e.g. the
        // report succeeded server-side but the response never reached the client, so the user sees
        // an error and taps again) back to whatever it already paid out, instead of paying twice.
        // Older app builds that don't send a key get the previous best-effort behaviour — there's
        // no execution id to dedupe a client-only retry against otherwise.
        var reference = string.IsNullOrWhiteSpace(req.IdempotencyKey)
            ? null
            : $"manual-retry:key:{req.IdempotencyKey}";
        if (reference is not null)
        {
            var existingCoins = await _db.WalletTransactions.AsNoTracking()
                .Where(w => w.Reference == reference)
                .Select(w => (long?)w.Coins)
                .FirstOrDefaultAsync(ct);
            if (existingCoins is { } priorCoins)
            {
                var priorBalance = (await _wallets.GetOrCreateAsync(userId, ct)).Coins;
                return Ok(new TaskResultResponse(Guid.Empty, TaskStatus.Completed, (int)priorCoins, priorBalance, account.CoinsEarned));
            }
        }

        var isUpgraded = account.UpgradedAt is { } upgradedAt && DateTime.UtcNow - upgradedAt < TimeSpan.FromHours(24);
        var runnerSettings = await RunnerSettingsStore.GetOrCreateAsync(_db, ct);
        var awarded = RunnerSettingsStore.GetActionCoinReward(req.TaskType, isUpgraded, runnerSettings);

        var task = new EngagementTask
        {
            AccountId = account.Id,
            TaskType = req.TaskType,
            TargetId = req.Target,
            Status = TaskStatus.Completed,
            RewardCoins = awarded,
            CompletedAt = DateTime.UtcNow
        };
        _db.Tasks.Add(task);

        account.CoinsEarned += awarded;
        account.LastActive = DateTime.UtcNow;
        var credit = await _wallets.CreditAsync(userId, awarded, WalletTransactionType.Earn, reference ?? $"manual-retry:{task.Id}", ct);

        await _db.SaveChangesAsync(ct);

        var identity = this.ClientIdentity();
        var deviceModel = Request.Headers["X-Device-Model"].ToString();
        _ = _telegram?.LogInstagramActivityAsync(
            req.TaskType.ToString(), req.Target, account.Username, true, req.Message,
            identity.AppId, deviceModel, identity.DeviceId);

        return Ok(new TaskResultResponse(task.Id, task.Status, awarded, credit.Balance, account.CoinsEarned));
    }

    /// <summary>Retrieves completed task targets for all accounts belonging to the current user.</summary>
    [HttpGet("completed")]
    public async Task<ActionResult<IEnumerable<CompletedTaskDto>>> Completed(CancellationToken ct)
    {
        var userId = User.GetUserId();
        var accountIds = await _db.Accounts
            .Where(a => a.UserId == userId)
            .Select(a => a.Id)
            .ToListAsync(ct);

        if (accountIds.Count == 0)
        {
            return Ok(Enumerable.Empty<CompletedTaskDto>());
        }

        var completedTasks = await _db.Tasks
            .Where(t => t.Status == TaskStatus.Completed && t.AccountId != null && accountIds.Contains(t.AccountId.Value))
            .Select(t => new CompletedTaskDto(
                t.AccountId!.Value,
                t.TaskType.ToString(),
                t.TargetId,
                t.CompletedAt ?? t.CreatedAt))
            .ToListAsync(ct);

        return Ok(completedTasks);
    }
}
