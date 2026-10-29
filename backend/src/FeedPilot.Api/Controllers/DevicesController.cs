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
    /// Returns a paginated list of all registered devices with their online/offline status and bridge on/off state.
    /// Supports text search filtering by model, device id, installation id, or accounts.
    /// </summary>
    [AllowAnonymous]
    [HttpGet]
    public async Task<ActionResult<PagedResult<DeviceListItemDto>>> List(
        [FromQuery] string? search,
        [FromQuery] int page = 1,
        [FromQuery] int pageSize = 20,
        CancellationToken ct = default)
    {
        page = Math.Max(1, page);
        pageSize = Math.Clamp(pageSize, 1, 100);

        try
        {
            var query = _db.Devices.AsNoTracking().AsQueryable();

            if (!string.IsNullOrWhiteSpace(search))
            {
                var term = search.Trim();
                query = query.Where(d =>
                    (d.DeviceModel != null && d.DeviceModel.Contains(term)) ||
                    (d.DeviceId != null && d.DeviceId.Contains(term)) ||
                    (d.InstallationId != null && d.InstallationId.Contains(term)) ||
                    (d.ActiveAccount != null && d.ActiveAccount.Contains(term)) ||
                    (d.LoggedInAccountsJson != null && d.LoggedInAccountsJson.Contains(term)) ||
                    (d.AndroidId != null && d.AndroidId.Contains(term)) ||
                    (d.AppId != null && d.AppId.Contains(term)));
            }

            var allDevices = await query.ToListAsync(ct);
            var groups = BuildDeviceGroups(allDevices)
                .OrderByDescending(g => g.LastSeenAt)
                .ToList();
            var total = groups.Count;

            var paged = groups
                .Skip((page - 1) * pageSize)
                .Take(pageSize)
                .ToList();

            var deviceIds = paged.Select(d => d.DeviceId).Where(id => !string.IsNullOrWhiteSpace(id)).Distinct().ToList();
            var bridgeStates = deviceIds.Count > 0
                ? await _db.FeedBridgeDeviceStates.AsNoTracking()
                    .Where(b => deviceIds.Contains(b.DeviceId))
                    .ToListAsync(ct)
                : new List<FeedBridgeDeviceState>();

            var bridgeMap = bridgeStates
                .GroupBy(b => b.DeviceId)
                .ToDictionary(
                    g => g.Key,
                    g => g.OrderByDescending(b => b.LastSeenAt).First()
                );

            var cloneCounts = groups
                .Where(g => !string.IsNullOrWhiteSpace(g.InstallationId))
                .GroupBy(g => g.InstallationId)
                .ToDictionary(g => g.Key, g => g.Count());

            var now = DateTime.UtcNow;
            var onlineThreshold = TimeSpan.FromMinutes(2);

            var items = paged.Select(d =>
            {
                bridgeMap.TryGetValue(d.DeviceId, out var bridge);

                var effectiveLastSeen = bridge is not null && bridge.LastSeenAt > d.LastSeenAt
                    ? bridge.LastSeenAt
                    : d.LastSeenAt;

                var isOnline = (now - effectiveLastSeen) <= onlineThreshold;
                var isBridgeEnabled = bridge?.BridgeEnabled ?? false;
                var loggedInList = parseLoggedInAccounts(d.LoggedInAccountsJson);
                var cloneCount = !string.IsNullOrWhiteSpace(d.InstallationId) &&
                    cloneCounts.TryGetValue(d.InstallationId, out var count) ? count : 1;

                return new DeviceListItemDto(
                    d.Id,
                    d.AppId,
                    d.DeviceId,
                    d.InstallationId,
                    d.AppInstanceId,
                    d.DeviceModel ?? bridge?.DeviceModel,
                    d.AndroidVersion ?? bridge?.AndroidVersion,
                    d.AppVersion ?? bridge?.AppVersion,
                    d.ActiveAccount ?? bridge?.ActiveAccountUsername,
                    loggedInList,
                    d.RegisteredAt,
                    effectiveLastSeen,
                    isOnline,
                    isBridgeEnabled,
                    bridge?.BatteryLevel,
                    bridge?.NetworkType,
                    cloneCount
                );
            }).ToList();

            return Ok(new PagedResult<DeviceListItemDto>(items, total, page, pageSize));
        }
        catch (Exception)
        {
            return Ok(new PagedResult<DeviceListItemDto>(new List<DeviceListItemDto>(), 0, page, pageSize));
        }
    }

    private static IEnumerable<Device> BuildDeviceGroups(IEnumerable<Device> devices) =>
        devices
            .GroupBy(DeviceGroupKey)
            .Select(group =>
            {
                var ordered = group
                    .OrderByDescending(d => d.LastSeenAt)
                    .ThenByDescending(d => d.RegisteredAt)
                    .ToList();
                var canonical = ordered.First();
                var loggedInAccounts = ordered
                    .SelectMany(d => parseLoggedInAccounts(d.LoggedInAccountsJson) ?? new List<string>())
                    .Where(a => !string.IsNullOrWhiteSpace(a))
                    .Distinct(StringComparer.OrdinalIgnoreCase)
                    .ToList();
                canonical.LoggedInAccountsJson = loggedInAccounts.Count > 0
                    ? JsonSerializer.Serialize(loggedInAccounts)
                    : ordered.FirstOrDefault(d => !string.IsNullOrWhiteSpace(d.LoggedInAccountsJson))?.LoggedInAccountsJson;
                canonical.ActiveAccount ??= ordered.FirstOrDefault(d => !string.IsNullOrWhiteSpace(d.ActiveAccount))?.ActiveAccount;
                canonical.UserId ??= ordered.FirstOrDefault(d => d.UserId.HasValue)?.UserId;
                canonical.DeviceModel ??= ordered.FirstOrDefault(d => !string.IsNullOrWhiteSpace(d.DeviceModel))?.DeviceModel;
                canonical.AppVersion ??= ordered.FirstOrDefault(d => !string.IsNullOrWhiteSpace(d.AppVersion))?.AppVersion;
                canonical.AndroidVersion ??= ordered.FirstOrDefault(d => !string.IsNullOrWhiteSpace(d.AndroidVersion))?.AndroidVersion;
                canonical.AndroidId ??= ordered.FirstOrDefault(d => !string.IsNullOrWhiteSpace(d.AndroidId))?.AndroidId;
                canonical.AppInstanceId ??= ordered.FirstOrDefault(d => !string.IsNullOrWhiteSpace(d.AppInstanceId))?.AppInstanceId;
                canonical.RegisteredAt = ordered.Min(d => d.RegisteredAt);
                canonical.LastSeenAt = ordered.Max(d => d.LastSeenAt);
                return canonical;
            });

    private static string DeviceGroupKey(Device device)
    {
        var appId = string.IsNullOrWhiteSpace(device.AppId)
            ? ClientIdentityDefaults.Unknown
            : device.AppId.Trim().ToLowerInvariant();
        var appInstanceId = device.AppInstanceId?.Trim().ToLowerInvariant();
        if (!string.IsNullOrWhiteSpace(appInstanceId)) return $"{appId}|instance|{appInstanceId}";

        var deviceId = device.DeviceId?.Trim().ToLowerInvariant();
        if (!string.IsNullOrWhiteSpace(deviceId)) return $"{appId}|device|{deviceId}";

        var installationId = device.InstallationId?.Trim().ToLowerInvariant() ?? string.Empty;
        return $"{appId}|install|{installationId}";
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

        var canonicalInstance = canonical.AppInstanceId?.Trim();
        var mergeable = duplicates
            .Where(d => ShouldMergeDeviceRows(canonicalInstance, d.AppInstanceId))
            .ToList();

        foreach (var duplicate in mergeable)
        {
            canonical.UserId ??= duplicate.UserId;
            canonical.ActiveAccount ??= duplicate.ActiveAccount;
            canonical.LoggedInAccountsJson ??= duplicate.LoggedInAccountsJson;
            canonical.RegisteredAt = canonical.RegisteredAt <= duplicate.RegisteredAt
                ? canonical.RegisteredAt
                : duplicate.RegisteredAt;
            if (duplicate.LastSeenAt > canonical.LastSeenAt) canonical.LastSeenAt = duplicate.LastSeenAt;
        }

        if (mergeable.Count > 0)
        {
            _db.Devices.RemoveRange(mergeable);
        }
    }

    private static bool ShouldMergeDeviceRows(string? canonicalInstance, string? duplicateInstance)
    {
        var otherInstance = duplicateInstance?.Trim();
        if (string.IsNullOrWhiteSpace(canonicalInstance))
            return string.IsNullOrWhiteSpace(otherInstance);

        return string.IsNullOrWhiteSpace(otherInstance) ||
               string.Equals(canonicalInstance, otherInstance, StringComparison.Ordinal);
    }
}
