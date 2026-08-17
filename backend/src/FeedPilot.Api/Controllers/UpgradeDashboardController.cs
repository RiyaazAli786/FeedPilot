using Microsoft.AspNetCore.Mvc;
using FeedPilot.Api.Services;

namespace FeedPilot.Api.Controllers
{
    [ApiController]
    [Route("dashboard")]
    public class UpgradeDashboardController : ControllerBase
    {
        private readonly IUpgradeAssetStorage _storage;

        public UpgradeDashboardController(IUpgradeAssetStorage storage)
        {
            _storage = storage;
        }

        private static readonly string[] AllowedGenders = ["male", "female"];
        private static readonly string[] AllowedTypes = ["posts", "profiles", "stories", "bios", "captions"];
        // Types that only ever store a per-index .txt file, never a paired .jpg.
        private static bool IsTextOnlyType(string type) => type is "bios" or "captions";

        public class UploadPayload
        {
            public string Type { get; set; } = "posts";
            public string Gender { get; set; } = "male";
            public string? FileData { get; set; }
            public string? Text { get; set; }
        }

        [HttpPost("upload")]
        public async Task<IActionResult> Upload([FromBody] UploadPayload payload, CancellationToken ct)
        {
            try
            {
                if (string.IsNullOrWhiteSpace(payload.Type))
                    return BadRequest(new { status = "error", message = "Type is required" });

                if (Array.IndexOf(AllowedTypes, payload.Type) < 0)
                    return BadRequest(new { status = "error", message = "Invalid asset type" });

                if (Array.IndexOf(AllowedGenders, payload.Gender) < 0)
                    return BadRequest(new { status = "error", message = "Invalid gender" });

                var nextIndex = await _storage.NextIndexAsync(payload.Gender, payload.Type, ct);
                byte[]? fileBytes = null;

                if (!string.IsNullOrWhiteSpace(payload.FileData) && !IsTextOnlyType(payload.Type))
                {
                    var base64Part = payload.FileData;
                    if (base64Part.Contains(","))
                    {
                        base64Part = base64Part.Substring(base64Part.IndexOf(",") + 1);
                    }

                    fileBytes = Convert.FromBase64String(base64Part);
                }

                await _storage.SaveAsync(payload.Gender, payload.Type, nextIndex, fileBytes, payload.Text, ct);
                var saved = IsTextOnlyType(payload.Type) ? $"{nextIndex}.txt" : $"{nextIndex}.jpg";

                return Ok(new { status = "ok", path = $"upgrade/{payload.Gender}/{payload.Type}/{saved}" });
            }
            catch (Exception ex)
            {
                return StatusCode(500, new { status = "error", message = ex.Message });
            }
        }

        [HttpGet("assets")]
        public async Task<IActionResult> GetAssets([FromQuery] string gender = "male", CancellationToken ct = default)
        {
            try
            {
                if (Array.IndexOf(AllowedGenders, gender) < 0)
                    return BadRequest(new { status = "error", message = "Invalid gender" });

                var result = await _storage.ListAsync(gender, ct);
                return Ok(result);
            }
            catch (Exception ex)
            {
                return StatusCode(500, new { status = "error", message = ex.Message });
            }
        }

        [HttpGet("/upgrade/{gender}/{type}/{fileName}")]
        public async Task<IActionResult> GetAsset([FromRoute] string gender, [FromRoute] string type, [FromRoute] string fileName, CancellationToken ct)
        {
            try
            {
                var asset = await _storage.ReadAsync(gender, type, fileName, ct);
                if (asset is null) return NotFound();
                return File(asset.Bytes, asset.ContentType);
            }
            catch (Exception ex)
            {
                return StatusCode(500, new { status = "error", message = ex.Message });
            }
        }

        public class UploadZipPayload
        {
            public string Gender { get; set; } = "male";
            public string ZipData { get; set; } = string.Empty;
            public bool IsBioZip { get; set; }
            public bool IsCaptionZip { get; set; }
        }

        public class FolderFileItem
        {
            public string FileName { get; set; } = string.Empty;
            public string FileData { get; set; } = string.Empty;
        }

        public class UploadFolderImagesPayload
        {
            public string Gender { get; set; } = "male";
            public string? FolderName { get; set; }
            public List<FolderFileItem> Files { get; set; } = new();
        }

        [HttpPost("upload-zip")]
        public async Task<IActionResult> UploadZip([FromBody] UploadZipPayload payload, CancellationToken ct)
        {
            try
            {
                if (string.IsNullOrWhiteSpace(payload.ZipData))
                    return BadRequest(new { status = "error", message = "ZipData is required" });

                var base64 = payload.ZipData.Contains(",") ? payload.ZipData.Split(',')[1] : payload.ZipData;
                var bytes = Convert.FromBase64String(base64);

                if (payload.IsBioZip)
                {
                    if (Array.IndexOf(AllowedGenders, payload.Gender) < 0)
                        return BadRequest(new { status = "error", message = "Invalid gender" });

                    var count = await _storage.SaveBioZipAsync(payload.Gender, bytes, ct);
                    return Ok(new { status = "ok", message = $"Successfully imported {count} biographies from ZIP." });
                }
                else if (payload.IsCaptionZip)
                {
                    if (Array.IndexOf(AllowedGenders, payload.Gender) < 0)
                        return BadRequest(new { status = "error", message = "Invalid gender" });

                    var count = await _storage.SaveCaptionZipAsync(payload.Gender, bytes, ct);
                    return Ok(new { status = "ok", message = $"Successfully imported {count} post captions from ZIP." });
                }
                else
                {
                    var count = await _storage.SaveZipCollectionAsync(payload.Gender, bytes, ct);
                    return Ok(new { status = "ok", message = $"Successfully imported {count} profile folders from ZIP." });
                }
            }
            catch (Exception ex)
            {
                return StatusCode(500, new { status = "error", message = ex.Message });
            }
        }

        [HttpPost("upload-folder-images")]
        public async Task<IActionResult> UploadFolderImages([FromBody] UploadFolderImagesPayload payload, CancellationToken ct)
        {
            try
            {
                if (payload.Files == null || payload.Files.Count == 0)
                    return BadRequest(new { status = "error", message = "No files provided" });

                var imageList = new List<(string FileName, byte[] Bytes)>();
                foreach (var f in payload.Files)
                {
                    if (string.IsNullOrWhiteSpace(f.FileData)) continue;
                    var base64 = f.FileData.Contains(",") ? f.FileData.Split(',')[1] : f.FileData;
                    var bytes = Convert.FromBase64String(base64);
                    imageList.Add((f.FileName, bytes));
                }

                var folderName = await _storage.SaveFolderImagesAsync(payload.Gender, payload.FolderName, imageList, ct);
                return Ok(new { status = "ok", folderName, message = $"Saved {imageList.Count} images into folder '{folderName}'." });
            }
            catch (Exception ex)
            {
                return StatusCode(500, new { status = "error", message = ex.Message });
            }
        }

        [HttpGet("profile-folders")]
        public async Task<IActionResult> GetProfileFolders([FromQuery] string? gender = null, CancellationToken ct = default)
        {
            try
            {
                var folders = await _storage.ListFoldersAsync(gender, ct);
                return Ok(folders);
            }
            catch (Exception ex)
            {
                return StatusCode(500, new { status = "error", message = ex.Message });
            }
        }

        [HttpGet("/upgrade/folders/{folderName}/{fileName}")]
        [HttpGet("/upgrade/{gender}/folders/{folderName}/{fileName}")]
        public async Task<IActionResult> GetFolderAsset([FromRoute] string? gender, [FromRoute] string folderName, [FromRoute] string fileName, CancellationToken ct)
        {
            try
            {
                var asset = await _storage.ReadFolderFileAsync(gender, folderName, fileName, ct);
                if (asset is null) return NotFound();
                return File(asset.Bytes, asset.ContentType);
            }
            catch (Exception ex)
            {
                return StatusCode(500, new { status = "error", message = ex.Message });
            }
        }

        [HttpDelete("profile-folders")]
        public async Task<IActionResult> DeleteProfileFolder([FromQuery] string folderName, [FromQuery] string? gender = null, CancellationToken ct = default)
        {
            try
            {
                if (string.IsNullOrWhiteSpace(folderName))
                    return BadRequest(new { status = "error", message = "FolderName is required" });

                await _storage.DeleteFolderAsync(gender, folderName, ct);
                return Ok(new { status = "ok", message = $"Folder '{folderName}' deleted" });
            }
            catch (Exception ex)
            {
                return StatusCode(500, new { status = "error", message = ex.Message });
            }
        }

        [HttpDelete("profile-folders/file")]
        public async Task<IActionResult> DeleteProfileFolderFile([FromQuery] string folderName, [FromQuery] string fileName, [FromQuery] string? gender = null, CancellationToken ct = default)
        {
            try
            {
                if (string.IsNullOrWhiteSpace(folderName) || string.IsNullOrWhiteSpace(fileName))
                    return BadRequest(new { status = "error", message = "FolderName and FileName are required" });

                await _storage.DeleteFolderFileAsync(gender, folderName, fileName, ct);
                return Ok(new { status = "ok", message = $"File '{fileName}' deleted from folder '{folderName}'" });
            }
            catch (Exception ex)
            {
                return StatusCode(500, new { status = "error", message = ex.Message });
            }
        }

        [HttpDelete("assets")]
        public async Task<IActionResult> DeleteAsset([FromQuery] string gender, [FromQuery] string type, [FromQuery] int index, CancellationToken ct)
        {
            try
            {
                if (Array.IndexOf(AllowedTypes, type) < 0)
                    return BadRequest(new { status = "error", message = "Invalid asset type" });

                if (Array.IndexOf(AllowedGenders, gender) < 0)
                    return BadRequest(new { status = "error", message = "Invalid gender" });

                if (index <= 0)
                    return BadRequest(new { status = "error", message = "Index must be greater than 0" });

                await _storage.DeleteAsync(gender, type, index, ct);
                return Ok(new { status = "ok", message = $"Index {index} deleted" });
            }
            catch (Exception ex)
            {
                return StatusCode(500, new { status = "error", message = ex.Message });
            }
        }

        public class BulkDeletePayload
        {
            public string Type { get; set; } = "";
            public string Gender { get; set; } = "male";
            public List<int> Indices { get; set; } = new();
        }

        [HttpPost("assets/bulk-delete")]
        public async Task<IActionResult> BulkDelete([FromBody] BulkDeletePayload payload, CancellationToken ct)
        {
            try
            {
                if (Array.IndexOf(AllowedTypes, payload.Type) < 0)
                    return BadRequest(new { status = "error", message = "Invalid asset type" });

                if (Array.IndexOf(AllowedGenders, payload.Gender) < 0)
                    return BadRequest(new { status = "error", message = "Invalid gender" });

                if (payload.Indices == null || payload.Indices.Count == 0)
                    return BadRequest(new { status = "error", message = "No indices provided" });

                var sortedDesc = payload.Indices.Distinct().OrderByDescending(i => i).ToList();

                foreach (var idx in sortedDesc)
                {
                    await _storage.DeleteAsync(payload.Gender, payload.Type, idx, ct);
                }

                return Ok(new { status = "ok", message = $"Deleted {sortedDesc.Count} item(s)" });
            }
            catch (Exception ex)
            {
                return StatusCode(500, new { status = "error", message = ex.Message });
            }
        }
        [HttpGet("assign-profile-folder")]
        public async Task<IActionResult> AssignProfileFolder([FromQuery] string username, [FromQuery] string? gender = null, CancellationToken ct = default)
        {
            try
            {
                if (string.IsNullOrWhiteSpace(username))
                    return BadRequest(new { status = "error", message = "Username is required" });

                var folder = await _storage.AssignProfileFolderAsync(username, gender, ct);
                if (folder is null)
                    return NotFound(new { status = "error", message = "No profile folders available." });

                return Ok(folder);
            }
            catch (Exception ex)
            {
                return StatusCode(500, new { status = "error", message = ex.Message });
            }
        }
    }
}
