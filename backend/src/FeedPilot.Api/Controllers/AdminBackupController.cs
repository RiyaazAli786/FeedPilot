using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using FeedPilot.Api.Data;
using FeedPilot.Api.Domain;
using FeedPilot.Api.Dtos;
using FeedPilot.Api.Services;

namespace FeedPilot.Api.Controllers;

/// <summary>
/// One-click export/import of everything a backend migration must not lose: users, devices,
/// linked Instagram accounts, wallets and their coin history, app orders, and withdrawals.
///
/// The export is a single JSON file the admin downloads from the dashboard; the import reads
/// that same file back in. Restore is an upsert (match by Id, else insert) rather than a wipe-
/// and-replace, so running it against a database that already has some data — the normal case
/// right after standing up a fresh backend before DNS/traffic even moves over — merges instead
/// of destroying whatever's already there.
///
/// Gated by both [AdminSession] and [BackupSession] — this is the one admin endpoint that can
/// read out or overwrite every user's data in one call, so it needs a credential beyond the
/// general admin key used for everyday dashboard work.
/// </summary>
[ApiController]
[AdminSession]
[BackupSession]
[Route("api/admin/backup")]
public class AdminBackupController : ControllerBase
{
    private readonly AppDbContext _db;
    public AdminBackupController(AppDbContext db) => _db = db;

    /// <summary>
    /// One endpoint for both operations:
    /// POST {"action":"backup"} returns the full database backup payload.
    /// POST {"action":"restore","backup":{...}} restores a payload produced by backup.
    /// </summary>
    [HttpPost("database")]
    public async Task<ActionResult<DatabaseBackupRestoreResponse>> BackupOrRestore(
        DatabaseBackupRestoreRequest body,
        CancellationToken ct)
    {
        var action = body.Action.Trim().ToLowerInvariant();
        if (action == "backup")
        {
            var exported = await Export(ct);
            if (exported.Result is ObjectResult { Value: BackupPayload payload })
            {
                return Ok(new DatabaseBackupRestoreResponse("backup", DateTime.UtcNow, Backup: payload));
            }
            return StatusCode(StatusCodes.Status500InternalServerError,
                new ApiError("Could not create database backup.", "BACKUP_FAILED"));
        }

        if (action == "restore")
        {
            if (body.Backup is null)
                return BadRequest(new ApiError("A backup payload is required for restore.", "MISSING_BACKUP"));

            var restored = await Restore(body.Backup, ct);
            if (restored.Result is ObjectResult { Value: RestoreResult result })
            {
                return Ok(new DatabaseBackupRestoreResponse("restore", DateTime.UtcNow, Restore: result));
            }
            return StatusCode(StatusCodes.Status500InternalServerError,
                new ApiError("Could not restore database backup.", "RESTORE_FAILED"));
        }

        return BadRequest(new ApiError("Action must be 'backup' or 'restore'.", "INVALID_BACKUP_ACTION"));
    }

    [HttpGet]
    public async Task<ActionResult<BackupPayload>> Export(CancellationToken ct)
    {
        var users = await _db.Users.AsNoTracking()
            .Select(u => new BackupUser(u.Id, u.Name, u.Email, u.PasswordHash, u.RefreshToken,
                u.RefreshTokenExpiresAt, u.CreatedAt, u.Plan, u.PlanExpiresAt))
            .ToListAsync(ct);

        var devices = await _db.Devices.AsNoTracking()
            .Select(d => new BackupDevice(d.Id, d.UserId, d.DeviceId, d.InstallationId, d.AppInstanceId,
                d.AndroidId, d.AndroidVersion, d.AppVersion, d.ActiveAccount, d.LoggedInAccountsJson,
                d.RegisteredAt, d.LastSeenAt, d.DeviceModel, d.AppId))
            .ToListAsync(ct);

        var accounts = await _db.Accounts.AsNoTracking()
            .Select(a => new BackupAccount(a.Id, a.UserId, a.Username, a.ProfilePictureUrl, a.SessionData,
                a.DeviceId, a.AppId, a.Status, a.LastLogin, a.LastActive, a.CoinsEarned, a.CreatedAt, a.UpgradedAt))
            .ToListAsync(ct);

        var wallets = await _db.Wallets.AsNoTracking()
            .Select(w => new BackupWallet(w.Id, w.UserId, w.Coins, w.LifetimeCoins, w.PendingCoins,
                w.WithdrawnCoins, w.UpdatedAt))
            .ToListAsync(ct);

        var walletTransactions = await _db.WalletTransactions.AsNoTracking()
            .Select(t => new BackupWalletTransaction(t.Id, t.WalletId, t.Coins, t.Type, t.Reference, t.CreatedDate))
            .ToListAsync(ct);

        var appOrders = await _db.AppOrders.AsNoTracking()
            .Select(o => new BackupAppOrder(o.Id, o.UserId, o.OrderType, o.TargetUrl, o.TargetUsername,
                o.Quantity, o.CompletedCount, o.CoinsSpent, o.Status, o.ProviderName, o.ProviderServiceId,
                o.ProviderOrderId, o.AdminNote, o.ErrorMessage, o.AppId, o.DeviceId, o.StartCount, o.IsExternal,
                o.ExternalOrderId, o.ProcessingDeviceId, o.ProcessingStartedAt, o.CreatedAt, o.UpdatedAt,
                o.CompletedAt, o.ProviderServiceName, o.ProviderUsername, o.ProviderChargeAmount,
                o.ProviderChargeCurrency, o.ProviderCreatedAt))
            .ToListAsync(ct);

        var withdrawals = await _db.Withdrawals.AsNoTracking()
            .Select(w => new BackupWithdrawal(w.Id, w.WalletId, w.Coins, w.Amount, w.PaymentMethod, w.UpiId,
                w.BankDetails, w.Status, w.AppId, w.DeviceId, w.CoinsSettled, w.AdminNote, w.PaymentReference,
                w.CreatedAt, w.ProcessedAt, w.UsdtAddress))
            .ToListAsync(ct);

        var tasks = await _db.Tasks.AsNoTracking()
            .Select(t => new BackupTask(t.Id, t.OrderId, t.AccountId, t.TaskType, t.TargetId,
                t.Status, t.RetryCount, t.RewardCoins, t.CreatedAt, t.CompletedAt))
            .ToListAsync(ct);

        var subscriptionRequests = await _db.SubscriptionRequests.AsNoTracking()
            .Select(s => new BackupSubscriptionRequest(s.Id, s.UserId, s.Plan, s.PriceInr, s.Upi, s.Utr,
                s.Status, s.AppId, s.DeviceId, s.AdminNote, s.CreatedAt, s.ProcessedAt))
            .ToListAsync(ct);

        var passwordResetRequests = await _db.PasswordResetRequests.AsNoTracking()
            .Select(p => new BackupPasswordResetRequest(p.Id, p.UserId, p.Email, p.AppId, p.DeviceId,
                p.Status, p.AdminNote, p.RequestedAt, p.ProcessedAt))
            .ToListAsync(ct);

        var coinTransfers = await _db.CoinTransfers.AsNoTracking()
            .Select(c => new BackupCoinTransfer(c.Id, c.SenderUserId, c.SenderUsername, c.ReceiverUserId,
                c.ReceiverUsername, c.Coins, c.InitiatedBy, c.Note, c.CreatedAt))
            .ToListAsync(ct);

        var runnerSettings = await _db.RunnerSettings.AsNoTracking()
            .Select(r => new BackupRunnerSettings(r.Id, r.ActionDelayMinMs, r.ActionDelayMaxMs, r.FetchDelayMs,
                r.CooldownSeconds, r.UpdatedAt, r.AutoPartialCancelledTasks,
                r.CoinsPerInr, r.MinWithdrawalInr,
                r.UpiEnabled, r.BankEnabled, r.UsdtBep20Enabled, r.CoinsPerUsdt, r.MinWithdrawalUsdt))
            .ToListAsync(ct);

        var smmProviderConfigs = await _db.SmmProviderConfigs.AsNoTracking()
            .Select(s => new BackupSmmProviderConfig(s.Id, s.BaseUrl, s.ApiKey, s.FollowServiceId,
                s.LikeServiceId, s.CommentServiceId, s.RepostServiceId, s.SavePostServiceId, s.StoryViewServiceId,
                s.PollIntervalMinutes, s.UpdatedAt,
                s.FetchIntervalSeconds, s.FetchBatchSize,
                s.StatusPushIntervalSeconds, s.StatusPushBatchSize, s.StatusPushMaxBatchesPerPass,
                s.CancelPullIntervalSeconds, s.CancelPullBatchSize))
            .ToListAsync(ct);

        var pickedUsernames = await _db.PickedUsernames.AsNoTracking()
            .Select(p => new BackupPickedUsername(p.Id, p.Username, p.UserId, p.DeviceId, p.PickedAt))
            .ToListAsync(ct);

        return Ok(new BackupPayload(DateTime.UtcNow, users, devices, accounts, wallets,
            walletTransactions, appOrders, withdrawals, tasks, subscriptionRequests,
            passwordResetRequests, coinTransfers, runnerSettings, smmProviderConfigs, pickedUsernames));
    }

    /// <summary>
    /// Restores a backup previously produced by <see cref="Export"/>. Runs as one transaction —
    /// either the whole file lands or none of it does, so a truncated upload can't leave the
    /// database half-migrated. Order matters: Users before anything that references UserId,
    /// Wallets before WalletTransactions/Withdrawals.
    /// </summary>
    [HttpPost]
    public async Task<ActionResult<RestoreResult>> Restore(BackupPayload body, CancellationToken ct)
    {
        await using var tx = await _db.Database.BeginTransactionAsync(ct);

        var users = await UpsertAsync(_db.Users, body.Users, u => u.Id, (existing, u) =>
        {
            existing.Name = u.Name;
            existing.Email = u.Email;
            existing.PasswordHash = u.PasswordHash;
            existing.RefreshToken = u.RefreshToken;
            existing.RefreshTokenExpiresAt = u.RefreshTokenExpiresAt;
            existing.CreatedAt = u.CreatedAt;
            existing.Plan = u.Plan;
            existing.PlanExpiresAt = u.PlanExpiresAt;
        }, u => new User
        {
            Id = u.Id, Name = u.Name, Email = u.Email, PasswordHash = u.PasswordHash,
            RefreshToken = u.RefreshToken, RefreshTokenExpiresAt = u.RefreshTokenExpiresAt,
            CreatedAt = u.CreatedAt, Plan = u.Plan, PlanExpiresAt = u.PlanExpiresAt
        }, ct);

        var devices = await UpsertAsync(_db.Devices, body.Devices, d => d.Id, (existing, d) =>
        {
            existing.UserId = d.UserId;
            existing.AppId = d.AppId;
            existing.DeviceId = d.DeviceId;
            existing.InstallationId = d.InstallationId;
            existing.AppInstanceId = d.AppInstanceId;
            existing.AndroidId = d.AndroidId;
            existing.AndroidVersion = d.AndroidVersion;
            existing.AppVersion = d.AppVersion;
            existing.DeviceModel = d.DeviceModel;
            existing.ActiveAccount = d.ActiveAccount;
            existing.LoggedInAccountsJson = d.LoggedInAccountsJson;
            existing.RegisteredAt = d.RegisteredAt;
            existing.LastSeenAt = d.LastSeenAt;
        }, d => new Device
        {
            Id = d.Id, UserId = d.UserId, AppId = d.AppId, DeviceId = d.DeviceId, InstallationId = d.InstallationId,
            AppInstanceId = d.AppInstanceId, AndroidId = d.AndroidId, AndroidVersion = d.AndroidVersion,
            AppVersion = d.AppVersion, DeviceModel = d.DeviceModel, ActiveAccount = d.ActiveAccount,
            LoggedInAccountsJson = d.LoggedInAccountsJson, RegisteredAt = d.RegisteredAt, LastSeenAt = d.LastSeenAt
        }, ct);

        var accounts = await UpsertAsync(_db.Accounts, body.Accounts, a => a.Id, (existing, a) =>
        {
            existing.UserId = a.UserId;
            existing.Username = a.Username;
            existing.ProfilePictureUrl = a.ProfilePictureUrl;
            existing.SessionData = a.SessionData;
            existing.DeviceId = a.DeviceId;
            existing.AppId = a.AppId;
            existing.Status = a.Status;
            existing.LastLogin = a.LastLogin;
            existing.LastActive = a.LastActive;
            existing.CoinsEarned = a.CoinsEarned;
            existing.CreatedAt = a.CreatedAt;
            existing.UpgradedAt = a.UpgradedAt;
        }, a => new Account
        {
            Id = a.Id, UserId = a.UserId, Username = a.Username, ProfilePictureUrl = a.ProfilePictureUrl,
            SessionData = a.SessionData, DeviceId = a.DeviceId, AppId = a.AppId, Status = a.Status,
            LastLogin = a.LastLogin, LastActive = a.LastActive, CoinsEarned = a.CoinsEarned, CreatedAt = a.CreatedAt,
            UpgradedAt = a.UpgradedAt
        }, ct);

        var wallets = await UpsertAsync(_db.Wallets, body.Wallets, w => w.Id, (existing, w) =>
        {
            existing.UserId = w.UserId;
            existing.Coins = w.Coins;
            existing.LifetimeCoins = w.LifetimeCoins;
            existing.PendingCoins = w.PendingCoins;
            existing.WithdrawnCoins = w.WithdrawnCoins;
            existing.UpdatedAt = w.UpdatedAt;
        }, w => new Wallet
        {
            Id = w.Id, UserId = w.UserId, Coins = w.Coins, LifetimeCoins = w.LifetimeCoins,
            PendingCoins = w.PendingCoins, WithdrawnCoins = w.WithdrawnCoins, UpdatedAt = w.UpdatedAt
        }, ct);

        var walletTransactions = await UpsertAsync(_db.WalletTransactions, body.WalletTransactions, t => t.Id, (existing, t) =>
        {
            existing.WalletId = t.WalletId;
            existing.Coins = t.Coins;
            existing.Type = t.Type;
            existing.Reference = t.Reference;
            existing.CreatedDate = t.CreatedDate;
        }, t => new WalletTransaction
        {
            Id = t.Id, WalletId = t.WalletId, Coins = t.Coins, Type = t.Type,
            Reference = t.Reference, CreatedDate = t.CreatedDate
        }, ct);

        var appOrders = await UpsertAsync(_db.AppOrders, body.AppOrders, o => o.Id, (existing, o) =>
        {
            existing.UserId = o.UserId;
            existing.OrderType = o.OrderType;
            existing.TargetUrl = o.TargetUrl;
            existing.TargetUsername = o.TargetUsername;
            existing.Quantity = o.Quantity;
            existing.CompletedCount = o.CompletedCount;
            existing.CoinsSpent = o.CoinsSpent;
            existing.Status = o.Status;
            existing.ProviderName = o.ProviderName;
            existing.ProviderServiceId = o.ProviderServiceId;
            existing.ProviderOrderId = o.ProviderOrderId;
            existing.AdminNote = o.AdminNote;
            existing.ErrorMessage = o.ErrorMessage;
            existing.AppId = o.AppId;
            existing.DeviceId = o.DeviceId;
            existing.StartCount = o.StartCount;
            existing.IsExternal = o.IsExternal;
            existing.ExternalOrderId = o.ExternalOrderId;
            existing.ProcessingDeviceId = o.ProcessingDeviceId;
            existing.ProcessingStartedAt = o.ProcessingStartedAt;
            existing.CreatedAt = o.CreatedAt;
            existing.UpdatedAt = o.UpdatedAt;
            existing.CompletedAt = o.CompletedAt;
            existing.ProviderServiceName = o.ProviderServiceName;
            existing.ProviderUsername = o.ProviderUsername;
            existing.ProviderChargeAmount = o.ProviderChargeAmount;
            existing.ProviderChargeCurrency = o.ProviderChargeCurrency;
            existing.ProviderCreatedAt = o.ProviderCreatedAt;
        }, o => new AppOrder
        {
            Id = o.Id, UserId = o.UserId, OrderType = o.OrderType, TargetUrl = o.TargetUrl,
            TargetUsername = o.TargetUsername, Quantity = o.Quantity, CompletedCount = o.CompletedCount,
            CoinsSpent = o.CoinsSpent, Status = o.Status, ProviderName = o.ProviderName,
            ProviderServiceId = o.ProviderServiceId, ProviderOrderId = o.ProviderOrderId, AdminNote = o.AdminNote,
            ErrorMessage = o.ErrorMessage, AppId = o.AppId, DeviceId = o.DeviceId, StartCount = o.StartCount, IsExternal = o.IsExternal,
            ExternalOrderId = o.ExternalOrderId, ProcessingDeviceId = o.ProcessingDeviceId,
            ProcessingStartedAt = o.ProcessingStartedAt, CreatedAt = o.CreatedAt, UpdatedAt = o.UpdatedAt,
            CompletedAt = o.CompletedAt, ProviderServiceName = o.ProviderServiceName, ProviderUsername = o.ProviderUsername,
            ProviderChargeAmount = o.ProviderChargeAmount, ProviderChargeCurrency = o.ProviderChargeCurrency,
            ProviderCreatedAt = o.ProviderCreatedAt
        }, ct);

        var withdrawals = await UpsertAsync(_db.Withdrawals, body.Withdrawals, w => w.Id, (existing, w) =>
        {
            existing.WalletId = w.WalletId;
            existing.Coins = w.Coins;
            existing.Amount = w.Amount;
            existing.PaymentMethod = w.PaymentMethod;
            existing.UpiId = w.UpiId;
            existing.BankDetails = w.BankDetails;
            existing.UsdtAddress = w.UsdtAddress;
            existing.Status = w.Status;
            existing.AppId = w.AppId;
            existing.DeviceId = w.DeviceId;
            existing.CoinsSettled = w.CoinsSettled;
            existing.AdminNote = w.AdminNote;
            existing.PaymentReference = w.PaymentReference;
            existing.CreatedAt = w.CreatedAt;
            existing.ProcessedAt = w.ProcessedAt;
        }, w => new Withdrawal
        {
            Id = w.Id, WalletId = w.WalletId, Coins = w.Coins, Amount = w.Amount, PaymentMethod = w.PaymentMethod,
            UpiId = w.UpiId, BankDetails = w.BankDetails, UsdtAddress = w.UsdtAddress, Status = w.Status,
            AppId = w.AppId, DeviceId = w.DeviceId,
            CoinsSettled = w.CoinsSettled, AdminNote = w.AdminNote, PaymentReference = w.PaymentReference,
            CreatedAt = w.CreatedAt, ProcessedAt = w.ProcessedAt
        }, ct);

        // Depends on AppOrders (OrderId) and Accounts (AccountId), both upserted above.
        var tasks = await UpsertAsync(_db.Tasks, body.Tasks ?? new(), t => t.Id, (existing, t) =>
        {
            existing.OrderId = t.OrderId;
            existing.AccountId = t.AccountId;
            existing.TaskType = t.TaskType;
            existing.TargetId = t.TargetId;
            existing.Status = t.Status;
            existing.RetryCount = t.RetryCount;
            existing.RewardCoins = t.RewardCoins;
            existing.CreatedAt = t.CreatedAt;
            existing.CompletedAt = t.CompletedAt;
        }, t => new EngagementTask
        {
            Id = t.Id, OrderId = t.OrderId, AccountId = t.AccountId, TaskType = t.TaskType,
            TargetId = t.TargetId, Status = t.Status, RetryCount = t.RetryCount, RewardCoins = t.RewardCoins,
            CreatedAt = t.CreatedAt, CompletedAt = t.CompletedAt
        }, ct);

        var subscriptionRequests = await UpsertAsync(_db.SubscriptionRequests, body.SubscriptionRequests ?? new(), s => s.Id, (existing, s) =>
        {
            existing.UserId = s.UserId;
            existing.Plan = s.Plan;
            existing.PriceInr = s.PriceInr;
            existing.Upi = s.Upi;
            existing.Utr = s.Utr;
            existing.Status = s.Status;
            existing.AppId = s.AppId;
            existing.DeviceId = s.DeviceId;
            existing.AdminNote = s.AdminNote;
            existing.CreatedAt = s.CreatedAt;
            existing.ProcessedAt = s.ProcessedAt;
        }, s => new SubscriptionRequest
        {
            Id = s.Id, UserId = s.UserId, Plan = s.Plan, PriceInr = s.PriceInr, Upi = s.Upi, Utr = s.Utr,
            Status = s.Status, AppId = s.AppId, DeviceId = s.DeviceId, AdminNote = s.AdminNote,
            CreatedAt = s.CreatedAt, ProcessedAt = s.ProcessedAt
        }, ct);

        var passwordResetRequests = await UpsertAsync(_db.PasswordResetRequests, body.PasswordResetRequests ?? new(), p => p.Id, (existing, p) =>
        {
            existing.UserId = p.UserId;
            existing.Email = p.Email;
            existing.AppId = p.AppId;
            existing.DeviceId = p.DeviceId;
            existing.Status = p.Status;
            existing.AdminNote = p.AdminNote;
            existing.RequestedAt = p.RequestedAt;
            existing.ProcessedAt = p.ProcessedAt;
        }, p => new PasswordResetRequest
        {
            Id = p.Id, UserId = p.UserId, Email = p.Email, AppId = p.AppId, DeviceId = p.DeviceId,
            Status = p.Status, AdminNote = p.AdminNote, RequestedAt = p.RequestedAt, ProcessedAt = p.ProcessedAt
        }, ct);

        var coinTransfers = await UpsertAsync(_db.CoinTransfers, body.CoinTransfers ?? new(), c => c.Id, (existing, c) =>
        {
            existing.SenderUserId = c.SenderUserId;
            existing.SenderUsername = c.SenderUsername;
            existing.ReceiverUserId = c.ReceiverUserId;
            existing.ReceiverUsername = c.ReceiverUsername;
            existing.Coins = c.Coins;
            existing.InitiatedBy = c.InitiatedBy;
            existing.Note = c.Note;
            existing.CreatedAt = c.CreatedAt;
        }, c => new CoinTransfer
        {
            Id = c.Id, SenderUserId = c.SenderUserId, SenderUsername = c.SenderUsername,
            ReceiverUserId = c.ReceiverUserId, ReceiverUsername = c.ReceiverUsername, Coins = c.Coins,
            InitiatedBy = c.InitiatedBy, Note = c.Note, CreatedAt = c.CreatedAt
        }, ct);

        // Singleton (Id=1) config rows — same upsert-by-id path, just keyed by int instead of Guid.
        var runnerSettings = await UpsertAsync(_db.RunnerSettings, body.RunnerSettings ?? new(), r => r.Id, (existing, r) =>
        {
            existing.ActionDelayMinMs = r.ActionDelayMinMs;
            existing.ActionDelayMaxMs = r.ActionDelayMaxMs;
            existing.FetchDelayMs = r.FetchDelayMs;
            existing.CooldownSeconds = r.CooldownSeconds;
            existing.AutoPartialCancelledTasks = r.AutoPartialCancelledTasks;
            existing.CoinsPerInr = r.CoinsPerInr;
            existing.MinWithdrawalInr = r.MinWithdrawalInr;
            existing.UpiEnabled = r.UpiEnabled;
            existing.BankEnabled = r.BankEnabled;
            existing.UsdtBep20Enabled = r.UsdtBep20Enabled;
            existing.CoinsPerUsdt = r.CoinsPerUsdt;
            existing.MinWithdrawalUsdt = r.MinWithdrawalUsdt;
            existing.UpdatedAt = r.UpdatedAt;
        }, r => new RunnerSettings
        {
            Id = r.Id,
            ActionDelayMinMs = r.ActionDelayMinMs,
            ActionDelayMaxMs = r.ActionDelayMaxMs,
            FetchDelayMs = r.FetchDelayMs,
            CooldownSeconds = r.CooldownSeconds,
            AutoPartialCancelledTasks = r.AutoPartialCancelledTasks,
            CoinsPerInr = r.CoinsPerInr,
            MinWithdrawalInr = r.MinWithdrawalInr,
            UpiEnabled = r.UpiEnabled,
            BankEnabled = r.BankEnabled,
            UsdtBep20Enabled = r.UsdtBep20Enabled,
            CoinsPerUsdt = r.CoinsPerUsdt,
            MinWithdrawalUsdt = r.MinWithdrawalUsdt,
            UpdatedAt = r.UpdatedAt
        }, ct);

        var smmProviderConfigs = await UpsertAsync(_db.SmmProviderConfigs, body.SmmProviderConfigs ?? new(), s => s.Id, (existing, s) =>
        {
            existing.BaseUrl = s.BaseUrl;
            existing.ApiKey = s.ApiKey;
            existing.FollowServiceId = s.FollowServiceId;
            existing.LikeServiceId = s.LikeServiceId;
            existing.CommentServiceId = s.CommentServiceId;
            existing.RepostServiceId = s.RepostServiceId;
            existing.SavePostServiceId = s.SavePostServiceId;
            existing.StoryViewServiceId = s.StoryViewServiceId;
            existing.PollIntervalMinutes = s.PollIntervalMinutes;
            existing.FetchIntervalSeconds = s.FetchIntervalSeconds;
            existing.FetchBatchSize = s.FetchBatchSize;
            existing.StatusPushIntervalSeconds = s.StatusPushIntervalSeconds;
            existing.StatusPushBatchSize = s.StatusPushBatchSize;
            existing.StatusPushMaxBatchesPerPass = s.StatusPushMaxBatchesPerPass;
            existing.CancelPullIntervalSeconds = s.CancelPullIntervalSeconds;
            existing.CancelPullBatchSize = s.CancelPullBatchSize;
            existing.UpdatedAt = s.UpdatedAt;
        }, s => new SmmProviderConfig
        {
            Id = s.Id, BaseUrl = s.BaseUrl, ApiKey = s.ApiKey, FollowServiceId = s.FollowServiceId,
            LikeServiceId = s.LikeServiceId, CommentServiceId = s.CommentServiceId,
            RepostServiceId = s.RepostServiceId, SavePostServiceId = s.SavePostServiceId,
            StoryViewServiceId = s.StoryViewServiceId,
            PollIntervalMinutes = s.PollIntervalMinutes,
            FetchIntervalSeconds = s.FetchIntervalSeconds, FetchBatchSize = s.FetchBatchSize,
            StatusPushIntervalSeconds = s.StatusPushIntervalSeconds, StatusPushBatchSize = s.StatusPushBatchSize,
            StatusPushMaxBatchesPerPass = s.StatusPushMaxBatchesPerPass,
            CancelPullIntervalSeconds = s.CancelPullIntervalSeconds, CancelPullBatchSize = s.CancelPullBatchSize,
            UpdatedAt = s.UpdatedAt
        }, ct);

        var pickedUsernames = await UpsertAsync(_db.PickedUsernames, body.PickedUsernames ?? new(), p => p.Id, (existing, p) =>
        {
            existing.Username = p.Username;
            existing.UserId = p.UserId;
            existing.DeviceId = p.DeviceId;
            existing.PickedAt = p.PickedAt;
        }, p => new PickedUsername
        {
            Id = p.Id, Username = p.Username, UserId = p.UserId, DeviceId = p.DeviceId, PickedAt = p.PickedAt
        }, ct);

        await _db.SaveChangesAsync(ct);
        await tx.CommitAsync(ct);

        return Ok(new RestoreResult(users, devices, accounts, wallets, walletTransactions, appOrders, withdrawals,
            tasks, subscriptionRequests, passwordResetRequests, coinTransfers, runnerSettings, smmProviderConfigs, pickedUsernames));
    }

    /// <summary>
    /// Deletes all orders imported from the SMM panel (where IsExternal is true)
    /// along with their associated worker engagement tasks.
    /// </summary>
    [HttpDelete("clear-smm")]
    public async Task<ActionResult<object>> ClearSmmOrders(CancellationToken ct)
    {
        await using var tx = await _db.Database.BeginTransactionAsync(ct);

        var externalOrderIds = await _db.AppOrders
            .Where(o => o.IsExternal)
            .Select(o => o.Id)
            .ToListAsync(ct);

        if (externalOrderIds.Count > 0)
        {
            var relatedTasks = await _db.Tasks
                .Where(t => externalOrderIds.Contains(t.OrderId))
                .ToListAsync(ct);

            _db.Tasks.RemoveRange(relatedTasks);

            var externalOrders = await _db.AppOrders
                .Where(o => o.IsExternal)
                .ToListAsync(ct);

            _db.AppOrders.RemoveRange(externalOrders);

            await _db.SaveChangesAsync(ct);
        }

        await tx.CommitAsync(ct);

        return Ok(new { deletedCount = externalOrderIds.Count });
    }


    /// <summary>
    /// Generic upsert-by-id: updates the tracked entity's fields in place when a row with this id
    /// already exists, otherwise builds and adds a new one. Returns how many rows were processed.
    /// TKey is generic (not just Guid) so the two singleton config tables — RunnerSettings and
    /// SmmProviderConfig, both keyed by a plain int — can go through the same helper.
    /// </summary>
    private static async Task<int> UpsertAsync<TEntity, TBackup, TKey>(
        DbSet<TEntity> set,
        List<TBackup> items,
        Func<TBackup, TKey> idOf,
        Action<TEntity, TBackup> applyTo,
        Func<TBackup, TEntity> build,
        CancellationToken ct)
        where TEntity : class
        where TKey : notnull
    {
        var count = 0;
        foreach (var item in items)
        {
            var id = idOf(item);
            var existing = await set.FindAsync([id], ct);
            if (existing is null)
            {
                set.Add(build(item));
            }
            else
            {
                applyTo(existing, item);
            }
            count++;
        }
        return count;
    }
}
