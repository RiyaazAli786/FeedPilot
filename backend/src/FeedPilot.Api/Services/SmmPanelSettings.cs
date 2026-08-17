namespace FeedPilot.Api.Services;

/// <summary>
/// Configuration for the external SMM panel integration (smmorigin.com).
/// Set values via the "SmmPanel" appsettings section or environment variables
/// (e.g. SmmPanel__ApiKey).
/// </summary>
public class SmmPanelSettings
{
    /// <summary>Base URL including trailing slash, e.g. https://smmorigin.com/admin/adminapi/v2/</summary>
    public string BaseUrl { get; set; } = "https://smmorigin.com/admin/adminapi/v2/";

    public string ApiKey { get; set; } = string.Empty;

    /// <summary>SMM panel service ID for Instagram Follow orders (171).</summary>
    public int FollowServiceId { get; set; } = 171;

    /// <summary>SMM panel service ID for Instagram Like orders (172).</summary>
    public int LikeServiceId { get; set; } = 172;

    public int CommentServiceId { get; set; } = 177;
    public int CommentCustomServiceId { get; set; } = 178;
    public int RepostServiceId { get; set; } = 175;
    public int SavePostServiceId { get; set; } = 176;
    public int StoryViewServiceId { get; set; } = 179;

    /// <summary>How often to poll the panel for new / updated orders (minutes).</summary>
    public int PollIntervalMinutes { get; set; } = 5;

    /// <summary>AppId tag stamped on orders imported from the SMM panel.</summary>
    public const string ExternalAppId = "smmorigin";
}
