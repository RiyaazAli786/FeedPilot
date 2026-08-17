using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using FeedPilot.Api.Data;
using FeedPilot.Api.Dtos;
using FeedPilot.Api.Services;

namespace FeedPilot.Api.Controllers;

/// <summary>
/// Backs the account-management tab on the dashboard: every linked Instagram account across
/// every user, with edit and delete. Guarded by an admin session token rather than a user JWT —
/// see <see cref="AdminSessionAttribute"/>.
/// </summary>
[ApiController]
[AdminSession]
[Route("api/admin/accounts")]
public class AdminAccountsController : ControllerBase
{
    private readonly AppDbContext _db;
    public AdminAccountsController(AppDbContext db) => _db = db;

    [HttpGet]
    public async Task<ActionResult<PagedResult<AdminAccountDto>>> List(
        [FromQuery] string? search,
        [FromQuery] string? appId,
        [FromQuery] string? deviceId,
        [FromQuery] int page = 1,
        [FromQuery] int pageSize = 25,
        // "lastActive" sorts by most recently active first — used by the Coin Transfer tab's
        // user picker, where "who's active right now" matters more than creation order. Any
        // other value (including the default) keeps the original newest-created-first sort.
        [FromQuery] string? sortBy = null,
        CancellationToken ct = default)
    {
        page = Math.Max(1, page);
        pageSize = Math.Clamp(pageSize, 1, 200);

        try
        {
            var query = _db.Accounts.Include(a => a.User).AsQueryable();

            if (!string.IsNullOrWhiteSpace(appId)) query = query.Where(a => a.AppId == appId);
            if (!string.IsNullOrWhiteSpace(deviceId)) query = query.Where(a => a.DeviceId == deviceId);

            if (!string.IsNullOrWhiteSpace(search))
            {
                var term = search.Trim();
                query = query.Where(a =>
                    a.Username.Contains(term) ||
                    (a.DeviceId != null && a.DeviceId.Contains(term)) ||
                    (a.User != null && a.User.Email.Contains(term)));
            }

            var total = await query.CountAsync(ct);
            var sorted = sortBy == "lastActive"
                ? query.OrderByDescending(a => a.LastActive ?? a.LastLogin ?? a.CreatedAt)
                : query.OrderByDescending(a => a.CreatedAt);
            var items = await sorted
                .Skip((page - 1) * pageSize)
                .Take(pageSize)
                .ToListAsync(ct);

            var userIds = items.Select(a => a.UserId).Distinct().ToList();
            var wallets = userIds.Count > 0
                ? await _db.Wallets.Where(w => userIds.Contains(w.UserId)).ToDictionaryAsync(w => w.UserId, w => w.Coins, ct)
                : new Dictionary<Guid, long>();

            return Ok(new PagedResult<AdminAccountDto>(
                items.Select(a => ToAdminDto(a, wallets.TryGetValue(a.UserId, out var c) ? c : 0L)).ToList(),
                total, page, pageSize));
        }
        catch (Exception)
        {
            return Ok(new PagedResult<AdminAccountDto>(new List<AdminAccountDto>(), 0, page, pageSize));
        }
    }

    [HttpGet("stats")]
    public async Task<ActionResult<object>> Stats(CancellationToken ct)
    {
        try
        {
            var all = await _db.Accounts
                .Select(a => new { a.Status, a.AppId, HasSession = a.SessionData != null && a.SessionData != "" })
                .ToListAsync(ct);

            return Ok(new
            {
                total = all.Count,
                active = all.Count(a => a.Status == Domain.AccountStatus.Active),
                withSession = all.Count(a => a.HasSession),
                apps = all.Select(a => a.AppId ?? string.Empty).Distinct().OrderBy(a => a).ToList()
            });
        }
        catch (Exception)
        {
            return Ok(new { total = 0, active = 0, withSession = 0, apps = new List<string>() });
        }
    }

    /// <summary>Updates the handle, avatar, or status. Never touches the session payload.</summary>
    [HttpPatch("{id:guid}")]
    public async Task<ActionResult<AdminAccountDto>> Update(
        Guid id, [FromBody] UpdateAdminAccountRequest body, CancellationToken ct)
    {
        var account = await _db.Accounts.Include(a => a.User).FirstOrDefaultAsync(a => a.Id == id, ct);
        if (account is null) return NotFound(new ApiError("Account not found."));

        if (!string.IsNullOrWhiteSpace(body.Username)) account.Username = body.Username.Trim();
        if (body.ProfilePictureUrl is not null) account.ProfilePictureUrl = body.ProfilePictureUrl;
        if (body.Status.HasValue) account.Status = body.Status.Value;

        await _db.SaveChangesAsync(ct);
        var wallet = await _db.Wallets.FirstOrDefaultAsync(w => w.UserId == account.UserId, ct);
        return Ok(ToAdminDto(account, wallet?.Coins ?? 0L));
    }

    [HttpDelete("{id:guid}")]
    public async Task<IActionResult> Delete(Guid id, CancellationToken ct)
    {
        var account = await _db.Accounts.FirstOrDefaultAsync(a => a.Id == id, ct);
        if (account is null) return NotFound(new ApiError("Account not found."));

        _db.Accounts.Remove(account);
        await _db.SaveChangesAsync(ct);
        return NoContent();
    }

    private static AdminAccountDto ToAdminDto(Domain.Account a, long walletCoins = 0) => new(
        a.Id, a.Username, a.ProfilePictureUrl, a.Status,
        a.UserId, a.User?.Email ?? string.Empty, a.AppId, a.DeviceId,
        !string.IsNullOrEmpty(a.SessionData), a.LastLogin, a.LastActive, a.CoinsEarned, a.CreatedAt, walletCoins);
}
