using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using FeedPilot.Api.Data;
using FeedPilot.Api.Domain;
using FeedPilot.Api.Dtos;
using FeedPilot.Api.Services;

namespace FeedPilot.Api.Controllers;

/// <summary>
/// Admin lookup of a user's referral activity: who they were referred by, and — the main
/// point of this controller — every user who signed up directly using their referral code
/// (see <see cref="ReferralController.ApplyReferralCode"/> for how that relationship and its
/// one-time payout are created). Guarded by the admin session, never reachable from the app.
/// </summary>
[ApiController]
[AdminSession]
[Route("api/admin/referrals")]
public class AdminReferralsController : ControllerBase
{
    private readonly AppDbContext _db;

    public AdminReferralsController(AppDbContext db)
    {
        _db = db;
    }

    /// <summary>
    /// Autocomplete for the dashboard's referral lookup field: matches a partial referral code,
    /// email, or linked Instagram username, same pattern as the Coin Transfer tab's picker.
    /// </summary>
    [HttpGet("suggest")]
    public async Task<ActionResult<List<AdminReferralSuggestionDto>>> Suggest(
        [FromQuery] string query, CancellationToken ct)
    {
        var term = (query ?? string.Empty).Trim().ToLowerInvariant();
        if (term.Length == 0) return Ok(new List<AdminReferralSuggestionDto>());

        var suggestions = new List<AdminReferralSuggestionDto>();

        var byCode = await _db.Users
            .Where(u => u.ReferralCode != null && u.ReferralCode.ToLower().Contains(term))
            .OrderByDescending(u => u.CreatedAt)
            .Take(8)
            .Select(u => u.ReferralCode!)
            .ToListAsync(ct);
        suggestions.AddRange(byCode.Select(c => new AdminReferralSuggestionDto(c, "code")));

        var byEmail = await _db.Users
            .Where(u => u.Email.ToLower().Contains(term))
            .OrderByDescending(u => u.CreatedAt)
            .Take(8)
            .Select(u => u.Email)
            .ToListAsync(ct);
        suggestions.AddRange(byEmail
            .Where(e => !suggestions.Any(s => s.Label.Equals(e, StringComparison.OrdinalIgnoreCase)))
            .Select(e => new AdminReferralSuggestionDto(e, "email")));

        var termUsername = term.TrimStart('@');
        var byUsername = await _db.Accounts
            .Where(a => a.Username.ToLower().Contains(termUsername))
            .OrderByDescending(a => a.LastActive ?? a.LastLogin ?? a.CreatedAt)
            .GroupBy(a => a.Username)
            .Select(g => g.Key)
            .Take(8)
            .ToListAsync(ct);
        suggestions.AddRange(byUsername
            .Where(uName => !suggestions.Any(s => s.Label.Equals(uName, StringComparison.OrdinalIgnoreCase)))
            .Select(uName => new AdminReferralSuggestionDto(uName, "username")));

        return Ok(suggestions.Take(10).ToList());
    }

    /// <summary>
    /// Resolves a referrer by referral code, email, or linked Instagram username (in that
    /// order), and returns every user who signed up directly using their code, plus how many
    /// coins each of those referrals actually paid out.
    /// </summary>
    [HttpGet("lookup")]
    public async Task<ActionResult<AdminReferralLookupDto>> Lookup(
        [FromQuery] string query, CancellationToken ct)
    {
        if (string.IsNullOrWhiteSpace(query))
            return BadRequest(new ApiError("A referral code, email, or username is required.", "QUERY_REQUIRED"));

        var referrer = await ResolveUserAsync(query, ct);
        if (referrer is null)
            return NotFound(new ApiError("No user found for that referral code, email, or username.", "not_found"));

        if (string.IsNullOrWhiteSpace(referrer.ReferralCode))
            return NotFound(new ApiError("This user has never generated a referral code.", "no_referral_code"));

        string? referredByCode = null;
        if (referrer.ReferredByUserId.HasValue)
        {
            var upstream = await _db.Users.FirstOrDefaultAsync(u => u.Id == referrer.ReferredByUserId.Value, ct);
            referredByCode = upstream?.ReferralCode;
        }

        var wallet = await _db.Wallets
            .Include(w => w.Transactions)
            .FirstOrDefaultAsync(w => w.UserId == referrer.Id, ct);

        var referralTransactions = wallet?.Transactions
            .Where(t => t.Type == WalletTransactionType.Referral)
            .ToList() ?? new List<WalletTransaction>();
        var totalReferralCoinsEarned = referralTransactions.Sum(t => t.Coins);

        var directReferrals = await _db.Users
            .Where(u => u.ReferredByUserId == referrer.Id)
            .OrderByDescending(u => u.CreatedAt)
            .ToListAsync(ct);

        var referredUsers = directReferrals.Select(u =>
        {
            // ApplyReferralCode only ever runs once per user (guarded by ReferredByUserId
            // already being set), so there is at most one level1 payout row per referred user.
            var coinsFromThisUser = referralTransactions
                .Where(t => t.Reference == $"referral:level1:from:{u.Id}")
                .Sum(t => t.Coins);
            return new AdminReferredUserDto(u.Id, u.Email, u.Name, u.CreatedAt, u.Plan, coinsFromThisUser);
        }).ToList();

        return Ok(new AdminReferralLookupDto(
            referrer.Id, referrer.Email, referrer.Name, referrer.ReferralCode, referrer.CreatedAt,
            referrer.Plan, referredByCode, wallet?.Coins ?? 0,
            directReferrals.Count, totalReferralCoinsEarned, referredUsers));
    }

    /// <summary>
    /// Every referral relationship in the system, most recently joined first — the default view
    /// so an admin can browse who was referred by whom without already knowing a specific code,
    /// email, or username to search for (see <see cref="Lookup"/> for that narrower search).
    /// </summary>
    [HttpGet("all")]
    public async Task<ActionResult<PagedResult<AdminReferralRowDto>>> All(
        [FromQuery] int page = 1, [FromQuery] int pageSize = 25, CancellationToken ct = default)
    {
        page = Math.Max(1, page);
        pageSize = Math.Clamp(pageSize, 1, 200);

        var query = _db.Users.Where(u => u.ReferredByUserId != null);
        var total = await query.CountAsync(ct);

        var pageUsers = await query
            .OrderByDescending(u => u.CreatedAt)
            .Skip((page - 1) * pageSize)
            .Take(pageSize)
            .ToListAsync(ct);

        var referrerIds = pageUsers.Select(u => u.ReferredByUserId!.Value).Distinct().ToList();
        var referrers = await _db.Users
            .Where(u => referrerIds.Contains(u.Id))
            .ToDictionaryAsync(u => u.Id, ct);

        // Only need each referrer's Referral-typed transactions, not their whole ledger — this
        // page's referrer set is small (bounded by pageSize distinct referrers at most), so
        // pulling just those wallets' Referral rows stays cheap regardless of how large either
        // table grows overall.
        var referrerWalletIds = await _db.Wallets
            .Where(w => referrerIds.Contains(w.UserId))
            .Select(w => new { w.Id, w.UserId })
            .ToListAsync(ct);
        var walletIdToUserId = referrerWalletIds.ToDictionary(w => w.Id, w => w.UserId);
        var referralTxByWalletId = await _db.WalletTransactions
            .Where(t => t.Type == WalletTransactionType.Referral &&
                        referrerWalletIds.Select(w => w.Id).Contains(t.WalletId))
            .ToListAsync(ct);

        var rows = pageUsers.Select(u =>
        {
            var referrerId = u.ReferredByUserId!.Value;
            referrers.TryGetValue(referrerId, out var referrer);

            // ApplyReferralCode only ever runs once per user, so there is at most one level1
            // payout row for this specific (referrer, referred user) pair.
            var coins = referralTxByWalletId
                .Where(t => walletIdToUserId.TryGetValue(t.WalletId, out var ownerId) && ownerId == referrerId &&
                            t.Reference == $"referral:level1:from:{u.Id}")
                .Sum(t => t.Coins);

            return new AdminReferralRowDto(
                u.Id, u.Email, u.Name, u.CreatedAt, u.Plan,
                referrerId, referrer?.Email ?? "", referrer?.ReferralCode, coins);
        }).ToList();

        return Ok(new PagedResult<AdminReferralRowDto>(rows, total, page, pageSize));
    }

    private async Task<User?> ResolveUserAsync(string query, CancellationToken ct)
    {
        var term = query.Trim();
        if (term.Length == 0) return null;

        var code = term.ToUpperInvariant();
        var byCode = await _db.Users.FirstOrDefaultAsync(u => u.ReferralCode == code, ct);
        if (byCode is not null) return byCode;

        var lower = term.ToLowerInvariant();
        var byEmail = await _db.Users.FirstOrDefaultAsync(u => u.Email.ToLower() == lower, ct);
        if (byEmail is not null) return byEmail;

        var username = lower.TrimStart('@');
        var account = await _db.Accounts
            .Where(a => a.Username.ToLower() == username)
            .OrderByDescending(a => a.LastActive ?? a.LastLogin ?? a.CreatedAt)
            .FirstOrDefaultAsync(ct);
        if (account is null) return null;

        return await _db.Users.FirstOrDefaultAsync(u => u.Id == account.UserId, ct);
    }
}
