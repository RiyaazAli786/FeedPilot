using System.Text.Json;
using System.Text.RegularExpressions;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using FeedPilot.Api.Data;
using FeedPilot.Api.Domain;
using FeedPilot.Api.Dtos;
using FeedPilot.Api.Services;

namespace FeedPilot.Api.Controllers;

/// <summary>
/// Admin endpoint that lists all registered devices with their linked user, logged-in accounts, and wallet balance.
/// Guarded by an admin session token — see <see cref="AdminSessionAttribute"/>.
/// </summary>
[ApiController]
[AdminSession]
[Route("api/admin/devices")]
public class AdminDevicesController : ControllerBase
{
    private readonly AppDbContext _db;
    private readonly DeviceRestoreSettings _restoreSettings;

    // Inverse of DeviceEmail: "device.{oldDeviceId}.{appId}@device.feedpilot". The old device id
    // never contains a dot (it's a UUID or a hex hash), so the first segment is unambiguous even
    // though appId (a package name) commonly does.
    private static readonly Regex DeviceEmailPattern =
        new(@"^device\.([^.]+)\.(.+)@device\.feedpilot$", RegexOptions.IgnoreCase | RegexOptions.Compiled);

    public AdminDevicesController(AppDbContext db, IOptions<DeviceRestoreSettings> restoreSettings)
    {
        _db = db;
        _restoreSettings = restoreSettings.Value;
    }

    /// <summary>
    /// Returns a paginated list of every device that has registered,
    /// enriched with active logged-in accounts, user email, and current wallet balance.
    /// </summary>
    [HttpGet]
    public async Task<ActionResult<PagedResult<AdminDeviceDto>>> List(
        [FromQuery] string? search,
        [FromQuery] int page = 1,
        [FromQuery] int pageSize = 25,
        CancellationToken ct = default)
    {
        page = Math.Max(1, page);
        pageSize = Math.Clamp(pageSize, 1, 200);

        try
        {
            var query = _db.Devices.AsNoTracking().AsQueryable();

            if (!string.IsNullOrWhiteSpace(search))
            {
                var term = search.Trim();
                query = query.Where(d =>
                    (d.AppId != null && d.AppId.Contains(term)) ||
                    (d.DeviceId != null && d.DeviceId.Contains(term)) ||
                    (d.InstallationId != null && d.InstallationId.Contains(term)) ||
                    (d.ActiveAccount != null && d.ActiveAccount.Contains(term)) ||
                    (d.LoggedInAccountsJson != null && d.LoggedInAccountsJson.Contains(term)) ||
                    (d.AndroidId != null && d.AndroidId.Contains(term)));
            }

            var allDevices = await query.ToListAsync(ct);
            var groups = BuildDeviceGroups(allDevices)
                .OrderByDescending(g => g.LastSeenAt)
                .ToList();
            var total = groups.Count;

            var devices = groups
                .Skip((page - 1) * pageSize)
                .Take(pageSize)
                .ToList();

            var userIds = devices
                .Where(d => d.UserId.HasValue)
                .Select(d => d.UserId!.Value)
                .Distinct()
                .ToList();

            var users = userIds.Count > 0
                ? await _db.Users
                    .Where(u => userIds.Contains(u.Id))
                    .ToDictionaryAsync(u => u.Id, u => u.Email, ct)
                : new Dictionary<Guid, string>();

            var wallets = userIds.Count > 0
                ? await _db.Wallets
                    .Where(w => userIds.Contains(w.UserId))
                    .ToDictionaryAsync(w => w.UserId, w => w.Coins, ct)
                : new Dictionary<Guid, long>();

            // Every clone on one physical device shares InstallationId (the hardware fingerprint —
            // see DeviceIdentity.kt's hardwareDeviceId), even though each has its own DeviceId/AppId
            // group. Counted over the full (pre-page) group list so the count is accurate regardless
            // of which page a given clone happens to land on.
            var cloneCounts = groups
                .Where(g => !string.IsNullOrWhiteSpace(g.InstallationId))
                .GroupBy(g => g.InstallationId)
                .ToDictionary(g => g.Key, g => g.Count());

            var items = devices.Select(d =>
            {
                var email = d.UserId.HasValue && users.TryGetValue(d.UserId.Value, out var e) ? e : null;
                var coins = d.UserId.HasValue && wallets.TryGetValue(d.UserId.Value, out var c) ? c : 0L;
                var loggedInList = parseLoggedInAccounts(d.LoggedInAccountsJson);
                var cloneCount = !string.IsNullOrWhiteSpace(d.InstallationId) &&
                    cloneCounts.TryGetValue(d.InstallationId, out var n) ? n : 1;

                return new AdminDeviceDto(
                    d.Id,
                    d.AppId,
                    d.DeviceId ?? string.Empty,
                    d.InstallationId ?? string.Empty,
                    d.AppInstanceId,
                    d.AndroidId,
                    d.AndroidVersion,
                    d.AppVersion,
                    d.DeviceModel,
                    d.ActiveAccount,
                    loggedInList,
                    d.UserId,
                    email,
                    coins,
                    d.RegisteredAt,
                    d.LastSeenAt,
                    cloneCount);
            }).ToList();

            return Ok(new PagedResult<AdminDeviceDto>(items, total, page, pageSize));
        }
        catch (Exception)
        {
            return Ok(new PagedResult<AdminDeviceDto>(new List<AdminDeviceDto>(), 0, page, pageSize));
        }
    }

    /// <summary>
    /// Deletes a device, every Instagram account linked to it, and — when this was the device's
    /// only registration — its wallet and coin transaction history.
    ///
    /// The account rows carry the same hardware DeviceId the device row does (see
    /// AccountsController.Create), which is what makes them "logged in on this device" —
    /// removing the device without them would leave orphaned accounts still claiming orders and
    /// earning coins for a device the operator just told us to forget. The device-auth model
    /// (see AuthController.DeviceAuth) mints one deterministic User per (hardware device, app
    /// clone), so in the overwhelmingly common case this device's UserId belongs to no other
    /// device either — the wallet is cleared too, rather than leaving coins earned by a device
    /// that no longer exists sitting there. Guarded by a check for other devices sharing the
    /// same user so a real multi-device account never gets its wallet wiped by deleting just one
    /// of them.
    /// </summary>
    [HttpDelete("{id:guid}")]
    public async Task<IActionResult> Delete(Guid id, CancellationToken ct)
    {
        var device = await _db.Devices.FirstOrDefaultAsync(d => d.Id == id, ct);
        if (device is null) return NotFound(new ApiError("Device not found."));
        var devicesToDelete = await RelatedDeviceRows(device).ToListAsync(ct);
        if (devicesToDelete.All(d => d.Id != device.Id)) devicesToDelete.Add(device);
        var deviceIdsToDelete = devicesToDelete.Select(d => d.Id).ToHashSet();

        if (!string.IsNullOrWhiteSpace(device.DeviceId))
        {
            var linkedAccounts = await _db.Accounts
                .Where(a => a.AppId == device.AppId && a.DeviceId == device.DeviceId)
                .ToListAsync(ct);
            _db.Accounts.RemoveRange(linkedAccounts);
        }

        var userIds = devicesToDelete
            .Where(d => d.UserId.HasValue)
            .Select(d => d.UserId!.Value)
            .Distinct()
            .ToList();

        foreach (var userId in userIds)
        {
            var otherDevicesForUser = await _db.Devices
                .AnyAsync(d => !deviceIdsToDelete.Contains(d.Id) && d.UserId == userId, ct);

            if (!otherDevicesForUser)
            {
                var wallet = await _db.Wallets.FirstOrDefaultAsync(w => w.UserId == userId, ct);
                if (wallet is not null)
                {
                    var transactions = await _db.WalletTransactions
                        .Where(t => t.WalletId == wallet.Id)
                        .ToListAsync(ct);
                    _db.WalletTransactions.RemoveRange(transactions);
                    _db.Wallets.Remove(wallet);
                }
            }
        }

        _db.Devices.RemoveRange(devicesToDelete);
        await _db.SaveChangesAsync(ct);
        return NoContent();
    }

    /// <summary>Returns aggregate stats for the devices dashboard tile strip.</summary>
    [HttpGet("stats")]
    public async Task<ActionResult<object>> Stats(CancellationToken ct)
    {
        try
        {
            var now = DateTime.UtcNow;
            var cutoff24h = now.AddHours(-24);
            var cutoff7d = now.AddDays(-7);

            var groups = BuildDeviceGroups(await _db.Devices.ToListAsync(ct)).ToList();
            var total = groups.Count;
            var active24h = groups.Count(d => d.LastSeenAt >= cutoff24h);
            var active7d = groups.Count(d => d.LastSeenAt >= cutoff7d);
            var linked = groups.Count(d => d.UserId != null || d.ActiveAccount != null);

            return Ok(new { total, active24h, active7d, linked });
        }
        catch (Exception)
        {
            return Ok(new { total = 0, active24h = 0, active7d = 0, linked = 0 });
        }
    }

    private static List<string>? parseLoggedInAccounts(string? json)
    {
        if (string.IsNullOrWhiteSpace(json)) return null;
        try { return JsonSerializer.Deserialize<List<string>>(json); }
        catch { return null; }
    }

    [HttpGet("restore-config")]
    public ActionResult<DeviceRestoreConfigDto> RestoreConfig()
    {
        return Ok(new DeviceRestoreConfigDto(_restoreSettings.Enabled));
    }

    /// <summary>
    /// Lists passwordless device accounts that still hold coins but aren't linked to any
    /// currently-registered device — candidates to bring back via <see cref="Restore"/>. The
    /// device-auth email embeds the exact old device id (<c>device.{oldDeviceId}.{appId}@...</c>),
    /// so it can be parsed back out and dropped straight into the restore form.
    /// </summary>
    [HttpGet("orphaned-accounts")]
    public async Task<ActionResult<List<OrphanedDeviceAccountDto>>> OrphanedAccounts(
        [FromQuery] string? search, CancellationToken ct)
    {
        if (!_restoreSettings.Enabled)
            return NotFound(new ApiError("Device restore is disabled.", "DEVICE_RESTORE_DISABLED"));

        var linkedUserIds = _db.Devices.Where(d => d.UserId != null).Select(d => d.UserId!.Value);

        var candidates = await _db.Users
            .Where(u => u.Email.EndsWith("@device.feedpilot") && !linkedUserIds.Contains(u.Id))
            .Join(_db.Wallets, u => u.Id, w => w.UserId, (u, w) => new { u, w })
            .Where(x => x.w.Coins > 0)
            .OrderByDescending(x => x.w.Coins)
            .Take(200)
            .Select(x => new { x.u.Id, x.u.Email, x.u.Name, x.u.CreatedAt, x.w.Coins, x.w.LifetimeCoins })
            .ToListAsync(ct);

        var result = new List<OrphanedDeviceAccountDto>();
        foreach (var c in candidates)
        {
            var match = DeviceEmailPattern.Match(c.Email);
            if (!match.Success) continue;

            var oldDeviceId = match.Groups[1].Value;
            var appId = match.Groups[2].Value;
            if (!string.IsNullOrWhiteSpace(search) &&
                !c.Email.Contains(search, StringComparison.OrdinalIgnoreCase) &&
                !appId.Contains(search, StringComparison.OrdinalIgnoreCase))
                continue;

            result.Add(new OrphanedDeviceAccountDto(c.Id, c.Email, appId, oldDeviceId, c.Name, c.Coins, c.LifetimeCoins, c.CreatedAt));
        }

        return Ok(result);
    }

    /// <summary>
    /// Deletes an orphaned device user account, its wallet, wallet transactions, and linked Instagram accounts.
    /// </summary>
    [HttpDelete("orphaned-accounts/{id:guid}")]
    public async Task<IActionResult> DeleteOrphanedAccount(Guid id, CancellationToken ct)
    {
        if (!_restoreSettings.Enabled)
            return NotFound(new ApiError("Device restore is disabled.", "DEVICE_RESTORE_DISABLED"));

        var user = await _db.Users.FirstOrDefaultAsync(u => u.Id == id && u.Email.EndsWith("@device.feedpilot"), ct);
        if (user is null) return NotFound(new ApiError("Orphaned account not found."));

        var linkedAccounts = await _db.Accounts.Where(a => a.UserId == user.Id).ToListAsync(ct);
        _db.Accounts.RemoveRange(linkedAccounts);

        var wallet = await _db.Wallets.FirstOrDefaultAsync(w => w.UserId == user.Id, ct);
        if (wallet is not null)
        {
            var transactions = await _db.WalletTransactions.Where(t => t.WalletId == wallet.Id).ToListAsync(ct);
            _db.WalletTransactions.RemoveRange(transactions);
            _db.Wallets.Remove(wallet);
        }

        var userDevices = await _db.Devices.Where(d => d.UserId == user.Id).ToListAsync(ct);
        _db.Devices.RemoveRange(userDevices);

        _db.Users.Remove(user);
        await _db.SaveChangesAsync(ct);
        return NoContent();
    }

    [HttpPost("restore")]
    public async Task<ActionResult<object>> Restore(AdminDeviceRestoreRequest req, CancellationToken ct)
    {
        if (!_restoreSettings.Enabled)
            return NotFound(new ApiError("Device restore is disabled.", "DEVICE_RESTORE_DISABLED"));

        var oldAppId = NormalizeAppId(req.OldAppId);
        var newAppId = NormalizeAppId(req.NewAppId);
        var oldDeviceId = req.OldDeviceId.Trim();
        var newDeviceId = req.NewDeviceId.Trim();

        if (string.IsNullOrWhiteSpace(oldDeviceId) || oldDeviceId == ClientIdentityDefaults.Unknown)
            return BadRequest(new ApiError("Enter a valid old device id.", "INVALID_OLD_DEVICE_ID"));
        if (string.IsNullOrWhiteSpace(newDeviceId) || newDeviceId == ClientIdentityDefaults.Unknown)
            return BadRequest(new ApiError("Enter a valid new device id.", "INVALID_NEW_DEVICE_ID"));

        var oldDeviceRef = await FindDeviceReferenceAsync(oldAppId, oldDeviceId, ct);
        var newDeviceRef = await FindDeviceReferenceAsync(newAppId, newDeviceId, ct);
        if (oldDeviceRef is not null)
        {
            oldAppId = oldDeviceRef.AppId;
            oldDeviceId = oldDeviceRef.DeviceId;
        }
        if (newDeviceRef is not null)
        {
            newAppId = newDeviceRef.AppId;
            newDeviceId = newDeviceRef.DeviceId;
        }

        if (oldAppId == newAppId && oldDeviceId == newDeviceId)
            return BadRequest(new ApiError("Old and new device ids are the same.", "SAME_DEVICE"));

        var oldUserId = oldDeviceRef?.UserId ?? await _db.Devices
            .Where(d => d.AppId == oldAppId && d.DeviceId == oldDeviceId && d.UserId != null)
            .OrderByDescending(d => d.LastSeenAt)
            .Select(d => d.UserId)
            .FirstOrDefaultAsync(ct);

        var oldDeviceEmail = DeviceEmail(oldDeviceId, oldAppId);
        var oldUser = oldUserId is { } fromDevice
            ? await _db.Users.FirstOrDefaultAsync(u => u.Id == fromDevice, ct)
            : await _db.Users.FirstOrDefaultAsync(u => u.Email == oldDeviceEmail, ct);

        if (oldUser is null)
            return NotFound(new ApiError("No account was found for the old device id.", "OLD_DEVICE_NOT_FOUND"));

        var newUserId = newDeviceRef?.UserId ?? await _db.Devices
            .Where(d => d.AppId == newAppId && d.DeviceId == newDeviceId && d.UserId != null)
            .OrderByDescending(d => d.LastSeenAt)
            .Select(d => d.UserId)
            .FirstOrDefaultAsync(ct);
        var newUser = newUserId is { } toDevice
            ? await _db.Users.FirstOrDefaultAsync(u => u.Id == toDevice, ct)
            : await _db.Users.FirstOrDefaultAsync(u => u.Email == DeviceEmail(newDeviceId, newAppId), ct);

        await using var tx = await _db.Database.BeginTransactionAsync(ct);

        var now = DateTime.UtcNow;
        // Both the target device's own in-flight claims AND the old device's — the rename below
        // retires oldAppId/oldDeviceId's identity strings, so any order still Processing under
        // the OLD ProcessingDeviceId would otherwise go orphaned (no future claim ever matches it
        // again) until the stale-claim sweep eventually reclaims it minutes later.
        var oldClaimDeviceIds = ClaimDeviceIds(oldAppId, oldDeviceId);
        var newClaimDeviceIds = ClaimDeviceIds(newAppId, newDeviceId);
        var staleClaimDeviceIds = oldClaimDeviceIds.Concat(newClaimDeviceIds).Distinct().ToArray();
        var staleClaims = await _db.AppOrders
            .Where(o => o.Status == AppOrderStatus.Processing &&
                        o.ProcessingDeviceId != null &&
                        staleClaimDeviceIds.Contains(o.ProcessingDeviceId))
            .ToListAsync(ct);
        foreach (var order in staleClaims)
        {
            order.Status = order.CompletedCount >= order.Quantity
                ? AppOrderStatus.Completed
                : AppOrderStatus.Pending;
            order.ProcessingDeviceId = null;
            order.ProcessingStartedAt = null;
            order.UpdatedAt = now;
        }

        var newDeviceRows = await _db.Devices
            .Where(d => d.AppId == newAppId && d.DeviceId == newDeviceId)
            .ToListAsync(ct);
        foreach (var device in newDeviceRows)
        {
            device.UserId = oldUser.Id;
            device.LastSeenAt = now;
        }

        await _db.Accounts
            .Where(a => a.UserId == oldUser.Id)
            .ExecuteUpdateAsync(s => s
                .SetProperty(a => a.AppId, newAppId)
                .SetProperty(a => a.DeviceId, newDeviceId), ct);

        await _db.Devices
            .Where(d => d.AppId == oldAppId && d.DeviceId == oldDeviceId)
            .ExecuteUpdateAsync(s => s
                .SetProperty(d => d.AppId, newAppId)
                .SetProperty(d => d.DeviceId, newDeviceId)
                .SetProperty(d => d.UserId, oldUser.Id)
                .SetProperty(d => d.LastSeenAt, now), ct);

        var newDeviceEmail = DeviceEmail(newDeviceId, newAppId);
        var removeNewUser = newUser is not null &&
            newUser.Id != oldUser.Id &&
            newUser.Email.EndsWith("@device.feedpilot", StringComparison.OrdinalIgnoreCase);
        if (newUser is not null &&
            newUser.Id != oldUser.Id &&
            newUser.Email.Equals(newDeviceEmail, StringComparison.OrdinalIgnoreCase))
        {
            newUser.Email = $"deleted.{newUser.Id:N}@device.feedpilot";
            await _db.SaveChangesAsync(ct);
        }

        if (oldUser.Email.EndsWith("@device.feedpilot", StringComparison.OrdinalIgnoreCase))
        {
            oldUser.Email = newDeviceEmail;
            oldUser.Name = $"{newAppId} - {newDeviceId[..Math.Min(8, newDeviceId.Length)]}";
            oldUser.PasswordHash = string.Empty;
        }

        oldUser.RefreshToken = null;
        oldUser.RefreshTokenExpiresAt = null;
        var oldWallet = await GetOrCreateWalletAsync(oldUser.Id, ct);
        if (newUser is not null && newUser.Id != oldUser.Id)
        {
            await MergeUserDataAsync(newUser, oldUser, oldWallet, ct);
            if (removeNewUser)
            {
                newUser.RefreshToken = null;
                newUser.RefreshTokenExpiresAt = null;
                _db.Users.Remove(newUser);
            }
        }

        await _db.SaveChangesAsync(ct);
        await tx.CommitAsync(ct);

        return Ok(new
        {
            message = "Device account restored.",
            userId = oldUser.Id,
            email = oldUser.Email,
            appId = newAppId,
            deviceId = newDeviceId
        });
    }

    [HttpPost("clear-order-claims")]
    public async Task<ActionResult<object>> ClearOrderClaims(AdminClearDeviceOrderClaimsRequest req, CancellationToken ct)
    {
        var appId = NormalizeAppId(req.AppId);
        var deviceId = req.DeviceId?.Trim();
        var clearAll = string.IsNullOrWhiteSpace(deviceId) || deviceId == ClientIdentityDefaults.Unknown;

        IQueryable<AppOrder> query = _db.AppOrders
            .Where(o => o.Status == AppOrderStatus.Processing &&
                        o.ProcessingDeviceId != null);

        if (!clearAll)
        {
            var deviceRef = await FindDeviceReferenceAsync(appId, deviceId!, ct);
            if (deviceRef is not null)
            {
                appId = deviceRef.AppId;
                deviceId = deviceRef.DeviceId;
            }

            var claimDeviceIds = ClaimDeviceIds(appId, deviceId!);
            query = query.Where(o => claimDeviceIds.Contains(o.ProcessingDeviceId!));
        }

        var now = DateTime.UtcNow;
        var claimedOrders = await query.ToListAsync(ct);

        var pending = 0;
        var completed = 0;
        foreach (var order in claimedOrders)
        {
            if (order.CompletedCount >= order.Quantity)
            {
                order.Status = AppOrderStatus.Completed;
                order.CompletedAt ??= now;
                completed++;
            }
            else
            {
                order.Status = AppOrderStatus.Pending;
                pending++;
            }

            order.ProcessingDeviceId = null;
            order.ProcessingStartedAt = null;
            order.UpdatedAt = now;
        }

        if (claimedOrders.Count > 0)
        {
            await _db.SaveChangesAsync(ct);
        }

        return Ok(new
        {
            message = claimedOrders.Count == 0
                ? (clearAll ? "No active order claims found." : "No active order claims found for this device.")
                : "Order claims cleared.",
            appId,
            deviceId,
            allDevices = clearAll,
            cleared = claimedOrders.Count,
            pending,
            completed
        });
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

    private IQueryable<Device> RelatedDeviceRows(Device device)
    {
        var appId = string.IsNullOrWhiteSpace(device.AppId)
            ? ClientIdentityDefaults.Unknown
            : device.AppId.Trim();
        var deviceId = device.DeviceId?.Trim();
        if (!string.IsNullOrWhiteSpace(deviceId))
        {
            return _db.Devices.Where(d =>
                d.DeviceId == deviceId &&
                (d.AppId == appId ||
                 (appId != ClientIdentityDefaults.Unknown && d.AppId == ClientIdentityDefaults.Unknown)));
        }

        var installationId = device.InstallationId?.Trim() ?? string.Empty;
        return _db.Devices.Where(d => d.AppId == appId && d.InstallationId == installationId);
    }

    private static string DeviceGroupKey(Device device)
    {
        var appId = string.IsNullOrWhiteSpace(device.AppId)
            ? ClientIdentityDefaults.Unknown
            : device.AppId.Trim().ToLowerInvariant();
        var deviceId = device.DeviceId?.Trim().ToLowerInvariant();
        if (!string.IsNullOrWhiteSpace(deviceId)) return $"{appId}|device|{deviceId}";

        var installationId = device.InstallationId?.Trim().ToLowerInvariant() ?? string.Empty;
        return $"{appId}|install|{installationId}";
    }

    private static string NormalizeAppId(string? appId) =>
        string.IsNullOrWhiteSpace(appId) ? ClientIdentityDefaults.Unknown : appId.Trim();

    private static string DeviceEmail(string deviceId, string appId) =>
        $"device.{deviceId}.{appId}@device.feedpilot".ToLowerInvariant();

    private static string StableAppDeviceId(string appId, string deviceId) =>
        Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(
            System.Text.Encoding.UTF8.GetBytes($"{appId}|{deviceId}"))).ToLowerInvariant();

    private static string[] ClaimDeviceIds(string appId, string deviceId) =>
        [deviceId, StableAppDeviceId(appId, deviceId)];

    private async Task<DeviceReference?> FindDeviceReferenceAsync(string appId, string id, CancellationToken ct)
    {
        if (string.IsNullOrWhiteSpace(id)) return null;
        var trimmed = id.Trim();

        var exactDeviceIdMatch = await _db.Devices
            .Where(d => (appId == ClientIdentityDefaults.Unknown || d.AppId == appId) && d.DeviceId == trimmed)
            .OrderByDescending(d => d.LastSeenAt)
            .Select(d => new DeviceReference(d.AppId, d.DeviceId, d.InstallationId, d.UserId))
            .FirstOrDefaultAsync(ct);

        if (exactDeviceIdMatch is not null) return exactDeviceIdMatch;

        return await _db.Devices
            .Where(d => (appId == ClientIdentityDefaults.Unknown || d.AppId == appId) && d.InstallationId == trimmed)
            .OrderByDescending(d => d.LastSeenAt)
            .Select(d => new DeviceReference(d.AppId, d.DeviceId, d.InstallationId, d.UserId))
            .FirstOrDefaultAsync(ct);
    }

    private async Task<Wallet> GetOrCreateWalletAsync(Guid userId, CancellationToken ct)
    {
        var wallet = await _db.Wallets.FirstOrDefaultAsync(w => w.UserId == userId, ct);
        if (wallet is not null) return wallet;

        wallet = new Wallet { UserId = userId };
        _db.Wallets.Add(wallet);
        await _db.SaveChangesAsync(ct);
        return wallet;
    }

    private async Task MergeUserDataAsync(User fromUser, User toUser, Wallet toWallet, CancellationToken ct)
    {
        await _db.Accounts
            .Where(a => a.UserId == fromUser.Id)
            .ExecuteUpdateAsync(s => s.SetProperty(a => a.UserId, toUser.Id), ct);

        await _db.AppOrders
            .Where(o => o.UserId == fromUser.Id)
            .ExecuteUpdateAsync(s => s.SetProperty(o => o.UserId, toUser.Id), ct);

        try
        {
            await _db.SubscriptionRequests
                .Where(r => r.UserId == fromUser.Id)
                .ExecuteUpdateAsync(s => s.SetProperty(r => r.UserId, toUser.Id), ct);
        }
        catch
        {
            // Older live databases may not have this optional table yet; wallet/session restore
            // must still succeed and the startup schema migrator will create it on next boot.
        }

        await _db.PasswordResetRequests
            .Where(r => r.UserId == fromUser.Id)
            .ExecuteUpdateAsync(s => s.SetProperty(r => r.UserId, toUser.Id), ct);

        await _db.CoinTransfers
            .Where(t => t.SenderUserId == fromUser.Id)
            .ExecuteUpdateAsync(s => s.SetProperty(t => t.SenderUserId, toUser.Id), ct);

        await _db.CoinTransfers
            .Where(t => t.ReceiverUserId == fromUser.Id)
            .ExecuteUpdateAsync(s => s.SetProperty(t => t.ReceiverUserId, toUser.Id), ct);

        var fromWallet = await _db.Wallets.FirstOrDefaultAsync(w => w.UserId == fromUser.Id, ct);
        if (fromWallet is null) return;

        await _db.WalletTransactions
            .Where(t => t.WalletId == fromWallet.Id)
            .ExecuteUpdateAsync(s => s.SetProperty(t => t.WalletId, toWallet.Id), ct);

        await _db.Withdrawals
            .Where(w => w.WalletId == fromWallet.Id)
            .ExecuteUpdateAsync(s => s.SetProperty(w => w.WalletId, toWallet.Id), ct);

        toWallet.Coins += fromWallet.Coins;
        toWallet.LifetimeCoins += fromWallet.LifetimeCoins;
        toWallet.PendingCoins += fromWallet.PendingCoins;
        toWallet.WithdrawnCoins += fromWallet.WithdrawnCoins;
        toWallet.UpdatedAt = DateTime.UtcNow;
        _db.Wallets.Remove(fromWallet);
    }

    private sealed record DeviceReference(string AppId, string DeviceId, string InstallationId, Guid? UserId);
}
