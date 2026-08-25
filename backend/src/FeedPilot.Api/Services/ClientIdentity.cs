using Microsoft.AspNetCore.Mvc;

namespace FeedPilot.Api.Services;

/// <summary>
/// Identifies which cloned app build, on which install, made a request.
///
/// Every clone ships a distinct <c>X-App-Id</c>, and each install sends a stable
/// <c>X-Device-Id</c>. Records are stamped with both so the dashboard can tell one clone's
/// orders, coins and withdrawals from another's. <c>X-Hardware-Id</c> is the one identifier that
/// stays identical across every clone on the same physical device (see DeviceIdentity.kt's
/// hardwareDeviceId) — use it, not DeviceId, whenever a check needs to reach across clones.
/// </summary>
public record ClientIdentity(string AppId, string DeviceId, string HardwareId, string InstallationId, string AppInstanceId)
{
    public const string AppIdHeader = "X-App-Id";
    public const string DeviceIdHeader = "X-Device-Id";
    public const string HardwareIdHeader = "X-Hardware-Id";
    public const string InstallationIdHeader = "X-Installation-Id";
    public const string AppInstanceIdHeader = "X-App-Instance-Id";

    /// <summary>Used when a client predates these headers, so old builds keep working.</summary>
    public const string Unknown = "unknown";

    public static ClientIdentity From(HttpRequest request)
    {
        var appId = Clean(request.Headers[AppIdHeader].ToString());
        var deviceId = Clean(request.Headers[DeviceIdHeader].ToString());
        var hardwareId = Clean(request.Headers[HardwareIdHeader].ToString());
        var installationId = Clean(request.Headers[InstallationIdHeader].ToString());
        var appInstanceId = Clean(request.Headers[AppInstanceIdHeader].ToString());
        return new ClientIdentity(appId, deviceId, hardwareId, installationId, appInstanceId);
    }

    private static string Clean(string? raw)
    {
        var value = raw?.Trim();
        if (string.IsNullOrWhiteSpace(value)) return Unknown;
        return value.Length > 128 ? value[..128] : value;
    }
}

public static class ClientIdentityExtensions
{
    public static ClientIdentity ClientIdentity(this ControllerBase controller) =>
        Services.ClientIdentity.From(controller.HttpContext.Request);
}
