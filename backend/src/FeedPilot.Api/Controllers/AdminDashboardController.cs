using System.Security.Cryptography;
using System.Text;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.Caching.Memory;
using Microsoft.Extensions.Options;
using FeedPilot.Api.Dtos;
using FeedPilot.Api.Services;

namespace FeedPilot.Api.Controllers;

/// <summary>
/// Backs the dashboard's idle-lock screen (index.html/backup.html re-lock 60s after the last
/// interaction). The passcode itself lives only in <see cref="AdminSettings.DashboardPasscode"/>
/// (an operator-set env var, never something entered through the browser), so unlocking is
/// always a round trip here rather than a local comparison — nothing worth checking against ever
/// reaches client storage. Deliberately NOT gated by the admin session: the lock is a PIN gate in
/// front of an already-connected browser tab, independent of API-level authorization, so unlocking
/// it must not require (or transmit) that separate credential. <see cref="PasscodeChallenge"/> and
/// <see cref="VerifyPasscode"/> fall under the admin-auth branch of the "api" rate-limit policy
/// instead, since without the session gate they're reachable by anyone on the network — see
/// Program.cs.
///
/// The passcode never crosses the wire in the clear: the frontend first calls
/// <see cref="PasscodeChallenge"/> for a one-time nonce, then <see cref="VerifyPasscode"/> takes
/// only HMAC-SHA256(key: passcode, message: nonce) — keeping the literal passcode out of the
/// request body, browser network log, and any proxy/access log that captures request payloads.
/// The nonce is single-use and short-lived, so a captured hash can't be replayed either.
/// </summary>
[ApiController]
[Route("api/admin/dashboard")]
public class AdminDashboardController : ControllerBase
{
    private const string NonceCacheKeyPrefix = "dashboard-passcode-nonce:";
    private static readonly TimeSpan NonceLifetime = TimeSpan.FromSeconds(60);

    private readonly AdminSettings _settings;
    private readonly IMemoryCache _cache;

    public AdminDashboardController(IOptions<AdminSettings> settings, IMemoryCache cache)
    {
        _settings = settings.Value;
        _cache = cache;
    }

    /// <summary>Lets the frontend know whether it has anything to lock against before showing the lock screen.</summary>
    [HttpGet("lock-status")]
    public ActionResult<DashboardLockStatusResponse> LockStatus() =>
        Ok(new DashboardLockStatusResponse(!string.IsNullOrWhiteSpace(_settings.DashboardPasscode)));

    /// <summary>Returns feature flag toggles configured for the dashboard.</summary>
    [HttpGet("feature-flags")]
    public ActionResult<DashboardFeatureFlagsResponse> FeatureFlags()
    {
        var envVal = Environment.GetEnvironmentVariable("SHOW_JSON_UPLOAD_FEATURE");
        var isEnabled = _settings.ShowJsonUploadFeature ||
            string.Equals(envVal, "true", StringComparison.OrdinalIgnoreCase) ||
            envVal == "1";
        return Ok(new DashboardFeatureFlagsResponse(isEnabled));
    }

    /// <summary>Issues a fresh one-time nonce for <see cref="VerifyPasscode"/>'s challenge-response.</summary>
    [HttpPost("passcode-challenge")]
    public ActionResult<DashboardPasscodeChallengeResponse> PasscodeChallenge()
    {
        if (string.IsNullOrWhiteSpace(_settings.DashboardPasscode))
        {
            return StatusCode(StatusCodes.Status503ServiceUnavailable, new ApiError(
                "Dashboard lock is not configured.", "DASHBOARD_LOCK_DISABLED"));
        }

        var token = Convert.ToHexString(RandomNumberGenerator.GetBytes(16));
        var nonce = RandomNumberGenerator.GetBytes(32);
        _cache.Set(NonceCacheKeyPrefix + token, nonce, NonceLifetime);

        return Ok(new DashboardPasscodeChallengeResponse(token, Convert.ToBase64String(nonce)));
    }

    [HttpPost("verify-passcode")]
    public ActionResult VerifyPasscode([FromBody] VerifyDashboardPasscodeRequest body)
    {
        if (string.IsNullOrWhiteSpace(_settings.DashboardPasscode))
        {
            return StatusCode(StatusCodes.Status503ServiceUnavailable, new ApiError(
                "Dashboard lock is not configured.", "DASHBOARD_LOCK_DISABLED"));
        }

        var cacheKey = NonceCacheKeyPrefix + body.Token;
        if (!_cache.TryGetValue(cacheKey, out byte[]? nonce))
        {
            return Unauthorized(new ApiError(
                "Challenge expired — try again.", "DASHBOARD_PASSCODE_UNAUTHORIZED"));
        }
        // One-shot: remove immediately so this same challenge can't be answered twice, even if
        // the first (correct) hash gets intercepted and replayed.
        _cache.Remove(cacheKey);

        byte[] providedHash;
        try
        {
            providedHash = Convert.FromBase64String(body.Hash);
        }
        catch (FormatException)
        {
            return Unauthorized(new ApiError("Incorrect passcode.", "DASHBOARD_PASSCODE_UNAUTHORIZED"));
        }

        var expectedHash = HMACSHA256.HashData(Encoding.UTF8.GetBytes(_settings.DashboardPasscode), nonce!);

        if (providedHash.Length != expectedHash.Length ||
            !CryptographicOperations.FixedTimeEquals(providedHash, expectedHash))
        {
            return Unauthorized(new ApiError("Incorrect passcode.", "DASHBOARD_PASSCODE_UNAUTHORIZED"));
        }

        return Ok();
    }
}
