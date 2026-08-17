using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using FeedPilot.Api.Data;
using FeedPilot.Api.Domain;

namespace FeedPilot.Api.Services;

/// <summary>
/// Background service that periodically pulls orders from smmorigin.com via
/// POST /adminapi/v2/orders/pull, imports pending/processing orders as local
/// <see cref="AppOrder"/> rows so they can be fulfilled by worker devices, and pushes status &amp;
/// remains updates back to smmorigin.com (/orders/update).
/// </summary>
public class SmmPanelSyncService : BackgroundService
{
    private readonly IServiceScopeFactory _scopeFactory;
    private readonly IOptions<SmmPanelSettings> _fallback;
    private readonly IHttpClientFactory _http;
    private readonly ILogger<SmmPanelSyncService> _logger;
    private readonly TelegramRequestLogger? _telegram;

    // SyncNowAsync is called from three places — the background loop, OrderProcessingController's
    // on-demand backfill, and the admin dashboard's manual trigger. Without this, two overlapping
    // calls could each see the same panel order as new and both try to import it (ExternalOrderId
    // has a unique DB index as a second line of defense, but skipping the redundant pull entirely
    // is both cheaper and avoids hitting the panel twice for the same batch).
    private readonly CommentFileService? _commentFileService;
    private readonly SemaphoreSlim _syncLock = new(1, 1);
    private readonly System.Threading.Channels.Channel<bool> _onDemandSignal =
        System.Threading.Channels.Channel.CreateBounded<bool>(new System.Threading.Channels.BoundedChannelOptions(1)
        {
            FullMode = System.Threading.Channels.BoundedChannelFullMode.DropOldest
        });

    /// <summary>
    /// Non-blocking trigger method called by worker claim requests when 0 local orders are found.
    /// Signals the background sync loop to perform an immediate sync pass without blocking the caller's HTTP request.
    /// </summary>
    public void TriggerImmediateSync()
    {
        _onDemandSignal.Writer.TryWrite(true);
    }

    public SmmPanelSyncService(
        IServiceScopeFactory scopeFactory,
        IOptions<SmmPanelSettings> settings,
        IHttpClientFactory http,
        ILogger<SmmPanelSyncService> logger,
        TelegramRequestLogger? telegram = null,
        CommentFileService? commentFileService = null)
    {
        _scopeFactory = scopeFactory;
        _fallback = settings;
        _http = http;
        _logger = logger;
        _telegram = telegram;
        _commentFileService = commentFileService;
    }

    /// <summary>
    /// Reads the current, dashboard-editable config from the DB (seeded from appsettings/env on
    /// first read) rather than caching the IOptions snapshot this service was constructed with —
    /// an admin's edit from the dashboard must take effect on the very next sync, not just after
    /// a restart.
    /// </summary>
    private async Task<SmmProviderConfig> GetConfigAsync(AppDbContext db, CancellationToken ct) =>
        await SmmProviderConfigStore.GetOrCreateAsync(db, _fallback, ct);

    /// <summary>
    /// How often the outer loop wakes up to check whether any of the three panel operations are
    /// due. Not itself a sync cadence — just needs to be comfortably shorter than the shortest
    /// configurable interval (fetch defaults to 10s) so that interval is actually honored.
    /// </summary>
    private const int LoopTickSeconds = 2;

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        await Task.Delay(TimeSpan.FromSeconds(10), stoppingToken);

        // Each operation runs on its own dashboard-configured interval (SmmProviderConfig's
        // Fetch/StatusPush/CancelPull *IntervalSeconds) instead of the old single shared
        // PollIntervalMinutes cadence. DateTime.MinValue makes every operation due immediately on
        // the very first tick.
        var nextFetchDue = DateTime.MinValue;
        var nextStatusPushDue = DateTime.MinValue;
        var nextCancelPullDue = DateTime.MinValue;

        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                using var scope = _scopeFactory.CreateScope();
                var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
                var config = await GetConfigAsync(db, stoppingToken);
                var now = DateTime.UtcNow;

                if (now >= nextFetchDue)
                {
                    await SyncNowAsync(stoppingToken);
                    nextFetchDue = DateTime.UtcNow.AddSeconds(Math.Max(1, config.FetchIntervalSeconds));
                }
                if (now >= nextStatusPushDue)
                {
                    await PushOrderStatusUpdatesAsync(stoppingToken);
                    nextStatusPushDue = DateTime.UtcNow.AddSeconds(Math.Max(1, config.StatusPushIntervalSeconds));
                }
                if (now >= nextCancelPullDue)
                {
                    await SyncCancelledOrdersAsync(stoppingToken);
                    nextCancelPullDue = DateTime.UtcNow.AddSeconds(Math.Max(1, config.CancelPullIntervalSeconds));
                }
            }
            catch (Exception ex) when (ex is not OperationCanceledException)
            {
                _logger.LogError(ex, "SMM panel sync loop error");
            }

            try
            {
                var timeoutTask = Task.Delay(TimeSpan.FromSeconds(LoopTickSeconds), stoppingToken);
                var signalTask = _onDemandSignal.Reader.ReadAsync(stoppingToken).AsTask();
                var completed = await Task.WhenAny(timeoutTask, signalTask);
                // A manual trigger (worker claim found 0 local orders, or the dashboard's Sync Now
                // button) means "fetch right now" — force that operation due on the next tick
                // rather than only waking the loop to find fetch still not due yet.
                if (completed == signalTask) nextFetchDue = DateTime.MinValue;
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                break;
            }
        }
    }

    /// <summary>
    /// Synchronizes orders with the SMM panel:
    /// 1. Pulls orders from smmorigin via POST /adminapi/v2/orders/pull.
    /// 2. Saves/upserts them locally as AppOrders.
    /// 3. Pushes updated order status/remains back to smmorigin via POST /orders/update.
    /// </summary>
    public async Task<(int Created, int Updated)> SyncNowAsync(CancellationToken ct = default)
    {
        using var configScope = _scopeFactory.CreateScope();
        var config = await GetConfigAsync(configScope.ServiceProvider.GetRequiredService<AppDbContext>(), ct);

        if (string.IsNullOrWhiteSpace(config.ApiKey))
        {
            _logger.LogWarning("SMM provider API key is not configured — skipping sync.");
            return (0, 0);
        }

        if (!await _syncLock.WaitAsync(0, ct))
        {
            _logger.LogInformation("SMM panel sync already in progress — skipping this trigger.");
            return (0, 0);
        }

        try
        {
            var panelOrders = await FetchOrdersAsync(config, ct);
            if (panelOrders.Count == 0)
            {
                _logger.LogInformation("SMM panel returned 0 orders.");
                return (0, 0);
            }

            using var scope = _scopeFactory.CreateScope();
            var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();

            var (created, updated) = await SaveOrdersToDatabaseAsync(db, config, panelOrders, ct);

            // Cancel-sync used to also run here on every fetch pass — now that fetch and
            // cancel-pull have independent dashboard-configured intervals (see ExecuteAsync),
            // running it here too would make cancel-pull run as often as fetch (e.g. every 10s
            // instead of its own 60s), defeating the point of giving it a separate cadence.
            // AdminOrdersController's manual "Sync Now" endpoint calls it explicitly instead.

            _logger.LogInformation("SMM panel sync done: {Created} created, {Updated} updated.", created, updated);
            return (created, updated);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Failed to sync SMM panel orders");
            return (0, 0);
        }
        finally
        {
            _syncLock.Release();
        }
    }

    /// <summary>
    /// Ingests SMM panel order JSON (from a payload file or webhook) directly into the database.
    /// </summary>
    public async Task<(int Created, int Updated)> IngestOrdersJsonAsync(string json, CancellationToken ct = default)
    {
        if (string.IsNullOrWhiteSpace(json)) return (0, 0);

        List<PanelOrder> panelOrders = new();
        try
        {
            using var doc = JsonDocument.Parse(json);
            var root = doc.RootElement;

            JsonElement listElement = default;
            if (root.ValueKind == JsonValueKind.Object)
            {
                if (root.TryGetProperty("data", out var dataEl) && dataEl.ValueKind == JsonValueKind.Object && dataEl.TryGetProperty("list", out var listEl))
                    listElement = listEl;
                else if (root.TryGetProperty("orders", out var ordersEl))
                    listElement = ordersEl;
                else if (root.TryGetProperty("rawResponse", out var rawEl) && rawEl.ValueKind == JsonValueKind.Object && rawEl.TryGetProperty("data", out var rawDataEl) && rawDataEl.TryGetProperty("list", out var rawListEl))
                    listElement = rawListEl;
                else if (root.TryGetProperty("list", out var directListEl))
                    listElement = directListEl;
            }
            else if (root.ValueKind == JsonValueKind.Array)
            {
                listElement = root;
            }

            if (listElement.ValueKind == JsonValueKind.Array)
            {
                var options = new JsonSerializerOptions { PropertyNameCaseInsensitive = true };
                foreach (var el in listElement.EnumerateArray())
                {
                    var po = JsonSerializer.Deserialize<PanelOrder>(el.GetRawText(), options);
                    if (po is not null && po.OrderId > 0)
                        panelOrders.Add(po);
                }
            }
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Failed to parse JSON for SMM orders ingestion.");
            return (0, 0);
        }

        if (panelOrders.Count == 0) return (0, 0);

        using var scope = _scopeFactory.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
        var config = await GetConfigAsync(db, ct);

        return await SaveOrdersToDatabaseAsync(db, config, panelOrders, ct);
    }

    private async Task<(int Created, int Updated)> SaveOrdersToDatabaseAsync(
        AppDbContext db, SmmProviderConfig config, List<PanelOrder> panelOrders, CancellationToken ct)
    {
        int created = 0, updated = 0;
        var systemUser = await GetOrCreateSystemUserAsync(db, ct);

        var validOrders = panelOrders.Where(p => p.OrderId > 0).ToList();
        if (validOrders.Count == 0) return (0, 0);

        var externalIds = validOrders
            .Select(po => Truncate(po.OrderId.ToString(), 60)!)
            .Where(id => !string.IsNullOrEmpty(id))
            .Distinct()
            .ToList();

        var existingMap = await db.AppOrders
            .Where(o => o.ExternalOrderId != null && externalIds.Contains(o.ExternalOrderId))
            .ToDictionaryAsync(o => o.ExternalOrderId!, ct);

        foreach (var po in validOrders)
        {
            var extId = Truncate(po.OrderId.ToString(), 60)!;
            existingMap.TryGetValue(extId, out var existing);

            var localStatus = MapStatus(po.Status);
            var orderType = MapServiceType(po.ServiceId, po.ServiceName, config);
            var safeUrl = Truncate(po.Link ?? string.Empty, 500) ?? string.Empty;
            var safeUsername = Truncate(ExtractUsername(po.Link), 120);

            if (existing is null)
            {
                var order = new AppOrder
                {
                    UserId = systemUser.Id,
                    AppId = SmmPanelSettings.ExternalAppId,
                    DeviceId = SmmPanelSettings.ExternalAppId,
                    IsExternal = true,
                    ExternalOrderId = extId,
                    OrderType = orderType,
                    TargetUrl = safeUrl,
                    TargetUsername = safeUsername,
                    Quantity = Math.Max(1, po.Quantity),
                    CompletedCount = po.StartCount.HasValue && po.StartCount.Value > 0
                        ? Math.Clamp(po.Remains > 0 ? po.Quantity - po.Remains : po.Quantity, 0, po.Quantity)
                        : 0,
                    StartCount = po.StartCount.HasValue && po.StartCount.Value > 0 ? po.StartCount.Value : -1,
                    CoinsSpent = 0,
                    Status = localStatus,
                    ProviderName = "smmorigin",
                    ProviderServiceId = Truncate(po.ServiceId.ToString(), 60),
                    ProviderOrderId = extId,
                    ProviderServiceName = Truncate(po.ServiceName, 128),
                    ProviderUsername = Truncate(po.User, 120),
                    ProviderChargeAmount = decimal.TryParse(po.Charge?.Value, out var charge) ? charge : null,
                    ProviderChargeCurrency = Truncate(po.Charge?.CurrencyCode, 8),
                    ProviderCreatedAt = po.CreatedTimestamp.HasValue
                        ? DateTimeOffset.FromUnixTimeSeconds(po.CreatedTimestamp.Value).UtcDateTime
                        : null,
                };

                if (orderType == TaskType.Comment || po.ServiceId == config.CommentServiceId || po.ServiceId == config.CommentCustomServiceId)
                {
                    var buttonUserData = po.OrderButtons?.FirstOrDefault(b => b.UserData != null && b.UserData.Count > 0)?.UserData;
                    if (buttonUserData != null && buttonUserData.Count > 0)
                    {
                        order.CommentText = string.Join("\n", buttonUserData);
                    }
                    else if (!string.IsNullOrWhiteSpace(po.Comments))
                    {
                        order.CommentText = po.Comments;
                    }
                    else if (_commentFileService is not null)
                    {
                        order.CommentText = _commentFileService.GetCommentsForQuantity(order.Quantity, po.ServiceId);
                    }
                }

                if (localStatus == AppOrderStatus.Completed)
                {
                    order.CompletedCount = order.Quantity;
                    order.CompletedAt = DateTime.UtcNow;
                }

                db.AppOrders.Add(order);
                created++;
            }
            else
            {
                var newCompletedCount = po.Remains > 0
                    ? Math.Clamp(existing.Quantity - po.Remains, 0, existing.Quantity)
                    : (localStatus == AppOrderStatus.Completed ? existing.Quantity : existing.CompletedCount);
                
                var statusChanged = existing.Status != localStatus;
                var completedCountChanged = existing.CompletedCount != newCompletedCount;
                var incomingStartCount = po.StartCount.HasValue && po.StartCount.Value > 0 ? po.StartCount.Value : (int?)null;
                var startCountChanged = incomingStartCount.HasValue && existing.StartCount != incomingStartCount.Value;
                var usernameBackfilled = string.IsNullOrWhiteSpace(existing.TargetUsername) &&
                    !string.IsNullOrWhiteSpace(safeUsername);

                if (statusChanged || completedCountChanged || startCountChanged || usernameBackfilled)
                {
                    if (statusChanged) existing.Status = localStatus;
                    if (completedCountChanged) existing.CompletedCount = newCompletedCount;
                    if (startCountChanged && incomingStartCount.HasValue) existing.StartCount = incomingStartCount.Value;
                    if (usernameBackfilled) existing.TargetUsername = safeUsername;

                    if (localStatus == AppOrderStatus.Completed)
                        existing.CompletedAt ??= DateTime.UtcNow;

                    existing.UpdatedAt = DateTime.UtcNow;
                    updated++;
                }
            }
        }

        if (created > 0 || updated > 0)
        {
            await db.SaveChangesAsync(ct);
        }

        return (created, updated);
    }

    /// <summary>
    /// Pushes completed or updated status/remains back to smmorigin.com via POST /orders/update,
    /// working through up to <see cref="SmmProviderConfig.StatusPushMaxBatchesPerPass"/> batches
    /// of <see cref="SmmProviderConfig.StatusPushBatchSize"/> orders each — so a backlog larger
    /// than one batch (e.g. many orders completing between passes) still gets pushed within the
    /// same pass instead of trickling out one batch per <see cref="SmmProviderConfig.StatusPushIntervalSeconds"/>.
    /// </summary>
    public async Task<int> PushOrderStatusUpdatesAsync(CancellationToken ct = default)
    {
        using var scope = _scopeFactory.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
        var config = await GetConfigAsync(db, ct);
        if (string.IsNullOrWhiteSpace(config.ApiKey)) return 0;

        var batchSize = Math.Clamp(config.StatusPushBatchSize, 1, 500);
        var maxBatches = Math.Clamp(config.StatusPushMaxBatchesPerPass, 1, 20);

        var totalFetched = 0;
        var totalSucceeded = 0;
        for (var batch = 0; batch < maxBatches; batch++)
        {
            var (fetched, succeeded) = await PushOrderStatusBatchAsync(db, config, batchSize, ct);
            totalFetched += fetched;
            totalSucceeded += succeeded;
            // Fewer rows than the batch size means the dirty queue is drained for this pass —
            // no point spending another round-trip confirming that against an empty next page.
            if (fetched < batchSize) break;
        }

        if (totalFetched > batchSize)
        {
            _logger.LogInformation(
                "Pushed status updates across {Batches} batch(es): {Succeeded}/{Fetched} external orders.",
                (totalFetched + batchSize - 1) / batchSize, totalSucceeded, totalFetched);
        }
        return totalSucceeded;
    }

    /// <summary>One page of the status-push loop above — pulls up to [batchSize] dirty orders and
    /// pushes them in a single /orders/update call. Returns how many orders this batch pulled
    /// (so the caller knows whether to try another batch) and how many the panel accepted.</summary>
    private async Task<(int Fetched, int Succeeded)> PushOrderStatusBatchAsync(
        AppDbContext db, SmmProviderConfig config, int batchSize, CancellationToken ct)
    {
        try
        {
            var updatedOrders = await db.AppOrders
                .Where(o => o.IsExternal && !string.IsNullOrEmpty(o.ExternalOrderId) &&
                            (o.PanelLastPushedAt == null ||
                             o.UpdatedAt > o.PanelLastPushedAt))
                .OrderByDescending(o => o.UpdatedAt)
                .Take(batchSize)
                .ToListAsync(ct);

            if (updatedOrders.Count == 0) return (0, 0);

            var updatePayload = new
            {
                orders = updatedOrders.Select(o =>
                {
                    int extId = int.TryParse(o.ExternalOrderId, out var parsed) ? parsed : 0;
                    int remains = Math.Max(0, o.Quantity - o.CompletedCount);
                    string panelStatus = MapToPanelStatus(o.Status);
                    return new
                    {
                        id = extId,
                        status = panelStatus,
                        remains = remains,
                        start_count = Math.Max(0, o.StartCount),
                        reason = Truncate(o.ErrorMessage, 512)
                    };
                }).Where(o => o.id > 0).ToList()
            };

            // Nothing persisted (no valid ExternalOrderId in this page) — return 0, not
            // updatedOrders.Count, so the caller doesn't loop back for another batch of the exact
            // same unpushable rows.
            if (updatePayload.orders.Count == 0) return (0, 0);
            var pushedOrdersByExternalId = updatedOrders
                .Where(o => int.TryParse(o.ExternalOrderId, out _))
                .ToDictionary(o => int.Parse(o.ExternalOrderId!), o => o);

            var client = _http.CreateClient("SmmPanel");
            var baseUrl = GetNormalizedBaseUrl(config);
            var updateUrl = $"{baseUrl}/orders/update";
            var payloadJson = JsonSerializer.Serialize(updatePayload);

            var req = new HttpRequestMessage(HttpMethod.Post, updateUrl)
            {
                Content = new StringContent(payloadJson, Encoding.UTF8, "application/json")
            };
            req.Headers.TryAddWithoutValidation("X-Api-Key", config.ApiKey);

            var resp = await client.SendAsync(req, ct);
            var responseBody = await resp.Content.ReadAsStringAsync(ct);
            _ = _telegram?.LogSmmPanelUpdateAsync(updateUrl, (int)resp.StatusCode, payloadJson, responseBody);

            // Nothing was marked as pushed on a failed call, so the same rows would just come
            // back on the next batch — return 0 to stop this pass rather than retry them
            // immediately against a request that just failed.
            if (!resp.IsSuccessStatusCode) return (0, 0);

            // The panel answers 200 for the batch even when every individual order inside it was
            // rejected (e.g. "Remains update is not allowed" for an order it still considers
            // Pending on its own side) — the per-order result only shows up in the body. Treating
            // HTTP success as batch success used to log "Pushed N orders" while N of them had
            // actually been rejected, which made a fully-failing sync look silently healthy.
            var perOrderResults = ParseUpdateResults(responseBody);
            if (perOrderResults is null)
            {
                // Unrecognized response shape — fall back to the old assume-success behaviour
                // rather than reporting 0 for what was, per HTTP status, a successful call.
                MarkPanelPushSucceeded(updatedOrders, DateTime.UtcNow);
                await db.SaveChangesAsync(ct);
                _logger.LogInformation("Pushed status updates for {Count} external orders to SMM panel.", updatePayload.orders.Count);
                return (updatedOrders.Count, updatePayload.orders.Count);
            }

            var succeeded = perOrderResults.Count(r => r.Success);
            var failed = perOrderResults.Where(r => !r.Success).ToList();
            if (failed.Count > 0)
            {
                _logger.LogWarning(
                    "SMM panel rejected {Failed}/{Total} order update(s): {Reasons}",
                    failed.Count, perOrderResults.Count,
                    string.Join("; ", failed.Select(f => $"#{f.Id} [{f.ErrorCode}] {f.Message}").Distinct()));
            }
            if (succeeded > 0)
            {
                var succeededIds = perOrderResults.Where(r => r.Success).Select(r => r.Id).ToHashSet();
                MarkPanelPushSucceeded(
                    pushedOrdersByExternalId
                        .Where(kv => succeededIds.Contains(kv.Key))
                        .Select(kv => kv.Value),
                    DateTime.UtcNow);
                await db.SaveChangesAsync(ct);
                _logger.LogInformation("Pushed status updates for {Count} external orders to SMM panel.", succeeded);
            }
            return (updatedOrders.Count, succeeded);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error pushing order updates to SMM panel.");
        }

        return (0, 0);
    }

    /// <summary>
    /// Continuously fetches cancelled tasks from SMM Admin API (POST /adminapi/v2/cancel/pull),
    /// sets their status to "partial" and updates their remaining count via POST /orders/update.
    /// Also updates local AppOrders status in DB to Canceled.
    /// </summary>
    public async Task<int> SyncCancelledOrdersAsync(CancellationToken ct = default)
    {
        try
        {
            using var scope = _scopeFactory.CreateScope();
            var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
            var config = await GetConfigAsync(db, ct);
            if (string.IsNullOrWhiteSpace(config.ApiKey)) return 0;

            var runnerSettings = await db.RunnerSettings.FirstOrDefaultAsync(ct);
            if (runnerSettings != null && !runnerSettings.AutoPartialCancelledTasks)
            {
                return 0;
            }

            var client = _http.CreateClient("SmmPanel");
            var baseUrl = GetNormalizedBaseUrl(config);
            var cancelPullUrl = $"{baseUrl}/cancel/pull";

            var pullPayloadJson = JsonSerializer.Serialize(new { limit = Math.Clamp(config.CancelPullBatchSize, 1, 500) });
            var req = new HttpRequestMessage(HttpMethod.Post, cancelPullUrl)
            {
                Content = new StringContent(pullPayloadJson, Encoding.UTF8, "application/json")
            };
            req.Headers.TryAddWithoutValidation("X-Api-Key", config.ApiKey);

            var resp = await client.SendAsync(req, ct);
            if (!resp.IsSuccessStatusCode) return 0;

            var responseBody = await resp.Content.ReadAsStringAsync(ct);
            if (string.IsNullOrWhiteSpace(responseBody)) return 0;

            using var doc = JsonDocument.Parse(responseBody);
            var root = doc.RootElement;

            JsonElement listElement = default;
            if (root.ValueKind == JsonValueKind.Array)
            {
                listElement = root;
            }
            else if (root.ValueKind == JsonValueKind.Object)
            {
                if (root.TryGetProperty("error_code", out var errCode) && errCode.GetInt32() != 0)
                    return 0;

                if (root.TryGetProperty("data", out var dataEl))
                {
                    if (dataEl.ValueKind == JsonValueKind.Array) listElement = dataEl;
                    else if (dataEl.ValueKind == JsonValueKind.Object && dataEl.TryGetProperty("list", out var listEl)) listElement = listEl;
                }
                else if (root.TryGetProperty("list", out var listEl) && listEl.ValueKind == JsonValueKind.Array)
                {
                    listElement = listEl;
                }
            }

            if (listElement.ValueKind != JsonValueKind.Array || listElement.GetArrayLength() == 0)
                return 0;

            var cancelItems = new List<(string Id, int Remains)>();
            foreach (var item in listElement.EnumerateArray())
            {
                string idStr = "";
                if (item.TryGetProperty("id", out var idProp)) idStr = idProp.ToString();
                else if (item.TryGetProperty("order_id", out var oIdProp)) idStr = oIdProp.ToString();

                int remains = 0;
                if (item.TryGetProperty("remains", out var remProp)) remProp.TryGetInt32(out remains);

                if (!string.IsNullOrWhiteSpace(idStr))
                {
                    cancelItems.Add((idStr, remains));
                }
            }

            if (cancelItems.Count == 0) return 0;

            // 1. Send update to SMM Panel (POST /orders/update)
            var updatePayload = new
            {
                orders = cancelItems.Select(item =>
                {
                    int extId = int.TryParse(item.Id, out var parsed) ? parsed : 0;
                    var existing = db.AppOrders.FirstOrDefault(o => o.ExternalOrderId == item.Id);
                    int calculatedRemains = item.Remains > 0
                        ? item.Remains
                        : (existing != null ? Math.Max(0, existing.Quantity - existing.CompletedCount) : 0);
                    return new
                    {
                        id = extId,
                        status = "partial",
                        remains = calculatedRemains
                    };
                }).Where(o => o.id > 0).ToList()
            };

            if (updatePayload.orders.Count > 0)
            {
                var updateUrl = $"{baseUrl}/orders/update";
                var updateJson = JsonSerializer.Serialize(updatePayload);
                var updateReq = new HttpRequestMessage(HttpMethod.Post, updateUrl)
                {
                    Content = new StringContent(updateJson, Encoding.UTF8, "application/json")
                };
                updateReq.Headers.TryAddWithoutValidation("X-Api-Key", config.ApiKey);
                await client.SendAsync(updateReq, ct);
            }

            // 2. Update local DB AppOrders
            int dbUpdated = 0;
            foreach (var item in cancelItems)
            {
                var existing = await db.AppOrders.FirstOrDefaultAsync(o => o.ExternalOrderId == item.Id, ct);
                if (existing != null && existing.Status != AppOrderStatus.Canceled)
                {
                    existing.Status = AppOrderStatus.Canceled;
                    existing.CompletedCount = Math.Clamp(existing.Quantity - item.Remains, 0, existing.Quantity);
                    existing.UpdatedAt = DateTime.UtcNow;
                    dbUpdated++;
                }
            }

            if (dbUpdated > 0)
            {
                await db.SaveChangesAsync(ct);
            }

            _logger.LogInformation("SyncCancelledOrdersAsync: Processed {Count} cancelled tasks and updated SMM panel + local DB.", cancelItems.Count);
            return cancelItems.Count;
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error syncing cancelled orders from SMM panel.");
            return 0;
        }
    }

    /// <summary>Parses the per-order results out of an /orders/update response body, or null if the shape is unrecognized.</summary>
    private static List<PanelUpdateResult>? ParseUpdateResults(string responseBody)
    {
        try
        {
            using var doc = JsonDocument.Parse(responseBody);
            if (!doc.RootElement.TryGetProperty("data", out var dataEl) ||
                !dataEl.TryGetProperty("orders", out var ordersEl))
            {
                return null;
            }
            return JsonSerializer.Deserialize<List<PanelUpdateResult>>(ordersEl.GetRawText(),
                new JsonSerializerOptions { PropertyNameCaseInsensitive = true });
        }
        catch (JsonException)
        {
            return null;
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    /// <summary>Snapshots what the panel accepted so later syncs only push changed rows.</summary>
    private static void MarkPanelPushSucceeded(IEnumerable<AppOrder> orders, DateTime pushedAt)
    {
        foreach (var order in orders)
        {
            order.PanelLastPushedStatus = MapToPanelStatus(order.Status);
            order.PanelLastPushedRemains = Math.Max(0, order.Quantity - order.CompletedCount);
            order.PanelLastPushedStartCount = Math.Max(0, order.StartCount);
            order.PanelLastPushedReason = Truncate(order.ErrorMessage, 512);
            order.PanelLastPushedAt = pushedAt;
        }
    }

    private static readonly JsonSerializerOptions SyncJsonOptions = new()
    {
        PropertyNameCaseInsensitive = true,
        NumberHandling = System.Text.Json.Serialization.JsonNumberHandling.AllowReadingFromString
    };

    /// <summary>Pulls pending orders via POST /adminapi/v2/orders/pull.</summary>
    private async Task<List<PanelOrder>> FetchOrdersAsync(SmmProviderConfig config, CancellationToken ct)
    {
        var client = _http.CreateClient("SmmPanel");
        var url = $"{GetNormalizedBaseUrl(config)}/orders/pull";

        try
        {
            var serviceIdList = new List<int>();
            serviceIdList.Add(config.FollowServiceId > 0 ? config.FollowServiceId : 171);
            serviceIdList.Add(config.LikeServiceId > 0 ? config.LikeServiceId : 172);
            serviceIdList.Add(config.CommentServiceId > 0 ? config.CommentServiceId : 177);
            serviceIdList.Add(config.CommentCustomServiceId > 0 ? config.CommentCustomServiceId : 178);
            serviceIdList.Add(config.RepostServiceId > 0 ? config.RepostServiceId : 175);
            serviceIdList.Add(config.SavePostServiceId > 0 ? config.SavePostServiceId : 176);
            serviceIdList.Add(config.StoryViewServiceId > 0 ? config.StoryViewServiceId : 179);

            var serviceIds = string.Join(",", serviceIdList.Distinct());
            var payload = new { service_ids = serviceIds, limit = Math.Clamp(config.FetchBatchSize, 1, 500) };
            var req = new HttpRequestMessage(HttpMethod.Post, url)
            {
                Content = new StringContent(JsonSerializer.Serialize(payload), Encoding.UTF8, "application/json")
            };
            req.Headers.TryAddWithoutValidation("X-Api-Key", config.ApiKey);

            var resp = await client.SendAsync(req, ct);
            if (!resp.IsSuccessStatusCode)
            {
                _logger.LogWarning("POST /adminapi/v2/orders/pull returned {Status}", resp.StatusCode);
                return new List<PanelOrder>();
            }

            var json = await resp.Content.ReadAsStringAsync(ct);
            using var doc = JsonDocument.Parse(json);
            var root = doc.RootElement;

            JsonElement listElement = default;
            if (root.ValueKind == JsonValueKind.Object)
            {
                if (root.TryGetProperty("data", out var dataEl))
                {
                    if (dataEl.ValueKind == JsonValueKind.Array)
                        listElement = dataEl;
                    else if (dataEl.ValueKind == JsonValueKind.Object && dataEl.TryGetProperty("list", out var listEl))
                        listElement = listEl;
                }
                else if (root.TryGetProperty("orders", out var ordersEl) && ordersEl.ValueKind == JsonValueKind.Array)
                {
                    listElement = ordersEl;
                }
                else if (root.TryGetProperty("list", out var directListEl) && directListEl.ValueKind == JsonValueKind.Array)
                {
                    listElement = directListEl;
                }
            }
            else if (root.ValueKind == JsonValueKind.Array)
            {
                listElement = root;
            }

            if (listElement.ValueKind == JsonValueKind.Array)
            {
                return JsonSerializer.Deserialize<List<PanelOrder>>(listElement.GetRawText(), SyncJsonOptions) ?? new List<PanelOrder>();
            }
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "POST /adminapi/v2/orders/pull fetch error");
        }

        return new List<PanelOrder>();
    }

    private static string GetNormalizedBaseUrl(SmmProviderConfig config)
    {
        var raw = config.BaseUrl.TrimEnd('/');
        if (raw.EndsWith("/adminapi/v2", StringComparison.OrdinalIgnoreCase)) return raw;
        if (raw.EndsWith("/admin/adminapi/v2", StringComparison.OrdinalIgnoreCase))
            return raw.Replace("/admin/adminapi/v2", "/adminapi/v2", StringComparison.OrdinalIgnoreCase);
        return "https://smmorigin.com/adminapi/v2";
    }

    private static AppOrderStatus MapStatus(string? panelStatus) => panelStatus?.ToLowerInvariant() switch
    {
        "pending" => AppOrderStatus.Pending,
        "in progress" or "inprogress" or "in_progress" or "processing" => AppOrderStatus.InProgress,
        "completed" => AppOrderStatus.Completed,
        "canceled" or "cancelled" => AppOrderStatus.Canceled,
        "partial" => AppOrderStatus.InProgress,
        "error" or "fail" or "failed" => AppOrderStatus.Failed,
        _ => AppOrderStatus.Pending,
    };

    private static string MapToPanelStatus(AppOrderStatus status) => status switch
    {
        AppOrderStatus.Pending or AppOrderStatus.Approved or AppOrderStatus.Submitted or AppOrderStatus.InProgress or AppOrderStatus.Processing => "in_progress",
        AppOrderStatus.Completed => "completed",
        AppOrderStatus.Canceled or AppOrderStatus.Rejected => "canceled",
        AppOrderStatus.Failed or AppOrderStatus.NotFound => "canceled",
        _ => "in_progress"
    };

    private static TaskType MapServiceType(int serviceId, string? serviceName, SmmProviderConfig config)
    {
        if (serviceId == config.LikeServiceId || serviceId == 172 || serviceName?.Contains("like", StringComparison.OrdinalIgnoreCase) == true)
            return TaskType.Like;
        if (serviceId == config.CommentServiceId || serviceId == config.CommentCustomServiceId || serviceId == 177 || serviceId == 178 || serviceName?.Contains("comment", StringComparison.OrdinalIgnoreCase) == true)
            return TaskType.Comment;
        if (serviceId == config.RepostServiceId || serviceId == 175 || serviceName?.Contains("repost", StringComparison.OrdinalIgnoreCase) == true || serviceName?.Contains("share", StringComparison.OrdinalIgnoreCase) == true)
            return TaskType.Repost;
        if (serviceId == config.SavePostServiceId || serviceId == 176 || serviceName?.Contains("save", StringComparison.OrdinalIgnoreCase) == true)
            return TaskType.SavePost;
        if (serviceId == config.StoryViewServiceId || serviceId == 179 || serviceName?.Contains("story", StringComparison.OrdinalIgnoreCase) == true)
            return TaskType.StoryView;

        return TaskType.Follow;
    }

    /// <summary>
    /// Pulls a username out of whatever the panel sent as a link. Mirrors the Android client's
    /// own extractInstagramHandle (InstagramLinks.kt) — gate on the literal "instagram.com/"
    /// substring before doing any URL parsing, rather than blindly prepending "https://" and
    /// handing the result to <see cref="Uri"/>. The panel sometimes sends a bare handle with no
    /// domain at all (e.g. "freshdiaries_"); prepending a scheme and parsing that as a URI treats
    /// the handle itself as the *hostname*, leaving an empty path — that's what was producing a
    /// blank TargetUsername (and a "—" target in the dashboard) for every such order, even though
    /// TargetUrl still held the handle just fine.
    /// </summary>
    private static string? ExtractUsername(string? link)
    {
        if (string.IsNullOrWhiteSpace(link)) return null;
        var trimmed = link.Trim();
        if (trimmed.StartsWith('@')) return trimmed.TrimStart('@');

        var domainAt = trimmed.IndexOf("instagram.com/", StringComparison.OrdinalIgnoreCase);
        if (domainAt < 0) return trimmed;

        try
        {
            var afterDomain = trimmed[(domainAt + "instagram.com/".Length)..];
            var path = afterDomain.Split('?', '#')[0].Trim('/');
            if (path.Length == 0) return trimmed;

            var segments = path.Split('/');
            var first = segments[0];
            if (segments.Length > 1 && (
                first.Equals("p", StringComparison.OrdinalIgnoreCase) ||
                first.Equals("reel", StringComparison.OrdinalIgnoreCase) ||
                first.Equals("reels", StringComparison.OrdinalIgnoreCase) ||
                first.Equals("tv", StringComparison.OrdinalIgnoreCase)))
            {
                return segments[1];
            }
            return first;
        }
        catch { return trimmed; }
    }

    private static string? Truncate(string? value, int maxLength)
    {
        if (string.IsNullOrEmpty(value)) return value;
        return value.Length <= maxLength ? value : value.Substring(0, maxLength);
    }

    private static async Task<User> GetOrCreateSystemUserAsync(AppDbContext db, CancellationToken ct)
    {
        const string systemEmail = "system@smmorigin.internal";
        var user = await db.Users.FirstOrDefaultAsync(u => u.Email == systemEmail, ct);
        if (user is not null) return user;

        user = new User
        {
            Name = "SMM Panel (smmorigin)",
            Email = systemEmail,
            PasswordHash = "!SYSTEM!",
        };
        db.Users.Add(user);
        await db.SaveChangesAsync(ct);
        return user;
    }

    // ─── SMM panel response model ───────────────────────────────────────────────

    /// <summary>One order's outcome from the /orders/update response's data.orders[] array.</summary>
    private sealed class PanelUpdateResult
    {
        [JsonPropertyName("id")]
        public int Id { get; set; }

        [JsonPropertyName("success")]
        public bool Success { get; set; }

        [JsonPropertyName("message")]
        public string? Message { get; set; }

        [JsonPropertyName("error_code")]
        public int? ErrorCode { get; set; }
    }

    private sealed class PanelOrder
    {
        [JsonPropertyName("order")]
        public int OrderIdProp { get; set; }

        [JsonPropertyName("id")]
        public int Id { get; set; }

        public int OrderId => Id > 0 ? Id : OrderIdProp;

        [JsonPropertyName("service_id")]
        public int ServiceIdProp { get; set; }

        [JsonPropertyName("service")]
        public int ServiceProp { get; set; }

        public int ServiceId => ServiceIdProp > 0 ? ServiceIdProp : ServiceProp;

        [JsonPropertyName("service_name")]
        public string? ServiceName { get; set; }

        [JsonPropertyName("link")]
        public string? Link { get; set; }

        [JsonPropertyName("quantity")]
        public int Quantity { get; set; }

        [JsonPropertyName("start_count")]
        public int? StartCount { get; set; }

        [JsonPropertyName("remains")]
        public int Remains { get; set; }

        [JsonPropertyName("status")]
        public string? Status { get; set; }

        /// <summary>The panel customer who placed the order — display-only, distinct from the target being followed/liked.</summary>
        [JsonPropertyName("user")]
        public string? User { get; set; }

        [JsonPropertyName("charge")]
        public PanelCharge? Charge { get; set; }

        [JsonPropertyName("created_timestamp")]
        public long? CreatedTimestamp { get; set; }

        [JsonPropertyName("comments")]
        public string? Comments { get; set; }

        [JsonPropertyName("order_buttons")]
        public List<PanelOrderButton>? OrderButtons { get; set; }
    }

    private sealed class PanelOrderButton
    {
        [JsonPropertyName("title")]
        public string? Title { get; set; }

        [JsonPropertyName("service_type")]
        public string? ServiceType { get; set; }

        [JsonPropertyName("type")]
        public string? Type { get; set; }

        [JsonPropertyName("user_data")]
        public List<string>? UserData { get; set; }
    }

    /// <summary>What the panel charged its customer for the order — display-only, never used in our own coin pricing.</summary>
    private sealed class PanelCharge
    {
        [JsonPropertyName("value")]
        public string? Value { get; set; }

        [JsonPropertyName("currency_code")]
        public string? CurrencyCode { get; set; }
    }
}
