using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using FeedPilot.Api.Data;
using FeedPilot.Api.Dtos;
using FeedPilot.Api.Services;

namespace FeedPilot.Api.Controllers;

/// <summary>
/// Read-only view of the runner pacing an operator configures from the dashboard (see
/// <see cref="AdminRunnerSettingsController"/>) — every Android client polls this to pick up
/// changes without needing a per-device local setting or a new app release.
/// </summary>
[ApiController]
[Authorize]
[Route("api/runner-settings")]
public class RunnerSettingsController : ControllerBase
{
    private readonly AppDbContext _db;

    public RunnerSettingsController(AppDbContext db) => _db = db;

    [HttpGet]
    public async Task<ActionResult<RunnerSettingsDto>> Get(CancellationToken ct)
    {
        var s = await RunnerSettingsStore.GetOrCreateAsync(_db, ct);
        return Ok(AdminRunnerSettingsController.ToDto(s));
    }
}
