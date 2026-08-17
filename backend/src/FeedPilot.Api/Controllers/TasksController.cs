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
    private readonly ISubscriptionService _subscriptions;
    private readonly TelegramRequestLogger? _telegram;

    public TasksController(
        AppDbContext db,
        IWalletService wallets,
        ISubscriptionService subscriptions,
        TelegramRequestLogger? telegram = null)
    {
        _db = db;
        _wallets = wallets;
        _subscriptions = subscriptions;
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
        var task = await _db.Tasks.FirstOrDefaultAsync(t => t.Id == req.TaskId, ct);
        if (task is null) return NotFound(new ApiError("Task not found.", "not_found"));

        var account = await _db.Accounts.FirstOrDefaultAsync(a => a.Id == req.AccountId && a.UserId == userId, ct);
        if (account is null) return BadRequest(new ApiError("Account does not belong to user.", "invalid_account"));

        int awarded = 0;
        long walletBalance = 0;

        AppOrder? order = null;
        if (req.Success)
        {
            task.Status = TaskStatus.Completed;
            task.CompletedAt = DateTime.UtcNow;
            task.AccountId = account.Id;
            // A paid plan multiplies the reward (2× / 3× / 5×).
            var multiplier = await _subscriptions.GetCoinMultiplierAsync(userId, ct);
            var isUpgraded = account.UpgradedAt is { } upgradedAt && DateTime.UtcNow - upgradedAt < TimeSpan.FromHours(24);

            // Dashboard-configured per-action-type base reward (see AdminRunnerSettingsController's
            // "Action Coin Pricing" panel) rather than the per-task RewardCoins column, which is
            // never actually set anywhere and always defaults to 1. The Normal/Upgraded distinction
            // already lives in this lookup, so — unlike the old flat-reward code — the multiplier
            // here is purely the paid-plan multiplier, not also floored to 2 for an upgraded free
            // account (that would double the upgrade bonus on top of the Upgraded column already
            // being higher than Normal).
            var runnerSettings = await RunnerSettingsStore.GetOrCreateAsync(_db, ct);
            var baseReward = RunnerSettingsStore.GetActionCoinReward(task.TaskType, isUpgraded, runnerSettings);
            awarded = baseReward * multiplier;

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
        }

        await _db.SaveChangesAsync(ct);

        if (!req.Success)
        {
            var wallet = await _wallets.GetOrCreateAsync(userId, ct);
            walletBalance = wallet.Coins;
        }

        // Instagram's own response for this attempt — separate from the HTTP request log, which
        // only shows that POST /api/tasks/result itself returned 200.
        var identity = this.ClientIdentity();
        var deviceModel = Request.Headers["X-Device-Model"].ToString();
        _ = _telegram?.LogInstagramActivityAsync(
            task.TaskType.ToString(), task.TargetId, account.Username, req.Success, req.Message,
            identity.AppId, deviceModel, identity.DeviceId);

        return Ok(new TaskResultResponse(task.Id, task.Status, awarded, walletBalance));
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
