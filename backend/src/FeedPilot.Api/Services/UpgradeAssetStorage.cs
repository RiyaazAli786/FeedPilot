using System.IO.Compression;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace FeedPilot.Api.Services;

public class UpgradeAssetStorageSettings
{
    public string Provider { get; set; } = "Local";
    public string KeyId { get; set; } = string.Empty;
    public string ApplicationKey { get; set; } = string.Empty;
    public string BucketId { get; set; } = string.Empty;
    public string BucketName { get; set; } = string.Empty;
    public string Prefix { get; set; } = "upgrade";
}

public record UpgradeAssetItem(
    int Index,
    string? Image,
    string? ImageUrl,
    string? Caption,
    string? CaptionText,
    string? Text,
    string? BioText);

public record ProfileFolderInfo(
    string Name,
    int Index,
    int ImageCount,
    List<string> ImageUrls,
    string? PrimaryThumbnailUrl);

public record UpgradeAssetFile(byte[] Bytes, string ContentType);

public interface IUpgradeAssetStorage
{
    Task<int> NextIndexAsync(string gender, string type, CancellationToken ct);
    Task SaveAsync(string gender, string type, int index, byte[]? fileBytes, string? text, CancellationToken ct);
    Task<Dictionary<string, List<UpgradeAssetItem>>> ListAsync(string gender, CancellationToken ct);
    Task DeleteAsync(string gender, string type, int index, CancellationToken ct);
    Task<UpgradeAssetFile?> ReadAsync(string gender, string type, string fileName, CancellationToken ct);

    // Unified Profile Folder and ZIP collection API (Gender-Independent)
    Task<List<ProfileFolderInfo>> ListFoldersAsync(string? gender = null, CancellationToken ct = default);
    Task<ProfileFolderInfo?> AssignProfileFolderAsync(string username, string? gender = null, CancellationToken ct = default);
    Task<UpgradeAssetFile?> ReadFolderFileAsync(string? gender, string folderName, string fileName, CancellationToken ct = default);
    Task DeleteFolderAsync(string? gender, string folderName, CancellationToken ct = default);
    Task DeleteFolderFileAsync(string? gender, string folderName, string fileName, CancellationToken ct = default);
    Task<int> SaveZipCollectionAsync(string? gender, byte[] zipBytes, CancellationToken ct = default);
    Task<string> SaveFolderImagesAsync(string? gender, string? folderName, List<(string FileName, byte[] Bytes)> images, CancellationToken ct = default);
    Task<int> SaveBioZipAsync(string gender, byte[] zipBytes, CancellationToken ct);
    Task<int> SaveCaptionZipAsync(string gender, byte[] zipBytes, CancellationToken ct);
    Task MigrateLocalToB2Async(CancellationToken ct = default);
}

public class UpgradeAssetStorage : IUpgradeAssetStorage
{
    private static readonly string[] AllowedTypes = ["posts", "profiles", "stories", "bios", "captions"];
    // Types that only ever store a per-index .txt file, never a paired .jpg — same treatment as "bios".
    private static bool IsTextOnlyType(string type) => type is "bios" or "captions";
    private static readonly string[] AllowedGenders = ["male", "female"];
    private readonly IWebHostEnvironment _env;
    private readonly IHttpClientFactory _http;
    private readonly UpgradeAssetStorageSettings _settings;
    private readonly ILogger<UpgradeAssetStorage>? _logger;
    private B2Auth? _b2Auth;
    private string? _bucketId;

    public UpgradeAssetStorage(
        IWebHostEnvironment env,
        IHttpClientFactory http,
        Microsoft.Extensions.Options.IOptions<UpgradeAssetStorageSettings> settings,
        ILogger<UpgradeAssetStorage>? logger = null)
    {
        _env = env;
        _http = http;
        _settings = settings.Value;
        _logger = logger;
    }

    public async Task<int> NextIndexAsync(string gender, string type, CancellationToken ct)
    {
        EnsureGender(gender);
        EnsureType(type);
        var existing = await ListTypeAsync(gender, type, ct);
        return existing.Count == 0 ? 1 : existing.Keys.Max() + 1;
    }

    public async Task SaveAsync(string gender, string type, int index, byte[]? fileBytes, string? text, CancellationToken ct)
    {
        EnsureGender(gender);
        EnsureType(type);
        if (UseB2())
        {
            if (fileBytes is not null && !IsTextOnlyType(type))
                await UploadB2Async($"{Prefix()}/{gender}/{type}/{index}.jpg", fileBytes, "image/jpeg", ct);
            if (!string.IsNullOrWhiteSpace(text))
                await UploadB2Async($"{Prefix()}/{gender}/{type}/{index}.txt", Encoding.UTF8.GetBytes(text), "text/plain; charset=utf-8", ct);
            return;
        }

        var dir = LocalTypeDir(gender, type);
        Directory.CreateDirectory(dir);
        if (fileBytes is not null && !IsTextOnlyType(type))
            await File.WriteAllBytesAsync(Path.Combine(dir, $"{index}.jpg"), fileBytes, ct);
        if (!string.IsNullOrWhiteSpace(text))
            await File.WriteAllTextAsync(Path.Combine(dir, $"{index}.txt"), text, Encoding.UTF8, ct);
    }

    public async Task<Dictionary<string, List<UpgradeAssetItem>>> ListAsync(string gender, CancellationToken ct)
    {
        EnsureGender(gender);
        var result = new Dictionary<string, List<UpgradeAssetItem>>();
        foreach (var type in AllowedTypes)
        {
            var files = await ListTypeAsync(gender, type, ct);
            result[type] = files
                .OrderBy(x => x.Key)
                .Select(x => ToItem(type, x.Key, x.Value))
                .ToList();
        }
        return result;
    }

    public async Task DeleteAsync(string gender, string type, int index, CancellationToken ct)
    {
        EnsureGender(gender);
        EnsureType(type);
        if (index <= 0) throw new ArgumentOutOfRangeException(nameof(index), "Index must be greater than 0.");

        if (UseB2())
        {
            var prefix = $"{Prefix()}/{gender}/{type}/{index}.";
            var files = await ListB2FilesAsync(prefix, ct);
            foreach (var file in files)
                await DeleteB2FileAsync(file.FileName, file.FileId, ct);
            return;
        }

        var dir = LocalTypeDir(gender, type);
        if (!Directory.Exists(dir)) return;
        foreach (var ext in new[] { ".jpg", ".txt" })
        {
            var path = Path.Combine(dir, $"{index}{ext}");
            if (File.Exists(path)) File.Delete(path);
        }
    }

    public async Task<UpgradeAssetFile?> ReadAsync(string gender, string type, string fileName, CancellationToken ct)
    {
        EnsureGender(gender);
        EnsureType(type);
        var parsed = ParseIndexedFile(fileName);
        if (parsed is null) return null;
        var contentType = parsed.Value.Ext == ".txt" ? "text/plain; charset=utf-8" : "image/jpeg";

        if (UseB2())
        {
            var fullName = $"{Prefix()}/{gender}/{type}/{fileName}";
            var files = await ListB2FilesAsync(fullName, ct);
            if (!files.Any(f => f.FileName == fullName)) return null;
            return new UpgradeAssetFile(await ReadB2BytesAsync(fullName, ct), contentType);
        }

        var path = Path.Combine(LocalTypeDir(gender, type), fileName);
        return File.Exists(path) ? new UpgradeAssetFile(await File.ReadAllBytesAsync(path, ct), contentType) : null;
    }

    private async Task<Dictionary<int, AssetFiles>> ListTypeAsync(string gender, string type, CancellationToken ct)
    {
        if (UseB2())
            return await ListTypeB2Async(gender, type, ct);

        var dir = LocalTypeDir(gender, type);
        var result = new Dictionary<int, AssetFiles>();
        if (!Directory.Exists(dir)) return result;

        foreach (var path in Directory.EnumerateFiles(dir))
        {
            var name = Path.GetFileName(path);
            AddLocalFile(gender, type, result, name, path);
        }
        return result;
    }

    private async Task<Dictionary<int, AssetFiles>> ListTypeB2Async(string gender, string type, CancellationToken ct)
    {
        var result = new Dictionary<int, AssetFiles>();
        var prefix = $"{Prefix()}/{gender}/{type}/";
        var files = await ListB2FilesAsync(prefix, ct);
        foreach (var file in files)
        {
            var name = file.FileName[prefix.Length..];
            AddRemoteFile(gender, type, result, name, file.FileName);
        }
        return result;
    }

    private static UpgradeAssetItem ToItem(string type, int index, AssetFiles files)
    {
        if (IsTextOnlyType(type))
        {
            return new UpgradeAssetItem(index, null, null, null, null, $"{index}.txt", files.Text);
        }

        return new UpgradeAssetItem(
            index,
            files.Image is null ? null : $"{index}.jpg",
            files.ImageUrl,
            files.Text is null ? null : $"{index}.txt",
            files.Text,
            null,
            null);
    }

    private void AddLocalFile(string gender, string type, Dictionary<int, AssetFiles> result, string name, string path)
    {
        var parsed = ParseIndexedFile(name);
        if (parsed is null) return;
        var (index, ext) = parsed.Value;
        var item = GetOrAdd(result, index);
        if (ext == ".jpg" && !IsTextOnlyType(type))
        {
            item.Image = name;
            item.ImageUrl = $"/upgrade/{gender}/{type}/{index}.jpg";
        }
        else if (ext == ".txt")
        {
            item.Text = File.ReadAllText(path, Encoding.UTF8);
        }
    }

    private void AddRemoteFile(string gender, string type, Dictionary<int, AssetFiles> result, string name, string fileName)
    {
        var parsed = ParseIndexedFile(name);
        if (parsed is null) return;
        var (index, ext) = parsed.Value;
        var item = GetOrAdd(result, index);
        if (ext == ".jpg" && !IsTextOnlyType(type))
        {
            item.Image = name;
            item.ImageUrl = $"/upgrade/{gender}/{type}/{index}.jpg";
        }
        else if (ext == ".txt")
        {
            item.Text = ReadB2TextAsync(fileName, CancellationToken.None).GetAwaiter().GetResult();
        }
    }

    private static AssetFiles GetOrAdd(Dictionary<int, AssetFiles> result, int index)
    {
        if (!result.TryGetValue(index, out var item))
        {
            item = new AssetFiles();
            result[index] = item;
        }
        return item;
    }

    private static (int Index, string Ext)? ParseIndexedFile(string name)
    {
        var ext = Path.GetExtension(name).ToLowerInvariant();
        var stem = Path.GetFileNameWithoutExtension(name);
        if ((ext != ".jpg" && ext != ".txt") || !int.TryParse(stem, out var index) || index <= 0)
            return null;
        return (index, ext);
    }

    private bool UseB2() =>
        _settings.Provider.Equals("B2", StringComparison.OrdinalIgnoreCase) &&
        !string.IsNullOrWhiteSpace(Clean(_settings.KeyId)) &&
        !string.IsNullOrWhiteSpace(Clean(_settings.ApplicationKey)) &&
        !string.IsNullOrWhiteSpace(Clean(_settings.BucketName));

    private string Prefix() => _settings.Prefix.Trim('/').Length == 0 ? "upgrade" : _settings.Prefix.Trim('/');

    private string LocalTypeDir(string gender, string type)
    {
        var webRoot = string.IsNullOrWhiteSpace(_env.WebRootPath)
            ? Path.Combine(Directory.GetCurrentDirectory(), "wwwroot")
            : _env.WebRootPath;
        return Path.Combine(webRoot, "upgrade", gender, type);
    }

    private string LocalFoldersDir(string? gender = null)
    {
        var cleanGender = NormalizeGender(gender);
        var webRoot = WebRootPath();
        return Path.Combine(webRoot, "upgrade", cleanGender, "folders");
    }

    // ── Profile Folder & Zip implementations (Gender-Specific) ────────────────────

    private void NormalizeLocalFolders()
    {
        var webRoot = WebRootPath();
        var ungenderedDir = Path.Combine(webRoot, "upgrade", "folders");
        var femaleFoldersDir = Path.Combine(webRoot, "upgrade", "female", "folders");

        // 1. Move any legacy ungendered local folders into female/folders
        if (Directory.Exists(ungenderedDir))
        {
            Directory.CreateDirectory(femaleFoldersDir);
            foreach (var subDir in Directory.GetDirectories(ungenderedDir))
            {
                var fName = Path.GetFileName(subDir);
                var destDir = Path.Combine(femaleFoldersDir, fName);
                try
                {
                    if (!Directory.Exists(destDir))
                    {
                        Directory.Move(subDir, destDir);
                    }
                    else
                    {
                        foreach (var file in Directory.GetFiles(subDir))
                        {
                            var destFile = Path.Combine(destDir, Path.GetFileName(file));
                            if (!File.Exists(destFile)) File.Move(file, destFile);
                        }
                        Directory.Delete(subDir, true);
                    }
                }
                catch { }
            }
            try { Directory.Delete(ungenderedDir, true); } catch { }
        }

        // 2. Normalize male and female folder and file names strictly to folder_1, folder_2... and 1.jpg, 2.jpg...
        var baseDirs = new[]
        {
            Path.Combine(webRoot, "upgrade", "male", "folders"),
            Path.Combine(webRoot, "upgrade", "female", "folders")
        };

        foreach (var baseDir in baseDirs)
        {
            if (!Directory.Exists(baseDir)) continue;
            var subDirs = Directory.GetDirectories(baseDir)
                .OrderBy(d => NumericOrNameSort(Path.GetFileName(d)))
                .ToList();

            if (subDirs.Count == 0) continue;

            var tempDirs = new List<(string TempPath, string OriginalName)>();
            foreach (var dir in subDirs)
            {
                var origName = Path.GetFileName(dir);
                var tempPath = Path.Combine(baseDir, $"_temp_{Guid.NewGuid():N}");
                try { Directory.Move(dir, tempPath); tempDirs.Add((tempPath, origName)); } catch { }
            }

            int folderIdx = 1;
            foreach (var (tempPath, _) in tempDirs)
            {
                var targetDir = Path.Combine(baseDir, $"folder_{folderIdx}");
                try { Directory.Move(tempPath, targetDir); } catch { continue; }

                var images = Directory.GetFiles(targetDir)
                    .Where(f => IsImageFile(f))
                    .OrderBy(f => NumericOrNameSort(Path.GetFileName(f)))
                    .ToList();

                var tempImages = new List<(string TempFile, string Ext)>();
                foreach (var imgFile in images)
                {
                    var ext = Path.GetExtension(imgFile).ToLowerInvariant();
                    if (string.IsNullOrEmpty(ext)) ext = ".jpg";
                    var tempImg = Path.Combine(targetDir, $"_img_{Guid.NewGuid():N}{ext}");
                    try { File.Move(imgFile, tempImg); tempImages.Add((tempImg, ext)); } catch { }
                }

                int imgIdx = 1;
                foreach (var (tempImg, ext) in tempImages)
                {
                    var targetImg = Path.Combine(targetDir, $"{imgIdx}{ext}");
                    try { File.Move(tempImg, targetImg); } catch { }
                    imgIdx++;
                }

                folderIdx++;
            }
        }
    }

    private async Task NormalizeB2FoldersAsync(CancellationToken ct = default)
    {
        if (!UseB2()) return;

        // 1. Move any legacy ungendered B2 objects from '{Prefix()}/folders/' to '{Prefix()}/female/folders/'
        var ungenderedPrefix = $"{Prefix()}/folders/";
        var ungenderedFiles = await ListB2FilesAsync(ungenderedPrefix, ct);
        if (ungenderedFiles.Count > 0)
        {
            _logger?.LogInformation($"[B2 Normalization] Migrating {ungenderedFiles.Count} ungendered object(s) under '{ungenderedPrefix}' to '{Prefix()}/female/folders/'...");
            foreach (var f in ungenderedFiles)
            {
                var relative = f.FileName[ungenderedPrefix.Length..];
                var expectedKey = $"{Prefix()}/female/folders/{relative}";
                try
                {
                    var ext = Path.GetExtension(f.FileName).ToLowerInvariant();
                    var contentType = ext switch
                    {
                        ".png" => "image/png",
                        ".webp" => "image/webp",
                        ".gif" => "image/gif",
                        _ => "image/jpeg"
                    };
                    var bytes = await ReadB2BytesAsync(f.FileName, ct);
                    await UploadB2Async(expectedKey, bytes, contentType, ct);
                    await DeleteB2FileAsync(f.FileName, f.FileId, ct);
                }
                catch (Exception ex)
                {
                    _logger?.LogWarning($"[B2 Normalization] Failed to migrate ungendered B2 file '{f.FileName}': {ex.Message}");
                }
            }
        }

        // 2. Normalize male and female folder keys strictly to folder_1, folder_2... and 1.jpg, 2.jpg...
        var prefixes = new[]
        {
            $"{Prefix()}/male/folders/",
            $"{Prefix()}/female/folders/"
        };

        foreach (var pfx in prefixes)
        {
            var b2Files = await ListB2FilesAsync(pfx, ct);
            if (b2Files.Count == 0) continue;

            var folderGroups = new Dictionary<string, List<(string FileId, string FullKey, string FileName)>>(StringComparer.OrdinalIgnoreCase);

            foreach (var f in b2Files)
            {
                var relative = f.FileName[pfx.Length..];
                var parts = relative.Split('/');
                if (parts.Length < 2) continue;

                var fName = parts[0];
                var fileName = parts[1];
                if (!IsImageFile(fileName)) continue;

                if (!folderGroups.TryGetValue(fName, out var list))
                {
                    list = new List<(string, string, string)>();
                    folderGroups[fName] = list;
                }
                list.Add((f.FileId, f.FileName, fileName));
            }

            if (folderGroups.Count == 0) continue;

            int folderIdx = 1;
            foreach (var kvp in folderGroups.OrderBy(x => NumericOrNameSort(x.Key)))
            {
                var targetFolderName = $"folder_{folderIdx}";
                var sortedImages = kvp.Value.OrderBy(x => NumericOrNameSort(x.FileName)).ToList();

                int imgIdx = 1;
                foreach (var img in sortedImages)
                {
                    var ext = Path.GetExtension(img.FileName).ToLowerInvariant();
                    if (string.IsNullOrEmpty(ext)) ext = ".jpg";
                    var expectedKey = $"{pfx}{targetFolderName}/{imgIdx}{ext}";

                    if (!img.FullKey.Equals(expectedKey, StringComparison.OrdinalIgnoreCase))
                    {
                        var contentType = ext switch
                        {
                            ".png" => "image/png",
                            ".webp" => "image/webp",
                            ".gif" => "image/gif",
                            _ => "image/jpeg"
                        };

                        try
                        {
                            var bytes = await ReadB2BytesAsync(img.FullKey, ct);
                            await UploadB2Async(expectedKey, bytes, contentType, ct);
                            await DeleteB2FileAsync(img.FullKey, img.FileId, ct);
                        }
                        catch (Exception ex)
                        {
                            _logger?.LogWarning($"Failed to normalize B2 file '{img.FullKey}' to '{expectedKey}': {ex.Message}");
                        }
                    }
                    imgIdx++;
                }

                folderIdx++;
            }
        }
    }

    public async Task MigrateLocalToB2Async(CancellationToken ct = default)
    {
        // 0. Pre-normalize any non-sequential local folders/files
        NormalizeLocalFolders();

        if (!UseB2()) return;

        var webRoot = WebRootPath();
        var upgradeDir = Path.Combine(webRoot, "upgrade");
        if (!Directory.Exists(upgradeDir)) return;

        var migrationItems = new List<(string LocalPath, string B2FileName, string ContentType)>();

        // 1. Collect single assets: upgrade/{gender}/{type}/{file}
        foreach (var gender in AllowedGenders)
        {
            foreach (var type in AllowedTypes)
            {
                var dir = Path.Combine(upgradeDir, gender, type);
                if (!Directory.Exists(dir)) continue;

                foreach (var file in Directory.EnumerateFiles(dir))
                {
                    var fileName = Path.GetFileName(file);
                    var b2FileName = $"{Prefix()}/{gender}/{type}/{fileName}";
                    var contentType = Path.GetExtension(fileName).Equals(".txt", StringComparison.OrdinalIgnoreCase)
                        ? "text/plain; charset=utf-8"
                        : "image/jpeg";
                    migrationItems.Add((file, b2FileName, contentType));
                }
            }
        }

        // 2. Collect profile folders: upgrade/{gender}/folders/{folderName}/{file}
        var folderBaseDirs = new List<(string DirPath, string PrefixPath)>
        {
            (Path.Combine(upgradeDir, "male", "folders"), $"{Prefix()}/male/folders"),
            (Path.Combine(upgradeDir, "female", "folders"), $"{Prefix()}/female/folders")
        };

        foreach (var (dirPath, prefixPath) in folderBaseDirs)
        {
            if (!Directory.Exists(dirPath)) continue;

            foreach (var subDir in Directory.GetDirectories(dirPath))
            {
                var folderName = Path.GetFileName(subDir);
                foreach (var file in Directory.EnumerateFiles(subDir))
                {
                    var fileName = Path.GetFileName(file);
                    if (!IsImageFile(fileName)) continue;

                    var b2FileName = $"{prefixPath}/{folderName}/{fileName}";
                    var ext = Path.GetExtension(fileName).ToLowerInvariant();
                    var contentType = ext switch
                    {
                        ".png" => "image/png",
                        ".webp" => "image/webp",
                        ".gif" => "image/gif",
                        _ => "image/jpeg"
                    };
                    migrationItems.Add((file, b2FileName, contentType));
                }
            }
        }

        if (migrationItems.Count == 0) return;

        var msgStart = $"[B2 Asset Migration] Found {migrationItems.Count} local asset file(s) to migrate to Backblaze B2...";
        _logger?.LogInformation(msgStart);
        Console.WriteLine(msgStart);

        int currentIndex = 0;
        int successCount = 0;

        foreach (var item in migrationItems)
        {
            currentIndex++;
            var fileInfo = new FileInfo(item.LocalPath);
            var sizeKb = fileInfo.Exists ? (fileInfo.Length / 1024.0).ToString("0.0") : "0";

            var msgUploading = $"[B2 Asset Migration] [{currentIndex}/{migrationItems.Count}] Uploading '{item.B2FileName}' ({sizeKb} KB)...";
            _logger?.LogInformation(msgUploading);
            Console.WriteLine(msgUploading);

            bool success = false;
            try
            {
                var bytes = await File.ReadAllBytesAsync(item.LocalPath, ct);
                await UploadB2Async(item.B2FileName, bytes, item.ContentType, ct);

                // Verify B2 confirmed storage before deleting local copy
                var check = await ListB2FilesAsync(item.B2FileName, ct);
                if (check.Any(f => f.FileName.Equals(item.B2FileName, StringComparison.OrdinalIgnoreCase)))
                {
                    success = true;
                }
            }
            catch (Exception ex)
            {
                var msgError = $"[B2 Asset Migration] [{currentIndex}/{migrationItems.Count}] Failed to upload '{item.B2FileName}': {ex.Message}";
                _logger?.LogError(msgError);
                Console.WriteLine(msgError);
                success = false;
            }

            if (success)
            {
                try
                {
                    File.Delete(item.LocalPath);
                    successCount++;
                    var msgDone = $"[B2 Asset Migration] [{currentIndex}/{migrationItems.Count}] Success! '{item.B2FileName}' uploaded & verified on B2. Local file deleted.";
                    _logger?.LogInformation(msgDone);
                    Console.WriteLine(msgDone);
                }
                catch (Exception ex)
                {
                    var msgDelError = $"[B2 Asset Migration] Warning: Uploaded to B2, but could not delete local file '{item.LocalPath}': {ex.Message}";
                    _logger?.LogWarning(msgDelError);
                    Console.WriteLine(msgDelError);
                }
            }
        }

        // Clean up empty directories
        foreach (var (dirPath, _) in folderBaseDirs)
        {
            if (!Directory.Exists(dirPath)) continue;
            foreach (var subDir in Directory.GetDirectories(dirPath))
            {
                if (Directory.GetFiles(subDir).Length == 0 && Directory.GetDirectories(subDir).Length == 0)
                {
                    try { Directory.Delete(subDir); } catch { }
                }
            }
        }

        // Post-normalize any non-sequential B2 folders/files
        await NormalizeB2FoldersAsync(ct);

        var msgFinish = $"[B2 Asset Migration] Migration Completed! {successCount}/{migrationItems.Count} file(s) successfully migrated to Backblaze B2.";
        _logger?.LogInformation(msgFinish);
        Console.WriteLine(msgFinish);
    }

    public async Task<List<ProfileFolderInfo>> ListFoldersAsync(string? gender = null, CancellationToken ct = default)
    {
        var targetGender = NormalizeGender(gender);
        var result = new List<ProfileFolderInfo>();
        var webRoot = WebRootPath();

        if (UseB2())
        {
            var searchPrefix = $"{Prefix()}/{targetGender}/folders/";
            var b2Files = await ListB2FilesAsync(searchPrefix, ct);

            // If gender-specific folder search returned 0 files, check if top-level ungendered '{Prefix()}/folders/' has files in B2
            if (b2Files.Count == 0)
            {
                var ungenderedPrefix = $"{Prefix()}/folders/";
                var ungenderedFiles = await ListB2FilesAsync(ungenderedPrefix, ct);
                if (ungenderedFiles.Count > 0)
                {
                    _logger?.LogInformation($"[B2 Storage] Found {ungenderedFiles.Count} ungendered profile folder file(s) under '{ungenderedPrefix}'. Auto-migrating to '{Prefix()}/{targetGender}/folders/'...");
                    await NormalizeB2FoldersAsync(ct);
                    b2Files = await ListB2FilesAsync(searchPrefix, ct);
                }
            }

            var folderMapB2 = new Dictionary<string, List<string>>(StringComparer.OrdinalIgnoreCase);

            foreach (var f in b2Files)
            {
                var relative = f.FileName[searchPrefix.Length..];
                var parts = relative.Split('/');
                if (parts.Length < 2) continue;

                var fName = parts[0];
                var fileName = parts[1];
                if (!IsImageFile(fileName)) continue;

                if (!folderMapB2.TryGetValue(fName, out var list))
                {
                    list = new List<string>();
                    folderMapB2[fName] = list;
                }

                if (!list.Contains(fileName, StringComparer.OrdinalIgnoreCase))
                {
                    list.Add(fileName);
                }
            }

            int folderIdxB2 = 1;
            foreach (var kvp in folderMapB2.OrderBy(x => NumericOrNameSort(x.Key)))
            {
                var folderName = kvp.Key;
                var sortedImages = kvp.Value.OrderBy(f => NumericOrNameSort(f)).ToList();
                var imageUrls = sortedImages.Select(f => $"/upgrade/{targetGender}/folders/{folderName}/{f}").ToList();

                result.Add(new ProfileFolderInfo(
                    Name: folderName,
                    Index: folderIdxB2++,
                    ImageCount: sortedImages.Count,
                    ImageUrls: imageUrls,
                    PrimaryThumbnailUrl: imageUrls.FirstOrDefault()
                ));
            }

            return result;
        }

        var dir = LocalFoldersDir(targetGender);
        if (!Directory.Exists(dir)) return result;

        var folderMap = new Dictionary<string, List<string>>(StringComparer.OrdinalIgnoreCase);
        foreach (var subDir in Directory.GetDirectories(dir))
        {
            var fName = Path.GetFileName(subDir);
            var imageFiles = Directory.GetFiles(subDir)
                .Where(f => IsImageFile(f))
                .OrderBy(f => NumericOrNameSort(Path.GetFileName(f)))
                .Select(Path.GetFileName)
                .Where(f => !string.IsNullOrEmpty(f))
                .Cast<string>()
                .ToList();

            if (imageFiles.Count > 0)
            {
                folderMap[fName] = imageFiles;
            }
        }

        int folderIdx = 1;
        foreach (var kvp in folderMap.OrderBy(x => NumericOrNameSort(x.Key)))
        {
            var folderName = kvp.Key;
            var sortedImages = kvp.Value.ToList();
            var imageUrls = sortedImages.Select(f => $"/upgrade/{targetGender}/folders/{folderName}/{f}").ToList();

            result.Add(new ProfileFolderInfo(
                Name: folderName,
                Index: folderIdx++,
                ImageCount: sortedImages.Count,
                ImageUrls: imageUrls,
                PrimaryThumbnailUrl: imageUrls.FirstOrDefault()
            ));
        }

        return result;
    }

    public async Task<ProfileFolderInfo?> GetFolderByNameAsync(string? gender, string folderName, CancellationToken ct = default)
    {
        var folders = await ListFoldersAsync(gender, ct);
        return folders.FirstOrDefault(f => f.Name.Equals(folderName, StringComparison.OrdinalIgnoreCase));
    }

    public async Task<UpgradeAssetFile?> ReadFolderFileAsync(string? gender, string folderName, string fileName, CancellationToken ct = default)
    {
        if (string.IsNullOrWhiteSpace(folderName) || string.IsNullOrWhiteSpace(fileName)) return null;

        var targetGender = NormalizeGender(gender);
        var ext = Path.GetExtension(fileName).ToLowerInvariant();
        var contentType = ext switch
        {
            ".png" => "image/png",
            ".webp" => "image/webp",
            ".gif" => "image/gif",
            _ => "image/jpeg"
        };

        if (UseB2())
        {
            var path = $"{Prefix()}/{targetGender}/folders/{folderName}/{fileName}";
            try
            {
                var bytes = await ReadB2BytesAsync(path, ct);
                return new UpgradeAssetFile(bytes, contentType);
            }
            catch
            {
                // Fallback check: check ungendered path '{Prefix()}/folders/{folderName}/{fileName}'
                try
                {
                    var fallbackPath = $"{Prefix()}/folders/{folderName}/{fileName}";
                    var bytes = await ReadB2BytesAsync(fallbackPath, ct);
                    return new UpgradeAssetFile(bytes, contentType);
                }
                catch
                {
                    // Fallthrough to local check
                }
            }
        }

        var webRoot = WebRootPath();
        var localPath = Path.Combine(webRoot, "upgrade", targetGender, "folders", folderName, fileName);
        if (!File.Exists(localPath)) return null;

        var localBytes = await File.ReadAllBytesAsync(localPath, ct);
        return new UpgradeAssetFile(localBytes, contentType);
    }

    public async Task DeleteFolderAsync(string? gender, string folderName, CancellationToken ct = default)
    {
        var targetGender = NormalizeGender(gender);
        if (UseB2())
        {
            var prefix = $"{Prefix()}/{targetGender}/folders/{folderName}/";
            var files = await ListB2FilesAsync(prefix, ct);
            foreach (var file in files)
            {
                try { await DeleteB2FileAsync(file.FileName, file.FileId, ct); } catch { }
            }
        }

        var webRoot = WebRootPath();
        var dir = Path.Combine(webRoot, "upgrade", targetGender, "folders", folderName);
        if (Directory.Exists(dir))
        {
            try { Directory.Delete(dir, true); } catch { }
        }

        var stateFilePath = Path.Combine(WebRootPath(), "upgrade", "folder_assignments.json");
        lock (FolderLock)
        {
            if (File.Exists(stateFilePath))
            {
                try
                {
                    var json = File.ReadAllText(stateFilePath);
                    var state = JsonSerializer.Deserialize<FolderAssignmentState>(json);
                    if (state != null)
                    {
                        var keysToRemove = state.AccountFolders
                            .Where(kvp => kvp.Value.Equals(folderName, StringComparison.OrdinalIgnoreCase))
                            .Select(kvp => kvp.Key)
                            .ToList();

                        foreach (var key in keysToRemove)
                        {
                            state.AccountFolders.Remove(key);
                        }

                        var updatedJson = JsonSerializer.Serialize(state, new JsonSerializerOptions { WriteIndented = true });
                        File.WriteAllText(stateFilePath, updatedJson);
                    }
                }
                catch
                {
                    // Ignore transient cleanup errors
                }
            }
        }
    }

    public async Task DeleteFolderFileAsync(string? gender, string folderName, string fileName, CancellationToken ct = default)
    {
        if (string.IsNullOrWhiteSpace(folderName) || string.IsNullOrWhiteSpace(fileName)) return;

        if (UseB2())
        {
            var targetGender = NormalizeGender(gender);
            var b2FolderPrefix = $"{Prefix()}/{targetGender}/folders/{folderName}/";
            var files = await ListB2FilesAsync(b2FolderPrefix, ct);
            var targetFile = files.FirstOrDefault(f => Path.GetFileName(f.FileName).Equals(fileName, StringComparison.OrdinalIgnoreCase));
            if (targetFile != null)
            {
                try { await DeleteB2FileAsync(targetFile.FileName, targetFile.FileId, ct); } catch { }
            }

            // Re-index remaining B2 files in folder sequentially (1.jpg, 2.jpg, 3.jpg...) to maintain sequence without gaps
            var remainingB2 = (await ListB2FilesAsync(b2FolderPrefix, ct))
                .Where(f => IsImageFile(Path.GetFileName(f.FileName)))
                .OrderBy(f => NumericOrNameSort(Path.GetFileName(f.FileName)))
                .ToList();

            if (remainingB2.Count == 0)
            {
                await DeleteFolderAsync(gender, folderName, ct);
                return;
            }

            int b2Idx = 1;
            foreach (var b2Img in remainingB2)
            {
                var currentName = Path.GetFileName(b2Img.FileName);
                var ext = Path.GetExtension(currentName).ToLowerInvariant();
                if (string.IsNullOrEmpty(ext)) ext = ".jpg";
                var expectedName = $"{b2Idx}{ext}";
                if (!currentName.Equals(expectedName, StringComparison.OrdinalIgnoreCase))
                {
                    var newKey = $"{b2FolderPrefix}{expectedName}";
                    var bytes = await ReadB2BytesAsync(b2Img.FileName, ct);
                    var contentType = ext switch
                    {
                        ".png" => "image/png",
                        ".webp" => "image/webp",
                        ".gif" => "image/gif",
                        _ => "image/jpeg"
                    };
                    await UploadB2Async(newKey, bytes, contentType, ct);
                    try { await DeleteB2FileAsync(b2Img.FileName, b2Img.FileId, ct); } catch { }
                }
                b2Idx++;
            }
        }

        var webRoot = WebRootPath();
        var pathsToTry = new List<string>
        {
            Path.Combine(LocalFoldersDir(), folderName, fileName),
            Path.Combine(webRoot, "upgrade", gender ?? "female", "folders", folderName, fileName),
            Path.Combine(webRoot, "upgrade", "male", "folders", folderName, fileName)
        };

        string? foundPath = pathsToTry.FirstOrDefault(File.Exists);
        if (foundPath != null)
        {
            var folderDir = Path.GetDirectoryName(foundPath)!;
            File.Delete(foundPath);

            var remainingImages = Directory.GetFiles(folderDir)
                .Where(f => IsImageFile(f))
                .OrderBy(f => NumericOrNameSort(Path.GetFileName(f)))
                .ToList();

            if (remainingImages.Count == 0)
            {
                await DeleteFolderAsync(gender, folderName, ct);
                return;
            }

            int newIdx = 1;
            foreach (var imgPath in remainingImages)
            {
                var ext = Path.GetExtension(imgPath).ToLowerInvariant();
                var targetPath = Path.Combine(folderDir, $"{newIdx}{ext}");
                if (!imgPath.Equals(targetPath, StringComparison.OrdinalIgnoreCase))
                {
                    if (File.Exists(targetPath))
                    {
                        var tempPath = Path.Combine(folderDir, $"temp_{Guid.NewGuid():N}{ext}");
                        File.Move(targetPath, tempPath);
                    }
                    File.Move(imgPath, targetPath);
                }
                newIdx++;
            }
        }
    }

    public async Task<int> SaveZipCollectionAsync(string? gender, byte[] zipBytes, CancellationToken ct = default)
    {
        using var stream = new MemoryStream(zipBytes);
        using var archive = new ZipArchive(stream, ZipArchiveMode.Read);

        var folderMap = new Dictionary<string, List<(string FileName, byte[] Bytes)>>(StringComparer.OrdinalIgnoreCase);

        foreach (var entry in archive.Entries)
        {
            if (string.IsNullOrWhiteSpace(entry.Name)) continue;
            if (entry.FullName.Contains("__MACOSX") || entry.Name.StartsWith(".")) continue;

            if (!IsImageFile(entry.Name)) continue;

            var fullPath = entry.FullName.Replace('\\', '/').Trim('/');
            var parts = fullPath.Split('/');
            
            string folderName;
            if (parts.Length > 1)
            {
                folderName = parts[^2];
            }
            else
            {
                folderName = "Folder_1";
            }

            using var entryStream = entry.Open();
            using var ms = new MemoryStream();
            await entryStream.CopyToAsync(ms, ct);

            if (!folderMap.TryGetValue(folderName, out var list))
            {
                list = new List<(string, byte[])>();
                folderMap[folderName] = list;
            }
            list.Add((entry.Name, ms.ToArray()));
        }

        int savedFolders = 0;
        foreach (var kvp in folderMap)
        {
            var folderName = kvp.Key;
            var sortedImages = kvp.Value.OrderBy(x => NumericOrNameSort(x.FileName)).ToList();
            if (sortedImages.Count > 0)
            {
                await SaveFolderImagesAsync(gender, folderName, sortedImages, ct);
                savedFolders++;
            }
        }

        return savedFolders;
    }

    public async Task<string> SaveFolderImagesAsync(string? gender, string? folderName, List<(string FileName, byte[] Bytes)> images, CancellationToken ct = default)
    {
        if (images == null || images.Count == 0) throw new ArgumentException("No images provided");

        var targetGender = NormalizeGender(gender);

        if (string.IsNullOrWhiteSpace(folderName))
        {
            var existing = await ListFoldersAsync(targetGender, ct);
            folderName = $"folder_{existing.Count + 1}";
        }

        folderName = SanitizeFolderName(folderName);

        if (UseB2())
        {
            var b2FolderPrefix = $"{Prefix()}/{targetGender}/folders/{folderName}";

            int imgIdx = 1;
            foreach (var (fileName, bytes) in images)
            {
                var ext = Path.GetExtension(fileName).ToLowerInvariant();
                if (string.IsNullOrEmpty(ext)) ext = ".jpg";
                var contentType = ext switch
                {
                    ".png" => "image/png",
                    ".webp" => "image/webp",
                    ".gif" => "image/gif",
                    _ => "image/jpeg"
                };

                var b2FileName = $"{b2FolderPrefix}/{imgIdx}{ext}";
                await UploadB2Async(b2FileName, bytes, contentType, ct);
                imgIdx++;
            }

            return folderName;
        }

        var dir = Path.Combine(LocalFoldersDir(targetGender), folderName);
        Directory.CreateDirectory(dir);

        int imgIndex = 1;
        foreach (var (fileName, bytes) in images)
        {
            var ext = Path.GetExtension(fileName).ToLowerInvariant();
            if (string.IsNullOrEmpty(ext)) ext = ".jpg";
            var savePath = Path.Combine(dir, $"{imgIndex}{ext}");
            await File.WriteAllBytesAsync(savePath, bytes, ct);
            imgIndex++;
        }

        return folderName;
    }

    private static string NormalizeGender(string? gender)
    {
        if (string.IsNullOrWhiteSpace(gender)) return "female";
        var lower = gender.Trim().ToLowerInvariant();
        return lower is "male" or "female" ? lower : "female";
    }

    public Task<int> SaveBioZipAsync(string gender, byte[] zipBytes, CancellationToken ct) =>
        SaveTextZipAsync(gender, "bios", zipBytes, ct);

    public Task<int> SaveCaptionZipAsync(string gender, byte[] zipBytes, CancellationToken ct) =>
        SaveTextZipAsync(gender, "captions", zipBytes, ct);

    /// <summary>
    /// Shared by bios and post captions: extracts every non-empty .txt entry from a zip and
    /// saves each as its own next-index text-only asset (see <see cref="IsTextOnlyType"/>).
    /// </summary>
    private async Task<int> SaveTextZipAsync(string gender, string type, byte[] zipBytes, CancellationToken ct)
    {
        EnsureGender(gender);
        using var stream = new MemoryStream(zipBytes);
        using var archive = new ZipArchive(stream, ZipArchiveMode.Read);

        int count = 0;
        var txtEntries = archive.Entries
            .Where(e => !string.IsNullOrWhiteSpace(e.Name) && !e.FullName.Contains("__MACOSX") && !e.Name.StartsWith(".") && e.Name.EndsWith(".txt", StringComparison.OrdinalIgnoreCase))
            .OrderBy(e => e.Name)
            .ToList();

        foreach (var entry in txtEntries)
        {
            using var entryStream = entry.Open();
            using var reader = new StreamReader(entryStream, Encoding.UTF8);
            var text = await reader.ReadToEndAsync(ct);
            if (!string.IsNullOrWhiteSpace(text))
            {
                var nextIdx = await NextIndexAsync(gender, type, ct);
                await SaveAsync(gender, type, nextIdx, null, text.Trim(), ct);
                count++;
            }
        }

        return count;
    }

    private static bool IsImageFile(string fileName)
    {
        var ext = Path.GetExtension(fileName).ToLowerInvariant();
        return ext == ".jpg" || ext == ".jpeg" || ext == ".png" || ext == ".webp" || ext == ".gif";
    }

    private static string SanitizeFolderName(string name)
    {
        var invalid = Path.GetInvalidFileNameChars();
        var clean = new string(name.Where(c => !invalid.Contains(c)).ToArray()).Trim();
        return string.IsNullOrWhiteSpace(clean) ? "folder_1" : clean;
    }

    private string WebRootPath() => _env.WebRootPath ?? Path.Combine(Directory.GetCurrentDirectory(), "wwwroot");

    private static readonly object FolderLock = new();

    public async Task<ProfileFolderInfo?> AssignProfileFolderAsync(string username, string? gender = null, CancellationToken ct = default)
    {
        var folders = await ListFoldersAsync(gender, ct);
        if (folders.Count == 0) return null;

        var stateFilePath = Path.Combine(WebRootPath(), "upgrade", "folder_assignments.json");
        FolderAssignmentState state;

        lock (FolderLock)
        {
            if (File.Exists(stateFilePath))
            {
                try
                {
                    var json = File.ReadAllText(stateFilePath);
                    state = JsonSerializer.Deserialize<FolderAssignmentState>(json) ?? new FolderAssignmentState();
                }
                catch
                {
                    state = new FolderAssignmentState();
                }
            }
            else
            {
                state = new FolderAssignmentState();
            }

            // Prune stale mappings pointing to deleted folders
            var staleKeys = state.AccountFolders
                .Where(kvp => !folders.Any(f => f.Name.Equals(kvp.Value, StringComparison.OrdinalIgnoreCase)))
                .Select(kvp => kvp.Key)
                .ToList();

            foreach (var staleKey in staleKeys)
            {
                state.AccountFolders.Remove(staleKey);
            }

            var key = username.Trim().ToLowerInvariant();
            if (state.AccountFolders.TryGetValue(key, out var assignedFolderName))
            {
                var existingFolder = folders.FirstOrDefault(f => f.Name.Equals(assignedFolderName, StringComparison.OrdinalIgnoreCase));
                if (existingFolder != null)
                {
                    return existingFolder;
                }
            }

            const string pointerKey = "global";
            if (!state.GlobalPointers.TryGetValue(pointerKey, out var currentPointer) || currentPointer < 1)
            {
                currentPointer = 1;
            }

            if (currentPointer > folders.Count)
            {
                currentPointer = 1;
            }

            var targetFolder = folders[currentPointer - 1];
            state.AccountFolders[key] = targetFolder.Name;

            var nextPointer = currentPointer + 1;
            if (nextPointer > folders.Count)
            {
                nextPointer = 1;
            }
            state.GlobalPointers[pointerKey] = nextPointer;

            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(stateFilePath)!);
                var updatedJson = JsonSerializer.Serialize(state, new JsonSerializerOptions { WriteIndented = true });
                File.WriteAllText(stateFilePath, updatedJson);
            }
            catch
            {
                // Ignore transient write errors
            }

            return targetFolder;
        }
    }

    public sealed class FolderAssignmentState
    {
        public Dictionary<string, int> GlobalPointers { get; set; } = new();
        public Dictionary<string, string> AccountFolders { get; set; } = new();
    }

    private static string NumericOrNameSort(string fileName)
    {
        var stem = Path.GetFileNameWithoutExtension(fileName);
        if (int.TryParse(stem, out var n)) return n.ToString("D10");
        return fileName.ToLowerInvariant();
    }

    private static void EnsureType(string type)
    {
        if (Array.IndexOf(AllowedTypes, type) < 0)
            throw new InvalidOperationException("Invalid asset type.");
    }

    private static void EnsureGender(string gender)
    {
        if (Array.IndexOf(AllowedGenders, gender) < 0)
            throw new InvalidOperationException("Invalid gender.");
    }

    private async Task<B2Auth> AuthorizeB2Async(CancellationToken ct)
    {
        if (_b2Auth is not null) return _b2Auth;

        var client = _http.CreateClient("B2");
        using var req = new HttpRequestMessage(HttpMethod.Get, "https://api.backblazeb2.com/b2api/v2/b2_authorize_account");
        var basic = Convert.ToBase64String(Encoding.UTF8.GetBytes($"{Clean(_settings.KeyId)}:{Clean(_settings.ApplicationKey)}"));
        req.Headers.Authorization = new AuthenticationHeaderValue("Basic", basic);
        using var res = await client.SendAsync(req, ct);
        await EnsureB2SuccessAsync(res, "authorize account", ct);
        _b2Auth = (await res.Content.ReadFromJsonAsync<B2Auth>(cancellationToken: ct))!;
        return _b2Auth;
    }

    private async Task<string> BucketIdAsync(CancellationToken ct)
    {
        var configuredBucketId = Clean(_settings.BucketId);
        if (!string.IsNullOrWhiteSpace(configuredBucketId)) return configuredBucketId;
        if (!string.IsNullOrWhiteSpace(_bucketId)) return _bucketId;

        var auth = await AuthorizeB2Async(ct);
        var client = _http.CreateClient("B2");
        using var req = new HttpRequestMessage(HttpMethod.Post, $"{auth.ApiUrl}/b2api/v2/b2_list_buckets");
        req.Headers.TryAddWithoutValidation("Authorization", auth.AuthorizationToken);
        req.Content = JsonContent.Create(new { accountId = auth.AccountId, bucketName = Clean(_settings.BucketName) });
        using var res = await client.SendAsync(req, ct);
        await EnsureB2SuccessAsync(res, "list buckets", ct);
        var body = await res.Content.ReadFromJsonAsync<B2BucketList>(cancellationToken: ct);
        _bucketId = body?.Buckets.FirstOrDefault()?.BucketId
            ?? throw new InvalidOperationException($"B2 bucket '{Clean(_settings.BucketName)}' was not found.");
        return _bucketId;
    }

    private async Task UploadB2Async(string fileName, byte[] bytes, string contentType, CancellationToken ct)
    {
        var auth = await AuthorizeB2Async(ct);
        var bucketId = await BucketIdAsync(ct);
        var client = _http.CreateClient("B2");

        using var uploadReq = new HttpRequestMessage(HttpMethod.Post, $"{auth.ApiUrl}/b2api/v2/b2_get_upload_url");
        uploadReq.Headers.TryAddWithoutValidation("Authorization", auth.AuthorizationToken);
        uploadReq.Content = JsonContent.Create(new { bucketId });
        using var uploadRes = await client.SendAsync(uploadReq, ct);
        await EnsureB2SuccessAsync(uploadRes, "get upload URL", ct);
        var upload = (await uploadRes.Content.ReadFromJsonAsync<B2UploadUrl>(cancellationToken: ct))!;

        using var req = new HttpRequestMessage(HttpMethod.Post, upload.UploadUrl);
        req.Headers.TryAddWithoutValidation("Authorization", upload.AuthorizationToken);
        req.Headers.TryAddWithoutValidation("X-Bz-File-Name", Uri.EscapeDataString(fileName));
        req.Headers.TryAddWithoutValidation("X-Bz-Content-Sha1", Convert.ToHexString(SHA1.HashData(bytes)).ToLowerInvariant());
        req.Content = new ByteArrayContent(bytes);
        req.Content.Headers.ContentType = MediaTypeHeaderValue.Parse(contentType);
        using var res = await client.SendAsync(req, ct);
        await EnsureB2SuccessAsync(res, $"upload {fileName}", ct);
    }

    private async Task<List<B2File>> ListB2FilesAsync(string prefix, CancellationToken ct)
    {
        var auth = await AuthorizeB2Async(ct);
        var bucketId = await BucketIdAsync(ct);
        var client = _http.CreateClient("B2");
        var result = new List<B2File>();
        string? startFileName = null;

        do
        {
            using var req = new HttpRequestMessage(HttpMethod.Post, $"{auth.ApiUrl}/b2api/v2/b2_list_file_names");
            req.Headers.TryAddWithoutValidation("Authorization", auth.AuthorizationToken);
            req.Content = JsonContent.Create(new { bucketId, prefix, startFileName, maxFileCount = 1000 });
            using var res = await client.SendAsync(req, ct);
            await EnsureB2SuccessAsync(res, $"list files under {prefix}", ct);
            var body = (await res.Content.ReadFromJsonAsync<B2FileList>(cancellationToken: ct))!;
            result.AddRange(body.Files);
            startFileName = body.NextFileName;
        } while (!string.IsNullOrWhiteSpace(startFileName));

        return result;
    }

    private async Task<string> ReadB2TextAsync(string fileName, CancellationToken ct)
    {
        return Encoding.UTF8.GetString(await ReadB2BytesAsync(fileName, ct)).Trim();
    }

    private async Task<byte[]> ReadB2BytesAsync(string fileName, CancellationToken ct)
    {
        var auth = await AuthorizeB2Async(ct);
        var client = _http.CreateClient("B2");
        using var req = new HttpRequestMessage(HttpMethod.Get, PublicB2Url(fileName, auth.DownloadUrl));
        req.Headers.TryAddWithoutValidation("Authorization", auth.AuthorizationToken);
        using var res = await client.SendAsync(req, ct);
        await EnsureB2SuccessAsync(res, $"read {fileName}", ct);
        return await res.Content.ReadAsByteArrayAsync(ct);
    }

    private async Task DeleteB2FileAsync(string fileName, string fileId, CancellationToken ct)
    {
        var auth = await AuthorizeB2Async(ct);
        var client = _http.CreateClient("B2");
        using var req = new HttpRequestMessage(HttpMethod.Post, $"{auth.ApiUrl}/b2api/v2/b2_delete_file_version");
        req.Headers.TryAddWithoutValidation("Authorization", auth.AuthorizationToken);
        req.Content = JsonContent.Create(new { fileName, fileId });
        using var res = await client.SendAsync(req, ct);
        await EnsureB2SuccessAsync(res, $"delete {fileName}", ct);
    }

    private string PublicB2Url(string fileName, string? downloadUrl = null)
    {
        var authDownloadUrl = downloadUrl ?? _b2Auth?.DownloadUrl ?? "https://f005.backblazeb2.com";
        var encodedPath = string.Join("/", fileName.Split('/').Select(Uri.EscapeDataString));
        return $"{authDownloadUrl.TrimEnd('/')}/file/{Uri.EscapeDataString(Clean(_settings.BucketName))}/{encodedPath}";
    }

    private static string Clean(string value) => value.Trim().Trim('"', '\'');

    private static async Task EnsureB2SuccessAsync(HttpResponseMessage response, string operation, CancellationToken ct)
    {
        if (response.IsSuccessStatusCode) return;

        var body = await response.Content.ReadAsStringAsync(ct);
        throw new InvalidOperationException(
            $"Backblaze B2 failed to {operation}: {(int)response.StatusCode} {response.ReasonPhrase}. {body}");
    }

    private sealed class AssetFiles
    {
        public string? Image { get; set; }
        public string? ImageUrl { get; set; }
        public string? Text { get; set; }
    }

    private sealed class B2Auth
    {
        [JsonPropertyName("accountId")] public string AccountId { get; set; } = string.Empty;
        [JsonPropertyName("authorizationToken")] public string AuthorizationToken { get; set; } = string.Empty;
        [JsonPropertyName("apiUrl")] public string ApiUrl { get; set; } = string.Empty;
        [JsonPropertyName("downloadUrl")] public string DownloadUrl { get; set; } = string.Empty;
    }

    private sealed class B2BucketList
    {
        [JsonPropertyName("buckets")] public List<B2Bucket> Buckets { get; set; } = [];
    }

    private sealed class B2Bucket
    {
        [JsonPropertyName("bucketId")] public string BucketId { get; set; } = string.Empty;
    }

    private sealed class B2UploadUrl
    {
        [JsonPropertyName("uploadUrl")] public string UploadUrl { get; set; } = string.Empty;
        [JsonPropertyName("authorizationToken")] public string AuthorizationToken { get; set; } = string.Empty;
    }

    private sealed class B2FileList
    {
        [JsonPropertyName("files")] public List<B2File> Files { get; set; } = [];
        [JsonPropertyName("nextFileName")] public string? NextFileName { get; set; }
    }

    private sealed class B2File
    {
        [JsonPropertyName("fileName")] public string FileName { get; set; } = string.Empty;
        [JsonPropertyName("fileId")] public string FileId { get; set; } = string.Empty;
    }
}
