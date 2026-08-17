using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using FeedPilot.Api.Dtos;
using FeedPilot.Api.Services;

namespace FeedPilot.Api.Controllers;

/// <summary>
/// Relays telemetry the device can see but this backend otherwise can't — right now just the
/// outcome of the app's direct-to-Instagram HTTP calls (InstagramWebClient.kt on the client),
/// which go straight to instagram.com and never pass through this API. Requires a signed-in
/// app session so this can't be used to spam the Telegram monitoring chat from outside the app.
/// </summary>
[ApiController]
[Authorize]
[Route("api/log")]
public class ClientLogController : ControllerBase
{
    private readonly TelegramRequestLogger? _telegram;

    public ClientLogController(TelegramRequestLogger? telegram = null) => _telegram = telegram;

    [HttpPost("instagram-call")]
    public IActionResult LogInstagramCall([FromBody] ClientApiLogRequest body)
    {
        if (_telegram is { IsEnabled: true })
        {
            var appId = Request.Headers["X-App-Id"].ToString();
            var deviceModel = Request.Headers["X-Device-Model"].ToString();
            var deviceId = Request.Headers["X-Device-Id"].ToString();

            // Not awaited — same fire-and-forget contract as the rest of TelegramRequestLogger;
            // this endpoint must return immediately regardless of Telegram's own latency.
            _ = _telegram.LogClientApiCallAsync(
                body.Method, body.Url, body.StatusCode, body.ResponseSnippet, appId, deviceModel, deviceId);
        }

        return Ok();
    }

    [HttpPost("crash")]
    [AllowAnonymous]
    public IActionResult LogCrash([FromBody] ClientCrashLogRequest body)
    {
        if (_telegram is { IsEnabled: true })
        {
            var appId = Request.Headers["X-App-Id"].ToString();
            var deviceModel = Request.Headers["X-Device-Model"].ToString();
            var deviceId = Request.Headers["X-Device-Id"].ToString();

            _ = _telegram.LogClientCrashAsync(
                body.Title, body.Summary, body.StackTrace, appId, deviceModel, deviceId);
        }

        return Ok();
    }
}
