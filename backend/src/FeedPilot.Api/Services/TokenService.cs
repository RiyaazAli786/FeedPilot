using System.IdentityModel.Tokens.Jwt;
using System.Security.Claims;
using System.Security.Cryptography;
using System.Text;
using Microsoft.Extensions.Options;
using Microsoft.IdentityModel.Tokens;
using FeedPilot.Api.Domain;

namespace FeedPilot.Api.Services;

public interface ITokenService
{
    (string accessToken, DateTime expiresAt) CreateAccessToken(User user, ClientIdentity? identity = null);
    string CreateRefreshToken();
    TokenValidationParameters GetValidationParameters();
}

public class TokenService : ITokenService
{
    private readonly JwtSettings _settings;

    public TokenService(IOptions<JwtSettings> settings) => _settings = settings.Value;

    private SymmetricSecurityKey SigningKey =>
        new(Encoding.UTF8.GetBytes(_settings.Secret));

    /// <summary>
    /// Identity claims bind a token to the app install that received it. Without this, a clone
    /// that copied local storage could replay the original app's bearer token and fetch the
    /// original account/wallet before device auth had a chance to create a new backend device.
    /// </summary>
    public (string accessToken, DateTime expiresAt) CreateAccessToken(User user, ClientIdentity? identity = null)
    {
        var expiresAt = DateTime.UtcNow.AddMinutes(_settings.AccessTokenMinutes);
        var claims = new List<Claim>
        {
            new Claim(JwtRegisteredClaimNames.Sub, user.Id.ToString()),
            new Claim(JwtRegisteredClaimNames.Email, user.Email),
            new Claim("name", user.Name),
            new Claim(JwtRegisteredClaimNames.Jti, Guid.NewGuid().ToString())
        };
        if (identity is not null)
        {
            claims.Add(new Claim("app_id", identity.AppId));
            claims.Add(new Claim("device_id", identity.DeviceId));
            claims.Add(new Claim("installation_id", identity.InstallationId));
            claims.Add(new Claim("app_instance_id", identity.AppInstanceId));
        }

        var creds = new SigningCredentials(SigningKey, SecurityAlgorithms.HmacSha256);
        var token = new JwtSecurityToken(
            issuer: _settings.Issuer,
            audience: _settings.Audience,
            claims: claims,
            expires: expiresAt,
            signingCredentials: creds);

        return (new JwtSecurityTokenHandler().WriteToken(token), expiresAt);
    }

    public string CreateRefreshToken() =>
        Convert.ToBase64String(RandomNumberGenerator.GetBytes(64));

    public TokenValidationParameters GetValidationParameters() => new()
    {
        ValidateIssuer = true,
        ValidateAudience = true,
        ValidateLifetime = true,
        ValidateIssuerSigningKey = true,
        ValidIssuer = _settings.Issuer,
        ValidAudience = _settings.Audience,
        IssuerSigningKey = SigningKey,
        ClockSkew = TimeSpan.FromSeconds(30)
    };
}
