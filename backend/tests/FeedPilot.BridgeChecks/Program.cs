using System.Reflection;
using FeedPilot.Api.Data;
using FeedPilot.Api.Domain;
using FeedPilot.Api.Dtos;
using FeedPilot.Api.Services;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Logging.Abstractions;

// Offline regression checks: no real Instagram sessions, devices, or HTTP calls.
using var db = new AppDbContext(new DbContextOptionsBuilder<AppDbContext>()
    .UseInMemoryDatabase(Guid.NewGuid().ToString()).Options);
var service = new FeedBridgeService(db, NullLogger<FeedBridgeService>.Instance);
var now = DateTime.UtcNow;
var device = new FeedBridgeDeviceState
{
    AppId = "app", DeviceId = "live", InstallationId = "install", LastSeenAt = now,
    ActiveAccountUsername = "active"
};
var active = new FeedBridgeAccountState
{
    AppId = "app", DeviceId = "live", InstallationId = "install",
    InstagramUsername = "active", LastValidatedAt = now
};
db.AddRange(device, active);
await db.SaveChangesAsync();
Check((await Select())?.Id == active.Id, "fresh matching session is selected");

device.LastSeenAt = now.AddSeconds(-20);
await db.SaveChangesAsync();
Check(await Select() is null, "stale device is rejected");
device.LastSeenAt = now;
active.InstallationId = "different";
await db.SaveChangesAsync();
Check(await Select() is null, "session is never moved to a different installation");
active.InstallationId = "install";
active.LastValidatedAt = now.AddSeconds(-20);
await db.SaveChangesAsync();
Check(await Select() is null, "stale account snapshot is rejected");
active.LastValidatedAt = now;

var other = new FeedBridgeAccountState
{
    AppId = "app", DeviceId = "live", InstallationId = "install",
    InstagramUsername = "other", LastValidatedAt = now,
    LastUsedAt = now.AddMinutes(-10)
};
db.Add(other);
await db.SaveChangesAsync();
Check((await Select())?.Id == active.Id, "unused account is selected before previously used account");
active.LastUsedAt = now;
await db.SaveChangesAsync();
Check((await Select())?.Id == other.Id, "next request rotates away from the active account");
other.LastUsedAt = now.AddSeconds(1);
await db.SaveChangesAsync();
Check((await Select())?.Id == active.Id, "account rotation returns to the least recently assigned account");

var secondDevice = new FeedBridgeDeviceState
{
    AppId = "app", DeviceId = "second", InstallationId = "second-install",
    LastSeenAt = now.AddSeconds(-1)
};
var secondAccount = new FeedBridgeAccountState
{
    AppId = "app", DeviceId = "second", InstallationId = "second-install",
    InstagramUsername = "second-account", LastValidatedAt = now
};
db.AddRange(secondDevice, secondAccount);
await db.SaveChangesAsync();
Check((await Select())?.Id == secondAccount.Id, "unused online device wins regardless of heartbeat recency");
secondAccount.LastUsedAt = now.AddSeconds(2);
await db.SaveChangesAsync();
Check((await Select())?.Id == active.Id, "device rotation returns to earlier device before rotating its accounts");
secondDevice.BridgeEnabled = false;
await db.SaveChangesAsync();

var job = new FeedBridgeJob
{
    AssignedDeviceId = "live", AssignedInstallationId = "install",
    Status = FeedBridgeJobStatus.Running, ExpiresAt = now.AddSeconds(40)
};
db.Add(job);
await db.SaveChangesAsync();
Check(await Select() is null, "busy device cannot receive a second job through another account");
job.Status = FeedBridgeJobStatus.Completed;
await db.SaveChangesAsync();
Check(await Select() is not null, "completed job releases device capacity");

await service.HeartbeatAsync(null, "app", new BridgeHeartbeatRequest(
    "live", "install", Accounts: new List<BridgeHeartbeatAccountDto>()), CancellationToken.None);
Check(await Select() is null, "removed account sessions become unavailable immediately");
Check(active.Status == AccountStatus.SessionExpired, "removed session is marked expired");
Console.WriteLine("All bridge selection checks passed.");
await TestStrictAlternatingRoundRobinAsync();
await FailoverChecks.RunAsync();

async Task TestStrictAlternatingRoundRobinAsync()
{
    using var rrDb = new AppDbContext(new DbContextOptionsBuilder<AppDbContext>()
        .UseInMemoryDatabase(Guid.NewGuid().ToString()).Options);
    var rrService = new FeedBridgeService(rrDb, NullLogger<FeedBridgeService>.Instance);
    var t0 = DateTime.UtcNow;

    var devA = new FeedBridgeDeviceState { AppId = "app", DeviceId = "devA", InstallationId = "instA", LastSeenAt = t0, CreatedAt = t0.AddMinutes(-10) };
    var devB = new FeedBridgeDeviceState { AppId = "app", DeviceId = "devB", InstallationId = "instB", LastSeenAt = t0, CreatedAt = t0.AddMinutes(-9) };
    var accA1 = new FeedBridgeAccountState { AppId = "app", DeviceId = "devA", InstallationId = "instA", InstagramUsername = "accA1", LastValidatedAt = t0, CreatedAt = t0.AddMinutes(-10) };
    var accA2 = new FeedBridgeAccountState { AppId = "app", DeviceId = "devA", InstallationId = "instA", InstagramUsername = "accA2", LastValidatedAt = t0, CreatedAt = t0.AddMinutes(-9) };
    var accB1 = new FeedBridgeAccountState { AppId = "app", DeviceId = "devB", InstallationId = "instB", InstagramUsername = "accB1", LastValidatedAt = t0, CreatedAt = t0.AddMinutes(-8) };
    var accB2 = new FeedBridgeAccountState { AppId = "app", DeviceId = "devB", InstallationId = "instB", InstagramUsername = "accB2", LastValidatedAt = t0, CreatedAt = t0.AddMinutes(-7) };

    rrDb.AddRange(devA, devB, accA1, accA2, accB1, accB2);
    await rrDb.SaveChangesAsync();

    var selectMethod = typeof(FeedBridgeService).GetMethod("SelectAccountAsync", BindingFlags.Instance | BindingFlags.NonPublic)!;
    async Task<FeedBridgeAccountState> PickAndAssign(DateTime time)
    {
        var picked = (await (Task<FeedBridgeAccountState?>)selectMethod.Invoke(rrService, new object?[] { CancellationToken.None, null })!)!;
        picked.LastUsedAt = time;
        var d = await rrDb.FeedBridgeDeviceStates.FirstAsync(x => x.DeviceId == picked.DeviceId);
        d.LastUsedAt = time;
        await rrDb.SaveChangesAsync();
        return picked;
    }

    var p1 = await PickAndAssign(t0.AddSeconds(1));
    Check(p1.DeviceId == "devA" && p1.InstagramUsername == "accA1", "Round-robin 1 selects devA / accA1");

    var p2 = await PickAndAssign(t0.AddSeconds(2));
    Check(p2.DeviceId == "devB" && p2.InstagramUsername == "accB1", "Round-robin 2 alternates to devB / accB1");

    var p3 = await PickAndAssign(t0.AddSeconds(3));
    Check(p3.DeviceId == "devA" && p3.InstagramUsername == "accA2", "Round-robin 3 alternates back to devA / accA2");

    var p4 = await PickAndAssign(t0.AddSeconds(4));
    Check(p4.DeviceId == "devB" && p4.InstagramUsername == "accB2", "Round-robin 4 alternates back to devB / accB2");

    var p5 = await PickAndAssign(t0.AddSeconds(5));
    Check(p5.DeviceId == "devA" && p5.InstagramUsername == "accA1", "Round-robin 5 wraps around on devA to accA1");

    var p6 = await PickAndAssign(t0.AddSeconds(6));
    Check(p6.DeviceId == "devB" && p6.InstagramUsername == "accB1", "Round-robin 6 wraps around on devB to accB1");
    Console.WriteLine("All multi-device round-robin checks passed.");
}

async Task<FeedBridgeAccountState?> Select()
{
    var method = typeof(FeedBridgeService).GetMethod("SelectAccountAsync",
        BindingFlags.Instance | BindingFlags.NonPublic)!;
    return await (Task<FeedBridgeAccountState?>)method.Invoke(service, new object?[] { CancellationToken.None, null })!;
}

static void Check(bool condition, string message)
{
    if (!condition) throw new InvalidOperationException("FAILED: " + message);
    Console.WriteLine("PASS: " + message);
}
