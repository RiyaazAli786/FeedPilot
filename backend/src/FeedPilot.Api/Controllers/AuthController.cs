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
        var (accessToken, expiresAt) = _tokens.CreateAccessToken(user);
        user.RefreshToken = _tokens.CreateRefreshToken();
        user.RefreshTokenExpiresAt = DateTime.UtcNow.AddDays(_jwt.RefreshTokenDays);
        await _db.SaveChangesAsync(ct);
        return new AuthResponse(user.Id, user.Name, user.Email, accessToken, user.RefreshToken!, expiresAt);
    }

    private static string StableAppDeviceId(string appId, string deviceId) =>
        Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(
            System.Text.Encoding.UTF8.GetBytes($"{appId}|{deviceId}"))).ToLowerInvariant();

    private static string[] ClaimDeviceIds(string appId, string deviceId) =>
        [deviceId, StableAppDeviceId(appId, deviceId)];
}
