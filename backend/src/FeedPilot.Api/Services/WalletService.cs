using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;
using FeedPilot.Api.Data;
using FeedPilot.Api.Domain;
using FeedPilot.Api.Hubs;

namespace FeedPilot.Api.Services;

/// <summary>Result of an atomic wallet mutation. <see cref="Success"/> is only false for a debit
/// that the balance could not cover — nothing is applied in that case.</summary>
public readonly record struct WalletMutationResult(bool Success, long Balance, Guid WalletId);

public interface IWalletService
{
    Task<Wallet> GetOrCreateAsync(Guid userId, CancellationToken ct = default);

    /// <summary>
    /// Atomically applies a signed delta (positive = credit, negative = debit) to a user's
    /// wallet balance in a single conditional UPDATE, so concurrent callers can never lose or
    /// duplicate a coin movement the way an in-memory read-modify-write would. A debit only
    /// applies if the balance covers it; otherwise nothing is changed and Success is false.
    /// </summary>
    Task<WalletMutationResult> CreditAsync(
        Guid userId, long coins, WalletTransactionType type, string? reference, CancellationToken ct = default);

    /// <summary>Atomically moves coins from the spendable balance into PendingCoins for a new withdrawal request.</summary>
    Task<WalletMutationResult> TryReserveForWithdrawalAsync(
        Guid userId, long coins, string reference, CancellationToken ct = default);

    /// <summary>Atomically moves reserved coins from PendingCoins into WithdrawnCoins once a payout is confirmed.</summary>
    Task SettleWithdrawalAsync(Guid walletId, long coins, string reference, CancellationToken ct = default);

    /// <summary>Atomically returns reserved coins from PendingCoins back to the spendable balance (rejected withdrawal).</summary>
    Task ReleaseWithdrawalReservationAsync(Guid walletId, long coins, string reference, CancellationToken ct = default);

    Task FlushPendingBroadcastsAsync(CancellationToken ct = default);
}

public class WalletService : IWalletService
{
    private readonly AppDbContext _db;
    private readonly IHubContext<CoinSyncHub, ICoinSyncClient>? _hubContext;
    private readonly Dictionary<Guid, PendingBroadcastMeta> _pendingBroadcasts = new();

    private readonly record struct PendingBroadcastMeta(Guid UserId, string? Type, string? Reference);

    public WalletService(AppDbContext db, IHubContext<CoinSyncHub, ICoinSyncClient>? hubContext = null)
    {
        _db = db;
        _hubContext = hubContext;
    }

    private async Task BroadcastWalletUpdateAsync(Guid userId, Wallet wallet, string? type = null, string? reference = null, CancellationToken ct = default)
    {
        if (_hubContext is null) return;
        try
        {
            var msg = new WalletUpdateMessage(
                wallet.Coins,
                wallet.LifetimeCoins,
                wallet.PendingCoins,
                wallet.WithdrawnCoins,
                wallet.UpdatedAt.ToString("o"),
                reference,
                type
            );
            await _hubContext.Clients.Group($"user:{userId}").WalletUpdated(msg);
        }
        catch { }
    }

    private async Task BroadcastOrDeferAsync(Guid userId, Wallet wallet, string? type = null, string? reference = null, CancellationToken ct = default)
    {
        if (_db.Database.CurrentTransaction is not null)
        {
            _pendingBroadcasts[wallet.Id] = new PendingBroadcastMeta(userId, type, reference);
            return;
        }

        await BroadcastWalletUpdateAsync(userId, wallet, type, reference, ct);
    }

    public async Task FlushPendingBroadcastsAsync(CancellationToken ct = default)
    {
        if (_pendingBroadcasts.Count == 0) return;

        var pending = _pendingBroadcasts.ToArray();
        _pendingBroadcasts.Clear();
        foreach (var item in pending)
        {
            var wallet = await _db.Wallets.AsNoTracking().FirstOrDefaultAsync(w => w.Id == item.Key, ct);
            if (wallet is not null)
                await BroadcastWalletUpdateAsync(item.Value.UserId, wallet, item.Value.Type, item.Value.Reference, ct);
        }
    }

    public async Task<Wallet> GetOrCreateAsync(Guid userId, CancellationToken ct = default) =>
        await EnsureWalletRowAsync(userId, ct);

    public async Task<WalletMutationResult> CreditAsync(
        Guid userId, long coins, WalletTransactionType type, string? reference, CancellationToken ct = default)
    {
        var wallet = await EnsureWalletRowAsync(userId, ct);
        var now = DateTime.UtcNow;

        var rows = await _db.Wallets
            .Where(w => w.Id == wallet.Id && w.Coins + coins >= 0)
            .ExecuteUpdateAsync(s => s
                .SetProperty(w => w.Coins, w => w.Coins + coins)
                .SetProperty(w => w.LifetimeCoins, w => type == WalletTransactionType.Earn ? w.LifetimeCoins + coins : w.LifetimeCoins)
                .SetProperty(w => w.UpdatedAt, now), ct);

        if (rows == 0)
            return new WalletMutationResult(false, wallet.Coins, wallet.Id);

        _db.WalletTransactions.Add(new WalletTransaction
        {
            WalletId = wallet.Id,
            Coins = coins,
            Type = type,
            Reference = reference
        });
        await _db.SaveChangesAsync(ct);

        var updatedWallet = await _db.Wallets.AsNoTracking().FirstOrDefaultAsync(w => w.Id == wallet.Id, ct);
        if (updatedWallet is not null)
            await BroadcastOrDeferAsync(userId, updatedWallet, type.ToString(), reference, ct);

        return new WalletMutationResult(true, updatedWallet?.Coins ?? wallet.Coins, wallet.Id);
    }

    public async Task<WalletMutationResult> TryReserveForWithdrawalAsync(
        Guid userId, long coins, string reference, CancellationToken ct = default)
    {
        var wallet = await EnsureWalletRowAsync(userId, ct);
        var now = DateTime.UtcNow;

        var rows = await _db.Wallets
            .Where(w => w.Id == wallet.Id && w.Coins >= coins)
            .ExecuteUpdateAsync(s => s
                .SetProperty(w => w.Coins, w => w.Coins - coins)
                .SetProperty(w => w.PendingCoins, w => w.PendingCoins + coins)
                .SetProperty(w => w.UpdatedAt, now), ct);

        if (rows == 0)
            return new WalletMutationResult(false, wallet.Coins, wallet.Id);

        _db.WalletTransactions.Add(new WalletTransaction
        {
            WalletId = wallet.Id,
            Coins = -coins,
            Type = WalletTransactionType.WithdrawHold,
            Reference = reference
        });
        await _db.SaveChangesAsync(ct);

        var balance = await _db.Wallets.Where(w => w.Id == wallet.Id).Select(w => w.Coins).FirstAsync(ct);
        var updatedWallet = await _db.Wallets.AsNoTracking().FirstOrDefaultAsync(w => w.Id == wallet.Id, ct);
        if (updatedWallet is not null)
            await BroadcastOrDeferAsync(userId, updatedWallet, WalletTransactionType.WithdrawHold.ToString(), reference, ct);

        return new WalletMutationResult(true, balance, wallet.Id);
    }

    public async Task SettleWithdrawalAsync(Guid walletId, long coins, string reference, CancellationToken ct = default)
    {
        var now = DateTime.UtcNow;
        await _db.Wallets
            .Where(w => w.Id == walletId)
            .ExecuteUpdateAsync(s => s
                .SetProperty(w => w.PendingCoins, w => w.PendingCoins - coins < 0 ? 0 : w.PendingCoins - coins)
                .SetProperty(w => w.WithdrawnCoins, w => w.WithdrawnCoins + coins)
                .SetProperty(w => w.UpdatedAt, now), ct);

        _db.WalletTransactions.Add(new WalletTransaction
        {
            WalletId = walletId,
            Coins = -coins,
            Type = WalletTransactionType.Withdraw,
            Reference = reference
        });
        await _db.SaveChangesAsync(ct);

        var updatedWallet = await _db.Wallets.AsNoTracking().FirstOrDefaultAsync(w => w.Id == walletId, ct);
        if (updatedWallet is not null)
            await BroadcastOrDeferAsync(updatedWallet.UserId, updatedWallet, WalletTransactionType.Withdraw.ToString(), reference, ct);
    }

    public async Task ReleaseWithdrawalReservationAsync(Guid walletId, long coins, string reference, CancellationToken ct = default)
    {
        var now = DateTime.UtcNow;
        await _db.Wallets
            .Where(w => w.Id == walletId)
            .ExecuteUpdateAsync(s => s
                .SetProperty(w => w.PendingCoins, w => w.PendingCoins - coins < 0 ? 0 : w.PendingCoins - coins)
                .SetProperty(w => w.Coins, w => w.Coins + coins)
                .SetProperty(w => w.UpdatedAt, now), ct);

        _db.WalletTransactions.Add(new WalletTransaction
        {
            WalletId = walletId,
            Coins = coins,
            Type = WalletTransactionType.Refund,
            Reference = reference
        });
        await _db.SaveChangesAsync(ct);

        var updatedWallet = await _db.Wallets.AsNoTracking().FirstOrDefaultAsync(w => w.Id == walletId, ct);
        if (updatedWallet is not null)
            await BroadcastOrDeferAsync(updatedWallet.UserId, updatedWallet, WalletTransactionType.Refund.ToString(), reference, ct);
    }

    /// <summary>
    /// Loads the wallet row for a user, creating it on first touch. The insert is wrapped in a
    /// catch/re-query because two near-simultaneous first requests for a brand-new user/device
    /// (e.g. the first couple of API calls from a freshly cloned app instance) can otherwise both
    /// miss the initial read and race to insert the same row, tripping the unique index on
    /// UserId for whichever one loses.
    /// </summary>
    private async Task<Wallet> EnsureWalletRowAsync(Guid userId, CancellationToken ct)
    {
        var wallet = await _db.Wallets.FirstOrDefaultAsync(w => w.UserId == userId, ct);
        if (wallet is not null) return wallet;

        wallet = new Wallet { UserId = userId };
        _db.Wallets.Add(wallet);
        try
        {
            await _db.SaveChangesAsync(ct);
        }
        catch (DbUpdateException)
        {
            _db.Entry(wallet).State = EntityState.Detached;
            wallet = await _db.Wallets.FirstOrDefaultAsync(w => w.UserId == userId, ct);
            if (wallet is null) throw;
        }
        return wallet;
    }
}
