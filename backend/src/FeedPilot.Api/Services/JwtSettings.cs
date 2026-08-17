namespace FeedPilot.Api.Services;

public class JwtSettings
{
    public string Issuer { get; set; } = "FeedPilot";
    public string Audience { get; set; } = "FeedPilotClient";
    public string Secret { get; set; } = "CHANGE_ME_super_secret_key_of_at_least_32_characters!!";
    public int AccessTokenMinutes { get; set; } = 60;
    public int RefreshTokenDays { get; set; } = 30;
}
