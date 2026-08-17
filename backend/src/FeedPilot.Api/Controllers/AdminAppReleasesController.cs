using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Serialization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using FeedPilot.Api.Data;
using FeedPilot.Api.Domain;
using FeedPilot.Api.Dtos;
using FeedPilot.Api.Services;

namespace FeedPilot.Api.Controllers;

[ApiController]
[AdminSession]
[Route("api/admin/app-releases")]
public class AdminAppReleasesController : ControllerBase
{
    private readonly AppDbContext _db;
    private readonly IHttpClientFactory _http;
    private readonly UpgradeAssetStorageSettings _settings;

    public AdminAppReleasesController(
        AppDbContext db,
        IHttpClientFactory http,
        IOptions<UpgradeAssetStorageSettings> settings)
    {
        _db = db;
        _http = http;
        _settings = settings.Value;
    }

    [HttpPost("upload")]
    [RequestSizeLimit(300_000_000)]
    [RequestFormLimits(MultipartBodyLengthLimit = 300_000_000)]
    public async Task<ActionResult<VersionDto>> Upload(
        [FromForm] IFormFile apk,
        [FromForm] int versionCode,
        [FromForm] string versionName,
        [FromForm] string? releaseNotes,
        [FromForm] bool forceUpdate,
        CancellationToken ct)
    {
        if (apk.Length <= 0)
            return BadRequest(new ApiError("APK file is required.", "APK_REQUIRED"));
        if (versionCode <= 0 || string.IsNullOrWhiteSpace(versionName))
            return BadRequest(new ApiError("Version code and version name are required.", "INVALID_VERSION"));
        if (!UseB2())
            return StatusCode(StatusCodes.Status503ServiceUnavailable,
                new ApiError("B2 asset storage is not configured on this backend.", "B2_NOT_CONFIGURED"));

        await using var input = apk.OpenReadStream();
        using var ms = new MemoryStream();
        await input.CopyToAsync(ms, ct);
        var bytes = ms.ToArray();

        var fileName = $"feedpilot-{versionName.Trim()}-{versionCode}.apk";
        var objectName = $"{Prefix()}/apk/{fileName}";
        await UploadB2Async(objectName, bytes, "application/vnd.android.package-archive", ct);

        var apkUrl = await PublicB2UrlAsync(objectName, ct);
        var sha256 = Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();

        var release = await _db.AppReleases.FirstOrDefaultAsync(r => r.VersionCode == versionCode, ct);
        if (release is null)
        {
            release = new AppRelease { VersionCode = versionCode };
            _db.AppReleases.Add(release);
        }

        release.VersionName = versionName.Trim();
        release.ApkUrl = apkUrl;
        release.Sha256 = sha256;
        release.SizeBytes = bytes.LongLength;
        release.ReleaseNotes = string.IsNullOrWhiteSpace(releaseNotes) ? null : releaseNotes.Trim();
        release.ForceUpdate = forceUpdate;

        await _db.SaveChangesAsync(ct);

        return Ok(new VersionDto(
            release.VersionCode,
            release.VersionName,
            release.ApkUrl,
            release.Sha256,
            release.SizeBytes,
            release.ReleaseNotes,
            release.ForceUpdate));
    }

    private bool UseB2() =>
        _settings.Provider.Equals("B2", StringComparison.OrdinalIgnoreCase) &&
        !string.IsNullOrWhiteSpace(Clean(_settings.KeyId)) &&
        !string.IsNullOrWhiteSpace(Clean(_settings.ApplicationKey)) &&
        !string.IsNullOrWhiteSpace(Clean(_settings.BucketName));

    private string Prefix() => string.IsNullOrWhiteSpace(_settings.Prefix) ? "upgrade" : _settings.Prefix.Trim('/');

    private async Task<B2Auth> AuthorizeB2Async(CancellationToken ct)
    {
        var client = _http.CreateClient("B2");
        using var req = new HttpRequestMessage(HttpMethod.Get, "https://api.backblazeb2.com/b2api/v2/b2_authorize_account");
        var basic = Convert.ToBase64String(Encoding.UTF8.GetBytes($"{Clean(_settings.KeyId)}:{Clean(_settings.ApplicationKey)}"));
        req.Headers.Authorization = new AuthenticationHeaderValue("Basic", basic);
        using var res = await client.SendAsync(req, ct);
        await EnsureB2SuccessAsync(res, "authorize account", ct);
        return (await res.Content.ReadFromJsonAsync<B2Auth>(cancellationToken: ct))!;
    }

    private async Task<string> BucketIdAsync(B2Auth auth, CancellationToken ct)
    {
        var configuredBucketId = Clean(_settings.BucketId);
        if (!string.IsNullOrWhiteSpace(configuredBucketId)) return configuredBucketId;

        var client = _http.CreateClient("B2");
        using var req = new HttpRequestMessage(HttpMethod.Post, $"{auth.ApiUrl}/b2api/v2/b2_list_buckets");
        req.Headers.TryAddWithoutValidation("Authorization", auth.AuthorizationToken);
        req.Content = JsonContent.Create(new { accountId = auth.AccountId, bucketName = Clean(_settings.BucketName) });
        using var res = await client.SendAsync(req, ct);
        await EnsureB2SuccessAsync(res, "list buckets", ct);
        var body = await res.Content.ReadFromJsonAsync<B2BucketList>(cancellationToken: ct);
        return body?.Buckets.FirstOrDefault()?.BucketId
            ?? throw new InvalidOperationException($"B2 bucket '{Clean(_settings.BucketName)}' was not found.");
    }

    private async Task UploadB2Async(string fileName, byte[] bytes, string contentType, CancellationToken ct)
    {
        var auth = await AuthorizeB2Async(ct);
        var bucketId = await BucketIdAsync(auth, ct);
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

    private async Task<string> PublicB2UrlAsync(string fileName, CancellationToken ct)
    {
        var auth = await AuthorizeB2Async(ct);
        var encodedPath = string.Join("/", fileName.Split('/').Select(Uri.EscapeDataString));
        return $"{auth.DownloadUrl.TrimEnd('/')}/file/{Uri.EscapeDataString(Clean(_settings.BucketName))}/{encodedPath}";
    }

    private static string Clean(string? value) => (value ?? string.Empty).Trim().Trim('"', '\'');

    private static async Task EnsureB2SuccessAsync(HttpResponseMessage response, string operation, CancellationToken ct)
    {
        if (response.IsSuccessStatusCode) return;

        var body = await response.Content.ReadAsStringAsync(ct);
        throw new InvalidOperationException(
            $"Backblaze B2 failed to {operation}: {(int)response.StatusCode} {response.ReasonPhrase}. {body}");
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
}
