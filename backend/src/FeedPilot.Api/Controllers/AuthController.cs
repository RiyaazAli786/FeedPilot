using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using FeedPilot.Api.Data;
using FeedPilot.Api.Domain;
using FeedPilot.Api.Dtos;
using FeedPilot.Api.Services;

namespace FeedPilot.Api.Controllers;

[ApiController]
[Route("api/auth")]
public class AuthController : ControllerBase
{
    private readonly AppDbContext _db;
    private readonly ITokenService _tokens;
    private readonly IWalletService _wallets;
    private readonly JwtSettings _jwt;

    public AuthController(AppDbContext db, ITokenService tokens, IWalletService wallets,
        Microsoft.Extensions.Options.IOptions<JwtSettings> jwt)
    {
        _db = db;
        _tokens = tokens;
        _wallets = wallets;
        _jwt = jwt.Value;
    }

    /// <summary>
    /// Queues a password reset for an admin to action on the dashboard.
    ///
    /// Always answers the same way whether or not the address exists — otherwise this
    /// endpoint becomes a way to discover which emails have accounts.
    /// </summary>
    [AllowAnonymous]
    [HttpPost("forgot-password")]
    public async Task<ActionResult<object>> ForgotPassword(ForgotPasswordRequest req, CancellationToken ct)
    {
        var email = req.Email.Trim().ToLowerInvariant();
        var identity = this.ClientIdentity();
        var user = await _db.Users.FirstOrDefaultAsync(u => u.Email.ToLower() == email, ct);

        // Don't queue a second request while one is already waiting for the same address.
        var alreadyPending = await _db.PasswordResetRequests.AnyAsync(
            r => r.Email == email && r.Status == PasswordResetStatus.Pending, ct);

        if (!alreadyPending)
        {
            _db.PasswordResetRequests.Add(new PasswordResetRequest
            {
                UserId = user?.Id,
                Email = email,
                AppId = identity.AppId,
                DeviceId = identity.DeviceId,
                Status = PasswordResetStatus.Pending
            });
            await _db.SaveChangesAsync(ct);
        }

        return Ok(new
        {
            message = "Your password reset request has been sent to the support team. " +
                      "They will contact you with a new password."
        });
    }

    [HttpPost("register")]
    public async Task<ActionResult<AuthResponse>> Register(RegisterRequest req, CancellationToken ct)
    {
        var email = req.Email.Trim().ToLowerInvariant();
        if (await _db.Users.AnyAsync(u => u.Email == email, ct))
            return Conflict(new ApiError("Email already registered.", "email_taken"));

        var user = new User
        {
            Name = req.Name.Trim(),
            Email = email,
            PasswordHash = BCrypt.Net.BCrypt.HashPassword(req.Password)
        };
        _db.Users.Add(user);
        await _db.SaveChangesAsync(ct);
        await _wallets.GetOrCreateAsync(user.Id, ct);

        return Ok(await IssueTokens(user, ct));
    }

    [HttpPost("login")]
    public async Task<ActionResult<AuthResponse>> Login(LoginRequest req, CancellationToken ct)
    {
        var email = req.Email.Trim().ToLowerInvariant();
        var user = await _db.Users.FirstOrDefaultAsync(u => u.Email == email, ct);
        // An empty hash is a passwordless device account — it has no password login.
        if (user is null || string.IsNullOrEmpty(user.PasswordHash) ||
            !BCrypt.Net.BCrypt.Verify(req.Password, user.PasswordHash))
            return Unauthorized(new ApiError("Invalid credentials.", "invalid_credentials"));

        return Ok(await IssueTokens(user, ct));
    }

    /// <summary>
    /// Passwordless sign-in keyed by the app install. Finds or creates the account for this
    /// clone + installation and issues tokens, so a freshly-installed app (or clone) is signed
    /// in automatically with no login screen. Each install gets its own wallet.
    /// </summary>
    [AllowAnonymous]
    [HttpPost("device")]
    public async Task<ActionResult<AuthResponse>> DeviceAuth(DeviceAuthRequest req, CancellationToken ct)
    {
        var identity = this.ClientIdentity();
        var install = (string.IsNullOrWhiteSpace(req.InstallationId) ? identity.DeviceId : req.InstallationId).Trim();
        if (string.IsNullOrWhiteSpace(install) || install == ClientIdentity.Unknown)
            return BadRequest(new ApiError("A device installation id is required.", "missing_install_id"));

        var existingDeviceUserId = await _db.Devices
            .Where(d => d.AppId == identity.AppId &&
                        d.DeviceId == install &&
                        d.UserId != null)
            .OrderByDescending(d => d.LastSeenAt)
            .Select(d => d.UserId)
            .FirstOrDefaultAsync(ct);
        if (existingDeviceUserId.HasValue)
        {
            var existingDeviceUser = await _db.Users.FirstOrDefaultAsync(u => u.Id == existingDeviceUserId.Value, ct);
            if (existingDeviceUser is not null)
            {
                await _wallets.GetOrCreateAsync(existingDeviceUser.Id, ct);
                return Ok(await IssueTokens(existingDeviceUser, ct));
            }
        }

        // Deterministic identity per (clone, install). The reserved @device.feedpilot domain
        // can never collide with a real user's email, and the empty password hash means this
        // account can only be reached through device auth, never a password login.
        var email = $"device.{install}.{identity.AppId}@device.feedpilot".ToLowerInvariant();

        var user = await _db.Users.FirstOrDefaultAsync(u => u.Email == email, ct);
        if (user is null)
        {
            user = new User
            {
                Name = $"{identity.AppId} · {install[..Math.Min(8, install.Length)]}",
                Email = email,
                PasswordHash = string.Empty
            };
            _db.Users.Add(user);
            await _db.SaveChangesAsync(ct);
            await _wallets.GetOrCreateAsync(user.Id, ct);
        }

        return Ok(await IssueTokens(user, ct));
    }

    /// <summary>
    /// Turns the caller's passwordless device account into one they can log back into from
    /// anywhere — the fix for "coins disappeared after reinstall": device auth binds to a
    /// hardware fingerprint that isn't guaranteed to survive every reinstall (OEM ANDROID_ID
    /// resets, factory reset, a different device), and until now there was no way to recover
    /// that account except getting lucky on the fingerprint matching again. This keeps the same
    /// User row (and therefore the same Wallet/coins) — it only replaces the reserved device
    /// placeholder email with a real one and sets a password, exactly like Register but without
    /// losing what's already on the account.
    /// </summary>
    [Authorize]
    [HttpPost("claim")]
    public async Task<ActionResult<AuthResponse>> Claim(ClaimAccountRequest req, CancellationToken ct)
    {
        var user = await _db.Users.FindAsync([User.GetUserId()], ct);
        if (user is null) return NotFound(new ApiError("Account not found.", "not_found"));

        if (!user.Email.EndsWith("@device.feedpilot", StringComparison.OrdinalIgnoreCase))
            return BadRequest(new ApiError(
                "This account already has its own login — use Forgot Password to recover it instead.",
                "already_claimed"));

        var email = req.Email.Trim().ToLowerInvariant();
        if (await _db.Users.AnyAsync(u => u.Email == email && u.Id != user.Id, ct))
            return Conflict(new ApiError("Email already registered.", "email_taken"));

        user.Email = email;
        user.PasswordHash = BCrypt.Net.BCrypt.HashPassword(req.Password);
        await _db.SaveChangesAsync(ct);

        return Ok(await IssueTokens(user, ct));
    }

    [HttpPost("refresh")]
    public async Task<ActionResult<AuthResponse>> Refresh(RefreshRequest req, CancellationToken ct)
    {
        var user = await _db.Users.FirstOrDefaultAsync(u => u.RefreshToken == req.RefreshToken, ct);
        if (user is null || user.RefreshTokenExpiresAt < DateTime.UtcNow)
            return Unauthorized(new ApiError("Invalid or expired refresh token.", "invalid_refresh"));

        var identity = this.ClientIdentity();
        if (!await CurrentIdentityHasDeviceBindingAsync(user.Id, identity, ct))
            return Unauthorized(new ApiError("This refresh token belongs to another app install.", "device_session_mismatch"));

        return Ok(await IssueTokens(user, ct));
    }

    [Authorize]
    [HttpPost("logout")]
    public async Task<IActionResult> Logout(CancellationToken ct)
    {
        var userId = User.GetUserId();
        var user = await _db.Users.FindAsync([userId], ct);
        if (user is not null)
        {
            user.RefreshToken = null;
            user.RefreshTokenExpiresAt = null;
            await _db.SaveChangesAsync(ct);
        }
        return NoContent();
    }

    [Authorize]
    [HttpDelete("account")]
    public async Task<IActionResult> DeleteAccount(CancellationToken ct)
    {
        var userId = User.GetUserId();
        var identity = this.ClientIdentity();
        var user = await _db.Users.FirstOrDefaultAsync(u => u.Id == userId, ct);
        if (user is null) return NoContent();

        await using var tx = await _db.Database.BeginTransactionAsync(ct);

        var now = DateTime.UtcNow;
        var claimDeviceIds = ClaimDeviceIds(identity.AppId, identity.DeviceId);
        var claimedOrders = await _db.AppOrders
            .Where(o => o.Status == AppOrderStatus.Processing &&
                        o.ProcessingDeviceId != null &&
                        claimDeviceIds.Contains(o.ProcessingDeviceId))
            .ToListAsync(ct);

        foreach (var order in claimedOrders)
        {
            order.Status = order.CompletedCount >= order.Quantity
                ? AppOrderStatus.Completed
                : AppOrderStatus.Pending;
            order.ProcessingDeviceId = null;
            order.ProcessingStartedAt = null;
            order.UpdatedAt = now;
        }

        var accountIds = await _db.Accounts
            .Where(a => a.UserId == userId)
            .Select(a => a.Id)
            .ToListAsync(ct);

        if (accountIds.Count > 0)
        {
            await _db.Tasks
                .Where(t => t.AccountId != null && accountIds.Contains(t.AccountId.Value))
                .ExecuteDeleteAsync(ct);
        }

        await _db.CoinTransfers
            .Where(t => t.SenderUserId == userId || t.ReceiverUserId == userId)
            .ExecuteDeleteAsync(ct);

        await _db.PasswordResetRequests
            .Where(r => r.UserId == userId ||
                        (r.AppId == identity.AppId && r.DeviceId == identity.DeviceId))
            .ExecuteDeleteAsync(ct);

        await _db.Devices
            .Where(d => d.UserId == userId ||
                        (d.AppId == identity.AppId && d.DeviceId == identity.DeviceId))
            .ExecuteDeleteAsync(ct);

        _db.Users.Remove(user);
        await _db.SaveChangesAsync(ct);
        await tx.CommitAsync(ct);

        return NoContent();
    }

    private async Task<AuthResponse> IssueTokens(User user, CancellationToken ct)
    {
        var identity = this.ClientIdentity();
        await UpsertDeviceBindingAsync(user.Id, identity, ct);
        if (IsBackupAccount(user))
            await MoveRestoredAccountToCurrentDeviceAsync(user.Id, identity, ct);

        var (accessToken, expiresAt) = _tokens.CreateAccessToken(user, identity);
        user.RefreshToken = _tokens.CreateRefreshToken();
        user.RefreshTokenExpiresAt = DateTime.UtcNow.AddDays(_jwt.RefreshTokenDays);
        await _db.SaveChangesAsync(ct);
        return new AuthResponse(user.Id, user.Name, user.Email, accessToken, user.RefreshToken!, expiresAt);
    }

    private async Task<bool> CurrentIdentityHasDeviceBindingAsync(Guid userId, ClientIdentity identity, CancellationToken ct)
    {
        if (identity.AppId == ClientIdentity.Unknown || identity.DeviceId == ClientIdentity.Unknown)
            return true;

        return await _db.Devices.AnyAsync(d =>
            d.UserId == userId &&
            d.AppId == identity.AppId &&
            d.DeviceId == identity.DeviceId &&
            (identity.AppInstanceId == ClientIdentity.Unknown ||
             d.AppInstanceId == null ||
             d.AppInstanceId == identity.AppInstanceId), ct);
    }

    private async Task UpsertDeviceBindingAsync(Guid userId, ClientIdentity identity, CancellationToken ct)
    {
        if (identity.AppId == ClientIdentity.Unknown || identity.DeviceId == ClientIdentity.Unknown)
            return;

        var device = await _db.Devices.FirstOrDefaultAsync(d =>
            d.AppId == identity.AppId &&
            d.DeviceId == identity.DeviceId &&
            (identity.AppInstanceId == ClientIdentity.Unknown ||
             d.AppInstanceId == identity.AppInstanceId), ct);

        if (device is null)
        {
            device = new Device
            {
                AppId = identity.AppId,
                DeviceId = identity.DeviceId,
                InstallationId = identity.InstallationId == ClientIdentity.Unknown
                    ? identity.DeviceId
                    : identity.InstallationId,
                AppInstanceId = identity.AppInstanceId == ClientIdentity.Unknown
                    ? null
                    : identity.AppInstanceId
            };
            _db.Devices.Add(device);
        }

        device.UserId = userId;
        device.LastSeenAt = DateTime.UtcNow;
    }

    private async Task MoveRestoredAccountToCurrentDeviceAsync(Guid userId, ClientIdentity identity, CancellationToken ct)
    {
        if (identity.AppId == ClientIdentity.Unknown || identity.DeviceId == ClientIdentity.Unknown)
            return;

        var targetPickedDeviceId = CurrentPickedUsernameDeviceId(identity);
        if (targetPickedDeviceId == ClientIdentity.Unknown)
            return;

        var userDevices = await _db.Devices.AsNoTracking()
            .Where(d => d.UserId == userId)
            .Select(d => new DevicePickReference(d.AppId, d.DeviceId, d.InstallationId))
            .ToListAsync(ct);

        var knownDeviceIds = userDevices
            .SelectMany(d => new[] { d.DeviceId, d.InstallationId })
            .Append(identity.DeviceId)
            .Append(identity.InstallationId)
            .Append(identity.HardwareId)
            .Where(IsKnownIdentityValue)
            .Distinct(StringComparer.Ordinal)
            .ToList();

        await MoveDeviceScopedRowsToCurrentDeviceAsync(userId, identity.AppId, identity.DeviceId, userDevices, knownDeviceIds, ct);
        await MovePickedUsernamesToCurrentDeviceAsync(userId, identity.AppId, targetPickedDeviceId, userDevices, knownDeviceIds, ct);
        await RemoveOldRestoredDeviceRowsAsync(userId, identity, ct);
    }

    private async Task MoveDeviceScopedRowsToCurrentDeviceAsync(
        Guid userId,
        string targetAppId,
        string targetDeviceId,
        List<DevicePickReference> userDevices,
        List<string> knownDeviceIds,
        CancellationToken ct)
    {
        var accounts = await _db.Accounts
            .Where(a => a.UserId == userId && a.DeviceId != null && knownDeviceIds.Contains(a.DeviceId))
            .ToListAsync(ct);
        foreach (var account in accounts)
        {
            account.AppId = targetAppId;
            account.DeviceId = targetDeviceId;
        }

        var orders = await _db.AppOrders
            .Where(o => o.UserId == userId && knownDeviceIds.Contains(o.DeviceId))
            .ToListAsync(ct);
        foreach (var order in orders)
        {
            order.AppId = targetAppId;
            order.DeviceId = targetDeviceId;
            order.UpdatedAt = DateTime.UtcNow;
        }

        var withdrawals = await _db.Withdrawals
            .Where(w => w.Wallet != null &&
                        w.Wallet.UserId == userId &&
                        knownDeviceIds.Contains(w.DeviceId))
            .ToListAsync(ct);
        foreach (var withdrawal in withdrawals)
        {
            withdrawal.AppId = targetAppId;
            withdrawal.DeviceId = targetDeviceId;
        }

        var subscriptionRequests = await _db.SubscriptionRequests
            .Where(s => s.UserId == userId && knownDeviceIds.Contains(s.DeviceId))
            .ToListAsync(ct);
        foreach (var request in subscriptionRequests)
        {
            request.AppId = targetAppId;
            request.DeviceId = targetDeviceId;
        }

        var knownDeviceRefs = userDevices
            .Where(d => IsKnownIdentityValue(d.DeviceId))
            .ToList();

        var passwordResetRequests = await _db.PasswordResetRequests
            .Where(r => r.UserId == userId || knownDeviceIds.Contains(r.DeviceId))
            .ToListAsync(ct);
        foreach (var request in passwordResetRequests.Where(r =>
                     r.UserId == userId || knownDeviceRefs.Any(d => MatchesDeviceReference(r.AppId, r.DeviceId, d))))
        {
            request.AppId = targetAppId;
            request.DeviceId = targetDeviceId;
        }

        var staleClaimDeviceIds = knownDeviceRefs
            .Where(d => d.DeviceId != targetDeviceId || d.AppId != targetAppId)
            .SelectMany(d => ClaimDeviceIds(d.AppId, d.DeviceId))
            .Distinct(StringComparer.Ordinal)
            .ToList();
        if (staleClaimDeviceIds.Count == 0)
            return;

        var staleClaims = await _db.AppOrders
            .Where(o => o.ProcessingDeviceId != null && staleClaimDeviceIds.Contains(o.ProcessingDeviceId))
            .ToListAsync(ct);
        foreach (var order in staleClaims)
        {
            order.Status = order.CompletedCount >= order.Quantity
                ? AppOrderStatus.Completed
                : AppOrderStatus.Pending;
            order.ProcessingDeviceId = null;
            order.ProcessingStartedAt = null;
            order.ReservedCount = 0;
            order.ReservedAt = null;
            order.UpdatedAt = DateTime.UtcNow;
        }
    }

    private async Task MovePickedUsernamesToCurrentDeviceAsync(
        Guid userId,
        string targetAppId,
        string targetDeviceId,
        List<DevicePickReference> userDevices,
        List<string> knownDeviceIds,
        CancellationToken ct)
    {
        var pickedRows = await _db.PickedUsernames
            .Where(p => p.UserId == userId || knownDeviceIds.Contains(p.DeviceId))
            .ToListAsync(ct);

        var targetRowsByUsername = pickedRows
            .Where(p => IsCurrentPickedUsernameTarget(p, targetAppId, targetDeviceId))
            .GroupBy(p => p.Username, StringComparer.OrdinalIgnoreCase)
            .ToDictionary(g => g.Key, g => g.First(), StringComparer.OrdinalIgnoreCase);

        foreach (var picked in pickedRows)
        {
            if (!BelongsToRestoredAccount(picked, userId, userDevices))
                continue;

            if (IsCurrentPickedUsernameTarget(picked, targetAppId, targetDeviceId))
            {
                picked.UserId ??= userId;
                continue;
            }

            if (targetRowsByUsername.TryGetValue(picked.Username, out var currentRow))
            {
                currentRow.UserId ??= userId;
                _db.PickedUsernames.Remove(picked);
                continue;
            }

            picked.UserId = userId;
            picked.DeviceId = targetDeviceId;
            picked.AppId = targetAppId;
            targetRowsByUsername[picked.Username] = picked;
        }
    }

    private async Task RemoveOldRestoredDeviceRowsAsync(Guid userId, ClientIdentity identity, CancellationToken ct)
    {
        var currentInstance = identity.AppInstanceId == ClientIdentity.Unknown ? null : identity.AppInstanceId;
        var oldDevices = await _db.Devices
            .Where(d => d.UserId == userId &&
                        (d.AppId != identity.AppId ||
                         d.DeviceId != identity.DeviceId ||
                         (currentInstance != null && d.AppInstanceId != currentInstance)))
            .ToListAsync(ct);

        if (oldDevices.Count > 0)
            _db.Devices.RemoveRange(oldDevices);
    }

    private static bool BelongsToRestoredAccount(
        PickedUsername picked,
        Guid userId,
        IEnumerable<DevicePickReference> userDevices)
    {
        if (picked.UserId == userId)
            return true;

        if (picked.UserId.HasValue)
            return false;

        return userDevices.Any(d =>
            MatchesDeviceReference(picked.AppId, picked.DeviceId, d) ||
            picked.DeviceId == d.InstallationId);
    }

    private sealed record DevicePickReference(string AppId, string DeviceId, string InstallationId);

    private static bool MatchesDeviceReference(string appId, string deviceId, DevicePickReference reference) =>
        deviceId == reference.DeviceId &&
        (appId == reference.AppId ||
         appId == ClientIdentityDefaults.Unknown ||
         reference.AppId == ClientIdentityDefaults.Unknown);

    private static string CurrentPickedUsernameDeviceId(ClientIdentity identity)
    {
        if (IsKnownIdentityValue(identity.HardwareId)) return identity.HardwareId;
        if (IsKnownIdentityValue(identity.InstallationId)) return identity.InstallationId;
        return IsKnownIdentityValue(identity.DeviceId) ? identity.DeviceId : ClientIdentity.Unknown;
    }

    private static bool IsBackupAccount(User user) =>
        user.Email.StartsWith("backup-", StringComparison.OrdinalIgnoreCase) &&
        user.Email.EndsWith("@backup.feedpilot", StringComparison.OrdinalIgnoreCase);

    private static bool IsCurrentPickedUsernameTarget(PickedUsername picked, string appId, string deviceId) =>
        picked.AppId == appId && picked.DeviceId == deviceId;

    private static bool IsKnownIdentityValue(string? value) =>
        !string.IsNullOrWhiteSpace(value) &&
        !string.Equals(value, ClientIdentity.Unknown, StringComparison.OrdinalIgnoreCase);

    private static string StableAppDeviceId(string appId, string deviceId) =>
        Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(
            System.Text.Encoding.UTF8.GetBytes($"{appId}|{deviceId}"))).ToLowerInvariant();

    private static string[] ClaimDeviceIds(string appId, string deviceId) =>
        [deviceId, StableAppDeviceId(appId, deviceId)];
}
