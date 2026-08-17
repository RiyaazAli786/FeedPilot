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

        var device = await FindDeviceAsync(appId, deviceId, installationId, ct);
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

        device.LastSeenAt = DateTime.UtcNow;
        if (User.Identity?.IsAuthenticated == true)
            device.UserId = User.GetUserId();

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

        var device = await FindDeviceAsync(appId, deviceId, installationId, ct);
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
        if (User.Identity?.IsAuthenticated == true)
            device.UserId = User.GetUserId();

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

    private async Task<Device?> FindDeviceAsync(string appId, string deviceId, string installationId, CancellationToken ct) =>
        await _db.Devices.FirstOrDefaultAsync(d => d.InstallationId == installationId, ct)
        ?? await _db.Devices.FirstOrDefaultAsync(d => d.AppId == appId && d.DeviceId == deviceId, ct)
        ?? await _db.Devices.FirstOrDefaultAsync(d => d.AppId == ClientIdentityDefaults.Unknown && d.DeviceId == deviceId, ct);

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
