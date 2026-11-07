using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using FeedPilot.Api.Dtos;
using FeedPilot.Api.Services;

namespace FeedPilot.Api.Controllers;

[ApiController]
[AllowAnonymous]
[Route("api/bridge/feed")]
public class BridgeFeedController : ControllerBase
{
    private readonly IFeedBridgeService _bridge;
    private readonly IConfiguration _config;
    private readonly ILogger<BridgeFeedController> _logger;

    public BridgeFeedController(IFeedBridgeService bridge, IConfiguration config, ILogger<BridgeFeedController> logger)
    {
        _bridge = bridge;
        _config = config;
        _logger = logger;
    }

    [HttpPost]
    public async Task<ActionResult<BridgeFeedResponse>> Fetch(
        [FromBody] BridgeFeedRequest request,
        CancellationToken ct)
    {
        if (!IsBridgeRequestAllowed())
        {
            return Unauthorized(new ApiError("Invalid bridge key.", "BRIDGE_AUTH_FAILED"));
        }

        var response = await _bridge.ExecuteFeedAsync(request, ct);
        if (response.Success)
        {
            _logger.LogInformation(
                "Bridge feed resolved for target {Target}: JobId={JobId}, Device={DeviceId}, Account=@{Account}, Duration={DurationMs}ms",
                request.Username ?? request.UserId, response.JobId, response.DeviceId, response.AccountUsername, response.DurationMs);
            return Ok(response);
        }

        _logger.LogWarning(
            "Bridge feed failed for target {Target}: Code={Code}, Message={Message}, JobId={JobId}",
            request.Username ?? request.UserId, response.Code, response.Message, response.JobId);

        var status = response.Code switch
        {
            "INVALID_REQUEST" => StatusCodes.Status400BadRequest,
            "NO_ACTIVE_SESSION" => StatusCodes.Status503ServiceUnavailable,
            "JOB_TIMEOUT" => StatusCodes.Status504GatewayTimeout,
            _ => StatusCodes.Status502BadGateway
        };
        return StatusCode(status, response);
    }

    private bool IsBridgeRequestAllowed()
    {
        var expected = _config["Bridge:Key"] ?? Environment.GetEnvironmentVariable("FEEDPILOT_BRIDGE_KEY");
        if (string.IsNullOrWhiteSpace(expected)) return false;
        var supplied = Request.Headers["X-Bridge-Key"].FirstOrDefault();
        return !string.IsNullOrWhiteSpace(supplied) &&
               string.Equals(supplied, expected, StringComparison.Ordinal);
    }
}
