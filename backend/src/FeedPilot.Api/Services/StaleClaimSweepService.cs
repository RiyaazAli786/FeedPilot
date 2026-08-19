using Microsoft.EntityFrameworkCore;
using FeedPilot.Api.Data;
using FeedPilot.Api.Domain;

namespace FeedPilot.Api.Services;

/// <summary>
/// Proactively resets orders whose claim has gone stale back to <see cref="AppOrderStatus.Pending"/>,
/// independent of any worker device calling <c>/api/orders/processing/claim</c>.
///
/// Without this, a stale claim only gets noticed as a side effect of *some* device polling for
/// work: <see cref="OrderProcessingController"/>'s claim query already skips past stale
/// <see cref="AppOrderStatus.Processing"/> rows, but that check only ever runs inside a claim
/// request. A device that claimed a batch and then crashed, was uninstalled, or simply never
/// dips its local queue low enough to poll again leaves that order locked forever — nobody is
/// left to trigger the reclaim. This sweep runs on its own schedule so an orphaned claim expires
/// even when nothing else happens to ask for work.
/// </summary>
public class StaleClaimSweepService : BackgroundService
{
    private readonly IServiceScopeFactory _scopeFactory;
    private readonly ILogger<StaleClaimSweepService> _logger;

    public StaleClaimSweepService(
        IServiceScopeFactory scopeFactory,
        ILogger<StaleClaimSweepService> logger)
    {
        _scopeFactory = scopeFactory;
        _logger = logger;
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        // Sweep every 15s — halves the worst-case gap between a claim going stale and another
        // device being able to pick it up, while still being far lighter than tight polling.
        // The timeout itself (dashboard-configured RunnerSettings.ClaimTimeoutMinutes) remains
        // the dominant factor.
        var interval = TimeSpan.FromSeconds(15);

        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                await SweepAsync(stoppingToken);
            }
            catch (Exception ex) when (ex is not OperationCanceledException)
            {
                _logger.LogError(ex, "Stale claim sweep error");
            }

            await Task.Delay(interval, stoppingToken);
        }
    }

    private async Task SweepAsync(CancellationToken ct)
    {
        using var scope = _scopeFactory.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();

        var runnerSettings = await RunnerSettingsStore.GetOrCreateAsync(db, ct);
        var staleBefore = DateTime.UtcNow.AddMinutes(-runnerSettings.ClaimTimeoutMinutes);

        var stale = await db.AppOrders
            .Where(o =>
                o.Status == AppOrderStatus.Processing &&
                (o.ProcessingDeviceId == null ||
                 o.ProcessingDeviceId == string.Empty ||
                 o.ProcessingStartedAt == null ||
                 o.ProcessingStartedAt < staleBefore))
            .ToListAsync(ct);

        var now = DateTime.UtcNow;
        foreach (var order in stale)
        {
            order.Status = order.CompletedCount >= order.Quantity
                ? AppOrderStatus.Completed
                : AppOrderStatus.Pending;
            order.ProcessingDeviceId = null;
            order.ProcessingStartedAt = null;
            order.ReservedCount = 0;
            order.ReservedAt = null;
            order.UpdatedAt = now;
        }

        var staleReservations = await db.AppOrders
            .Where(o =>
                o.Status != AppOrderStatus.Processing &&
                o.ReservedCount > 0 &&
                (o.ReservedAt == null || o.ReservedAt < staleBefore))
            .ToListAsync(ct);

        foreach (var order in staleReservations)
        {
            order.ProcessingDeviceId = null;
            order.ReservedCount = 0;
            order.ReservedAt = null;
            order.UpdatedAt = now;
        }

        if (stale.Count == 0 && staleReservations.Count == 0) return;

        await db.SaveChangesAsync(ct);
        _logger.LogInformation(
            "Stale claim sweep released {Count} orphaned order claim(s), cleared {ReservationCount} stale reservation(s).",
            stale.Count, staleReservations.Count);
    }
}
