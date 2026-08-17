using Microsoft.EntityFrameworkCore;
using FeedPilot.Api.Data;
using FeedPilot.Api.Domain;

namespace FeedPilot.Api.Services;

/// <summary>One tier's price and what it grants.</summary>
public record PlanInfo(
    SubscriptionPlan Plan,
    string Name,
    int PriceInr,
    int CoinMultiplier,
    IReadOnlyList<string> Features);

/// <summary>
/// The source of truth for subscription tiers — prices, coin multipliers and features. The
/// app fetches this rather than hard-coding plans, so a price or rate change is a one-line
/// edit here and never needs an app release.
/// </summary>
public static class PlanCatalog
{
    /// <summary>How many days a purchased plan stays active before lapsing to Free.</summary>
    public const int PlanDurationDays = 30;

    public static readonly IReadOnlyList<PlanInfo> All = new List<PlanInfo>
    {
        new(SubscriptionPlan.Free,     "Free",     0,   1, new[] { "1× coin rate" }),
        new(SubscriptionPlan.Silver,   "Silver",   99,  2, new[] { "2× coin rate", "No ads" }),
        new(SubscriptionPlan.Gold,     "Gold",     249, 3, new[] { "3× coin rate", "Priority tasks", "No ads" }),
        new(SubscriptionPlan.Platinum, "Platinum", 499, 5, new[] { "5× coin rate", "Priority tasks", "Unlimited accounts" }),
    };

    public static PlanInfo Get(SubscriptionPlan plan) =>
        All.FirstOrDefault(p => p.Plan == plan) ?? All[0];

    /// <summary>
    /// The coin multiplier a user actually earns at, treating Free — and any lapsed paid plan —
    /// as 1×. A day past <paramref name="expiresAt"/> the account silently earns at Free again.
    /// </summary>
    public static int MultiplierFor(SubscriptionPlan plan, DateTime? expiresAt)
    {
        if (plan == SubscriptionPlan.Free) return 1;
        if (expiresAt is { } exp && exp < DateTime.UtcNow) return 1;
        return Get(plan).CoinMultiplier;
    }
}

/// <summary>
/// UPI payee details shown to a user buying a plan. Configured via the <c>Payments</c> section
/// (env <c>Payments__UpiId</c> / <c>Payments__PayeeName</c>). Blank UpiId disables purchases.
/// </summary>
public class PaymentSettings
{
    public string UpiId { get; set; } = string.Empty;
    public string PayeeName { get; set; } = string.Empty;
}

public interface ISubscriptionService
{
    /// <summary>The coin-earn multiplier for a user, honouring their plan and its expiry.</summary>
    Task<int> GetCoinMultiplierAsync(Guid userId, CancellationToken ct = default);
}

public class SubscriptionService : ISubscriptionService
{
    private readonly AppDbContext _db;
    public SubscriptionService(AppDbContext db) => _db = db;

    public async Task<int> GetCoinMultiplierAsync(Guid userId, CancellationToken ct = default)
    {
        var user = await _db.Users
            .Select(u => new { u.Id, u.Plan, u.PlanExpiresAt })
            .FirstOrDefaultAsync(u => u.Id == userId, ct);
        return user is null ? 1 : PlanCatalog.MultiplierFor(user.Plan, user.PlanExpiresAt);
    }
}
