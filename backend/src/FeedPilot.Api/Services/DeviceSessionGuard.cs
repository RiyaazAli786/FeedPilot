using System.Security.Claims;
using Microsoft.EntityFrameworkCore;
using FeedPilot.Api.Data;
using FeedPilot.Api.Domain;

namespace FeedPilot.Api.Services;

public static class DeviceSessionGuard
{
    public static async Task<bool> CurrentIdentityBelongsToUserAsync(
        this AppDbContext db,
        ClaimsPrincipal user,
        Guid userId,
        ClientIdentity identity,
        CancellationToken ct)
    {
        if (identity.AppId == ClientIdentity.Unknown || identity.DeviceId == ClientIdentity.Unknown)
            return true;

        if (!TokenIdentityMatches(user, identity))
            return false;

        var hasTokenBinding = user.HasClaim(c => c.Type is "app_id" or "device_id" or "app_instance_id");
        if (hasTokenBinding)
            return true;

        return await db.Devices.AnyAsync(d =>
            d.UserId == userId &&
            d.AppId == identity.AppId &&
            d.DeviceId == identity.DeviceId &&
            (identity.AppInstanceId == ClientIdentity.Unknown ||
             d.AppInstanceId == null ||
             d.AppInstanceId == identity.AppInstanceId), ct);
    }

    private static bool TokenIdentityMatches(ClaimsPrincipal user, ClientIdentity identity)
    {
        var tokenAppId = user.FindFirstValue("app_id");
        var tokenDeviceId = user.FindFirstValue("device_id");
        var tokenInstanceId = user.FindFirstValue("app_instance_id");

        if (!string.IsNullOrWhiteSpace(tokenAppId) &&
            !string.Equals(tokenAppId, identity.AppId, StringComparison.Ordinal))
            return false;

        if (!string.IsNullOrWhiteSpace(tokenDeviceId) &&
            !string.Equals(tokenDeviceId, identity.DeviceId, StringComparison.Ordinal))
            return false;

        if (!string.IsNullOrWhiteSpace(tokenInstanceId) &&
            identity.AppInstanceId != ClientIdentity.Unknown &&
            !string.Equals(tokenInstanceId, identity.AppInstanceId, StringComparison.Ordinal))
            return false;

        return true;
    }
}
