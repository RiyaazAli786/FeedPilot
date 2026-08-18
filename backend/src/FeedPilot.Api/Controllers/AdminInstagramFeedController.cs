using Microsoft.AspNetCore.Mvc;
using FeedPilot.Api.Dtos;
using FeedPilot.Api.Services;

namespace FeedPilot.Api.Controllers;

[ApiController]
[AdminSession]
[Route("api/feedpilot/feed")]
public class AdminInstagramFeedController : ControllerBase
{
    private readonly IInstagramFeedService _instagramFeed;

    public AdminInstagramFeedController(IInstagramFeedService instagramFeed)
    {
        _instagramFeed = instagramFeed;
    }

    [HttpGet("{username}")]
    public async Task<ActionResult<InstagramJsonFeedResponse>> RssJson(
        string username,
        [FromQuery] int limit = 12,
        CancellationToken ct = default)
    {
        if (string.IsNullOrWhiteSpace(username))
            return BadRequest(new ApiError("Instagram username is required.", "INVALID_USERNAME"));

        try
        {
            var feed = await _instagramFeed.FetchJsonFeedAsync(username, limit, ct);
            return Ok(feed);
        }
        catch (ArgumentException ex)
        {
            return BadRequest(new ApiError(ex.Message, "INVALID_USERNAME"));
        }
        catch (InvalidOperationException ex) when (ex.Message.StartsWith("No active Instagram", StringComparison.OrdinalIgnoreCase))
        {
            return StatusCode(StatusCodes.Status503ServiceUnavailable, new ApiError(ex.Message, "NO_ACCOUNT_SESSION"));
        }
        catch (InvalidOperationException ex)
        {
            return NotFound(new ApiError(ex.Message, "INSTAGRAM_PROFILE_NOT_FOUND"));
        }
    }
}
