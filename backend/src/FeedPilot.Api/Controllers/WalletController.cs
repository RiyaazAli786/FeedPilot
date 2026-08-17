using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using FeedPilot.Api.Data;
using FeedPilot.Api.Dtos;
using FeedPilot.Api.Services;

namespace FeedPilot.Api.Controllers;

[ApiController]
[Authorize]
[Route("api/wallet")]
public class WalletController : ControllerBase
{
    private readonly AppDbContext _db;
    private readonly IWalletService _wallets;

    public WalletController(AppDbContext db, IWalletService wallets)
    {
        _db = db;
        _wallets = wallets;
    }

    [HttpGet]
    public async Task<ActionResult<WalletDto>> Get(CancellationToken ct)
    {
        var wallet = await _wallets.GetOrCreateAsync(User.GetUserId(), ct);
        return Ok(new WalletDto(wallet.Coins, wallet.LifetimeCoins, wallet.PendingCoins,
            wallet.WithdrawnCoins, wallet.UpdatedAt));
    }

    [HttpGet("history")]
    public async Task<ActionResult<IEnumerable<WalletTransactionDto>>> History(
        [FromQuery] int limit = 50, CancellationToken ct = default)
    {
        limit = Math.Clamp(limit, 1, 200);
        var wallet = await _wallets.GetOrCreateAsync(User.GetUserId(), ct);
        var items = await _db.WalletTransactions
            .Where(t => t.WalletId == wallet.Id)
            .OrderByDescending(t => t.CreatedDate)
            .Take(limit)
            .Select(t => new WalletTransactionDto(t.Id, t.Coins, t.Type, t.Reference, t.CreatedDate))
            .ToListAsync(ct);
        return Ok(items);
    }
}
