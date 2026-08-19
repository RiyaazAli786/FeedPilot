using System.Text.Json;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using FeedPilot.Api.Data;
using FeedPilot.Api.Domain;
using FeedPilot.Api.Dtos;
using FeedPilot.Api.Services;

namespace FeedPilot.Api.Controllers;

[ApiController]
[Route("api/devices")]
public class DevicesController : ControllerBase
{
    private readonly AppDbContext _db;
    public DevicesController(AppDbContext db) => _db = db;

    /// <summary>
    /// Registers (or updates) a device/installation. Supports multi-instance installs:
    /// each cloned install carries a unique InstallationId + AppInstanceId.
    /// Also records active logged-in accounts on the device for live dashboard rendering.
    /// </summary>
    [AllowAnonymous]
    [HttpPost("register")]
    public async Task<ActionResult<DeviceDto>> Register(RegisterDeviceRequest req, CancellationToken ct)
    {
        var identity = this.ClientIdentity();
        var appId = identity.AppId;
        var deviceId = string.IsNullOrWhiteSpace(req.DeviceId) ? identity.DeviceId : req.DeviceId.Trim();
        var installationId = string.IsNullOrWhiteSpace(req.InstallationId) ? deviceId : req.InstallationId.Trim();

        var userId = User.Identity?.IsAuthenticated == true ? User.GetUserId() : (Guid?)null;
        var device = await FindDeviceAsync(appId, deviceId, installationId, req.AppInstanceId, userId, ct);
        if (device is null)
        {
            device = new Device { InstallationId = installationId };
            _db.Devices.Add(device);
        }
        else
        {
            await MergeDuplicateDeviceRowsAsync(device, appId, deviceId, ct);
        }

        device.AppId = appId;
        device.DeviceId = deviceId;
        device.InstallationId = installationId;
        device.AppInstanceId = req.AppInstanceId;
        device.AndroidId = req.AndroidId;
        device.AndroidVersion = req.AndroidVersion;
        device.AppVersion = req.AppVersion;
        device.DeviceModel = req.DeviceModel;
        if (!string.IsNullOrWhiteSpace(req.ActiveAccount)) device.ActiveAccount = req.ActiveAccount;
        if (req.LoggedInAccounts is not null)
            device.LoggedInAccountsJson = JsonSerializer.Serialize(req.LoggedInAccounts);
        device.AppInstanceId = req.AppInstanceId;

        device.LastSeenAt = DateTime.UtcNow;
        if (userId.HasValue)
            device.UserId = userId.Value;

        await _db.SaveChangesAsync(ct);

        var loggedInList = parseLoggedInAccounts(device.LoggedInAccountsJson);
        return Ok(new DeviceDto(device.Id, device.AppId, device.DeviceId, device.InstallationId,
            device.AppInstanceId, device.ActiveAccount, loggedInList, device.RegisteredAt));
    }

    /// <summary>
    /// Called by the app when opened or when accounts change to sync live logged-in account status to the dashboard.
    /// </summary>
    [AllowAnonymous]
    [HttpPost("sync-accounts")]
    public async Task<ActionResult<DeviceDto>> SyncAccounts(SyncDeviceAccountsRequest req, CancellationToken ct)
    {
        var identity = this.ClientIdentity();
        var appId = identity.AppId;
        var deviceId = string.IsNullOrWhiteSpace(req.DeviceId) ? identity.DeviceId : req.DeviceId.Trim();
        var installationId = string.IsNullOrWhiteSpace(req.InstallationId) ? deviceId : req.InstallationId.Trim();

        var userId = User.Identity?.IsAuthenticated == true ? User.GetUserId() : (Guid?)null;
        var device = await FindDeviceAsync(appId, deviceId, installationId, req.AppInstanceId, userId, ct);
        if (device is null)
        {
            device = new Device
            {
                AppId = appId,
                DeviceId = deviceId,
                InstallationId = installationId
            };
            _db.Devices.Add(device);
        }
        else
        {
            await MergeDuplicateDeviceRowsAsync(device, appId, deviceId, ct);
            device.AppId = appId;
            device.DeviceId = deviceId;
            device.InstallationId = installationId;
        }

        if (!string.IsNullOrWhiteSpace(req.ActiveAccount)) device.ActiveAccount = req.ActiveAccount;
        if (req.LoggedInAccounts is not null)
            device.LoggedInAccountsJson = JsonSerializer.Serialize(req.LoggedInAccounts);

        device.LastSeenAt = DateTime.UtcNow;
        if (userId.HasValue)
            device.UserId = userId.Value;

        await _db.SaveChangesAsync(ct);

        var loggedInList = parseLoggedInAccounts(device.LoggedInAccountsJson);
        return Ok(new DeviceDto(device.Id, device.AppId, device.DeviceId, device.InstallationId,
            device.AppInstanceId, device.ActiveAccount, loggedInList, device.RegisteredAt));
    }

    private static List<string>? parseLoggedInAccounts(string? json)
    {
        if (string.IsNullOrWhiteSpace(json)) return null;
        try { return JsonSerializer.Deserialize<List<string>>(json); }
        catch { return null; }
    }

    private async Task<Device?> FindDeviceAsync(
        string appId,
        string deviceId,
        string installationId,
        string? appInstanceId,
        Guid? userId,
        CancellationToken ct)
    {
        var instance = appInstanceId?.Trim();
        if (!string.IsNullOrWhiteSpace(instance))
        {
            var exact = await _db.Devices.FirstOrDefaultAsync(d =>
                d.AppId == appId &&
                d.DeviceId == deviceId &&
                d.AppInstanceId == instance, ct);
            if (exact is not null) return exact;
        }

        // Original app auto-restore may receive a fresh private appInstanceId after Clear Data
        // while still recovering the same backend user. Keep that as one original row instead of
        // counting it as another clone.
        if (userId.HasValue)
        {
            var restoredOriginal = await _db.Devices.FirstOrDefaultAsync(d =>
                d.AppId == appId &&
                d.InstallationId == installationId &&
                d.UserId == userId.Value, ct);
            if (restoredOriginal is not null) return restoredOriginal;
        }

        if (string.IsNullOrWhiteSpace(instance))
        {
            var legacy = await _db.Devices.FirstOrDefaultAsync(d =>
                d.AppId == appId &&
                d.DeviceId == deviceId, ct);
            if (legacy is not null) return legacy;
        }

        return await _db.Devices.FirstOrDefaultAsync(d =>
            d.AppId == ClientIdentityDefaults.Unknown &&
            d.DeviceId == deviceId, ct);
    }

    private async Task MergeDuplicateDeviceRowsAsync(Device canonical, string appId, string deviceId, CancellationToken ct)
    {
        var duplicates = await _db.Devices
            .Where(d => d.Id != canonical.Id &&
                        d.DeviceId == deviceId &&
                        (d.AppId == appId || d.AppId == ClientIdentityDefaults.Unknown))
            .ToListAsync(ct);

        foreach (var duplicate in duplicates)
        {
            canonical.UserId ??= duplicate.UserId;
            canonical.ActiveAccount ??= duplicate.ActiveAccount;
            canonical.LoggedInAccountsJson ??= duplicate.LoggedInAccountsJson;
            canonical.RegisteredAt = canonical.RegisteredAt <= duplicate.RegisteredAt
                ? canonical.RegisteredAt
                : duplicate.RegisteredAt;
            if (duplicate.LastSeenAt > canonical.LastSeenAt) canonical.LastSeenAt = duplicate.LastSeenAt;
        }

        if (duplicates.Count > 0)
        {
            _db.Devices.RemoveRange(duplicates);
        }
    }
}
