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
await FailoverChecks.RunAsync();

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
