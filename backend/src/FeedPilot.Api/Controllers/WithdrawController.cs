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
[Route("api/withdraw")]
public class WithdrawController : ControllerBase
{
    private readonly AppDbContext _db;
    private readonly IWalletService _wallets;

    public WithdrawController(AppDbContext db, IWalletService wallets)
    {
        _db = db;
        _wallets = wallets;
    }

    [HttpPost]
    public async Task<ActionResult<WithdrawalDto>> Create(WithdrawRequest req, CancellationToken ct)
    {
        var settings = await RunnerSettingsStore.GetOrCreateAsync(_db, ct);

        var methodEnabled = req.PaymentMethod switch
        {
            PaymentMethod.Upi => settings.UpiEnabled,
            PaymentMethod.Bank => settings.BankEnabled,
            PaymentMethod.UsdtBep20 => settings.UsdtBep20Enabled,
            _ => false
        };
        if (!methodEnabled)
            return BadRequest(new ApiError("This payment method is currently unavailable.", "method_disabled"));

        // Coins-per-unit and the withdrawal amount are both currency-specific: INR methods
        // (UPI/Bank) share CoinsPerInr, while USDT BEP20 has its own independent rate so it can
        // be tuned to the actual USDT/coin exchange rate without moving the INR rate.
        decimal amount;
        long minWithdrawalCoins;
        string minWithdrawalMessage;
        if (req.PaymentMethod == PaymentMethod.UsdtBep20)
        {
            minWithdrawalCoins = (long)Math.Ceiling(settings.MinWithdrawalUsdt * settings.CoinsPerUsdt);
            amount = req.Coins / (decimal)settings.CoinsPerUsdt;
            minWithdrawalMessage = $"Minimum withdrawal is {settings.MinWithdrawalUsdt} USDT ({minWithdrawalCoins} coins).";
        }
        else
        {
            minWithdrawalCoins = (long)settings.MinWithdrawalInr * settings.CoinsPerInr;
            amount = req.Coins / (decimal)settings.CoinsPerInr;
            minWithdrawalMessage = $"Minimum withdrawal is ₹{settings.MinWithdrawalInr} ({minWithdrawalCoins} coins).";
        }

        if (req.Coins < minWithdrawalCoins)
            return BadRequest(new ApiError(minWithdrawalMessage, "below_minimum"));
        if (req.PaymentMethod == PaymentMethod.Upi && string.IsNullOrWhiteSpace(req.UpiId))
            return BadRequest(new ApiError("UPI ID is required.", "missing_upi"));
        if (req.PaymentMethod == PaymentMethod.Bank && string.IsNullOrWhiteSpace(req.BankDetails))
            return BadRequest(new ApiError("Bank details are required.", "missing_bank"));
        if (req.PaymentMethod == PaymentMethod.UsdtBep20 && string.IsNullOrWhiteSpace(req.UsdtAddress))
            return BadRequest(new ApiError("USDT BEP20 wallet address is required.", "missing_usdt_address"));

        var wallet = await _wallets.GetOrCreateAsync(User.GetUserId(), ct);
        if (wallet.Coins < req.Coins)
            return BadRequest(new ApiError("Insufficient balance.", "insufficient_balance"));

        var identity = this.ClientIdentity();
        var withdrawal = new Withdrawal
        {
            WalletId = wallet.Id,
            Coins = req.Coins,
            Amount = amount,
            PaymentMethod = req.PaymentMethod,
            UpiId = req.UpiId,
            BankDetails = req.BankDetails,
            UsdtAddress = req.UsdtAddress,
            Status = WithdrawalStatus.Pending,
            AppId = identity.AppId,
            DeviceId = identity.DeviceId,
            CoinsSettled = false
        };

        // Reserve, don't spend. Coins move out of the spendable balance into PendingCoins so
        // the same coins cannot back two requests, but they are only truly deducted once an
        // admin confirms the payment (see AdminWithdrawalsController). A rejected or
        // cancelled request returns them intact. The reservation is an atomic conditional
        // update (see WalletService.TryReserveForWithdrawalAsync), so two concurrent withdrawal
        // requests can no longer both pass a stale balance check and overdraw the wallet; both
        // writes share one transaction so a lost balance race can't leave an orphan Withdrawal row.
        await using var tx = await _db.Database.BeginTransactionAsync(ct);
        _db.Withdrawals.Add(withdrawal);
        await _db.SaveChangesAsync(ct);

        var reserved = await _wallets.TryReserveForWithdrawalAsync(
            User.GetUserId(), req.Coins, $"withdrawal:{withdrawal.Id}", ct);
        if (!reserved.Success)
        {
            await tx.RollbackAsync(ct);
            return BadRequest(new ApiError("Insufficient balance.", "insufficient_balance"));
        }
        await tx.CommitAsync(ct);

        return Ok(ToDto(withdrawal));
    }

    [HttpGet("history")]
    public async Task<ActionResult<IEnumerable<WithdrawalDto>>> History(CancellationToken ct)
    {
        var wallet = await _wallets.GetOrCreateAsync(User.GetUserId(), ct);
        var items = await _db.Withdrawals
            .Where(w => w.WalletId == wallet.Id)
            .OrderByDescending(w => w.CreatedAt)
            .ToListAsync(ct);
        return Ok(items.Select(ToDto));
    }

    private static WithdrawalDto ToDto(Withdrawal w) =>
        new(w.Id, w.Coins, w.Amount, w.PaymentMethod, w.Status, w.CreatedAt, w.ProcessedAt, w.CoinsSettled);
}
