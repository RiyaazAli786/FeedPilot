using FeedPilot.Api.Data;

namespace FeedPilot.Api.Services;

public class FeedBridgeJobSweepService : BackgroundService
{
    private readonly IServiceScopeFactory _scopeFactory;
    private readonly ILogger<FeedBridgeJobSweepService> _logger;

    public FeedBridgeJobSweepService(IServiceScopeFactory scopeFactory, ILogger<FeedBridgeJobSweepService> logger)
    {
        _scopeFactory = scopeFactory;
        _logger = logger;
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        var interval = TimeSpan.FromSeconds(15);
        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                using var scope = _scopeFactory.CreateScope();
                var bridge = scope.ServiceProvider.GetRequiredService<IFeedBridgeService>();
                await bridge.SweepExpiredAsync(stoppingToken);
            }
            catch (Exception ex) when (ex is not OperationCanceledException)
            {
                _logger.LogError(ex, "Feed bridge job sweep error");
            }

            await Task.Delay(interval, stoppingToken);
        }
    }
}
