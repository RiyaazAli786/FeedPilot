using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.Caching.Memory;
using Microsoft.Extensions.Options;
using FeedPilot.Api.Dtos;
using FeedPilot.Api.Services;

namespace FeedPilot.Api.Controllers;

/// <summary>
/// Mints the session token <see cref="BackupSessionAttribute"/> checks on <see cref="AdminBackupController"/>.
/// Gated by <see cref="AdminSessionAttribute"/> itself — a backup session can only be obtained by
/// someone who already holds a valid admin session, preserving the "both must pass, backup passcode
/// stacks on top of the admin key" relationship <see cref="AdminBackupController"/> has always had.
///
/// Same one-shot nonce+HMAC challenge-response as <see cref="AdminAuthController"/>: the raw backup
/// passcode is used once, here, and never sent again.
/// </summary>
[ApiController]
[AdminSession]
[Route("api/admin/backup/auth")]
public class AdminBackupAuthController : ControllerBase
{
    private const string NonceCacheKeyPrefix = "backup-login-nonce:";
    private static readonly TimeSpan NonceLifetime = TimeSpan.FromSeconds(60);

    /// <summary>Deliberately shorter than the admin session — backup export/restore can read or
    /// overwrite the entire database, so a leaked token should limit the blast radius. Re-obtaining
    /// it only costs re-entering the passcode.</summary>
    private static readonly TimeSpan SessionLifetime = TimeSpan.FromHours(2);

    private readonly AdminSettings _settings;
    private readonly IMemoryCache _cache;

    public AdminBackupAuthController(IOptions<AdminSettings> settings, IMemoryCache cache)
    {
        _settings = settings.Value;
        _cache = cache;
    }

    [HttpPost("challenge")]
    public ActionResult<BackupLoginChallengeResponse> IssueChallenge()
    {
        if (string.IsNullOrWhiteSpace(_settings.BackupPasscode))
        {
            return StatusCode(StatusCodes.Status503ServiceUnavailable, new ApiError(
                "Backup/restore is disabled because no Admin:BackupPasscode is configured.", "BACKUP_DISABLED"));
        }

        var token = ChallengeResponse.NewToken();
        var nonce = ChallengeResponse.NewNonce();
        _cache.Set(NonceCacheKeyPrefix + token, nonce, NonceLifetime);

        return Ok(new BackupLoginChallengeResponse(token, Convert.ToBase64String(nonce)));
    }

    [HttpPost("login")]
    public ActionResult<BackupSessionResponse> Login([FromBody] VerifyBackupLoginRequest body)
    {
        if (string.IsNullOrWhiteSpace(_settings.BackupPasscode))
        {
            return StatusCode(StatusCodes.Status503ServiceUnavailable, new ApiError(
                "Backup/restore is disabled because no Admin:BackupPasscode is configured.", "BACKUP_DISABLED"));
        }

        var cacheKey = NonceCacheKeyPrefix + body.Token;
        if (!_cache.TryGetValue(cacheKey, out byte[]? nonce))
        {
            return Unauthorized(new ApiError("Challenge expired — try again.", "BACKUP_UNAUTHORIZED"));
        }
        _cache.Remove(cacheKey);

        var expectedHash = ChallengeResponse.ComputeHmac(_settings.BackupPasscode, nonce!);
        if (!ChallengeResponse.VerifyHash(body.Hash, expectedHash))
        {
            return Unauthorized(new ApiError("Invalid backup passcode.", "BACKUP_UNAUTHORIZED"));
        }

        var sessionToken = ChallengeResponse.NewSessionToken();
        var expiresAt = DateTime.UtcNow.Add(SessionLifetime);
        _cache.Set(BackupSessionAttribute.CacheKeyPrefix + sessionToken, true, SessionLifetime);

        return Ok(new BackupSessionResponse(sessionToken, expiresAt));
    }

    [HttpPost("logout")]
    public ActionResult Logout()
    {
        var token = Request.Headers[BackupSessionAttribute.HeaderName].ToString();
        if (!string.IsNullOrEmpty(token))
        {
            _cache.Remove(BackupSessionAttribute.CacheKeyPrefix + token);
        }
        return NoContent();
    }
}
