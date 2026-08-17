using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.Filters;
using Microsoft.Extensions.Caching.Memory;
using Microsoft.Extensions.Options;
using FeedPilot.Api.Dtos;

namespace FeedPilot.Api.Services;

public class AdminSettings
{
    /// <summary>
    /// Shared secret for the dashboard. Never sent on ordinary requests: the browser exchanges it
    /// once, via <c>POST /api/admin/auth/challenge</c> then <c>/login</c> (HMAC challenge-response,
    /// same pattern as <see cref="DashboardPasscode"/>), for a short-lived session token that
    /// <see cref="AdminSessionAttribute"/> checks on every request after that.
    /// Blank disables every admin endpoint — a missing key must never mean "open".
    /// </summary>
    public string ApiKey { get; set; } = string.Empty;

    /// <summary>
    /// Second, separate secret required only for backup/restore. Backup/restore reads or overwrites
    /// the entire database (every user, every session cookie, every wallet) — worth gating behind a
    /// passcode that can be held more tightly than the general admin key, which day-to-day dashboard
    /// use may hand to more people. Exchanged for a session token the same way as
    /// <see cref="ApiKey"/>, via <c>POST /api/admin/backup/auth/challenge</c> then <c>/login</c> —
    /// see <see cref="BackupSessionAttribute"/>. Stacks on top of <see cref="ApiKey"/>'s session, it
    /// does not replace it. Blank disables the backup endpoints — a missing passcode must never mean
    /// "open".
    /// </summary>
    public string BackupPasscode { get; set; } = string.Empty;

    /// <summary>
    /// Optional passcode the dashboard's idle-lock screen (index.html/backup.html) checks via
    /// <c>POST /api/admin/dashboard/verify-passcode</c>. Unlike <see cref="ApiKey"/> and
    /// <see cref="BackupPasscode"/>, this guards a frontend convenience layer rather than an API
    /// action, so a blank value means the lock feature is simply off — the frontend never shows
    /// the lock screen — rather than "locked with no way to unlock".
    /// </summary>
    public string DashboardPasscode { get; set; } = string.Empty;

    /// <summary>
    /// Feature flag controlling whether the JSON order import/upload button and modal is shown on the admin dashboard.
    /// Can be set via Admin__ShowJsonUploadFeature configuration or SHOW_JSON_UPLOAD_FEATURE environment variable. Defaults to false.
    /// </summary>
    public bool ShowJsonUploadFeature { get; set; } = false;
}

/// <summary>
/// Guards the dashboard endpoints with a session token minted by
/// <see cref="Controllers.AdminAuthController"/> after a successful admin-key challenge-response
/// login. The app's JWT scheme carries no roles, so admin access is a separate credential rather
/// than an elevated user token; unlike the credential itself, the session token is what actually
/// travels on every request, and it expires — a stolen token from browser storage doesn't grant
/// indefinite access the way replaying the raw key forever would.
/// </summary>
public class AdminSessionAttribute : ActionFilterAttribute
{
    public const string HeaderName = "X-Admin-Session";
    public const string CacheKeyPrefix = "admin-session:";

    public override void OnActionExecuting(ActionExecutingContext context)
    {
        if (context.ActionDescriptor.EndpointMetadata.OfType<Microsoft.AspNetCore.Authorization.IAllowAnonymous>().Any()) return;

        var settings = context.HttpContext.RequestServices
            .GetRequiredService<IOptions<AdminSettings>>().Value;

        if (string.IsNullOrWhiteSpace(settings.ApiKey))
        {
            context.Result = new ObjectResult(new ApiError(
                "Admin API is disabled because no Admin:ApiKey is configured.", "ADMIN_DISABLED"))
            {
                StatusCode = StatusCodes.Status503ServiceUnavailable
            };
            return;
        }

        var token = context.HttpContext.Request.Headers[HeaderName].ToString();
        if (!string.IsNullOrEmpty(token) &&
            ChallengeResponse.VerifyPersistentSessionToken(token, settings.ApiKey))
        {
            return;
        }
        var cache = context.HttpContext.RequestServices.GetRequiredService<IMemoryCache>();
        if (string.IsNullOrEmpty(token) || !cache.TryGetValue(CacheKeyPrefix + token, out _))
        {
            context.Result = new UnauthorizedObjectResult(
                new ApiError("Invalid or expired admin session — sign in again.", "ADMIN_UNAUTHORIZED"));
        }
    }
}

/// <summary>
/// Second gate for backup/restore only, stacked on top of <see cref="AdminSessionAttribute"/> —
/// both must pass. Kept as a separate attribute/secret rather than folded into the admin key so
/// exporting or overwriting the entire database needs a credential that can be held more tightly
/// than the one used for everyday dashboard work. Session token minted by
/// <see cref="Controllers.AdminBackupAuthController"/>.
/// </summary>
public class BackupSessionAttribute : ActionFilterAttribute
{
    public const string HeaderName = "X-Backup-Session";
    public const string CacheKeyPrefix = "backup-session:";

    public override void OnActionExecuting(ActionExecutingContext context)
    {
        if (context.ActionDescriptor.EndpointMetadata.OfType<Microsoft.AspNetCore.Authorization.IAllowAnonymous>().Any()) return;

        var settings = context.HttpContext.RequestServices
            .GetRequiredService<IOptions<AdminSettings>>().Value;

        if (string.IsNullOrWhiteSpace(settings.BackupPasscode))
        {
            context.Result = new ObjectResult(new ApiError(
                "Backup/restore is disabled because no Admin:BackupPasscode is configured.", "BACKUP_DISABLED"))
            {
                StatusCode = StatusCodes.Status503ServiceUnavailable
            };
            return;
        }

        var token = context.HttpContext.Request.Headers[HeaderName].ToString();
        var cache = context.HttpContext.RequestServices.GetRequiredService<IMemoryCache>();
        if (string.IsNullOrEmpty(token) || !cache.TryGetValue(CacheKeyPrefix + token, out _))
        {
            context.Result = new UnauthorizedObjectResult(
                new ApiError("Invalid or expired backup session — re-enter the backup passcode.", "BACKUP_UNAUTHORIZED"));
        }
    }
}
