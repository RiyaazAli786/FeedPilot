using System.Security.Cryptography;
using System.Text;

namespace FeedPilot.Api.Services;

public class RequestSigningSettings
{
    /// <summary>
    /// Shared secret embedded in the Android client, used to sign every app-facing API call.
    /// Blank leaves the check unenforced — that is the state right after this ships, until the
    /// matching client build is actually the one installed. Flipping this on before then would
    /// lock out every already-installed app with no way to recover except a re-release.
    /// </summary>
    public string Secret { get; set; } = string.Empty;

    /// <summary>How far a request's timestamp may drift before it is treated as a replay.</summary>
    public int ToleranceSeconds { get; set; } = 300;
}

/// <summary>
/// Rejects app-facing API calls that were not signed by the shipped Android client, so hitting
/// the API directly (curl, Postman, a scraped JWT reused outside the app) fails even with an
/// otherwise-valid bearer token. The admin dashboard is deliberately exempt — see where this is
/// wired up in Program.cs — because it authenticates with its own admin session token instead and
/// its browser JS has no way to hold the client secret.
///
/// Verifies HMAC-SHA256(secret, "{method}:{path}:{timestamp}") sent as X-Request-Signature,
/// alongside X-Request-Timestamp (unix seconds) to bound how long a captured request stays
/// replayable. This does not cover the request body — the goal is keeping non-app callers off
/// the API, not detecting in-flight tampering, which TLS already covers.
/// </summary>
public class RequestSigningMiddleware
{
    private readonly RequestDelegate _next;
    public const string SignatureHeader = "X-Request-Signature";
    public const string TimestampHeader = "X-Request-Timestamp";

    public RequestSigningMiddleware(RequestDelegate next) => _next = next;

    public async Task InvokeAsync(HttpContext context, Microsoft.Extensions.Options.IOptions<RequestSigningSettings> options)
    {
        var settings = options.Value;

        // Unconfigured means "not enforced yet" — see the Secret doc comment. Once an operator
        // sets RequestSigning:Secret this branch stops being taken and the check goes live.
        if (string.IsNullOrWhiteSpace(settings.Secret))
        {
            await _next(context);
            return;
        }

        var signature = context.Request.Headers[SignatureHeader].ToString();
        var timestampRaw = context.Request.Headers[TimestampHeader].ToString();

        if (string.IsNullOrEmpty(signature) || string.IsNullOrEmpty(timestampRaw) ||
            !long.TryParse(timestampRaw, out var timestamp))
        {
            await Reject(context, "Missing or malformed request signature.");
            return;
        }

        var now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        if (Math.Abs(now - timestamp) > settings.ToleranceSeconds)
        {
            await Reject(context, "Request signature has expired.");
            return;
        }

        var expected = Sign(context.Request.Method, context.Request.Path.Value ?? "", timestampRaw, settings.Secret);
        if (!FixedTimeEquals(signature, expected))
        {
            await Reject(context, "Invalid request signature.");
            return;
        }

        await _next(context);
    }

    private static string Sign(string method, string path, string timestamp, string secret)
    {
        var payload = $"{method}:{path}:{timestamp}";
        using var hmac = new HMACSHA256(Encoding.UTF8.GetBytes(secret));
        var hash = hmac.ComputeHash(Encoding.UTF8.GetBytes(payload));
        return Convert.ToHexString(hash).ToLowerInvariant();
    }

    private static bool FixedTimeEquals(string a, string b)
    {
        var left = Encoding.UTF8.GetBytes(a);
        var right = Encoding.UTF8.GetBytes(b);
        return CryptographicOperations.FixedTimeEquals(SHA256.HashData(left), SHA256.HashData(right));
    }

    private static async Task Reject(HttpContext context, string message)
    {
        context.Response.StatusCode = StatusCodes.Status401Unauthorized;
        context.Response.ContentType = "application/json";
        await context.Response.WriteAsJsonAsync(new { message, code = "INVALID_REQUEST_SIGNATURE" });
    }
}

public static class RequestSigningMiddlewareExtensions
{
    /// <summary>Applies to everything except /api/admin/**, /api/generator**, /api/comments**, /api/version**, /api/apk**, and non-API paths.</summary>
    public static IApplicationBuilder UseRequestSigning(this IApplicationBuilder app) =>
        app.UseWhen(
            ctx =>
            {
                var path = ctx.Request.Path.Value ?? "";
                return path.StartsWith("/api", StringComparison.OrdinalIgnoreCase) &&
                       !path.StartsWith("/api/admin", StringComparison.OrdinalIgnoreCase) &&
                       !path.StartsWith("/api/generator", StringComparison.OrdinalIgnoreCase) &&
                       !path.StartsWith("/api/comments", StringComparison.OrdinalIgnoreCase) &&
                       !path.StartsWith("/api/version", StringComparison.OrdinalIgnoreCase) &&
                       !path.StartsWith("/api/apk", StringComparison.OrdinalIgnoreCase);
            },
            branch => branch.UseMiddleware<RequestSigningMiddleware>());
}
