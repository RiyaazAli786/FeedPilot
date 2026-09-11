using System.Diagnostics;
using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using FeedPilot.Api.Data;
using FeedPilot.Api.Domain;
using FeedPilot.Api.Dtos;

namespace FeedPilot.Api.Services;

public interface IFeedBridgeService
{
    Task<BridgeFeedResponse> ExecuteFeedAsync(BridgeFeedRequest request, CancellationToken ct);
    Task<BridgeHeartbeatResponse> HeartbeatAsync(Guid? userId, string appId, BridgeHeartbeatRequest request, CancellationToken ct);
    Task<BridgeJobDto?> PollAsync(string appId, string deviceId, string installationId, CancellationToken ct);
    Task<BridgeJobResultDto> CompleteAsync(Guid id, string appId, string deviceId, CompleteBridgeJobRequest request, CancellationToken ct);
    Task<BridgeJobResultDto> FailAsync(Guid id, string appId, string deviceId, FailBridgeJobRequest request, CancellationToken ct);
    Task SweepExpiredAsync(CancellationToken ct);
}

public class FeedBridgeService : IFeedBridgeService
{
    private static readonly SemaphoreSlim SelectionGate = new(1, 1);
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);
    private static readonly TimeSpan OnlineWindow = TimeSpan.FromMinutes(5);
    private static readonly TimeSpan JobTimeout = TimeSpan.FromSeconds(30);
    private static readonly TimeSpan JobExpiry = TimeSpan.FromSeconds(40);

    private readonly AppDbContext _db;
    private readonly ILogger<FeedBridgeService> _logger;

    public FeedBridgeService(AppDbContext db, ILogger<FeedBridgeService> logger)
    {
        _db = db;
        _logger = logger;
    }

    public async Task<BridgeFeedResponse> ExecuteFeedAsync(BridgeFeedRequest request, CancellationToken ct)
    {
        var username = NormalizeUsername(request.Username);
        var userId = request.UserId?.Trim();
        if (string.IsNullOrWhiteSpace(username) && string.IsNullOrWhiteSpace(userId))
        {
            return Fail("INVALID_REQUEST", "Username or userId is required.");
        }

        var sw = Stopwatch.StartNew();
        FeedBridgeJob? job;
        FeedBridgeAccountState? account;

        await SelectionGate.WaitAsync(ct);
        try
        {
            await ReleaseStaleAssignmentsAsync(ct);
            var selected = await SelectAccountAsync(ct);
            if (selected is null)
                return Fail("NO_ACTIVE_SESSION", await BuildNoActiveSessionMessageAsync(ct));

            account = selected;
            var now = DateTime.UtcNow;
            account.IsBusy = true;
            account.LastUsedAt = now;
            account.UpdatedAt = now;

            job = new FeedBridgeJob
            {
                ExternalRequestId = string.IsNullOrWhiteSpace(request.RequestId) ? null : request.RequestId.Trim(),
                ClientName = string.IsNullOrWhiteSpace(request.Client) ? "GetIGFeed" : request.Client.Trim(),
                TargetUsername = username,
                TargetUserId = userId,
                Limit = Math.Clamp(request.Limit, 1, 50),
                MaxId = string.IsNullOrWhiteSpace(request.MaxId) ? null : request.MaxId.Trim(),
                IncludeStories = request.IncludeStories,
                Status = FeedBridgeJobStatus.Assigned,
                AssignedAccountId = account.AccountId,
                AssignedAccountStateId = account.Id,
                AssignedDeviceId = account.DeviceId,
                AssignedInstallationId = account.InstallationId,
                AssignedAt = now,
                ExpiresAt = now.Add(JobExpiry),
                RequestJson = JsonSerializer.Serialize(request, JsonOptions),
                UpdatedAt = now
            };
            _db.FeedBridgeJobs.Add(job);
            await _db.SaveChangesAsync(ct);
        }
        finally
        {
            SelectionGate.Release();
        }

        var deadline = DateTime.UtcNow.Add(JobTimeout);
        while (DateTime.UtcNow < deadline && !ct.IsCancellationRequested)
        {
            await Task.Delay(500, ct);
            await _db.Entry(job).ReloadAsync(ct);
            if (job.Status == FeedBridgeJobStatus.Completed)
            {
                object? data = null;
                if (!string.IsNullOrWhiteSpace(job.ResponseJson))
                    data = JsonSerializer.Deserialize<object>(job.ResponseJson, JsonOptions);

                return new BridgeFeedResponse(
                    Success: true,
                    Source: "android-device",
                    JobId: job.Id,
                    DeviceId: job.AssignedDeviceId,
                    AccountUsername: account.InstagramUsername,
                    DurationMs: sw.ElapsedMilliseconds,
                    Data: data);
            }

            if (job.Status is FeedBridgeJobStatus.Failed or FeedBridgeJobStatus.Cancelled or FeedBridgeJobStatus.TimedOut)
            {
                return Fail(job.ErrorCode ?? "JOB_FAILED", job.ErrorMessage ?? "Feed bridge job failed.", job.Id);
            }
        }

        await MarkTimedOutAsync(job, ct);
        return Fail("JOB_TIMEOUT", "Android device did not complete the feed job before timeout.", job.Id);
    }

    public async Task<BridgeHeartbeatResponse> HeartbeatAsync(Guid? userId, string appId, BridgeHeartbeatRequest request, CancellationToken ct)
    {
        var now = DateTime.UtcNow;
        var deviceId = CleanRequired(request.DeviceId);
        var installationId = CleanRequired(request.InstallationId);

        var device = await _db.FeedBridgeDeviceStates.FirstOrDefaultAsync(d =>
            d.AppId == appId &&
            d.DeviceId == deviceId &&
            d.InstallationId == installationId, ct);

        if (device is null)
        {
            device = new FeedBridgeDeviceState
            {
                AppId = appId,
                DeviceId = deviceId,
                InstallationId = installationId,
                CreatedAt = now
            };
            _db.FeedBridgeDeviceStates.Add(device);
        }

        device.UserId = userId ?? device.UserId;
        device.AppInstanceId = request.AppInstanceId;
        device.DeviceModel = request.DeviceModel;
        device.AndroidVersion = request.AndroidVersion;
        device.AppVersion = request.AppVersion;
        device.LastSeenAt = now;
        device.BridgeEnabled = request.BridgeEnabled;
        device.BatteryLevel = request.BatteryLevel;
        device.NetworkType = request.NetworkType;
        device.ActiveAccountUsername = NormalizeUsername(request.ActiveAccountUsername);
        device.LoggedInAccountsJson = request.LoggedInAccounts is null ? device.LoggedInAccountsJson : JsonSerializer.Serialize(request.LoggedInAccounts, JsonOptions);
        device.CapabilitiesJson = request.Capabilities is null ? device.CapabilitiesJson : JsonSerializer.Serialize(request.Capabilities, JsonOptions);
        device.UpdatedAt = now;

        var existingDevice = await _db.Devices.FirstOrDefaultAsync(d =>
            d.AppId == appId && d.DeviceId == deviceId && d.InstallationId == installationId, ct);
        if (existingDevice is not null)
        {
            existingDevice.LastSeenAt = now;
            existingDevice.ActiveAccount = request.ActiveAccountUsername ?? existingDevice.ActiveAccount;
            if (request.LoggedInAccounts is not null)
                existingDevice.LoggedInAccountsJson = JsonSerializer.Serialize(request.LoggedInAccounts, JsonOptions);
        }

        foreach (var reported in request.Accounts ?? new List<BridgeHeartbeatAccountDto>())
        {
            var username = NormalizeUsername(reported.Username);
            if (string.IsNullOrWhiteSpace(username) || !reported.IsLoggedIn) continue;
            var accountId = Guid.TryParse(reported.AccountId, out var parsedId) ? parsedId : (Guid?)null;
            var account = await _db.FeedBridgeAccountStates.FirstOrDefaultAsync(a =>
                a.AppId == appId &&
                a.DeviceId == deviceId &&
                a.InstallationId == installationId &&
                a.InstagramUsername == username, ct);

            if (account is null)
            {
                account = new FeedBridgeAccountState
                {
                    AppId = appId,
                    DeviceId = deviceId,
                    InstallationId = installationId,
                    InstagramUsername = username,
                    CreatedAt = now
                };
                _db.FeedBridgeAccountStates.Add(account);
            }

            account.AccountId = accountId ?? account.AccountId;
            account.UserId = userId ?? account.UserId;
            account.InstagramUserId = reported.InstagramUserId ?? account.InstagramUserId;
            account.Status = ParseAccountStatus(reported.Status);
            account.LastValidatedAt = now;
            account.UpdatedAt = now;
            if (account.Status == AccountStatus.Active)
            {
                account.LastErrorCode = null;
                account.LastErrorMessage = null;
            }
        }

        await _db.SaveChangesAsync(ct);

        var pendingJobs = await _db.FeedBridgeJobs.CountAsync(j =>
            j.AssignedDeviceId == deviceId &&
            j.AssignedInstallationId == installationId &&
            (j.Status == FeedBridgeJobStatus.Assigned || j.Status == FeedBridgeJobStatus.Running), ct);

        return new BridgeHeartbeatResponse(true, now, pendingJobs);
    }

    public async Task<BridgeJobDto?> PollAsync(string appId, string deviceId, string installationId, CancellationToken ct)
    {
        var now = DateTime.UtcNow;
        var job = await _db.FeedBridgeJobs
            .Where(j =>
                j.AssignedDeviceId == deviceId &&
                j.AssignedInstallationId == installationId &&
                (j.Status == FeedBridgeJobStatus.Assigned || j.Status == FeedBridgeJobStatus.Running) &&
                j.ExpiresAt > now)
            .OrderBy(j => j.CreatedAt)
            .FirstOrDefaultAsync(ct);

        if (job is null) return null;

        job.Status = FeedBridgeJobStatus.Running;
        job.UpdatedAt = now;
        await _db.SaveChangesAsync(ct);

        string? accountUsername = null;
        if (job.AssignedAccountStateId is { } stateId)
        {
            accountUsername = await _db.FeedBridgeAccountStates.AsNoTracking()
                .Where(a => a.Id == stateId)
                .Select(a => a.InstagramUsername)
                .FirstOrDefaultAsync(ct);
        }

        return new BridgeJobDto(job.Id, job.TargetUsername, job.TargetUserId, job.Limit, job.MaxId,
            job.IncludeStories, job.ExpiresAt, job.AssignedAccountId, accountUsername);
    }

    public async Task<BridgeJobResultDto> CompleteAsync(Guid id, string appId, string deviceId, CompleteBridgeJobRequest request, CancellationToken ct)
    {
        var job = await FindDeviceJobAsync(id, deviceId, ct);
        if (job is null) return new BridgeJobResultDto(false, "NotFound");

        var now = DateTime.UtcNow;
        job.Status = FeedBridgeJobStatus.Completed;
        job.ResponseJson = JsonSerializer.Serialize(request.Data, JsonOptions);
        job.CompletedAt = now;
        job.UpdatedAt = now;
        job.ErrorCode = null;
        job.ErrorMessage = null;
        await ReleaseAssignedAccountAsync(job, success: true, errorCode: null, errorMessage: null, ct);
        await _db.SaveChangesAsync(ct);
        return new BridgeJobResultDto(true, job.Status.ToString());
    }

    public async Task<BridgeJobResultDto> FailAsync(Guid id, string appId, string deviceId, FailBridgeJobRequest request, CancellationToken ct)
    {
        var job = await FindDeviceJobAsync(id, deviceId, ct);
        if (job is null) return new BridgeJobResultDto(false, "NotFound");

        var now = DateTime.UtcNow;
        job.Status = FeedBridgeJobStatus.Failed;
        job.ErrorCode = request.Code;
        job.ErrorMessage = request.Message;
        job.CompletedAt = now;
        job.UpdatedAt = now;
        await ReleaseAssignedAccountAsync(job, success: false, errorCode: request.Code, errorMessage: request.Message, ct);
        await _db.SaveChangesAsync(ct);
        return new BridgeJobResultDto(true, job.Status.ToString());
    }

    public async Task SweepExpiredAsync(CancellationToken ct)
    {
        var now = DateTime.UtcNow;
        var expired = await _db.FeedBridgeJobs
            .Where(j => (j.Status == FeedBridgeJobStatus.Assigned || j.Status == FeedBridgeJobStatus.Running) && j.ExpiresAt <= now)
            .ToListAsync(ct);

        foreach (var job in expired)
        {
            job.Status = FeedBridgeJobStatus.TimedOut;
            job.ErrorCode = "JOB_TIMEOUT";
            job.ErrorMessage = "Bridge job expired before device completed it.";
            job.CompletedAt = now;
            job.UpdatedAt = now;
            await ReleaseAssignedAccountAsync(job, success: false, errorCode: job.ErrorCode, errorMessage: job.ErrorMessage, ct);
        }

        if (expired.Count > 0)
        {
            await _db.SaveChangesAsync(ct);
            _logger.LogInformation("Feed bridge sweep timed out {Count} job(s).", expired.Count);
        }
    }

    private async Task<FeedBridgeAccountState?> SelectAccountAsync(CancellationToken ct)
    {
        var now = DateTime.UtcNow;
        var onlineAfter = now.Subtract(OnlineWindow);

        var onlineDevices = _db.FeedBridgeDeviceStates.AsNoTracking()
            .Where(d => d.BridgeEnabled && d.LastSeenAt >= onlineAfter && (d.CooldownUntil == null || d.CooldownUntil <= now))
            .Select(d => new { d.AppId, d.DeviceId, d.InstallationId });

        return await _db.FeedBridgeAccountStates
            .Where(a =>
                a.Status == AccountStatus.Active &&
                !a.IsBusy &&
                (a.CooldownUntil == null || a.CooldownUntil <= now))
            .Join(onlineDevices,
                a => new { a.AppId, a.DeviceId, a.InstallationId },
                d => new { d.AppId, d.DeviceId, d.InstallationId },
                (a, _) => a)
            .OrderBy(a => a.FailureCount)
            .ThenBy(a => a.LastUsedAt ?? DateTime.MinValue)
            .ThenBy(a => a.CreatedAt)
            .FirstOrDefaultAsync(ct);
    }

    private async Task ReleaseStaleAssignmentsAsync(CancellationToken ct)
    {
        var now = DateTime.UtcNow;
        var staleJobs = await _db.FeedBridgeJobs
            .Where(j => (j.Status == FeedBridgeJobStatus.Assigned || j.Status == FeedBridgeJobStatus.Running) && j.ExpiresAt <= now)
            .ToListAsync(ct);

        foreach (var job in staleJobs)
        {
            job.Status = FeedBridgeJobStatus.TimedOut;
            job.ErrorCode = "JOB_TIMEOUT";
            job.ErrorMessage = "Bridge job expired before device completed it.";
            job.CompletedAt = now;
            job.UpdatedAt = now;
            await ReleaseAssignedAccountAsync(job, success: false, errorCode: job.ErrorCode, errorMessage: job.ErrorMessage, ct);
        }

        var staleBusyBefore = now.Subtract(TimeSpan.FromMinutes(2));
        var staleBusyAccounts = await _db.FeedBridgeAccountStates
            .Where(a => a.IsBusy && a.UpdatedAt <= staleBusyBefore)
            .ToListAsync(ct);
        foreach (var account in staleBusyAccounts)
        {
            account.IsBusy = false;
            account.UpdatedAt = now;
        }

        if (staleJobs.Count > 0 || staleBusyAccounts.Count > 0)
        {
            await _db.SaveChangesAsync(ct);
            _logger.LogInformation("Feed bridge released {JobCount} stale job(s) and {AccountCount} stale busy account(s).",
                staleJobs.Count, staleBusyAccounts.Count);
        }
    }

    private async Task<string> BuildNoActiveSessionMessageAsync(CancellationToken ct)
    {
        var now = DateTime.UtcNow;
        var onlineAfter = now.Subtract(OnlineWindow);
        var deviceCount = await _db.FeedBridgeDeviceStates.CountAsync(ct);
        var onlineDeviceCount = await _db.FeedBridgeDeviceStates.CountAsync(d =>
            d.BridgeEnabled && d.LastSeenAt >= onlineAfter && (d.CooldownUntil == null || d.CooldownUntil <= now), ct);
        var accountCount = await _db.FeedBridgeAccountStates.CountAsync(ct);
        var activeAccountCount = await _db.FeedBridgeAccountStates.CountAsync(a => a.Status == AccountStatus.Active, ct);
        var availableAccountCount = await _db.FeedBridgeAccountStates.CountAsync(a =>
            a.Status == AccountStatus.Active &&
            !a.IsBusy &&
            (a.CooldownUntil == null || a.CooldownUntil <= now), ct);

        return "No active Android device with a valid Instagram session is available. " +
               $"Diagnostics: devices={deviceCount}, onlineDevices={onlineDeviceCount}, " +
               $"accounts={accountCount}, activeAccounts={activeAccountCount}, availableAccounts={availableAccountCount}.";
    }

    private async Task MarkTimedOutAsync(FeedBridgeJob job, CancellationToken ct)
    {
        await _db.Entry(job).ReloadAsync(ct);
        if (job.Status is FeedBridgeJobStatus.Completed or FeedBridgeJobStatus.Failed) return;
        job.Status = FeedBridgeJobStatus.TimedOut;
        job.ErrorCode = "JOB_TIMEOUT";
        job.ErrorMessage = "Android device did not complete the feed job before timeout.";
        job.CompletedAt = DateTime.UtcNow;
        job.UpdatedAt = DateTime.UtcNow;
        await ReleaseAssignedAccountAsync(job, success: false, errorCode: job.ErrorCode, errorMessage: job.ErrorMessage, ct);
        await _db.SaveChangesAsync(ct);
    }

    private async Task ReleaseAssignedAccountAsync(FeedBridgeJob job, bool success, string? errorCode, string? errorMessage, CancellationToken ct)
    {
        if (job.AssignedAccountStateId is not { } stateId) return;
        var account = await _db.FeedBridgeAccountStates.FirstOrDefaultAsync(a => a.Id == stateId, ct);
        if (account is null) return;

        account.IsBusy = false;
        account.UpdatedAt = DateTime.UtcNow;
        if (success)
        {
            account.FailureCount = 0;
            account.LastErrorCode = null;
            account.LastErrorMessage = null;
            return;
        }

        account.LastErrorCode = errorCode;
        account.LastErrorMessage = errorMessage;
        if (errorCode is "IG_SESSION_EXPIRED")
        {
            account.FailureCount += 1;
            account.Status = AccountStatus.SessionExpired;
        }
        else if (errorCode is "IG_CHECKPOINT_REQUIRED")
        {
            account.FailureCount += 1;
            account.Status = AccountStatus.Checkpoint;
        }
        else if (errorCode is "IG_CAPTCHA_REQUIRED")
        {
            account.FailureCount += 1;
            account.Status = AccountStatus.Captcha;
        }
        else if (errorCode is "IG_RATE_LIMITED")
        {
            account.FailureCount += 1;
            account.CooldownUntil = DateTime.UtcNow.AddMinutes(30);
        }
        else if (errorCode is "IG_EMPTY_FEED" or "IG_TARGET_NOT_FOUND" or "IG_PRIVATE_PROFILE" or "INVALID_TARGET")
        {
            account.CooldownUntil = null;
        }
        else
        {
            account.FailureCount += 1;
            account.CooldownUntil = DateTime.UtcNow.AddMinutes(Math.Min(10, Math.Max(2, account.FailureCount)));
        }
    }

    private async Task<FeedBridgeJob?> FindDeviceJobAsync(Guid id, string deviceId, CancellationToken ct) =>
        await _db.FeedBridgeJobs.FirstOrDefaultAsync(j => j.Id == id && j.AssignedDeviceId == deviceId, ct);

    private static BridgeFeedResponse Fail(string code, string message, Guid? jobId = null) =>
        new(false, "android-device", jobId, Code: code, Message: message);

    private static string? NormalizeUsername(string? value)
    {
        if (string.IsNullOrWhiteSpace(value)) return null;
        return value.Trim().TrimStart('@').Split('?', '#')[0].Trim('/').ToLowerInvariant();
    }

    private static string CleanRequired(string value) => value.Trim();

    private static AccountStatus ParseAccountStatus(string? value) =>
        Enum.TryParse<AccountStatus>(value, true, out var status) ? status : AccountStatus.Unknown;
}
