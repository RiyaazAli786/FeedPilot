using System.Text.Json;
using FeedPilot.Api.Data;
using FeedPilot.Api.Domain;
using FeedPilot.Api.Dtos;
using FeedPilot.Api.Services;
using Microsoft.EntityFrameworkCore;
using Microsoft.Data.Sqlite;
using Microsoft.Extensions.Logging.Abstractions;

internal static class FailoverChecks
{
    public static async Task RunAsync()
    {
        await ScenarioAsync("reported failure", "IG_SESSION_EXPIRED");
        await ScenarioAsync("empty feed", "EMPTY");
        await ScenarioAsync("timeout", "TIMEOUT");
        await ScenarioAsync("all devices fail", "ALL_FAIL");
        await ScenarioAsync("relational failure", "IG_EMPTY_FEED", sqlite: true);
        await ScenarioAsync("disabled device", "DISABLED");
        await DisableEnableAsync();
        await CancellationAsync();
    }

    private static async Task ScenarioAsync(string name, string firstFailure, bool sqlite = false)
    {
        using var connection = sqlite ? new SqliteConnection("Data Source=:memory:") : null;
        if (connection is not null) await connection.OpenAsync();
        var builder = new DbContextOptionsBuilder<AppDbContext>();
        if (connection is not null) builder.UseSqlite(connection);
        else builder.UseInMemoryDatabase(Guid.NewGuid().ToString());
        var options = builder.Options;
        using var requestDb = new AppDbContext(options);
        await requestDb.Database.EnsureCreatedAsync();
        using (var seed = new AppDbContext(options))
        {
            await Heartbeat(Service(seed), "second");
            await Heartbeat(Service(seed), "first");
            var devices = await seed.FeedBridgeDeviceStates.ToListAsync();
            foreach (var d in devices)
            {
                d.LastSeenAt = DateTime.UtcNow.AddSeconds(d.DeviceId == "first" ? 0 : -1);
                // With unused devices, the rotation starts in stable creation order.
                d.CreatedAt = DateTime.UtcNow.AddMinutes(d.DeviceId == "first" ? -2 : -1);
            }
            await seed.SaveChangesAsync();
        }
        using var limit = new CancellationTokenSource(TimeSpan.FromSeconds(20));
        var resultTask = Service(requestDb).ExecuteFeedAsync(
            new BridgeFeedRequest(null, "target", null), limit.Token);
        BridgeJobDto? firstJob = null;
        while (!resultTask.IsCompleted)
        {
            using var workerDb = new AppDbContext(options);
            var worker = Service(workerDb);
            await Heartbeat(worker, "second");
            if (firstFailure != "TIMEOUT" && firstJob is null)
            {
                firstJob = await worker.PollAsync("app", "first", "install", limit.Token);
                if (firstJob is not null)
                {
                    Assert(await worker.PollAsync("app", "first", "install", limit.Token) is null,
                        "running job is not claimed twice");
                    if (firstFailure == "DISABLED")
                        await worker.HeartbeatAsync(null, "app", new BridgeHeartbeatRequest(
                            "first", "install", BridgeEnabled: false), limit.Token);
                    else if (firstFailure == "EMPTY")
                        await worker.CompleteAsync(firstJob.Id, "app", "first",
                            new CompleteBridgeJobRequest(JsonSerializer.SerializeToElement(new { items = Array.Empty<object>() })), limit.Token);
                    else
                        await worker.FailAsync(firstJob.Id, "app", "first",
                            new FailBridgeJobRequest(firstFailure == "ALL_FAIL" ? "IG_EMPTY_FEED" : firstFailure), limit.Token);
                }
            }
            Assert(await worker.PollAsync("foreign-app", "second", "install", limit.Token) is null,
                "foreign app cannot claim a job");
            var secondJob = await worker.PollAsync("app", "second", "install", limit.Token);
            if (secondJob is not null)
            {
                if (firstFailure == "ALL_FAIL")
                    await worker.FailAsync(secondJob.Id, "app", "second", new FailBridgeJobRequest("IG_EMPTY_FEED"), limit.Token);
                else
                    await worker.CompleteAsync(secondJob.Id, "app", "second", Feed(), limit.Token);
            }
            await Task.Delay(50, limit.Token);
        }

        var result = await resultTask;
        Assert(firstFailure == "ALL_FAIL" ? !result.Success : result.Success && result.DeviceId == "second",
            name + " retries a different available device");
        using var check = new AppDbContext(options);
        var jobs = await check.FeedBridgeJobs.OrderBy(j => j.Attempt).ToListAsync();
        Assert(jobs.Count == 2 && jobs[0].AssignedDeviceId == "first" && jobs[1].AssignedDeviceId == "second",
            name + " attempts each device once");
        Assert(!await check.FeedBridgeAccountStates.AnyAsync(a => a.IsBusy), name + " releases account locks");
        Assert(!(await Service(check).CompleteAsync(jobs[0].Id, "app", "first", Feed(), CancellationToken.None)).Accepted,
            name + " rejects late completion from the first device");
        Assert((await check.FeedBridgeJobs.FindAsync(jobs[0].Id))!.Status != FeedBridgeJobStatus.Completed,
            name + " preserves failed attempt state");
        Console.WriteLine("PASS: failover after " + name);
    }

    private static async Task CancellationAsync()
    {
        var options = new DbContextOptionsBuilder<AppDbContext>()
            .UseInMemoryDatabase(Guid.NewGuid().ToString()).Options;
        using var db = new AppDbContext(options);
        await Heartbeat(Service(db), "first");
        using var cancellation = new CancellationTokenSource(50);
        try
        {
            await Service(db).ExecuteFeedAsync(new BridgeFeedRequest(null, "target", null), cancellation.Token);
            throw new Exception("Expected cancellation");
        }
        catch (OperationCanceledException) { }
        Assert(!await db.FeedBridgeAccountStates.AnyAsync(a => a.IsBusy), "cancellation releases busy account");
        Assert((await db.FeedBridgeJobs.SingleAsync()).Status == FeedBridgeJobStatus.Cancelled,
            "cancellation retires pending job");
        Console.WriteLine("PASS: caller cancellation");
    }

    private static async Task DisableEnableAsync()
    {
        var options = new DbContextOptionsBuilder<AppDbContext>()
            .UseInMemoryDatabase(Guid.NewGuid().ToString()).Options;
        using var db = new AppDbContext(options);
        var service = Service(db);
        await Heartbeat(service, "first");
        var account = await db.FeedBridgeAccountStates.SingleAsync();
        var job = new FeedBridgeJob {
            AssignedDeviceId = "first", AssignedInstallationId = "install",
            AssignedAccountStateId = account.Id, ExpiresAt = DateTime.UtcNow.AddSeconds(30),
            Status = FeedBridgeJobStatus.Assigned
        };
        account.IsBusy = true;
        db.Add(job);
        await db.SaveChangesAsync();
        await service.HeartbeatAsync(null, "app",
            new BridgeHeartbeatRequest("first", "install", BridgeEnabled: false), CancellationToken.None);
        Assert(await service.PollAsync("app", "first", "install", CancellationToken.None) is null,
            "disabled device cannot claim work");
        Assert(job.Status == FeedBridgeJobStatus.Cancelled && !account.IsBusy,
            "disabling cancels pending assignments and frees the account");
        Assert(account.CooldownUntil is null && account.FailureCount == 0,
            "disabling does not penalize the session");
        Assert(!(await service.CompleteAsync(job.Id, "app", "first", Feed(), CancellationToken.None)).Accepted,
            "disabled device's late result is rejected");
        var unavailable = await service.ExecuteFeedAsync(new BridgeFeedRequest(null, "target", null), CancellationToken.None);
        Assert(!unavailable.Success && unavailable.Code == "NO_ACTIVE_SESSION", "disabled device is not selected");
        await Heartbeat(service, "first");
        var next = new FeedBridgeJob {
            AssignedDeviceId = "first", AssignedInstallationId = "install",
            AssignedAccountStateId = account.Id, ExpiresAt = DateTime.UtcNow.AddSeconds(30),
            Status = FeedBridgeJobStatus.Assigned
        };
        db.Add(next);
        await db.SaveChangesAsync();
        Assert((await service.PollAsync("app", "first", "install", CancellationToken.None))?.Id == next.Id,
            "re-enabled device can claim a new job");
        Console.WriteLine("PASS: disable, reject work, and re-enable");
    }

    private static FeedBridgeService Service(AppDbContext db) => new(db, NullLogger<FeedBridgeService>.Instance);
    private static Task<BridgeHeartbeatResponse> Heartbeat(FeedBridgeService bridge, string device) =>
        bridge.HeartbeatAsync(null, "app", new BridgeHeartbeatRequest(device, "install",
            ActiveAccountUsername: device, Accounts: new List<BridgeHeartbeatAccountDto>
            { new(null, device) }), CancellationToken.None);
    private static CompleteBridgeJobRequest Feed() =>
        new(JsonSerializer.SerializeToElement(new { items = new[] { new { id = "123" } } }));
    private static void Assert(bool condition, string message)
    {
        if (!condition) throw new InvalidOperationException("FAILED: " + message);
    }
}
