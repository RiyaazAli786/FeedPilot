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
[Route("api/accounts")]
public class AccountsController : ControllerBase
{
    private readonly AppDbContext _db;
    public AccountsController(AppDbContext db) => _db = db;

    [HttpGet]
    public async Task<ActionResult<IEnumerable<AccountDto>>> List(CancellationToken ct)
    {
        var userId = User.GetUserId();
        var accounts = await _db.Accounts
            .Where(a => a.UserId == userId)
            .OrderByDescending(a => a.CreatedAt)
            .Select(a => new AccountDto(a.Id, a.Username, a.ProfilePictureUrl, a.Status,
                a.LastLogin, a.LastActive, a.CoinsEarned, a.SessionData, a.UpgradedAt))
            .ToListAsync(ct);
        return Ok(accounts);
    }

    /// <summary>
    /// Whether this Instagram username is already linked under a different clone/app install on
    /// this same physical device. The client's own local check only sees accounts added from
    /// this one install's Room database, so App Cloner / Dual Apps / Parallel Space instances
    /// (each a separate backend User, same X-Hardware-Id) could otherwise farm the same account
    /// from every clone with no rejection.
    /// </summary>
    [HttpGet("check-duplicate")]
    public async Task<ActionResult<CheckDuplicateAccountResponse>> CheckDuplicate(
        [FromQuery] string username, CancellationToken ct)
    {
        if (string.IsNullOrWhiteSpace(username))
            return Ok(new CheckDuplicateAccountResponse(false));

        var identity = this.ClientIdentity();
        if (identity.HardwareId == ClientIdentity.Unknown)
            return Ok(new CheckDuplicateAccountResponse(false));

        var cleanUsername = username.Trim().TrimStart('@').ToLowerInvariant();
        var sameDeviceUserIds = _db.Devices
            .Where(d => d.InstallationId == identity.HardwareId && d.UserId != null)
            .Select(d => d.UserId!.Value);

        var isDuplicate = await _db.Accounts
            .Where(a => a.Username.ToLower() == cleanUsername)
            .Join(sameDeviceUserIds, a => a.UserId, id => id, (a, id) => a.Id)
            .AnyAsync(ct);

        return Ok(new CheckDuplicateAccountResponse(isDuplicate));
    }

    [HttpPost]
    public async Task<ActionResult<AccountDto>> Create(CreateAccountRequest req, CancellationToken ct)
    {
        var userId = User.GetUserId();
        var identity = this.ClientIdentity();
        var username = req.Username.Trim();

        // Prefer matching by the client's stable id: once an account's handle resolves from a
        // numeric placeholder to its real one, a username-only match would no longer find the
        // existing row and would silently fork a second one (fresh id, coins reset to zero,
        // history orphaned on the old row). The id round-trips through AccountDto since the very
        // first create, so this is available from the second sync onward.
        var account = req.Id is { } id
            ? await _db.Accounts.FirstOrDefaultAsync(a => a.UserId == userId && a.Id == id, ct)
            : null;

        // Fallback for the first sync (no id yet) or an older client build: match by handle, which
        // still updates rather than duplicating as long as the username has not changed.
        account ??= await _db.Accounts
            .FirstOrDefaultAsync(a => a.UserId == userId && a.Username == username, ct);

        if (account is null)
        {
            account = new Account
            {
                UserId = userId,
                Username = username,
                Status = AccountStatus.Active
            };
            _db.Accounts.Add(account);
        }

        account.ProfilePictureUrl = req.ProfilePictureUrl ?? account.ProfilePictureUrl;
        account.SessionData = req.SessionData ?? account.SessionData; // opaque encrypted payload
        account.DeviceId = req.DeviceId ?? identity.DeviceId;
        account.AppId = identity.AppId;
        account.LastLogin = DateTime.UtcNow;
        account.LastActive = DateTime.UtcNow;

        await _db.SaveChangesAsync(ct);

        return CreatedAtAction(nameof(List), new { id = account.Id },
            new AccountDto(account.Id, account.Username, account.ProfilePictureUrl, account.Status,
                account.LastLogin, account.LastActive, account.CoinsEarned, account.SessionData,
                account.UpgradedAt));
    }

    [HttpDelete("{id:guid}")]
    public async Task<IActionResult> Delete(Guid id, CancellationToken ct)
    {
        var userId = User.GetUserId();
        var account = await _db.Accounts.FirstOrDefaultAsync(a => a.Id == id && a.UserId == userId, ct);
        if (account is null) return NotFound(new ApiError("Account not found.", "not_found"));

        _db.Accounts.Remove(account);
        await _db.SaveChangesAsync(ct);
        return NoContent();
    }

    [HttpPost("{id:guid}/refresh")]
    public async Task<ActionResult<AccountDto>> RefreshSession(Guid id, CancellationToken ct)
    {
        var userId = User.GetUserId();
        var account = await _db.Accounts.FirstOrDefaultAsync(a => a.Id == id && a.UserId == userId, ct);
        if (account is null) return NotFound(new ApiError("Account not found.", "not_found"));

        account.LastActive = DateTime.UtcNow;
        account.Status = AccountStatus.Active;
        await _db.SaveChangesAsync(ct);
        return Ok(new AccountDto(account.Id, account.Username, account.ProfilePictureUrl, account.Status,
            account.LastLogin, account.LastActive, account.CoinsEarned, account.SessionData,
            account.UpgradedAt));
    }

    /// <summary>
    /// Marks an account upgraded for the next 24 hours: task rewards for it get a +2 coin bonus
    /// (see TasksController.SubmitResult) until UpgradedAt ages out. One upgrade per account per
    /// rolling 24h window — a second call inside that window is rejected rather than restarting
    /// the clock, so the reward can't be indefinitely refreshed.
    /// </summary>
    [HttpPost("{id:guid}/upgrade")]
    public async Task<ActionResult<AccountDto>> Upgrade(Guid id, CancellationToken ct)
    {
        var userId = User.GetUserId();
        var account = await _db.Accounts.FirstOrDefaultAsync(a => a.Id == id && a.UserId == userId, ct);
        if (account is null) return NotFound(new ApiError("Account not found.", "not_found"));

        if (account.UpgradedAt is { } last && DateTime.UtcNow - last < TimeSpan.FromHours(24))
        {
            return Conflict(new ApiError("This account is already upgraded for today.", "already_upgraded"));
        }

        account.UpgradedAt = DateTime.UtcNow;
        await _db.SaveChangesAsync(ct);

        return Ok(new AccountDto(account.Id, account.Username, account.ProfilePictureUrl, account.Status,
            account.LastLogin, account.LastActive, account.CoinsEarned, account.SessionData,
            account.UpgradedAt));
    }

    [HttpGet("leaderboard")]
    public async Task<ActionResult<LeaderboardResponseDto>> Leaderboard(
        [FromQuery] int page = 1,
        [FromQuery] int pageSize = 5,
        [FromQuery] string sortOrder = "DESCENDING",
        CancellationToken ct = default)
    {
        // Force top 5 limit as per specification — leaderboard only displays top 5 users
        const int maxTopUsers = 5;

        // Fetch users with their wallets and accounts to get up-to-date coin data per user
        var users = await _db.Users
            .AsNoTracking()
            .Include(u => u.Wallet)
            .Include(u => u.Accounts)
            .ToListAsync(ct);

        var userLeaderboardList = users.Select(u =>
        {
            var walletCoins = u.Wallet?.Coins ?? 0L;
            var accountsCoins = u.Accounts.Sum(a => a.CoinsEarned);
            var effectiveCoins = Math.Max(walletCoins, accountsCoins);

            var primaryAccount = u.Accounts
                .OrderByDescending(a => a.LastActive ?? a.CreatedAt)
                .FirstOrDefault();

            var username = primaryAccount?.Username;
            if (string.IsNullOrWhiteSpace(username))
            {
                username = !string.IsNullOrWhiteSpace(u.Name)
                    ? u.Name
                    : (!string.IsNullOrWhiteSpace(u.Email) ? u.Email.Split('@')[0] : "User");
            }

            var profilePic = primaryAccount?.ProfilePictureUrl;
            var id = primaryAccount?.Id ?? u.Id;

            return new
            {
                Id = id,
                Username = username,
                ProfilePictureUrl = profilePic,
                CoinsEarned = effectiveCoins
            };
        })
        .Where(u => u.CoinsEarned > 0);

        if (sortOrder.Equals("ASCENDING", StringComparison.OrdinalIgnoreCase))
        {
            userLeaderboardList = userLeaderboardList.OrderBy(u => u.CoinsEarned).ThenBy(u => u.Username);
        }
        else
        {
            userLeaderboardList = userLeaderboardList.OrderByDescending(u => u.CoinsEarned).ThenBy(u => u.Username);
        }

        var top5List = userLeaderboardList.Take(maxTopUsers).ToList();

        var items = top5List.Select((u, idx) => new LeaderboardItemDto(
            u.Id,
            u.Username,
            u.ProfilePictureUrl,
            u.CoinsEarned,
            idx + 1
        )).ToList();

        return Ok(new LeaderboardResponseDto(items, 1, items.Count, items.Count));
    }
}

