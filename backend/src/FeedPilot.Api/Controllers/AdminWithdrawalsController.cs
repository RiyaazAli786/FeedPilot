using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using FeedPilot.Api.Data;
using FeedPilot.Api.Domain;
using FeedPilot.Api.Dtos;
using FeedPilot.Api.Services;

namespace FeedPilot.Api.Controllers;

/// <summary>
/// Withdrawal management for the dashboard.
///
/// Coins are only ever deducted here, when a payout is confirmed. Until then they sit in
/// PendingCoins — reserved, so they cannot be spent or withdrawn twice, but not yet taken.
/// </summary>
[ApiController]
[AdminSession]
[Route("api/admin/withdrawals")]
public class AdminWithdrawalsController : ControllerBase
{
    private readonly AppDbContext _db;
    private readonly IWalletService _wallets;

    public AdminWithdrawalsController(AppDbContext db, IWalletService wallets)
    {
        _db = db;
        _wallets = wallets;
    }

    [HttpGet]
    public async Task<ActionResult<PagedResult<AdminWithdrawalDto>>> List(
        [FromQuery] WithdrawalStatus? status,
        [FromQuery] string? appId,
        [FromQuery] string? deviceId,
        [FromQuery] int page = 1,
        [FromQuery] int pageSize = 25,
        CancellationToken ct = default)
    {
        page = Math.Max(1, page);
        pageSize = Math.Clamp(pageSize, 1, 200);

        var query = _db.Withdrawals.Include(w => w.Wallet).ThenInclude(x => x!.User).AsQueryable();
        if (status.HasValue) query = query.Where(w => w.Status == status.Value);
        if (!string.IsNullOrWhiteSpace(appId)) query = query.Where(w => w.AppId == appId);
        if (!string.IsNullOrWhiteSpace(deviceId)) query = query.Where(w => w.DeviceId == deviceId);

        var total = await query.CountAsync(ct);
        var items = await query
            .OrderByDescending(w => w.CreatedAt)
            .Skip((page - 1) * pageSize)
            .Take(pageSize)
            .ToListAsync(ct);

        return Ok(new PagedResult<AdminWithdrawalDto>(
            items.Select(ToAdminDto).ToList(), total, page, pageSize));
    }

    /// <summary>
    /// Settles a withdrawal. Only the transition to <see cref="WithdrawalStatus.Completed"/>
    /// actually deducts coins, and only once — <c>CoinsSettled</c> guards a repeat call.
    /// Rejecting returns the reserved coins to the spendable balance.
    /// </summary>
    [HttpPatch("{id:guid}")]
    public async Task<ActionResult<AdminWithdrawalDto>> Update(
        Guid id, [FromBody] UpdateWithdrawalRequest body, CancellationToken ct)
    {
        var withdrawal = await _db.Withdrawals
            .Include(w => w.Wallet).ThenInclude(x => x!.User)
            .FirstOrDefaultAsync(w => w.Id == id, ct);
        if (withdrawal is null) return NotFound(new ApiError("Withdrawal not found."));

        var wallet = withdrawal.Wallet;
        if (wallet is null) return NotFound(new ApiError("Wallet not found for this withdrawal."));

        if (body.AdminNote is not null) withdrawal.AdminNote = body.AdminNote;
        if (body.PaymentReference is not null) withdrawal.PaymentReference = body.PaymentReference;

        if (body.Status.HasValue && body.Status.Value != withdrawal.Status)
        {
            var next = body.Status.Value;
            var previousStatus = withdrawal.Status;

            // The transition is claimed with a conditional UPDATE whose WHERE re-checks the
            // row's *current* Status/CoinsSettled in the database, not the in-memory snapshot
            // read above — so two concurrent PATCHes for the same withdrawal (e.g. a double
            // click, or settle racing reject) can no longer both pass the guard and both move
            // the wallet balance. Only the request that wins the claim touches the wallet.
            if (next == WithdrawalStatus.Completed && !withdrawal.CoinsSettled)
            {
                await using var tx = await _db.Database.BeginTransactionAsync(ct);
                var claimed = await _db.Withdrawals
                    .Where(w => w.Id == id && w.Status == previousStatus && !w.CoinsSettled)
                    .ExecuteUpdateAsync(s => s
                        .SetProperty(w => w.Status, next)
                        .SetProperty(w => w.CoinsSettled, true)
                        .SetProperty(w => w.ProcessedAt, DateTime.UtcNow), ct);

                if (claimed == 1)
                {
                    // Payment confirmed — now the coins actually leave the account.
                    await _wallets.SettleWithdrawalAsync(wallet.Id, withdrawal.Coins, $"withdrawal:{withdrawal.Id}", ct);
                    withdrawal.Status = next;
                    withdrawal.CoinsSettled = true;
                    withdrawal.ProcessedAt = DateTime.UtcNow;
                }
                else
                {
                    // Lost the race (or was already settled elsewhere) — reflect what's
                    // actually in the database rather than assuming our transition applied.
                    await _db.Entry(withdrawal).ReloadAsync(ct);
                }
                await tx.CommitAsync(ct);
            }
            else if (next is WithdrawalStatus.Rejected && !withdrawal.CoinsSettled)
            {
                await using var tx = await _db.Database.BeginTransactionAsync(ct);
                var claimed = await _db.Withdrawals
                    .Where(w => w.Id == id && w.Status == previousStatus && !w.CoinsSettled)
                    .ExecuteUpdateAsync(s => s
                        .SetProperty(w => w.Status, next)
                        .SetProperty(w => w.ProcessedAt, DateTime.UtcNow), ct);

                if (claimed == 1)
                {
                    // Never paid — hand the reserved coins back.
                    await _wallets.ReleaseWithdrawalReservationAsync(wallet.Id, withdrawal.Coins, $"withdrawal:{withdrawal.Id}", ct);
                    withdrawal.Status = next;
                    withdrawal.ProcessedAt = DateTime.UtcNow;
                }
                else
                {
                    await _db.Entry(withdrawal).ReloadAsync(ct);
                }
                await tx.CommitAsync(ct);
            }
            else
            {
                withdrawal.Status = next;
            }
        }

        await _db.SaveChangesAsync(ct);
        return Ok(ToAdminDto(withdrawal));
    }

    [HttpGet("stats")]
    public async Task<ActionResult<object>> Stats(CancellationToken ct)
    {
        var all = await _db.Withdrawals
            .Select(w => new { w.Status, w.Coins, w.Amount, w.AppId })
            .ToListAsync(ct);

        return Ok(new
        {
            total = all.Count,
            pending = all.Count(w => w.Status == WithdrawalStatus.Pending),
            processing = all.Count(w => w.Status == WithdrawalStatus.Processing),
            approved = all.Count(w => w.Status == WithdrawalStatus.Approved),
            completed = all.Count(w => w.Status == WithdrawalStatus.Completed),
            rejected = all.Count(w => w.Status == WithdrawalStatus.Rejected),
            coinsAwaitingPayout = all
                .Where(w => w.Status is WithdrawalStatus.Pending or WithdrawalStatus.Processing or WithdrawalStatus.Approved)
                .Sum(w => w.Coins),
            amountPaid = all.Where(w => w.Status == WithdrawalStatus.Completed).Sum(w => w.Amount),
            apps = all.Select(w => w.AppId).Distinct().OrderBy(a => a).ToList()
        });
    }

    private static AdminWithdrawalDto ToAdminDto(Withdrawal w) => new(
        w.Id,
        w.Wallet?.UserId ?? Guid.Empty,
        w.Wallet?.User?.Email ?? string.Empty,
        w.AppId,
        w.DeviceId,
        w.Coins,
        w.Amount,
        w.PaymentMethod,
        w.UpiId,
        w.BankDetails,
        w.UsdtAddress,
        w.Status,
        w.CoinsSettled,
        w.AdminNote,
        w.PaymentReference,
        w.CreatedAt,
        w.ProcessedAt);
}
