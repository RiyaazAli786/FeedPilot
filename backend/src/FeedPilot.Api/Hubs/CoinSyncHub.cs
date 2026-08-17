using System.Security.Claims;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.SignalR;
using FeedPilot.Api.Services;

namespace FeedPilot.Api.Hubs;

public interface ICoinSyncClient
{
    Task WalletUpdated(WalletUpdateMessage message);
    Task RunnerSettingsUpdated(Dtos.RunnerSettingsDto settings);
}

public record WalletUpdateMessage(
    long Coins,
    long LifetimeCoins,
    long PendingCoins,
    long WithdrawnCoins,
    string UpdatedAt,
    string? TransactionId = null,
    string? Type = null
);

[Authorize]
public class CoinSyncHub : Hub<ICoinSyncClient>
{
    public override async Task OnConnectedAsync()
    {
        var userIdStr = Context.User?.FindFirst(ClaimTypes.NameIdentifier)?.Value
                        ?? Context.User?.FindFirst("sub")?.Value;

        if (!string.IsNullOrEmpty(userIdStr))
        {
            await Groups.AddToGroupAsync(Context.ConnectionId, $"user:{userIdStr}");
        }

        await base.OnConnectedAsync();
    }

    public override async Task OnDisconnectedAsync(Exception? exception)
    {
        var userIdStr = Context.User?.FindFirst(ClaimTypes.NameIdentifier)?.Value
                        ?? Context.User?.FindFirst("sub")?.Value;

        if (!string.IsNullOrEmpty(userIdStr))
        {
            await Groups.RemoveFromGroupAsync(Context.ConnectionId, $"user:{userIdStr}");
        }

        await base.OnDisconnectedAsync(exception);
    }
}
