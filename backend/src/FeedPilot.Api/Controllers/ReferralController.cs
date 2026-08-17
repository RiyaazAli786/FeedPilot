using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using FeedPilot.Api.Data;
using FeedPilot.Api.Domain;
using FeedPilot.Api.Dtos;
using FeedPilot.Api.Services;

namespace FeedPilot.Api.Controllers;

[ApiController]
[Authorize]
[Route("api/referral")]
public class ReferralController : ControllerBase
{
    private readonly AppDbContext _db;
    private readonly IWalletService _wallets;

    public ReferralController(AppDbContext db, IWalletService wallets)
    {
        _db = db;
        _wallets = wallets;
    }

    [HttpGet]
    public async Task<ActionResult<ReferralStatsDto>> GetReferralStats(CancellationToken ct)
    {
        var userId = User.GetUserId();
        var user = await _db.Users.FirstOrDefaultAsync(u => u.Id == userId, ct);
        if (user is null) return NotFound(new ApiError("User not found."));

        if (string.IsNullOrWhiteSpace(user.ReferralCode))
        {
            user.ReferralCode = GenerateReferralCode();
            await _db.SaveChangesAsync(ct);
        }

        string? referredByCode = null;
        if (user.ReferredByUserId.HasValue)
        {
            var referrer = await _db.Users.FirstOrDefaultAsync(u => u.Id == user.ReferredByUserId.Value, ct);
            referredByCode = referrer?.ReferralCode;
        }

        var totalReferredUsers = await _db.Users.CountAsync(u => u.ReferredByUserId == userId, ct);

        var wallet = await _db.Wallets
            .Include(w => w.Transactions)
            .FirstOrDefaultAsync(w => w.UserId == userId, ct);

        var totalReferralCoins = wallet?.Transactions
            .Where(t => t.Type == WalletTransactionType.Referral)
            .Sum(t => t.Coins) ?? 0L;

        var settings = await RunnerSettingsStore.GetOrCreateAsync(_db, ct);

        return Ok(new ReferralStatsDto(
            ReferralCode: user.ReferralCode,
            ReferredByCode: referredByCode,
            TotalReferredUsers: totalReferredUsers,
            TotalReferralCoinsEarned: totalReferralCoins,
            ReferralBonusCoins: settings.ReferralBonusCoins,
            ReferralLevel1Percent: settings.ReferralLevel1Percent,
            ReferralLevel2Percent: settings.ReferralLevel2Percent,
            ReferralLevel3Percent: settings.ReferralLevel3Percent
        ));
    }

    [HttpPost("apply")]
    public async Task<ActionResult<ApplyReferralResponse>> ApplyReferralCode(
        [FromBody] ApplyReferralRequest body, CancellationToken ct)
    {
        if (string.IsNullOrWhiteSpace(body.ReferralCode))
            return BadRequest(new ApiError("Referral code is required.", "MISSING_REFERRAL_CODE"));

        var userId = User.GetUserId();
        var user = await _db.Users.FirstOrDefaultAsync(u => u.Id == userId, ct);
        if (user is null) return NotFound(new ApiError("User not found."));

        if (user.ReferredByUserId.HasValue)
            return BadRequest(new ApiError("You have already applied a referral code.", "ALREADY_REFERRED"));

        var code = body.ReferralCode.Trim().ToUpperInvariant();
        var level1Referrer = await _db.Users.FirstOrDefaultAsync(u => u.ReferralCode == code, ct);
        if (level1Referrer is null)
            return NotFound(new ApiError("Invalid referral code.", "REFERRAL_CODE_NOT_FOUND"));

        if (level1Referrer.Id == userId)
            return BadRequest(new ApiError("You cannot refer yourself.", "CANNOT_REFER_SELF"));

        // Claimed atomically so two concurrent applies for the same new user (double-tap, retry)
        // can't both pass the in-memory check above and both pay out the full bonus pool — the
        // WHERE re-checks the row's current ReferredByUserId at UPDATE time, not the stale read.
        var claimed = await _db.Users
            .Where(u => u.Id == userId && u.ReferredByUserId == null)
            .ExecuteUpdateAsync(s => s.SetProperty(u => u.ReferredByUserId, level1Referrer.Id), ct);
        if (claimed == 0)
            return BadRequest(new ApiError("You have already applied a referral code.", "ALREADY_REFERRED"));

        // Calculate and distribute multi-level referral bonus pool
        var settings = await RunnerSettingsStore.GetOrCreateAsync(_db, ct);
        var basePool = settings.ReferralBonusCoins;

        if (basePool > 0)
        {
            // All payouts share one transaction so a crash mid-payout rolls back cleanly
            // instead of leaving some levels credited and others silently skipped.
            await using var tx = await _db.Database.BeginTransactionAsync(ct);

            // Referee (new user applying the referral code) sign-up bonus payout
            await _wallets.CreditAsync(user.Id, basePool, WalletTransactionType.Referral, $"referral:signup_bonus:from:{level1Referrer.Id}", ct);

            // Level 1 Payout to Referrer
            var coinsL1 = (long)Math.Floor(basePool * (settings.ReferralLevel1Percent / 100.0));
            if (coinsL1 > 0)
                await _wallets.CreditAsync(level1Referrer.Id, coinsL1, WalletTransactionType.Referral, $"referral:level1:from:{user.Id}", ct);

            // Level 2 Payout
            if (level1Referrer.ReferredByUserId.HasValue)
            {
                var level2Referrer = await _db.Users.FirstOrDefaultAsync(u => u.Id == level1Referrer.ReferredByUserId.Value, ct);
                if (level2Referrer is not null && level2Referrer.Id != userId)
                {
                    var coinsL2 = (long)Math.Floor(basePool * (settings.ReferralLevel2Percent / 100.0));
                    if (coinsL2 > 0)
                        await _wallets.CreditAsync(level2Referrer.Id, coinsL2, WalletTransactionType.Referral, $"referral:level2:from:{user.Id}", ct);

                    // Level 3 Payout
                    if (level2Referrer.ReferredByUserId.HasValue)
                    {
                        var level3Referrer = await _db.Users.FirstOrDefaultAsync(u => u.Id == level2Referrer.ReferredByUserId.Value, ct);
                        if (level3Referrer is not null && level3Referrer.Id != userId)
                        {
                            var coinsL3 = (long)Math.Floor(basePool * (settings.ReferralLevel3Percent / 100.0));
                            if (coinsL3 > 0)
                                await _wallets.CreditAsync(level3Referrer.Id, coinsL3, WalletTransactionType.Referral, $"referral:level3:from:{user.Id}", ct);
                        }
                    }
                }
            }

            await tx.CommitAsync(ct);
        }

        return Ok(new ApplyReferralResponse(true, "Referral code applied successfully!"));
    }

    private static string GenerateReferralCode()
    {
        var randomPart = Guid.NewGuid().ToString("N")[..6].ToUpperInvariant();
        return $"TF-{randomPart}";
    }
}
