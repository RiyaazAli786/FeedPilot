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
}
