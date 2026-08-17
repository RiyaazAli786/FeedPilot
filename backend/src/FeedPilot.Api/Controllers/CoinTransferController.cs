using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using FeedPilot.Api.Data;
using FeedPilot.Api.Domain;
using FeedPilot.Api.Dtos;
using FeedPilot.Api.Services;

namespace FeedPilot.Api.Controllers;

/// <summary>
/// Self-service coin transfer: the signed-in user sends coins from their own wallet to whoever
/// owns a searched-for account username. The receiver is resolved to whichever of that
/// username's linked accounts was most recently active — "the device it's currently logged in
/// on" — since the same handle can only belong to one user at a time in practice, but a lookup
/// still needs a tiebreaker.
/// </summary>
[ApiController]
[Authorize]
[Route("api/wallet/transfer")]
public class CoinTransferController : ControllerBase
{
    private readonly AppDbContext _db;
    private readonly IWalletService _wallets;

    public CoinTransferController(AppDbContext db, IWalletService wallets)
    {
        _db = db;
        _wallets = wallets;
    }

    /// <summary>
    /// Autocomplete for the receiver field: usernames containing the (partial) query, most
    /// recently active first, so the sender can pick from a list instead of having to know and
    /// type the whole handle up front. <see cref="Search"/> below still does the exact-match
    /// resolution once a full username is settled on (typed complete, or picked from here).
    /// </summary>
    [HttpGet("suggest")]
    public async Task<ActionResult<List<TransferSuggestionDto>>> Suggest(
        [FromQuery] string query, CancellationToken ct)
    {
        var term = (query ?? string.Empty).Trim().ToLowerInvariant();
        if (term.Length == 0) return Ok(new List<TransferSuggestionDto>());

        var userId = User.GetUserId();
        var termUsername = term.TrimStart('@');
        var rows = await _db.Accounts
            .Where(a => a.Username.ToLower().Contains(termUsername) && a.UserId != userId)
            .OrderByDescending(a => a.LastActive ?? a.LastLogin ?? a.CreatedAt)
            .Take(30)
            .ToListAsync(ct);

        var suggestions = rows
            .GroupBy(a => a.Username, StringComparer.OrdinalIgnoreCase)
            .Select(g => g.First())
            .Select(a => new TransferSuggestionDto(a.Username, a.ProfilePictureUrl, a.LastActive))
            .ToList();

        var users = await _db.Users
            .Where(u => u.Email.ToLower().Contains(term) && u.Id != userId)
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

    [HttpGet("search")]
    public async Task<ActionResult<TransferSearchResultDto>> Search(
        [FromQuery] string username, CancellationToken ct)
    {
        if (string.IsNullOrWhiteSpace(username))
            return BadRequest(new ApiError("Username or email is required.", "USERNAME_REQUIRED"));

        var term = username.Trim().ToLowerInvariant();
        var userId = User.GetUserId();

        // 1. Try search by Email
        var user = await _db.Users.FirstOrDefaultAsync(u => u.Email.ToLower() == term, ct);
        if (user != null)
        {
            if (user.Id == userId)
                return BadRequest(new ApiError("You can't transfer coins to yourself.", "SELF_TRANSFER"));
            
            return Ok(new TransferSearchResultDto(user.Email, null, user.CreatedAt));
        }

        // 2. Try search by Instagram username
        var account = await ResolveByUsernameAsync(username, ct);
        if (account is null) return NotFound(new ApiError("No user found with that username or email.", "not_found"));

        if (account.UserId == userId)
            return BadRequest(new ApiError("You can't transfer coins to yourself.", "SELF_TRANSFER"));

        return Ok(new TransferSearchResultDto(account.Username, account.ProfilePictureUrl, account.LastActive));
    }

    [HttpPost]
    public async Task<ActionResult<TransferCoinsResponse>> Transfer(
        [FromBody] TransferCoinsRequest body, CancellationToken ct)
    {
        var senderId = User.GetUserId();
        var term = body.ReceiverUsername.Trim().ToLowerInvariant();

        Guid receiverUserId;
        string receiverUsername;

        // Resolve receiver
        var user = await _db.Users.FirstOrDefaultAsync(u => u.Email.ToLower() == term, ct);
        if (user != null)
        {
            receiverUserId = user.Id;
            receiverUsername = user.Email;
        }
        else
        {
            var receiverAccount = await ResolveByUsernameAsync(body.ReceiverUsername, ct);
            if (receiverAccount is null)
                return NotFound(new ApiError("No user found with that username or email.", "not_found"));
            receiverUserId = receiverAccount.UserId;
            receiverUsername = receiverAccount.Username;
        }

        if (receiverUserId == senderId)
            return BadRequest(new ApiError("You can't transfer coins to yourself.", "SELF_TRANSFER"));

        var senderWallet = await _wallets.GetOrCreateAsync(senderId, ct);
        if (senderWallet.Coins < body.Coins)
            return BadRequest(new ApiError(
                $"Not enough coins. You have {senderWallet.Coins}, transfer needs {body.Coins}.",
                "INSUFFICIENT_COINS"));

        var senderUsername = (await _db.Accounts
                .Where(a => a.UserId == senderId)
                .OrderByDescending(a => a.LastActive ?? a.LastLogin ?? a.CreatedAt)
                .Select(a => a.Username)
                .FirstOrDefaultAsync(ct))
            ?? (await _db.Users.Where(u => u.Id == senderId).Select(u => u.Email).FirstAsync(ct));

        var transfer = new CoinTransfer
        {
            SenderUserId = senderId,
            SenderUsername = senderUsername,
            ReceiverUserId = receiverUserId,
            ReceiverUsername = receiverUsername,
            Coins = body.Coins,
            InitiatedBy = "user",
            Note = body.Note,
        };

        // Both legs must land together — a debit with no matching credit (or vice versa) would
        // either destroy or mint coins out of nowhere. The debit's balance check happens inside
        // the atomic update itself (WalletService.CreditAsync), not against the pre-transaction
        // senderWallet snapshot above, so two concurrent transfers from the same sender can't
        // both pass a stale check and overdraw the wallet.
        await using var tx = await _db.Database.BeginTransactionAsync(ct);
        var debit = await _wallets.CreditAsync(senderId, -body.Coins, WalletTransactionType.TransferOut, $"transfer:{transfer.Id}", ct);
        if (!debit.Success)
        {
            await tx.RollbackAsync(ct);
            return BadRequest(new ApiError(
                $"Not enough coins. You have {senderWallet.Coins}, transfer needs {body.Coins}.",
                "INSUFFICIENT_COINS"));
        }
        await _wallets.CreditAsync(receiverUserId, body.Coins, WalletTransactionType.TransferIn, $"transfer:{transfer.Id}", ct);
        _db.CoinTransfers.Add(transfer);
        await _db.SaveChangesAsync(ct);
        await tx.CommitAsync(ct);

        return Ok(new TransferCoinsResponse(transfer.Id, receiverUsername, body.Coins, debit.Balance));
    }

    /// <summary>Every transfer the signed-in user sent or received, newest first.</summary>
    [HttpGet("history")]
    public async Task<ActionResult<IEnumerable<CoinTransferDto>>> History(CancellationToken ct)
    {
        var userId = User.GetUserId();
        var rows = await _db.CoinTransfers
            .Where(t => t.SenderUserId == userId || t.ReceiverUserId == userId)
            .OrderByDescending(t => t.CreatedAt)
            .Take(100)
            .ToListAsync(ct);

        return Ok(rows.Select(t => new CoinTransferDto(
            t.Id, t.SenderUsername, t.ReceiverUsername, t.Coins, t.InitiatedBy, t.Note, t.CreatedAt)));
    }

    /// <summary>
    /// Resolves a searched username to the account that used it most recently — the same handle
    /// can be linked under more than one user's install over time, so "most recently active"
    /// is the tiebreaker for "who owns this username right now."
    /// </summary>
    private async Task<Account?> ResolveByUsernameAsync(string username, CancellationToken ct)
    {
        var term = username.Trim().TrimStart('@').ToLowerInvariant();
        if (term.Length == 0) return null;

        return await _db.Accounts
            .Where(a => a.Username.ToLower() == term)
            .OrderByDescending(a => a.LastActive ?? a.LastLogin ?? a.CreatedAt)
            .FirstOrDefaultAsync(ct);
    }
}
