using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.Caching.Memory;
using Microsoft.Extensions.Options;
using FeedPilot.Api.Dtos;
using FeedPilot.Api.Services;

namespace FeedPilot.Api.Controllers;

/// <summary>
/// Mints the session token <see cref="AdminSessionAttribute"/> checks on every other admin
/// endpoint. The raw admin key is used exactly once, here, via the same nonce+HMAC
/// challenge-response <see cref="AdminDashboardController"/> already uses for the dashboard
/// passcode — <see cref="Login"/> never receives the key itself, only
/// HMAC-SHA256(key: ApiKey, message: nonce) — so it never crosses the wire in the clear, and every
/// other admin request afterward carries only the resulting session token, not the key.
///
/// Unauthenticated by design (it's the login entry point), so rate-limited instead — see the
/// admin-auth branch of the "api" rate-limit policy in Program.cs.
/// </summary>
[ApiController]
[Route("api/admin/auth")]
public class AdminAuthController : ControllerBase
{
    private const string NonceCacheKeyPrefix = "admin-login-nonce:";
    private static readonly TimeSpan NonceLifetime = TimeSpan.FromSeconds(60);

    /// <summary>Covers a full workday without forcing a re-login mid-task, while still expiring —
    /// unlike the raw key today, a session copied off a shared/borrowed machine doesn't grant
    /// indefinite access.</summary>
    private static readonly TimeSpan SessionLifetime = TimeSpan.FromDays(3650);

    private readonly AdminSettings _settings;
    private readonly IMemoryCache _cache;

    public AdminAuthController(IOptions<AdminSettings> settings, IMemoryCache cache)
    {
        _settings = settings.Value;
        _cache = cache;
    }

    [HttpPost("challenge")]
    public ActionResult<AdminLoginChallengeResponse> IssueChallenge()
    {
        if (string.IsNullOrWhiteSpace(_settings.ApiKey))
        {
            return StatusCode(StatusCodes.Status503ServiceUnavailable, new ApiError(
                "Admin API is disabled because no Admin:ApiKey is configured.", "ADMIN_DISABLED"));
        }

        var token = ChallengeResponse.NewToken();
        var nonce = ChallengeResponse.NewNonce();
        _cache.Set(NonceCacheKeyPrefix + token, nonce, NonceLifetime);

        return Ok(new AdminLoginChallengeResponse(token, Convert.ToBase64String(nonce)));
    }

    [HttpPost("login")]
    public ActionResult<AdminSessionResponse> Login([FromBody] VerifyAdminLoginRequest body)
    {
        if (string.IsNullOrWhiteSpace(_settings.ApiKey))
        {
            return StatusCode(StatusCodes.Status503ServiceUnavailable, new ApiError(
                "Admin API is disabled because no Admin:ApiKey is configured.", "ADMIN_DISABLED"));
        }

        var cacheKey = NonceCacheKeyPrefix + body.Token;
        if (!_cache.TryGetValue(cacheKey, out byte[]? nonce))
        {
            return Unauthorized(new ApiError("Challenge expired — try again.", "ADMIN_UNAUTHORIZED"));
        }
        // One-shot: remove immediately so this same challenge can't be answered twice.
        _cache.Remove(cacheKey);

        var expectedHash = ChallengeResponse.ComputeHmac(_settings.ApiKey, nonce!);
        if (!ChallengeResponse.VerifyHash(body.Hash, expectedHash))
        {
            return Unauthorized(new ApiError("Invalid admin key.", "ADMIN_UNAUTHORIZED"));
        }

        var sessionToken = ChallengeResponse.NewPersistentSessionToken(_settings.ApiKey);
        var expiresAt = DateTime.UtcNow.Add(SessionLifetime);

        return Ok(new AdminSessionResponse(sessionToken, expiresAt));
    }

    [HttpPost("logout")]
    [AdminSession]
    public ActionResult Logout()
    {
        var token = Request.Headers[AdminSessionAttribute.HeaderName].ToString();
        if (!string.IsNullOrEmpty(token))
        {
            _cache.Remove(AdminSessionAttribute.CacheKeyPrefix + token);
        }
        return NoContent();
    }
}
