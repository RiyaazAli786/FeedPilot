using System.Text.Json;
using System.Text.Json.Serialization;
using System.Threading.RateLimiting;
using System.Linq;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.AspNetCore.HttpOverrides;
using Microsoft.AspNetCore.RateLimiting;
using Microsoft.EntityFrameworkCore;
using Microsoft.OpenApi.Models;
using FeedPilot.Api.Data;
using FeedPilot.Api.Domain;
using FeedPilot.Api.Services;

// Render's Linux instances have a low inotify watcher limit. ASP.NET Core's default
// config reload watchers can exhaust it during startup, so keep deployed config static.
Environment.SetEnvironmentVariable("DOTNET_HOSTBUILDER__RELOADCONFIGONCHANGE", "false");

var builder = WebApplication.CreateBuilder(args);

// Hosts like Render tell the app which port to listen on via $PORT. Honour it; locally Kestrel
// keeps its configured default.
var port = Environment.GetEnvironmentVariable("PORT");
if (!string.IsNullOrWhiteSpace(port))
    builder.WebHost.UseUrls($"http://0.0.0.0:{port}");

// ---------- Configuration ----------
builder.Services.Configure<JwtSettings>(builder.Configuration.GetSection("Jwt"));
var jwtSettings = builder.Configuration.GetSection("Jwt").Get<JwtSettings>() ?? new JwtSettings();
builder.Services.Configure<OrderPricingSettings>(builder.Configuration.GetSection("Orders"));
builder.Services.Configure<AdminSettings>(builder.Configuration.GetSection("Admin"));
builder.Services.Configure<RequestSigningSettings>(builder.Configuration.GetSection("RequestSigning"));
builder.Services.Configure<PaymentSettings>(builder.Configuration.GetSection("Payments"));
builder.Services.Configure<TelegramSettings>(builder.Configuration.GetSection("Telegram"));
builder.Services.Configure<UpgradeAssetStorageSettings>(builder.Configuration.GetSection("AssetStorage"));
builder.Services.Configure<DeviceRestoreSettings>(builder.Configuration.GetSection("DeviceRestore"));

// Never run production on the shipped placeholder secret — tokens would be forgeable.
if (!builder.Environment.IsDevelopment() &&
    (string.IsNullOrWhiteSpace(jwtSettings.Secret) || jwtSettings.Secret.Contains("CHANGE_ME")))
{
    throw new InvalidOperationException(
        "Jwt:Secret must be set to a strong random value in production (set the Jwt__Secret env var).");
}

// ---------- Database ----------
var connectionString = ConnectionStringHelper.Resolve(builder.Configuration);
if (!builder.Environment.IsDevelopment() && string.IsNullOrWhiteSpace(connectionString))
{
    throw new InvalidOperationException(
        "A database connection string is required in production (set DATABASE_URL or ConnectionStrings__Default).");
}
builder.Services.AddDbContext<AppDbContext>(options =>
{
    if (string.IsNullOrWhiteSpace(connectionString))
    {
        // Zero-setup, but wiped on every restart. Prefer the SQLite dev file below so a
        // restart doesn't invalidate every logged-in session and drop all orders.
        options.UseInMemoryDatabase("FeedPilotDev");
    }
    else if (connectionString.TrimStart().StartsWith("Data Source", StringComparison.OrdinalIgnoreCase))
    {
        // A file-backed SQLite database: survives restarts, so tokens and orders persist.
        options.UseSqlite(connectionString);
    }
    else
    {
        options.UseNpgsql(connectionString);
    }
});

// ---------- Services ----------
// Backs the dashboard idle-lock's passcode challenge-response nonces (AdminDashboardController) —
// short-lived, single-use, no need for anything heavier than in-process memory.
builder.Services.AddMemoryCache();
builder.Services.AddScoped<ITokenService, TokenService>();
builder.Services.AddScoped<IWalletService, WalletService>();
builder.Services.AddScoped<ISubscriptionService, SubscriptionService>();
builder.Services.AddScoped<IAppOrderService, AppOrderService>();
builder.Services.AddScoped<IUpgradeAssetStorage, UpgradeAssetStorage>();
builder.Services.AddScoped<IInstagramFeedService, InstagramFeedService>();
builder.Services.AddSingleton<CommentFileService>();

// Proactively frees orders whose worker-device claim has gone stale, independent of any
// device polling for work again — see StaleClaimSweepService for why that matters.
builder.Services.AddHostedService<StaleClaimSweepService>();

// Optional Telegram request logging — see TelegramSettings for the on/off env vars.
builder.Services.AddHttpClient("Telegram");
builder.Services.AddSingleton<TelegramRequestLogger>();
builder.Services.AddHttpClient("B2");
builder.Services.AddHttpClient("Instagram");
builder.Services.AddSignalR();

// ---------- Auth ----------
builder.Services
    .AddAuthentication(JwtBearerDefaults.AuthenticationScheme)
    .AddJwtBearer(options =>
    {
        var tokenService = new TokenService(Microsoft.Extensions.Options.Options.Create(jwtSettings));
        options.TokenValidationParameters = tokenService.GetValidationParameters();
        options.Events = new JwtBearerEvents
        {
            OnMessageReceived = context =>
            {
                var accessToken = context.Request.Query["access_token"];
                var path = context.HttpContext.Request.Path;
                if (!string.IsNullOrEmpty(accessToken) && path.StartsWithSegments("/hubs"))
                {
                    context.Token = accessToken;
                }
                return Task.CompletedTask;
            }
        };
    });
builder.Services.AddAuthorization();

// ---------- Rate limiting ----------
// Endpoints that authenticate a secret rather than an already-issued session (admin/backup login,
// dashboard passcode challenge-response) are reachable without any prior credential — the general
// per-device budget below is too generous to stop a short PIN/key from being brute-forced there.
// Branched INSIDE the single "api" policy rather than layered on top via a second
// [EnableRateLimiting] attribute: ASP.NET Core resolves an endpoint's effective policy by taking
// the last matching metadata on it, and MapControllers().RequireRateLimiting(...) below applies
// its policy as an endpoint convention *after* attribute metadata is already collected — so an
// attribute-level override here would silently lose to this one. Confirmed empirically (hitting
// an attribute-decorated endpoint past its limit didn't 429 until this branch replaced it).
var adminAuthPathPrefixes = new[]
{
    "/api/admin/auth",
    "/api/admin/backup/auth",
    "/api/admin/dashboard/passcode-challenge",
    "/api/admin/dashboard/verify-passcode"
};

builder.Services.AddRateLimiter(options =>
{
    options.RejectionStatusCode = StatusCodes.Status429TooManyRequests;

    // Lets a rejected client back off exactly as long as needed instead of guessing — without
    // this, the app's poll loop can't tell a 429 apart from "no orders available" and just
    // retries blind on its own fixed cadence, which is what let a burst of 429s look identical to
    // an empty queue. FixedWindowRateLimiter supplies this metadata automatically on rejection.
    options.OnRejected = (context, _) =>
    {
        if (context.Lease.TryGetMetadata(MetadataName.RetryAfter, out var retryAfter))
            context.HttpContext.Response.Headers.RetryAfter = ((int)retryAfter.TotalSeconds).ToString();
        return ValueTask.CompletedTask;
    };

    options.AddPolicy("api", httpContext =>
    {
        var path = httpContext.Request.Path;
        var isAdminAuth = adminAuthPathPrefixes.Any(prefix => path.StartsWithSegments(prefix));

        if (path.StartsWithSegments("/dashboard") || path.StartsWithSegments("/upgrade"))
        {
            if (isAdminAuth)
            {
                return RateLimitPartition.GetFixedWindowLimiter(
                    partitionKey: "admin-auth:" + (httpContext.Connection.RemoteIpAddress?.ToString() ?? "unknown"),
                    factory: _ => new FixedWindowRateLimiterOptions
                    {
                        Window = TimeSpan.FromMinutes(1),
                        PermitLimit = 8,
                        QueueLimit = 0
                    });
            }

            return RateLimitPartition.GetNoLimiter("dashboard-unlimited");
        }

        if (isAdminAuth)
        {
            // Partitioned per client IP, not global, so one admin's dashboard use can't lock out
            // another's login attempt.
            return RateLimitPartition.GetFixedWindowLimiter(
                partitionKey: "admin-auth:" + (httpContext.Connection.RemoteIpAddress?.ToString() ?? "unknown"),
                factory: _ => new FixedWindowRateLimiterOptions
                {
                    Window = TimeSpan.FromMinutes(1),
                    PermitLimit = 8,
                    QueueLimit = 0
                });
        }

        // Ordinary app/device traffic (claim, task results, wallet/settings polling, etc.) is no
        // longer capped — a single device legitimately running several linked accounts at once
        // shares one X-Device-Id, and all of those accounts' claim/result/poll calls landed in the
        // same per-device bucket, so real multi-account usage could exhaust a 120/min budget and
        // get 429'd as if it were abuse. Only the admin-auth branches above still enforce a limit
        // (brute-force protection on a secret-only login, not something this app's own traffic
        // should ever hit).
        return RateLimitPartition.GetNoLimiter("api-unlimited");
    });
});

// ---------- MVC + Swagger ----------
// Enums travel as their names ("Like", "Pending") — the Android client reads them as strings.
builder.Services.AddControllers().AddJsonOptions(o =>
    o.JsonSerializerOptions.Converters.Add(new JsonStringEnumConverter()));
builder.Services.AddEndpointsApiExplorer();
builder.Services.AddSwaggerGen(c =>
{
    c.SwaggerDoc("v1", new OpenApiInfo { Title = "FeedPilot API", Version = "v1" });
    c.AddSecurityDefinition("Bearer", new OpenApiSecurityScheme
    {
        In = ParameterLocation.Header,
        Description = "Enter: Bearer {token}",
        Name = "Authorization",
        Type = SecuritySchemeType.ApiKey
    });
    c.AddSecurityRequirement(new OpenApiSecurityRequirement
    {
        {
            new OpenApiSecurityScheme
            {
                Reference = new OpenApiReference { Type = ReferenceType.SecurityScheme, Id = "Bearer" }
            },
            Array.Empty<string>()
        }
    });
});

builder.Services.AddCors(o => o.AddDefaultPolicy(p =>
    p.AllowAnyHeader().AllowAnyMethod().AllowAnyOrigin()));

// Render terminates TLS at its proxy and forwards over HTTP, so trust the X-Forwarded-* headers
// to recover the real scheme and client IP.
builder.Services.Configure<ForwardedHeadersOptions>(o =>
{
    o.ForwardedHeaders = ForwardedHeaders.XForwardedFor | ForwardedHeaders.XForwardedProto;
    o.KnownNetworks.Clear();
    o.KnownProxies.Clear();
});

var app = builder.Build();

// ---------- Middleware ----------
app.UseForwardedHeaders();

// First, so its "after next()" timing/status capture wraps everything downstream — the rate
// limiter, request signing, auth, and the controller itself — and logs the real outcome instead
// of whatever the request looked like on the way in. No-ops immediately when disabled.
app.UseTelegramRequestLogging();

if (app.Environment.IsDevelopment())
{
    app.UseSwagger();
    app.UseSwaggerUI();
}

app.UseCors();
// Serves the order-management dashboard from wwwroot (see wwwroot/dashboard/index.html).
// no-store: the dashboard ships as one unversioned index.html, so without this a mobile
// browser's normal heuristic caching can keep serving yesterday's build after a deploy —
// it looks like a UI bug (or a fix that "didn't take") when it is really just a stale copy.
app.UseStaticFiles(new StaticFileOptions
{
    OnPrepareResponse = ctx =>
    {
        ctx.Context.Response.Headers.CacheControl = "no-store, must-revalidate";
    }
});
app.UseRateLimiter();
// Before auth: an unsigned call should never even reach the JWT/admin-key check, let alone a
// controller. Exempts /api/admin/** — see UseRequestSigning.
app.UseRequestSigning();
app.UseAuthentication();
app.UseAuthorization();
app.MapControllers().RequireRateLimiting("api");
app.MapHub<FeedPilot.Api.Hubs.CoinSyncHub>("/hubs/coin-sync");
app.MapGet("/", () => Results.Ok(new { service = "FeedPilot API", status = "ok" }));
app.MapGet("/health", () => Results.Ok(new { status = "healthy", service = "FeedPilot API", checkedAt = DateTime.UtcNow }));
app.MapGet("/dashboard", () => Results.Redirect("/dashboard/index.html"));

// Lets the local dashboard connect without pasting the admin key. Development-only on
// purpose: in any other environment this endpoint does not exist, so the key must be entered
// and is never handed out over the wire.
if (app.Environment.IsDevelopment())
{
    app.MapGet("/dashboard/dev-key", (Microsoft.Extensions.Options.IOptions<AdminSettings> admin) =>
        Results.Ok(new { apiKey = admin.Value.ApiKey }));
}

app.UseExceptionHandler(appError =>
{
    appError.Run(async context =>
    {
        context.Response.StatusCode = StatusCodes.Status500InternalServerError;
        context.Response.ContentType = "application/json";
        var contextFeature = context.Features.Get<Microsoft.AspNetCore.Diagnostics.IExceptionHandlerFeature>();
        if (contextFeature != null)
        {
            var logger = context.RequestServices.GetRequiredService<ILogger<Program>>();
            logger.LogError(contextFeature.Error, "Global Exception Handler caught error: {Message}", contextFeature.Error.Message);
            await context.Response.WriteAsJsonAsync(new
            {
                error = "Internal Server Error",
                message = contextFeature.Error.Message
            });
        }
    });
});

// ---------- Migrate / seed ----------
using (var scope = app.Services.CreateScope())
{
    var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
    db.Database.EnsureCreated();
    DatabaseSchemaMigrator.EnsureSchemaUpToDate(db);

    var storage = scope.ServiceProvider.GetRequiredService<IUpgradeAssetStorage>();
    await storage.MigrateLocalToB2Async();
    await SeedBundledAppReleaseAsync(db, app.Environment.WebRootPath);
}

MigrateLegacyUpgradeAssets(app.Environment.WebRootPath);

app.Run();

// One-time move of local upgrade assets from the pre-gender flat layout
// (wwwroot/upgrade/{type}/{index}.ext) into wwwroot/upgrade/male/{type}/{index}.ext, the new
// gendered layout — see UpgradeAssetStorage. Local-disk only (B2 isn't configured in this
// project); a no-op once the legacy folders no longer exist, so safe to run on every startup.
static void MigrateLegacyUpgradeAssets(string webRootPath)
{
    if (string.IsNullOrWhiteSpace(webRootPath)) return;

    var upgradeDir = Path.Combine(webRootPath, "upgrade");
    if (!Directory.Exists(upgradeDir)) return;

    foreach (var type in new[] { "posts", "profiles", "stories", "bios" })
    {
        var legacyDir = Path.Combine(upgradeDir, type);
        if (!Directory.Exists(legacyDir)) continue;

        var targetDir = Path.Combine(upgradeDir, "male", type);
        Directory.CreateDirectory(targetDir);

        foreach (var file in Directory.EnumerateFiles(legacyDir))
        {
            var destination = Path.Combine(targetDir, Path.GetFileName(file));
            if (!File.Exists(destination))
                File.Move(file, destination);
        }

        if (!Directory.EnumerateFileSystemEntries(legacyDir).Any())
            Directory.Delete(legacyDir);
    }
}

static async Task SeedBundledAppReleaseAsync(AppDbContext db, string webRootPath)
{
    if (string.IsNullOrWhiteSpace(webRootPath)) return;

    var metadataPath = Path.Combine(webRootPath, "apk", "update-metadata.json");
    if (!File.Exists(metadataPath)) return;

    await using var stream = File.OpenRead(metadataPath);
    var metadata = await JsonSerializer.DeserializeAsync<BundledReleaseMetadata>(
        stream,
        new JsonSerializerOptions { PropertyNameCaseInsensitive = true });
    if (metadata is null || metadata.VersionCode <= 0 || string.IsNullOrWhiteSpace(metadata.ApkUrl))
        return;

    var existing = await db.AppReleases.FirstOrDefaultAsync(r => r.VersionCode == metadata.VersionCode);
    if (existing is null)
    {
        db.AppReleases.Add(new AppRelease
        {
            VersionCode = metadata.VersionCode,
            VersionName = metadata.VersionName,
            ApkUrl = metadata.ApkUrl,
            Sha256 = metadata.Sha256,
            SizeBytes = metadata.SizeBytes,
            ReleaseNotes = metadata.ReleaseNotes,
            ForceUpdate = metadata.ForceUpdate
        });
    }
    else
    {
        existing.VersionName = metadata.VersionName;
        existing.ApkUrl = metadata.ApkUrl;
        existing.Sha256 = metadata.Sha256;
        existing.SizeBytes = metadata.SizeBytes;
        existing.ReleaseNotes = metadata.ReleaseNotes;
        existing.ForceUpdate = metadata.ForceUpdate;
    }

    await db.SaveChangesAsync();
}

public sealed record BundledReleaseMetadata(
    int VersionCode,
    string VersionName,
    string ApkUrl,
    string Sha256,
    long SizeBytes,
    string? ReleaseNotes,
    bool ForceUpdate);
