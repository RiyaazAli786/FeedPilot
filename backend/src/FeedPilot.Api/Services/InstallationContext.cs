namespace FeedPilot.Api.Services;

/// <summary>
/// Populated by a future enforcement-phase middleware once a request's installation_id JWT claim
/// is validated against a non-revoked AppInstallation row. Left unpopulated (null) for every
/// request in this foundational phase — no controller reads it yet, so its mere presence changes
/// no existing behavior. Registered as scoped so a later middleware can populate it per-request.
/// </summary>
public class InstallationContext
{
    public Guid? InstallationRowId { get; set; }
    public string? InstallationId { get; set; }
    public Guid? UserId { get; set; }
    public bool IsAuthenticated => InstallationRowId.HasValue;
}
