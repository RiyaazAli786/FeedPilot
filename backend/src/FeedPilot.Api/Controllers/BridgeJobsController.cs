using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using FeedPilot.Api.Domain;
using FeedPilot.Api.Dtos;
using FeedPilot.Api.Services;

namespace FeedPilot.Api.Controllers;

[ApiController]
[Authorize]
[Route("api/bridge")]
public class BridgeJobsController : ControllerBase
{
    private readonly IFeedBridgeService _bridge;

    public BridgeJobsController(IFeedBridgeService bridge)
    {
        _bridge = bridge;
    }

    [HttpPost("devices/heartbeat")]
    public async Task<ActionResult<BridgeHeartbeatResponse>> Heartbeat(
        [FromBody] BridgeHeartbeatRequest request,
        CancellationToken ct)
    {
        var identity = this.ClientIdentity();
        var response = await _bridge.HeartbeatAsync(User.GetUserId(), identity.AppId, request, ct);
        return Ok(response);
    }

    [HttpGet("jobs/poll")]
    public async Task<ActionResult<BridgeJobDto?>> Poll(
        [FromQuery] string? deviceId,
        [FromQuery] string? installationId,
        CancellationToken ct)
    {
        var identity = this.ClientIdentity();
        var resolvedDeviceId = string.IsNullOrWhiteSpace(deviceId) ? identity.DeviceId : deviceId.Trim();
        var resolvedInstallationId = string.IsNullOrWhiteSpace(installationId) ? identity.InstallationId : installationId.Trim();
        if (string.IsNullOrWhiteSpace(resolvedDeviceId) || resolvedDeviceId == ClientIdentityDefaults.Unknown)
            return BadRequest(new ApiError("Device id is required.", "MISSING_DEVICE_ID"));

        var job = await _bridge.PollAsync(identity.AppId, resolvedDeviceId, resolvedInstallationId, ct);
        return Ok(job);
    }

    [HttpPost("jobs/{id:guid}/complete")]
    public async Task<ActionResult<BridgeJobResultDto>> Complete(
        Guid id,
        [FromBody] CompleteBridgeJobRequest request,
        CancellationToken ct)
    {
        var identity = this.ClientIdentity();
        var result = await _bridge.CompleteAsync(id, identity.AppId, identity.DeviceId, request, ct);
        return result.Accepted ? Ok(result) : NotFound(result);
    }

    [HttpPost("jobs/{id:guid}/fail")]
    public async Task<ActionResult<BridgeJobResultDto>> Fail(
        Guid id,
        [FromBody] FailBridgeJobRequest request,
        CancellationToken ct)
    {
        var identity = this.ClientIdentity();
        var result = await _bridge.FailAsync(id, identity.AppId, identity.DeviceId, request, ct);
        return result.Accepted ? Ok(result) : NotFound(result);
    }
}
