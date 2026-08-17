namespace FeedPilot.Api.Services;

/// <summary>
/// Manages comment template files stored on the server (e.g. wwwroot/comments/service_177_comments.txt)
/// and provides round-robin / loop-back comment picking for SMM service #177 (or generic comment orders).
/// </summary>
public class CommentFileService
{
    private readonly IWebHostEnvironment _env;
    private static readonly object _fileLock = new();

    public CommentFileService(IWebHostEnvironment env)
    {
        _env = env;
    }

    private string GetCommentsDirectory()
    {
        var dir = Path.Combine(_env.WebRootPath ?? Path.Combine(Directory.GetCurrentDirectory(), "wwwroot"), "comments");
        if (!Directory.Exists(dir))
        {
            Directory.CreateDirectory(dir);
        }
        return dir;
    }

    public string GetFilePath(int serviceId = 177)
    {
        return Path.Combine(GetCommentsDirectory(), $"service_{serviceId}_comments.txt");
    }

    /// <summary>
    /// Reads available comments from the server file. Returns default fallback comments if file doesn't exist.
    /// </summary>
    public List<string> ReadCommentsFromFile(int serviceId = 177)
    {
        lock (_fileLock)
        {
            var path = GetFilePath(serviceId);
            if (!File.Exists(path))
            {
                // Fall back to general comments.txt if service-specific file isn't present
                var fallbackPath = Path.Combine(GetCommentsDirectory(), "comments.txt");
                if (File.Exists(fallbackPath)) path = fallbackPath;
                else return GetDefaultComments();
            }

            var lines = File.ReadAllLines(path)
                .Select(line => line.Trim())
                .Where(line => !string.IsNullOrWhiteSpace(line))
                .ToList();

            return lines.Count > 0 ? lines : GetDefaultComments();
        }
    }

    /// <summary>
    /// Saves comment lines to the server file for the given service ID.
    /// </summary>
    public void SaveCommentsToFile(List<string> comments, int serviceId = 177)
    {
        lock (_fileLock)
        {
            var path = GetFilePath(serviceId);
            var cleanComments = comments
                .Select(c => c.Trim())
                .Where(c => !string.IsNullOrWhiteSpace(c))
                .ToList();

            File.WriteAllLines(path, cleanComments);
        }
    }

    /// <summary>
    /// Selects exactly [quantity] comments from the server file.
    /// If quantity &gt; available comments in file, loops back over available comments in a round-robin manner:
    ///   comment[0], comment[1], ..., comment[N-1], comment[0], comment[1], ...
    /// Returns the picked comments joined by newline (\n).
    /// </summary>
    public string GetCommentsForQuantity(int quantity, int serviceId = 177)
    {
        var available = ReadCommentsFromFile(serviceId);
        if (available.Count == 0) return string.Empty;

        var selected = new List<string>(quantity);
        for (int i = 0; i < quantity; i++)
        {
            // Loop-back (round-robin / modulo) picking if quantity exceeds file count
            selected.Add(available[i % available.Count]);
        }

        return string.Join("\n", selected);
    }

    private static List<string> GetDefaultComments() => new()
    {
        "Awesome post! 🔥",
        "Great content, keep it up! 👍",
        "Love this! ❤️",
        "Super cool! ✨",
        "Amazing view! 💯"
    };
}
