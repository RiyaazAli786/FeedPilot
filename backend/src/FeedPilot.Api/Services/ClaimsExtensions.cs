using System.IdentityModel.Tokens.Jwt;
using System.Security.Claims;

namespace FeedPilot.Api.Services;

public static class ClaimsExtensions
{
    /// <summary>Resolves the authenticated user's id from the JWT `sub` claim.</summary>
    public static Guid GetUserId(this ClaimsPrincipal principal)
    {
        var sub = principal.FindFirstValue(JwtRegisteredClaimNames.Sub)
                  ?? principal.FindFirstValue(ClaimTypes.NameIdentifier);
        return Guid.TryParse(sub, out var id)
            ? id
            : throw new UnauthorizedAccessException("Missing user identity in token.");
    }

    /// <summary>
    /// Resolves the optional `installation_id` claim (see TokenService.CreateAccessToken). Not yet
    /// populated by any token-issuing call site in this foundational phase, so this is dormant
    /// scaffolding for a future enforcement phase — callers must treat a null result as "no
    /// installation proof available", never as an error, so old/current tokens keep working.
    /// </summary>
    public static string? GetInstallationId(this ClaimsPrincipal principal) =>
        principal.FindFirstValue("installation_id");
}
