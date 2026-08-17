using Microsoft.AspNetCore.Mvc;
using FeedPilot.Api.Services;

namespace FeedPilot.Api.Controllers;

/// <summary>
/// Admin / system endpoint to manage comment templates stored on the server.
/// Serves comment orders (such as SMM Service #177) with pre-defined comment lines.
/// </summary>
[ApiController]
[Route("api/[controller]")]
public class CommentsController : ControllerBase
{
    private readonly CommentFileService _commentFileService;

    public CommentsController(CommentFileService commentFileService)
    {
        _commentFileService = commentFileService;
    }

    /// <summary>
    /// Gets the current list of comment lines from the server file for the specified service ID (default 177).
    /// </summary>
    [HttpGet]
    public IActionResult GetComments([FromQuery] int serviceId = 177)
    {
        var comments = _commentFileService.ReadCommentsFromFile(serviceId);
        return Ok(new
        {
            serviceId,
            totalComments = comments.Count,
            comments
        });
    }

    /// <summary>
    /// Updates the comments list on the server for the specified service ID.
    /// </summary>
    [HttpPost]
    public IActionResult UpdateComments([FromBody] List<string> comments, [FromQuery] int serviceId = 177)
    {
        if (comments == null || comments.Count == 0)
        {
            return BadRequest(new { message = "Comments list cannot be empty." });
        }

        _commentFileService.SaveCommentsToFile(comments, serviceId);
        return Ok(new
        {
            message = "Comments updated successfully.",
            serviceId,
            totalComments = comments.Count
        });
    }

    /// <summary>
    /// Uploads a comment text file (.txt) where each line represents one comment.
    /// </summary>
    [HttpPost("upload")]
    public async Task<IActionResult> UploadCommentsFile(IFormFile file, [FromQuery] int serviceId = 177)
    {
        if (file == null || file.Length == 0)
        {
            return BadRequest(new { message = "Please upload a valid text file containing comments." });
        }

        var comments = new List<string>();
        using (var reader = new StreamReader(file.OpenReadStream()))
        {
            while (!reader.EndOfStream)
            {
                var line = await reader.ReadLineAsync();
                if (!string.IsNullOrWhiteSpace(line))
                {
                    comments.Add(line.Trim());
                }
            }
        }

        if (comments.Count == 0)
        {
            return BadRequest(new { message = "File did not contain any non-empty comment lines." });
        }

        _commentFileService.SaveCommentsToFile(comments, serviceId);
        return Ok(new
        {
            message = "Comment file uploaded successfully.",
            serviceId,
            totalComments = comments.Count
        });
    }
}
