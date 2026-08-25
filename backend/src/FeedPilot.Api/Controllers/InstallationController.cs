using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using FeedPilot.Api.Dtos;
using FeedPilot.Api.Services;

namespace FeedPilot.Api.Controllers;

/// <summary>
/// Foundational-phase installation identity endpoints (see IdentityIsolation.md). Purely
/// additive: nothing else in the API depends on these succeeding yet, so every action is
/// [AllowAnonymous], matching api/auth/device and api/devices/register today.
/// </summary>
[ApiController]
[Route("api/installations")]
public class InstallationController : ControllerBase
{
    private readonly IInstallationService _installations;
    private readonly IInstallationChallengeService _challenges;
    private readonly ISignatureService _signatures;

    public InstallationController(
        IInstallationService installations,
        IInstallationChallengeService challenges,
        ISignatureService signatures)
    {
        _installations = installations;
        _challenges = challenges;
        _signatures = signatures;
    }

    /// <summary>
    /// Registers (or updates) the public key for an installation. Idempotent — safe to call
    /// repeatedly, e.g. after CloneDataIsolationGuard regenerates the Keystore key.
    /// </summary>
    [AllowAnonymous]
    [HttpPost("register")]
    public async Task<ActionResult<InstallationDto>> Register(InstallationRegisterRequest req, CancellationToken ct)
    {
        if (string.IsNullOrWhiteSpace(req.InstallationId) || string.IsNullOrWhiteSpace(req.PublicKey))
            return BadRequest(new ApiError("installationId and publicKey are required.", "invalid_request"));

        var userId = User.Identity?.IsAuthenticated == true ? User.GetUserId() : (Guid?)null;
        var installation = await _installations.RegisterAsync(req.InstallationId.Trim(), req.PublicKey.Trim(), userId, ct);
        return Ok(new InstallationDto(installation.InstallationId, installation.Status.ToString(), installation.CreatedAt));
    }

    [AllowAnonymous]
    [HttpPost("challenge")]
    public async Task<ActionResult<InstallationChallengeDto>> Challenge(InstallationChallengeRequest req, CancellationToken ct)
    {
        var installation = await _installations.GetAsync(req.InstallationId.Trim(), ct);
        if (installation is null)
            return NotFound(new ApiError("Unknown installation.", "not_found"));

        var challenge = await _challenges.IssueAsync(installation.InstallationId, ct);
        return Ok(new InstallationChallengeDto(challenge.Challenge, challenge.ExpiresAt));
    }

    [AllowAnonymous]
    [HttpPost("verify")]
    public async Task<ActionResult<InstallationVerifyResultDto>> Verify(InstallationVerifyRequest req, CancellationToken ct)
    {
        var installation = await _installations.GetAsync(req.InstallationId.Trim(), ct);
        if (installation is null)
            return Ok(new InstallationVerifyResultDto(false));

        var consumed = await _challenges.ConsumeAsync(installation.InstallationId, req.Challenge, ct);
        if (!consumed)
            return Ok(new InstallationVerifyResultDto(false));

        var valid = _signatures.Verify(installation.PublicKey, req.Challenge, req.Signature);
        if (valid)
            await _installations.TouchLastSeenAsync(installation, ct);

        return Ok(new InstallationVerifyResultDto(valid));
    }
}
