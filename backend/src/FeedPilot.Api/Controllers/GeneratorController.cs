using Microsoft.AspNetCore.Mvc;
using System.Text.Json;

namespace FeedPilot.Api.Controllers
{
    [ApiController]
    [Route("api/[controller]")]
    public class GeneratorController : ControllerBase
    {
        private readonly IWebHostEnvironment _env;
        private static readonly object _fileLock = new();

        public GeneratorController(IWebHostEnvironment env)
        {
            _env = env;
        }

        private string GetGeneratorDirectory()
        {
            var dir = Path.Combine(_env.WebRootPath ?? Path.Combine(Directory.GetCurrentDirectory(), "wwwroot"), "generator");
            if (!Directory.Exists(dir))
            {
                Directory.CreateDirectory(dir);
            }
            return dir;
        }

        private string GetDatasetPath() => Path.Combine(GetGeneratorDirectory(), "dataset.json");
        private string GetExcelPath() => Path.Combine(GetGeneratorDirectory(), "Brazil_Instagram_Username_Generator.xlsx");

        [HttpGet("dataset")]
        public IActionResult GetDataset()
        {
            lock (_fileLock)
            {
                var path = GetDatasetPath();
                if (!System.IO.File.Exists(path))
                {
                    // Default fallback dataset if file doesn't exist yet
                    var defaultData = new GeneratorDatasetDto
                    {
                        FirstNames = new List<string> { "Caio", "Lucas", "Enzo", "Kauan", "Vini", "Pedro", "Arthur", "Bruno", "Rafael", "Murilo" },
                        LastNames = new List<string> { "Almeida", "Santos", "Oliveira", "Costa", "Mendes", "Rocha", "Lima", "Martins", "Souza", "Ferreira" },
                        Suffixes = new List<string> { "zenn", "vex", "vyn", "zaro", "zyn", "vexo", "xyn", "zov", "rix", "zen", "varo", "nex", "zian", "voro", "xen", "rivo", "zelo", "vian", "nox", "zavi", "zuno", "ryx", "zayn", "viro", "xaro", "zeno", "vynx", "rizo" }
                    };
                    return Ok(defaultData);
                }

                var json = System.IO.File.ReadAllText(path);
                return Content(json, "application/json");
            }
        }

        [HttpGet("excel")]
        public IActionResult GetExcelFile()
        {
            lock (_fileLock)
            {
                var path = GetExcelPath();
                if (!System.IO.File.Exists(path))
                {
                    return NotFound("Excel file not found on server.");
                }

                var fileBytes = System.IO.File.ReadAllBytes(path);
                return File(fileBytes, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "Brazil_Instagram_Username_Generator.xlsx");
            }
        }

        [HttpPost("dataset")]
        public IActionResult UpdateDataset([FromBody] GeneratorDatasetDto dto)
        {
            if (dto == null || dto.FirstNames == null || dto.LastNames == null || dto.Suffixes == null)
            {
                return BadRequest("Invalid dataset payload. Requires firstNames, lastNames, and suffixes arrays.");
            }

            lock (_fileLock)
            {
                var path = GetDatasetPath();
                var json = JsonSerializer.Serialize(dto, new JsonSerializerOptions { WriteIndented = true });
                System.IO.File.WriteAllText(path, json);
                return Ok(new { message = "Dataset updated successfully", totalFirstNames = dto.FirstNames.Count, totalLastNames = dto.LastNames.Count, totalSuffixes = dto.Suffixes.Count });
            }
        }

        [HttpPost("upload")]
        public IActionResult UploadExcel(IFormFile file)
        {
            if (file == null || file.Length == 0 || !file.FileName.EndsWith(".xlsx", StringComparison.OrdinalIgnoreCase))
            {
                return BadRequest("Please upload a valid .xlsx Excel file.");
            }

            lock (_fileLock)
            {
                var path = GetExcelPath();
                using var stream = new FileStream(path, FileMode.Create);
                file.CopyTo(stream);
            }

            return Ok(new { message = "Excel file uploaded and stored on server successfully." });
        }
    }

    public class GeneratorDatasetDto
    {
        public List<string> FirstNames { get; set; } = new();
        public List<string> LastNames { get; set; } = new();
        public List<string> Suffixes { get; set; } = new();
    }
}
