using System.Diagnostics;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Threading.Channels;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Options;

namespace FeedPilot.Api.Services;

public class TelegramSettings
{
    /// <summary>
    /// Master switch, separate from BotToken/ChatId, so an operator can pause/resume logging from
    /// a Render env var (Telegram__Enabled) without touching the bot credentials.
    /// </summary>
    public bool Enabled { get; set; }

    /// <summary>Bot token from @BotFather. Set via Telegram__BotToken — never commit a real one.</summary>
    public string BotToken { get; set; } = string.Empty;

    /// <summary>
    /// Numeric chat id of the destination group (e.g. -1001234567890), not the invite link — the
    /// Bot API has no way to resolve an invite link to a chat id. Set via Telegram__ChatId.
    /// </summary>
    public string ChatId { get; set; } = string.Empty;
}

/// <summary>
/// Posts a one-line summary of an API request to a Telegram group for live monitoring. Fully
/// optional and never allowed to affect the actual request/response: every failure here is
/// caught and logged locally instead of surfacing to the caller. Disabled unless Enabled is true
/// and both BotToken and ChatId are set.
/// </summary>
public class TelegramRequestLogger : BackgroundService
{
    private readonly TelegramSettings _settings;
    private readonly IHttpClientFactory _http;
    private readonly ILogger<TelegramRequestLogger> _logger;
    private readonly Channel<string> _channel = Channel.CreateBounded<string>(
        new BoundedChannelOptions(500)
        {
            FullMode = BoundedChannelFullMode.DropOldest,
            SingleReader = true,
            SingleWriter = false
        });

    public TelegramRequestLogger(
        IOptions<TelegramSettings> settings,
        IHttpClientFactory http,
        ILogger<TelegramRequestLogger> logger)
    {
        _settings = settings.Value;
        _http = http;
        _logger = logger;
    }

    public bool IsEnabled =>
        _settings.Enabled &&
        !string.IsNullOrWhiteSpace(_settings.BotToken) &&
        !string.IsNullOrWhiteSpace(_settings.ChatId);

    /// <summary>Fire-and-forget from the caller's point of view — always returns, never throws.</summary>
    public Task LogRequestAsync(
        string method, string path, int statusCode, long elapsedMs,
        string appId, string deviceModel, string deviceId, string? responseBody = null)
    {
        var emoji = statusCode switch
        {
            >= 200 and < 300 => "✅",
            >= 400 and < 500 => "⚠️",
            >= 500 => "🔥",
            _ => "ℹ️"
        };

        var lines = new List<string>
        {
            $"{emoji} <b>{Escape(method)}</b>  <code>{Escape(path)}</code>",
            $"Status: <b>{statusCode}</b>   Time: <code>{elapsedMs}ms</code>"
        };
        var snippet = RedactAndTruncate(responseBody);
        if (snippet is not null)
            lines.Add($"Response: <code>{Escape(snippet)}</code>");
        lines.Add(DeviceLine(appId, deviceModel, deviceId));
        return SendAsync(string.Join("\n", lines));
    }

    /// <summary>
    /// Relays one direct-to-Instagram HTTP call's outcome, reported by a device via
    /// <c>POST /api/log/instagram-call</c> — those calls go straight from the device to
    /// instagram.com (see InstagramWebClient.kt on the client) and never touch this backend
    /// otherwise, so without this relay they are invisible here.
    /// </summary>
    public Task LogClientApiCallAsync(
        string method, string url, int statusCode, string? responseBody,
        string appId, string deviceModel, string deviceId)
    {
        var emoji = statusCode switch
        {
            >= 200 and < 300 => "📷✅",
            >= 400 and < 500 => "📷⚠️",
            >= 500 => "📷🔥",
            _ => "📷ℹ️"
        };

        var lines = new List<string>
        {
            $"{emoji} <b>Instagram {Escape(method)}</b>  <code>{Escape(url)}</code>",
            $"Status: <b>{statusCode}</b>"
        };
        var snippet = RedactAndTruncate(responseBody);
        if (snippet is not null)
            lines.Add($"Response: <code>{Escape(snippet)}</code>");
        lines.Add(DeviceLine(appId, deviceModel, deviceId));
        return SendAsync(string.Join("\n", lines));
    }

    /// <summary>
    /// Reports one Instagram engagement attempt's actual outcome — not the backend's own HTTP
    /// status (that's <see cref="LogRequestAsync"/>), but what Instagram itself said when a device
    /// tried to follow/like on the account's behalf: rate-limited, checkpointed, logged-out
    /// session, or a plain success. <paramref name="message"/> is the device's own report — see
    /// IgActionResult.reason on the Android side — so this reads as Instagram's actual response,
    /// not a guess. <paramref name="appId"/>/<paramref name="deviceModel"/>/<paramref name="deviceId"/>
    /// identify which install actually performed the action, same as the request log.
    /// </summary>
    public Task LogInstagramActivityAsync(
        string taskType, string target, string accountUsername, bool success, string? message,
        string appId, string deviceModel, string deviceId)
    {
        var emoji = success ? "✅" : "❌";
        var lines = new List<string>
        {
            $"{emoji} <b>{Escape(taskType)}</b>",
            $"Account: <b>@{Escape(accountUsername)}</b>   Target: <code>@{Escape(target)}</code>"
        };
        if (!string.IsNullOrWhiteSpace(message))
            lines.Add($"Reason: {Escape(message)}");
        lines.Add(DeviceLine(appId, deviceModel, deviceId));

        return SendAsync(string.Join("\n", lines));
    }

    /// <summary>
    /// Logs an outbound SMM panel status update. These requests do not pass through ASP.NET
    /// middleware because the backend sends them with HttpClient, so request logging would never
    /// see them unless the caller records them explicitly.
    /// </summary>
    public Task LogSmmPanelUpdateAsync(string url, int statusCode, string payloadJson, string? responseBody)
    {
        var emoji = statusCode switch
        {
            >= 200 and < 300 => "✅",
            >= 400 and < 500 => "⚠️",
            >= 500 => "🔥",
            _ => "ℹ️"
        };

        var lines = new List<string>
        {
            $"{emoji} <b>SMM /orders/update</b>",
            $"URL: <code>{Escape(url)}</code>",
            $"Status: <b>{statusCode}</b>",
            $"Payload: <code>{Escape(RedactAndTruncate(payloadJson) ?? payloadJson)}</code>"
        };

        var snippet = RedactAndTruncate(responseBody);
        if (snippet is not null)
            lines.Add($"Response: <code>{Escape(snippet)}</code>");

        return SendAsync(string.Join("\n", lines));
    }

    /// <summary>
    /// Shows the device's manufacturer + model (e.g. "samsung SM-G991B") rather than the opaque
    /// hardware-hash device id — that hash is what the backend binds accounts to, but it's
    /// meaningless to a human reading the log. Falls back to the id only for app builds that
    /// predate the X-Device-Model header.
    /// </summary>
    private static string DeviceLine(string appId, string deviceModel, string deviceId)
    {
        var device = !string.IsNullOrWhiteSpace(deviceModel) ? deviceModel : Or(deviceId, "-");
        return $"App: <code>{Escape(Or(appId, "-"))}</code>   Device: <code>{Escape(device)}</code>";
    }

    private static string Or(string? value, string fallback) =>
        string.IsNullOrWhiteSpace(value) ? fallback : value;

    /// <summary>
    /// Matches a JSON string field whose *key* looks like a credential/secret — access or
    /// refresh tokens, passwords (including Instagram's encrypted enc_password login field),
    /// session cookies, CSRF tokens, API keys — case-insensitively, regardless of formatting.
    /// </summary>
    private static readonly Regex SensitiveField = new(
        "\"(?<key>[a-zA-Z_]*(access|refresh)?_?(token|password|secret|api_?key|session_?cookies|sessionid|csrftoken|authorization)[a-zA-Z_]*)\"\\s*:\\s*\"(?:[^\"\\\\]|\\\\.)*\"",
        RegexOptions.IgnoreCase | RegexOptions.Compiled);

    private const int MaxSnippetLength = 700;

    /// <summary>
    /// Never forward a response body to Telegram verbatim: it may carry a JWT, an Instagram
    /// session cookie, or a raw password field, any of which would hand out a live credential to
    /// everyone in the monitoring chat. This redacts known-sensitive JSON fields first and only
    /// then truncates, so a secret can never survive by sitting past the truncation cut — the
    /// redaction always runs over the whole body before it's ever shortened.
    /// </summary>
    private static string? RedactAndTruncate(string? body)
    {
        if (string.IsNullOrWhiteSpace(body)) return null;
        var redacted = SensitiveField.Replace(body, m => $"\"{m.Groups["key"].Value}\":\"[redacted]\"");
        return redacted.Length > MaxSnippetLength
            ? redacted[..MaxSnippetLength] + "…"
            : redacted;
    }

    /// <summary>Telegram's HTML parse_mode only recognizes a handful of tags — anything else in
    /// dynamic content (a username, an Instagram error string) must be escaped or a stray
    /// "&lt;"/"&amp;" breaks the whole message instead of just displaying literally.</summary>
    private static string Escape(string value) =>
        value.Replace("&", "&amp;").Replace("<", "&lt;").Replace(">", "&gt;");

    public Task LogOrderProgressDiagnosticAsync(
        Guid orderId, string? deviceId, Guid? accountId, int before, int reportedCompleted,
        int newCompletedCount, int newlyDone, int quantity, bool failedReport)
    {
        var status = failedReport ? "Failed" : newlyDone > 0 ? "Awarded" : "Zero";
        var lines = new List<string>
        {
            $"<b>Order Progress Diag</b> ({Escape(status)})",
            $"Order: <code>{orderId}</code>",
            $"Before: <b>{before}</b>   Reported: <b>{reportedCompleted}</b>   New: <b>{newCompletedCount}</b>/<b>{quantity}</b>",
            $"NewlyDone: <b>{newlyDone}</b>   Failed: <b>{failedReport}</b>"
        };
        if (accountId is { } acc) lines.Add($"Account: <code>{acc}</code>");
        if (!string.IsNullOrWhiteSpace(deviceId)) lines.Add($"Device: <code>{Escape(deviceId)}</code>");
        return SendAsync(string.Join("\n", lines));
    }

    public Task LogClientCrashAsync(
        string title, string? summary, string stackTrace,
        string appId, string deviceModel, string deviceId)
    {
        var lines = new List<string>
        {
            $"💥 <b>CLIENT CRASH REPORT</b>",
            $"Error: <b>{Escape(title)}</b>"
        };
        if (!string.IsNullOrWhiteSpace(summary))
            lines.Add($"Summary: <code>{Escape(summary)}</code>");

        var snippet = RedactAndTruncate(stackTrace);
        if (snippet is not null)
            lines.Add($"Trace: <code>{Escape(snippet)}</code>");

        lines.Add(DeviceLine(appId, deviceModel, deviceId));
        return SendAsync(string.Join("\n", lines));
    }

    /// <summary>Fire-and-forget from the caller's point of view — enqueues and always returns immediately.</summary>
    private Task SendAsync(string text)
    {
        if (!IsEnabled) return Task.CompletedTask;
        _channel.Writer.TryWrite(text);
        return Task.CompletedTask;
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        if (!IsEnabled) return;

        string? pending = null;

        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                string current;
                if (pending != null)
                {
                    current = pending;
                    pending = null;
                }
                else
                {
                    current = await _channel.Reader.ReadAsync(stoppingToken);
                }

                // Batch items up to ~3500 chars to stay safely within Telegram's 4096 char limit
                // and minimize the number of API calls made to Telegram.
                var batch = new StringBuilder(current);
                while (_channel.Reader.TryRead(out var next))
                {
                    if (batch.Length + next.Length + 25 > 3800)
                    {
                        pending = next;
                        break;
                    }
                    batch.Append("\n\n────────────────\n\n");
                    batch.Append(next);
                }

                await SendWithThrottlingAndRetryAsync(batch.ToString(), stoppingToken);

                // Telegram strictly enforces ~1 message per second to a chat.
                await Task.Delay(1100, stoppingToken);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex)
            {
                _logger.LogWarning(ex, "Unexpected error in Telegram log background processor");
                await Task.Delay(2000, stoppingToken);
            }
        }
    }

    private async Task SendWithThrottlingAndRetryAsync(string text, CancellationToken stoppingToken)
    {
        const int maxRetries = 3;
        for (var attempt = 1; attempt <= maxRetries; attempt++)
        {
            try
            {
                var client = _http.CreateClient("Telegram");
                var body = JsonSerializer.Serialize(new
                {
                    chat_id = _settings.ChatId,
                    text,
                    parse_mode = "HTML",
                    disable_web_page_preview = true
                });
                using var content = new StringContent(body, Encoding.UTF8, "application/json");

                var response = await client.PostAsync(
                    $"https://api.telegram.org/bot{_settings.BotToken}/sendMessage",
                    content,
                    stoppingToken);

                if (response.IsSuccessStatusCode)
                {
                    return;
                }

                var responseBody = await response.Content.ReadAsStringAsync(stoppingToken);

                // Handle HTTP 429 Too Many Requests with dynamic backoff
                if ((int)response.StatusCode == 429)
                {
                    var retryAfter = ExtractRetryAfter(responseBody);
                    _logger.LogWarning(
                        "Telegram rate limit hit (429). Retrying after {Seconds}s (attempt {Attempt}/{Max})",
                        retryAfter, attempt, maxRetries);

                    await Task.Delay(TimeSpan.FromSeconds(retryAfter + 1), stoppingToken);
                    continue;
                }

                // For permanent errors (e.g. 400 Bad Request if HTML syntax failed), don't loop endlessly
                _logger.LogWarning(
                    "Telegram log post failed: {Status} {Body}",
                    response.StatusCode, responseBody);
                return;
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                return;
            }
            catch (Exception ex) when (attempt < maxRetries)
            {
                _logger.LogWarning(ex, "Telegram post attempt {Attempt} failed, retrying in 2s", attempt);
                await Task.Delay(2000, stoppingToken);
            }
            catch (Exception ex)
            {
                _logger.LogWarning(ex, "Telegram log post failed after {Max} attempts", maxRetries);
            }
        }
    }

    private static int ExtractRetryAfter(string json)
    {
        try
        {
            using var doc = JsonDocument.Parse(json);
            if (doc.RootElement.TryGetProperty("parameters", out var pars) &&
                pars.TryGetProperty("retry_after", out var val) &&
                val.TryGetInt32(out var sec))
            {
                return Math.Clamp(sec, 1, 120);
            }
        }
        catch
        {
            // fallback
        }
        return 15;
    }
}

/// <summary>
/// Times every request it wraps and hands the outcome (method, path, final status, duration) to
/// <see cref="TelegramRequestLogger"/> after the rest of the pipeline has run — so the status code
/// reflects whatever downstream actually decided (rate limit, signature check, auth, controller),
/// not just the entry state.
/// </summary>
public class TelegramLoggingMiddleware
{
    private readonly RequestDelegate _next;
    public TelegramLoggingMiddleware(RequestDelegate next) => _next = next;

    public async Task InvokeAsync(HttpContext context, TelegramRequestLogger telegram)
    {
        if (!telegram.IsEnabled)
        {
            await _next(context);
            return;
        }

        var method = context.Request.Method;
        var path = context.Request.Path.Value ?? "";
        var appId = context.Request.Headers["X-App-Id"].ToString();
        var deviceModel = context.Request.Headers["X-Device-Model"].ToString();
        var deviceId = context.Request.Headers["X-Device-Id"].ToString();
        var sw = Stopwatch.StartNew();

        // The response stream can only be read once, and the real client still needs to receive
        // it — so the pipeline is pointed at a buffer instead, and that buffer is copied back to
        // the real response afterwards. Capped well below MaxSnippetLength worth of bytes so a
        // huge response (an accounts list, an orders page) doesn't get fully buffered in memory
        // just to log a few hundred characters of it.
        var originalBody = context.Response.Body;
        await using var buffer = new MemoryStream();
        context.Response.Body = buffer;

        string? responseSnippet = null;
        try
        {
            await _next(context);
        }
        finally
        {
            sw.Stop();
            buffer.Seek(0, SeekOrigin.Begin);
            responseSnippet = await ReadSnippetAsync(buffer);
            buffer.Seek(0, SeekOrigin.Begin);
            context.Response.Body = originalBody;
            await buffer.CopyToAsync(originalBody);

            // Not awaited — a slow/unreachable Telegram must never add latency to the API's own
            // response, and the response has already been written by the time this runs anyway.
            _ = telegram.LogRequestAsync(
                method, path, context.Response.StatusCode, sw.ElapsedMilliseconds, appId, deviceModel, deviceId,
                responseSnippet);
        }
    }

    private static async Task<string?> ReadSnippetAsync(MemoryStream buffer)
    {
        if (buffer.Length == 0) return null;
        const int maxBytes = 2048; // redaction needs the whole JSON field, not just the truncated tail
        var len = (int)Math.Min(buffer.Length, maxBytes);
        var bytes = new byte[len];
        var read = 0;
        while (read < len)
        {
            var n = await buffer.ReadAsync(bytes.AsMemory(read, len - read));
            if (n == 0) break;
            read += n;
        }
        var text = Encoding.UTF8.GetString(bytes, 0, read);
        return buffer.Length > maxBytes ? text + "…" : text;
    }
}

public static class TelegramLoggingMiddlewareExtensions
{
    /// <summary>Applies to every /api/** request, including /api/admin/** — see TelegramSettings.</summary>
    public static IApplicationBuilder UseTelegramRequestLogging(this IApplicationBuilder app) =>
        app.UseWhen(
            ctx => ctx.Request.Path.StartsWithSegments("/api") &&
                   !ctx.Request.Path.StartsWithSegments("/api/log"),
            branch => branch.UseMiddleware<TelegramLoggingMiddleware>());
}
