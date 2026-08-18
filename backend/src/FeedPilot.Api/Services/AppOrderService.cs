using FeedPilot.Api.Data;
using FeedPilot.Api.Domain;

namespace FeedPilot.Api.Services;

/// <summary>Coin pricing for app-placed orders, overridable via the "Orders" config section.</summary>
public class OrderPricingSettings
{
    /// <summary>Coins charged per follower.</summary>
    public long CoinsPerFollow { get; set; } = 10;

    /// <summary>Coins charged per like.</summary>
    public long CoinsPerLike { get; set; } = 3;

    /// <summary>Coins charged per comment.</summary>
    public long CoinsPerComment { get; set; } = 10;

    /// <summary>Coins charged per repost.</summary>
    public long CoinsPerRepost { get; set; } = 12;

    /// <summary>Coins charged per post save.</summary>
    public long CoinsPerSavePost { get; set; } = 6;

    /// <summary>Coins charged per story view.</summary>
    public long CoinsPerStoryView { get; set; } = 4;

    /// <summary>Smallest order a user may place, mirroring typical provider minimums.</summary>
    public int MinQuantity { get; set; } = 10;

    public int MaxQuantity { get; set; } = 100_000;

    /// <summary>Orders handed out per claim when a worker does not ask for a specific size.</summary>
    public int DefaultBatchSize { get; set; } = 10;
}

public interface IAppOrderService
{
    long QuoteCoins(TaskType orderType, int quantity);
    bool IsRefundable(AppOrderStatus status);
    long ProratedRefund(AppOrder order);
}

public class AppOrderService : IAppOrderService
{
    private readonly AppDbContext _db;

    public AppOrderService(AppDbContext db) => _db = db;

    public long QuoteCoins(TaskType orderType, int quantity)
    {
        var settings = _db.RunnerSettings.FirstOrDefault(x => x.Id == 1) ?? new RunnerSettings();
        var rate = orderType switch
        {
            TaskType.Follow => settings.PricePerFollow,
            TaskType.Like => settings.PricePerLike,
            TaskType.Comment => settings.PricePerComment,
            TaskType.Repost => settings.PricePerRepost,
            TaskType.SavePost => settings.PricePerSavePost,
            TaskType.StoryView => settings.PricePerStoryView,
            _ => settings.PricePerLike
        };
        return rate * quantity;
    }

    /// <summary>
    /// Coins go back only when the order never reached a provider. Once submitted, the
    /// spend is real and a refund would have to be an explicit admin adjustment.
    /// </summary>
    public bool IsRefundable(AppOrderStatus status) =>
        status is AppOrderStatus.Pending or AppOrderStatus.Approved;

    /// <summary>
    /// The coins owed back for canceling/rejecting [order] right now — proportional to whatever
    /// is still outstanding, not the full original spend. An order sitting in a refundable status
    /// (Pending/Approved) can still carry real progress: a worker claims it, reports partial
    /// progress via OrderProcessingController.Progress, then releases it back to Pending for
    /// another device to pick up — the order's Status alone says nothing about CompletedCount.
    /// Refunding the full CoinsSpent regardless handed back coins for work that had already been
    /// delivered on the user's own profile.
    /// </summary>
    public long ProratedRefund(AppOrder order)
    {
        if (order.Quantity <= 0) return order.CoinsSpent;
        var remaining = Math.Max(0, order.Quantity - order.CompletedCount);
        return order.CoinsSpent * remaining / order.Quantity;
    }
}
