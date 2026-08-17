using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using FeedPilot.Api.Data;
using FeedPilot.Api.Domain;
using FeedPilot.Api.Dtos;
using FeedPilot.Api.Services;

namespace FeedPilot.Api.Controllers;

/// <summary>
/// Wallet adjustments from the dashboard. Guarded by the admin key, never reachable from
/// the app — a user must not be able to credit their own balance.
///
/// Every adjustment writes a WalletTransaction, so a manual top-up is auditable rather than
/// silently appearing as earnings.
/// </summary>
[ApiController]
[AdminSession]
[Route("api/admin/wallets")]
public class AdminWalletsController : ControllerBase
{
    private readonly AppDbContext _db;
    private readonly IWalletService _wallets;

    public AdminWalletsController(AppDbContext db, IWalletService wallets)
    {
        _db = db;
        _wallets = wallets;
    }

    [HttpGet("{email}")]
    public async Task<ActionResult<object>> Get(string email, CancellationToken ct)
    {
        var user = await _db.Users.FirstOrDefaultAsync(u => u.Email.ToLower() == email.ToLower(), ct);
        if (user is null) return NotFound(new ApiError("No account for that email."));

        var wallet = await _wallets.GetOrCreateAsync(user.Id, ct);
        return Ok(new
        {
            email = user.Email,
            coins = wallet.Coins,
            pendingCoins = wallet.PendingCoins,
            withdrawnCoins = wallet.WithdrawnCoins,
            lifetimeCoins = wallet.LifetimeCoins
        });
    }

    /// <summary>
    /// Credits (or, with a negative amount, debits) a user's balance. Intended for testing
    /// and for correcting a support issue, not as a routine way to hand out coins.
    /// </summary>
    [HttpPost("credit")]
    public async Task<ActionResult<object>> Credit(
        [FromBody] AdminCreditRequest body, CancellationToken ct)
    {
        if (body.Coins == 0)
            return BadRequest(new ApiError("Coins must be non-zero.", "ZERO_AMOUNT"));

        var user = await _db.Users.FirstOrDefaultAsync(
            u => u.Email.ToLower() == body.Email.ToLower(), ct);
        if (user is null) return NotFound(new ApiError("No account for that email."));

        var result = await _wallets.CreditAsync(
            user.Id, body.Coins, WalletTransactionType.Adjustment,
            body.Reason ?? "admin adjustment", ct);

        if (!result.Success)
            return BadRequest(new ApiError(
                $"That would take the balance below zero (currently {result.Balance}).",
                "INSUFFICIENT_COINS"));

        return Ok(new { email = user.Email, coins = result.Balance, applied = body.Coins });
    }

    /// <summary>
    /// Autocomplete for the dashboard's From/To transfer fields: usernames containing the
    /// (partial) query, most recently active first — lets the admin pick a match instead of
    /// having to type the whole handle, same as the app's self-service transfer screen.
    /// </summary>
    [HttpGet("transfer/suggest")]
    public async Task<ActionResult<List<TransferSuggestionDto>>> Suggest(
        [FromQuery] string query, CancellationToken ct)
    {
        var term = (query ?? string.Empty).Trim().ToLowerInvariant();
        if (term.Length == 0) return Ok(new List<TransferSuggestionDto>());

        var termUsername = term.TrimStart('@');
        var rows = await _db.Accounts
            .Where(a => a.Username.ToLower().Contains(termUsername))
            .OrderByDescending(a => a.LastActive ?? a.LastLogin ?? a.CreatedAt)
            .Take(30)
            .ToListAsync(ct);

        var suggestions = rows
            .GroupBy(a => a.Username, StringComparer.OrdinalIgnoreCase)
            .Select(g => g.First())
            .Select(a => new TransferSuggestionDto(a.Username, a.ProfilePictureUrl, a.LastActive))
            .ToList();

        var users = await _db.Users
            .Where(u => u.Email.ToLower().Contains(term))
            .OrderByDescending(u => u.CreatedAt)
            .Take(10)
            .ToListAsync(ct);

        foreach (var u in users)
        {
            if (suggestions.Any(s => s.Username.Equals(u.Email, StringComparison.OrdinalIgnoreCase))) continue;
            suggestions.Add(new TransferSuggestionDto(u.Email, null, u.CreatedAt));
        }

        return Ok(suggestions.Take(8).ToList());
    }

    [HttpGet("transfer/search")]
    public async Task<ActionResult<TransferSearchResultDto>> SearchByUsername(
        [FromQuery] string username, CancellationToken ct)
    {
        if (string.IsNullOrWhiteSpace(username))
            return BadRequest(new ApiError("Username or email is required.", "USERNAME_REQUIRED"));

        var term = username.Trim().ToLowerInvariant();

        // 1. Try search by Email
        var user = await _db.Users.FirstOrDefaultAsync(u => u.Email.ToLower() == term, ct);
        if (user != null)
        {
            return Ok(new TransferSearchResultDto(user.Email, null, user.CreatedAt));
        }

        // 2. Try search by Instagram username
        var account = await ResolveByUsernameAsync(username, ct);
        if (account is null) return NotFound(new ApiError("No user found with that username or email.", "not_found"));

        return Ok(new TransferSearchResultDto(account.Username, account.ProfilePictureUrl, account.LastActive));
    }

    [HttpPost("transfer")]
    public async Task<ActionResult<CoinTransferDto>> Transfer(
        [FromBody] AdminTransferCoinsRequest body, CancellationToken ct)
    {
        var senderRes = await ResolveUserAsync(body.SenderUsername, ct);
        if (senderRes == default) return NotFound(new ApiError("No user found for the sender username or email.", "SENDER_NOT_FOUND"));

        var receiverRes = await ResolveUserAsync(body.ReceiverUsername, ct);
        if (receiverRes == default) return NotFound(new ApiError("No user found for the receiver username or email.", "RECEIVER_NOT_FOUND"));

        if (senderRes.UserId == receiverRes.UserId)
            return BadRequest(new ApiError("Sender and receiver resolve to the same user.", "SAME_USER"));

        var senderWallet = await _wallets.GetOrCreateAsync(senderRes.UserId, ct);
        if (senderWallet.Coins < body.Coins)
            return BadRequest(new ApiError(
                $"Sender only has {senderWallet.Coins} coins, transfer needs {body.Coins}.",
                "INSUFFICIENT_COINS"));

        var transfer = new CoinTransfer
        {
            SenderUserId = senderRes.UserId,
            SenderUsername = senderRes.Username,
            ReceiverUserId = receiverRes.UserId,
            ReceiverUsername = receiverRes.Username,
            Coins = body.Coins,
            InitiatedBy = "admin",
            Note = body.Note,
        };

        await using var tx = await _db.Database.BeginTransactionAsync(ct);
        var debit = await _wallets.CreditAsync(senderRes.UserId, -body.Coins, WalletTransactionType.TransferOut, $"transfer:{transfer.Id}", ct);
        if (!debit.Success)
        {
            await tx.RollbackAsync(ct);
            return BadRequest(new ApiError(
                $"Sender only has {debit.Balance} coins, transfer needs {body.Coins}.",
                "INSUFFICIENT_COINS"));
        }
        await _wallets.CreditAsync(receiverRes.UserId, body.Coins, WalletTransactionType.TransferIn, $"transfer:{transfer.Id}", ct);
        _db.CoinTransfers.Add(transfer);
        await _db.SaveChangesAsync(ct);
        await tx.CommitAsync(ct);

        return Ok(new CoinTransferDto(
            transfer.Id, transfer.SenderUsername, transfer.ReceiverUsername, transfer.Coins,
            transfer.InitiatedBy, transfer.Note, transfer.CreatedAt));
    }

    [HttpGet("transfers")]
    public async Task<ActionResult<IEnumerable<CoinTransferDto>>> Transfers(
        [FromQuery] int limit, CancellationToken ct)
    {
        var take = limit > 0 ? Math.Clamp(limit, 1, 200) : 50;
        var rows = await _db.CoinTransfers
            .OrderByDescending(t => t.CreatedAt)
            .Take(take)
            .ToListAsync(ct);

        return Ok(rows.Select(t => new CoinTransferDto(
            t.Id, t.SenderUsername, t.ReceiverUsername, t.Coins, t.InitiatedBy, t.Note, t.CreatedAt)));
    }

    private async Task<(Guid UserId, string Username)> ResolveUserAsync(string query, CancellationToken ct)
    {
        var term = query?.Trim().ToLowerInvariant() ?? string.Empty;
        if (term.Length == 0) return default;

        // Try email first
        var user = await _db.Users.FirstOrDefaultAsync(u => u.Email.ToLower() == term, ct);
        if (user != null) return (user.Id, user.Email);

        // Try username
        var username = term.TrimStart('@');
        var account = await _db.Accounts
            .Where(a => a.Username.ToLower() == username)
            .OrderByDescending(a => a.LastActive ?? a.LastLogin ?? a.CreatedAt)
            .FirstOrDefaultAsync(ct);

        if (account != null) return (account.UserId, account.Username);

        return default;
    }

    private async Task<Account?> ResolveByUsernameAsync(string username, CancellationToken ct)
    {
        var term = username?.Trim().TrimStart('@').ToLowerInvariant() ?? string.Empty;
        if (term.Length == 0) return null;

        return await _db.Accounts
            .Where(a => a.Username.ToLower() == term)
            .OrderByDescending(a => a.LastActive ?? a.LastLogin ?? a.CreatedAt)
            .FirstOrDefaultAsync(ct);
    }
}
