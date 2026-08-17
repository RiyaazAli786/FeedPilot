using System.Text;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using FeedPilot.Api.Data;
using FeedPilot.Api.Dtos;

namespace FeedPilot.Api.Controllers;

[ApiController]
[AllowAnonymous]
[Route("api")]
public class VersionController : ControllerBase
{
    private readonly AppDbContext _db;
    public VersionController(AppDbContext db) => _db = db;

    /// <summary>Returns the latest release metadata used by the auto-update flow.</summary>
    [HttpGet("version")]
    public async Task<ActionResult<VersionDto>> Version(CancellationToken ct)
    {
        var release = await _db.AppReleases
            .OrderByDescending(r => r.VersionCode)
            .FirstOrDefaultAsync(ct);
        if (release is null) return NoContent();

        return Ok(new VersionDto(release.VersionCode, release.VersionName, release.ApkUrl,
            release.Sha256, release.SizeBytes, release.ReleaseNotes, release.ForceUpdate));
    }

    /// <summary>Returns all APK releases. Serves HTML cards view to browsers, JSON array if requested via JSON header/format.</summary>
    [HttpGet("versions")]
    public async Task<IActionResult> AllVersions([FromQuery] string? format, CancellationToken ct)
    {
        var accept = Request.Headers.Accept.ToString();
        var preferJson = format?.Equals("json", StringComparison.OrdinalIgnoreCase) == true ||
                         (accept.Contains("application/json") && !accept.Contains("text/html"));

        if (!preferJson)
        {
            return await VersionsHtml(ct);
        }

        var releases = await _db.AppReleases
            .OrderByDescending(r => r.VersionCode)
            .ToListAsync(ct);

        var dtos = releases.Select(r => new AppReleaseItemDto(
            r.VersionCode,
            r.VersionName,
            ExtractApkName(r.ApkUrl, r.VersionName),
            r.ApkUrl,
            $"/api/apk/{r.VersionCode}",
            r.Sha256,
            r.SizeBytes,
            r.ReleaseNotes,
            r.ForceUpdate,
            r.CreatedAt
        )).ToList();

        return Ok(dtos);
    }

    /// <summary>Redirects to the latest APK download URL.</summary>
    [HttpGet("apk/latest")]
    public async Task<IActionResult> LatestApk(CancellationToken ct)
    {
        var release = await _db.AppReleases
            .OrderByDescending(r => r.VersionCode)
            .FirstOrDefaultAsync(ct);
        if (release is null || string.IsNullOrWhiteSpace(release.ApkUrl))
            return NotFound(new ApiError("No release available.", "no_release"));

        return Redirect(release.ApkUrl);
    }

    /// <summary>Redirects to the APK download URL for a specific version code.</summary>
    [HttpGet("apk/{versionCode:int}")]
    [HttpGet("apk/version/{versionCode:int}")]
    public async Task<IActionResult> SpecificApk(int versionCode, CancellationToken ct)
    {
        var release = await _db.AppReleases
            .FirstOrDefaultAsync(r => r.VersionCode == versionCode, ct);

        if (release is null || string.IsNullOrWhiteSpace(release.ApkUrl))
            return NotFound(new ApiError($"No release found for version code {versionCode}.", "version_not_found"));

        return Redirect(release.ApkUrl);
    }

    /// <summary>Returns a web page listing all APK versions with details and download buttons.</summary>
    [HttpGet("versions/html")]
    [HttpGet("versionsHtml")]
    [HttpGet("/versions")]
    [HttpGet("/versionsHtml")]
    [HttpGet("/apk/versions")]
    [HttpGet("/versions/html")]
    public async Task<IActionResult> VersionsHtml(CancellationToken ct)
    {
        var releases = await _db.AppReleases
            .OrderByDescending(r => r.VersionCode)
            .ToListAsync(ct);

        var latestRelease = releases.FirstOrDefault();
        var latestVersionCode = latestRelease?.VersionCode ?? 0;

        var sb = new StringBuilder();
        sb.AppendLine("<!DOCTYPE html>");
        sb.AppendLine("<html lang=\"en\">");
        sb.AppendLine("<head>");
        sb.AppendLine("    <meta charset=\"UTF-8\">");
        sb.AppendLine("    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">");
        sb.AppendLine("    <title>FeedPilot - APK Release Center & Downloads</title>");
        sb.AppendLine("    <link rel=\"icon\" type=\"image/svg+xml\" href=\"/dashboard/favicon.svg\">");
        sb.AppendLine("    <link rel=\"preconnect\" href=\"https://fonts.googleapis.com\">");
        sb.AppendLine("    <link rel=\"preconnect\" href=\"https://fonts.gstatic.com\" crossorigin>");
        sb.AppendLine("    <link href=\"https://fonts.googleapis.com/css2?family=Plus+Jakarta+Sans:wght@400;500;600;700;800&display=swap\" rel=\"stylesheet\">");
        sb.AppendLine("    <style>");
        sb.AppendLine("        :root {");
        sb.AppendLine("            --bg-color: #090D16;");
        sb.AppendLine("            --card-bg: rgba(22, 28, 42, 0.85);");
        sb.AppendLine("            --card-border: rgba(255, 255, 255, 0.08);");
        sb.AppendLine("            --card-border-hover: rgba(59, 130, 246, 0.4);");
        sb.AppendLine("            --accent-blue: #3B82F6;");
        sb.AppendLine("            --accent-cyan: #06B6D4;");
        sb.AppendLine("            --accent-gradient: linear-gradient(135deg, #60A5FA 0%, #3B82F6 50%, #2563EB 100%);");
        sb.AppendLine("            --btn-gradient: linear-gradient(135deg, #3B82F6 0%, #1D4ED8 100%);");
        sb.AppendLine("            --btn-gradient-hover: linear-gradient(135deg, #60A5FA 0%, #2563EB 100%);");
        sb.AppendLine("            --text-main: #F9FAFB;");
        sb.AppendLine("            --text-muted: #9CA3AF;");
        sb.AppendLine("            --badge-latest-bg: rgba(16, 185, 129, 0.15);");
        sb.AppendLine("            --badge-latest-border: rgba(16, 185, 129, 0.35);");
        sb.AppendLine("            --badge-latest-text: #34D399;");
        sb.AppendLine("            --badge-force-bg: rgba(239, 68, 68, 0.15);");
        sb.AppendLine("            --badge-force-border: rgba(239, 68, 68, 0.35);");
        sb.AppendLine("            --badge-force-text: #F87171;");
        sb.AppendLine("        }");
        sb.AppendLine("        * { box-sizing: border-box; margin: 0; padding: 0; }");
        sb.AppendLine("        body { font-family: 'Plus Jakarta Sans', system-ui, -apple-system, sans-serif; background-color: var(--bg-color); color: var(--text-main); line-height: 1.6; padding: 36px 16px 60px; background-image: radial-gradient(circle at 50% 0%, rgba(59, 130, 246, 0.15) 0%, transparent 60%); min-height: 100vh; }");
        sb.AppendLine("        .container { max-width: 880px; margin: 0 auto; }");
        sb.AppendLine("        header { text-align: center; margin-bottom: 40px; }");
        sb.AppendLine("        .logo { font-size: 34px; font-weight: 800; background: var(--accent-gradient); -webkit-background-clip: text; -webkit-text-fill-color: transparent; margin-bottom: 8px; letter-spacing: -0.8px; display: inline-flex; align-items: center; gap: 10px; }");
        sb.AppendLine("        .subtitle { color: var(--text-muted); font-size: 15px; font-weight: 500; max-width: 500px; margin: 0 auto 20px; }");
        sb.AppendLine("        .quick-banner { background: rgba(59, 130, 246, 0.1); border: 1px solid rgba(59, 130, 246, 0.25); border-radius: 14px; padding: 14px 20px; display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 30px; flex-wrap: wrap; backdrop-filter: blur(8px); }");
        sb.AppendLine("        .quick-banner-text { font-size: 14px; font-weight: 600; color: #E0E7FF; display: flex; align-items: center; gap: 8px; }");
        sb.AppendLine("        .btn-quick-download { background: var(--btn-gradient); color: #FFF; font-weight: 700; font-size: 13px; padding: 8px 18px; border-radius: 8px; text-decoration: none; display: inline-flex; align-items: center; gap: 6px; transition: all 0.2s ease; box-shadow: 0 4px 12px rgba(59, 130, 246, 0.3); }");
        sb.AppendLine("        .btn-quick-download:hover { transform: translateY(-1px); box-shadow: 0 6px 18px rgba(59, 130, 246, 0.45); }");
        sb.AppendLine("        .release-card { background: var(--card-bg); border: 1px solid var(--card-border); border-radius: 20px; padding: 26px; margin-bottom: 24px; backdrop-filter: blur(12px); box-shadow: 0 12px 30px -10px rgba(0,0,0,0.5); transition: transform 0.25s ease, border-color 0.25s ease, box-shadow 0.25s ease; }");
        sb.AppendLine("        .release-card:hover { border-color: var(--card-border-hover); transform: translateY(-3px); box-shadow: 0 16px 36px -8px rgba(59, 130, 246, 0.15); }");
        sb.AppendLine("        .card-header { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; margin-bottom: 16px; padding-bottom: 14px; border-bottom: 1px solid rgba(255,255,255,0.06); }");
        sb.AppendLine("        .version-title { font-size: 22px; font-weight: 800; color: var(--text-main); display: flex; align-items: center; gap: 10px; letter-spacing: -0.4px; }");
        sb.AppendLine("        .version-code-tag { font-size: 13px; color: var(--text-muted); font-weight: 500; background: rgba(255,255,255,0.05); padding: 2px 10px; border-radius: 6px; }");
        sb.AppendLine("        .badges { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }");
        sb.AppendLine("        .badge { font-size: 11px; font-weight: 700; text-transform: uppercase; padding: 4px 12px; border-radius: 20px; letter-spacing: 0.5px; display: inline-flex; align-items: center; gap: 6px; }");
        sb.AppendLine("        .badge-latest { background: var(--badge-latest-bg); color: var(--badge-latest-text); border: 1px solid var(--badge-latest-border); }");
        sb.AppendLine("        .badge-latest::before { content: ''; width: 6px; height: 6px; background-color: var(--badge-latest-text); border-radius: 50%; display: inline-block; box-shadow: 0 0 8px var(--badge-latest-text); }");
        sb.AppendLine("        .badge-force { background: var(--badge-force-bg); color: var(--badge-force-text); border: 1px solid var(--badge-force-border); }");
        sb.AppendLine("        .badge-old { background: rgba(156, 163, 175, 0.1); color: var(--text-muted); border: 1px solid rgba(156, 163, 175, 0.2); }");
        sb.AppendLine("        .apk-details { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 14px; margin-bottom: 20px; background: rgba(10, 14, 23, 0.6); padding: 16px 20px; border-radius: 12px; border: 1px solid rgba(255,255,255,0.04); }");
        sb.AppendLine("        .detail-item { font-size: 13px; }");
        sb.AppendLine("        .detail-label { color: var(--text-muted); font-size: 11px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.6px; margin-bottom: 4px; }");
        sb.AppendLine("        .detail-value { font-weight: 600; color: #E5E7EB; word-break: break-all; font-family: monospace, sans-serif; }");
        sb.AppendLine("        .hash-row { display: flex; align-items: center; gap: 6px; }");
        sb.AppendLine("        .btn-copy-sm { background: rgba(255,255,255,0.08); border: none; color: #D1D5DB; padding: 2px 6px; border-radius: 4px; cursor: pointer; font-size: 11px; transition: background 0.15s ease; }");
        sb.AppendLine("        .btn-copy-sm:hover { background: rgba(255,255,255,0.18); color: #FFF; }");
        sb.AppendLine("        .release-notes-box { font-size: 14px; color: #E5E7EB; margin-bottom: 22px; background: rgba(59, 130, 246, 0.05); padding: 14px 18px; border-radius: 12px; border-left: 4px solid var(--accent-blue); border: 1px solid rgba(59, 130, 246, 0.12); border-left-width: 4px; }");
        sb.AppendLine("        .release-notes-title { font-weight: 700; font-size: 12px; text-transform: uppercase; color: #93C5FD; letter-spacing: 0.5px; margin-bottom: 6px; display: flex; align-items: center; gap: 6px; }");
        sb.AppendLine("        .action-row { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }");
        sb.AppendLine("        .btn-download { display: inline-flex; align-items: center; justify-content: center; gap: 10px; background: var(--btn-gradient); color: #FFFFFF; font-weight: 700; font-size: 14px; padding: 12px 24px; border-radius: 12px; text-decoration: none; transition: all 0.2s ease; box-shadow: 0 4px 16px rgba(59, 130, 246, 0.35); flex: 1; min-width: 240px; }");
        sb.AppendLine("        .btn-download:hover { background: var(--btn-gradient-hover); box-shadow: 0 6px 22px rgba(59, 130, 246, 0.5); transform: translateY(-1px); }");
        sb.AppendLine("        .btn-secondary { display: inline-flex; align-items: center; justify-content: center; gap: 8px; background: rgba(255, 255, 255, 0.06); color: #D1D5DB; font-weight: 600; font-size: 13px; padding: 12px 18px; border-radius: 12px; text-decoration: none; border: 1px solid rgba(255,255,255,0.08); transition: all 0.2s ease; cursor: pointer; }");
        sb.AppendLine("        .btn-secondary:hover { background: rgba(255, 255, 255, 0.12); color: #FFF; border-color: rgba(255,255,255,0.18); }");
        sb.AppendLine("        .toast { position: fixed; bottom: 24px; right: 24px; background: #10B981; color: #FFF; font-weight: 700; font-size: 13px; padding: 10px 20px; border-radius: 10px; box-shadow: 0 10px 25px rgba(0,0,0,0.3); opacity: 0; transform: translateY(20px); transition: all 0.3s ease; pointer-events: none; z-index: 100; }");
        sb.AppendLine("        .toast.show { opacity: 1; transform: translateY(0); }");
        sb.AppendLine("        .empty-state { text-align: center; padding: 60px 24px; color: var(--text-muted); font-size: 16px; background: var(--card-bg); border-radius: 20px; border: 1px solid var(--card-border); }");
        sb.AppendLine("        footer { text-align: center; margin-top: 50px; color: var(--text-muted); font-size: 13px; font-weight: 500; }");
        sb.AppendLine("    </style>");
        sb.AppendLine("</head>");
        sb.AppendLine("<body>");
        sb.AppendLine("    <div class=\"container\">");
        sb.AppendLine("        <header>");
        sb.AppendLine("            <div class=\"logo\">");
        sb.AppendLine("                <svg width=\"32\" height=\"32\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"url(#grad1)\" stroke-width=\"2.5\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><defs><linearGradient id=\"grad1\" x1=\"0%\" y1=\"0%\" x2=\"100%\" y2=\"100%\"><stop offset=\"0%\" stop-color=\"#60A5FA\" /><stop offset=\"100%\" stop-color=\"#2563EB\" /></linearGradient></defs><path d=\"M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4\"></path><polyline points=\"7 10 12 15 17 10\"></polyline><line x1=\"12\" y1=\"15\" x2=\"12\" y2=\"3\"></line></svg>");
        sb.AppendLine("                FeedPilot APK Release Center");
        sb.AppendLine("            </div>");
        sb.AppendLine("            <div class=\"subtitle\">Official Android Application Releases, Build Archives & Instant APK Downloads</div>");
        sb.AppendLine("        </header>");

        if (latestRelease != null)
        {
            var latestApkName = ExtractApkName(latestRelease.ApkUrl, latestRelease.VersionName);
            var latestDownloadUrl = string.IsNullOrWhiteSpace(latestRelease.ApkUrl) ? $"/api/apk/{latestRelease.VersionCode}" : latestRelease.ApkUrl;

            sb.AppendLine("        <div class=\"quick-banner\">");
            sb.AppendLine("            <div class=\"quick-banner-text\">");
            sb.AppendLine("                <svg width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"#60A5FA\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><circle cx=\"12\" cy=\"12\" r=\"10\"></circle><line x1=\"12\" y1=\"16\" x2=\"12\" y2=\"12\"></line><line x1=\"12\" y1=\"8\" x2=\"12.01\" y2=\"8\"></line></svg>");
            sb.AppendLine($"                Latest Build Available: <strong>v{latestRelease.VersionName} (Build {latestRelease.VersionCode})</strong>");
            sb.AppendLine("            </div>");
            sb.AppendLine($"            <a href=\"{latestDownloadUrl}\" class=\"btn-quick-download\" download=\"{latestApkName}\">");
            sb.AppendLine("                <svg width=\"16\" height=\"16\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.5\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4\"></path><polyline points=\"7 10 12 15 17 10\"></polyline><line x1=\"12\" y1=\"15\" x2=\"12\" y2=\"3\"></line></svg>");
            sb.AppendLine($"                Download Latest APK ({latestRelease.VersionName})");
            sb.AppendLine("            </a>");
            sb.AppendLine("        </div>");
        }

        if (!releases.Any())
        {
            sb.AppendLine("        <div class=\"empty-state\">No APK releases available at this time.</div>");
        }
        else
        {
            foreach (var r in releases)
            {
                var apkName = ExtractApkName(r.ApkUrl, r.VersionName);
                var isLatest = r.VersionCode == latestVersionCode;
                var sizeMb = r.SizeBytes > 0 ? (r.SizeBytes / (1024.0 * 1024.0)).ToString("0.00") + " MB" : "N/A";
                var downloadUrl = string.IsNullOrWhiteSpace(r.ApkUrl) ? $"/api/apk/{r.VersionCode}" : r.ApkUrl;
                var shortHash = !string.IsNullOrWhiteSpace(r.Sha256) && r.Sha256.Length > 12 ? r.Sha256.Substring(0, 12) + "..." : (r.Sha256 ?? "N/A");

                sb.AppendLine("        <div class=\"release-card\">");
                sb.AppendLine("            <div class=\"card-header\">");
                sb.AppendLine("                <div class=\"version-title\">");
                sb.AppendLine($"                    v{System.Net.WebUtility.HtmlEncode(r.VersionName)}");
                sb.AppendLine($"                    <span class=\"version-code-tag\">Build #{r.VersionCode}</span>");
                sb.AppendLine("                </div>");
                sb.AppendLine("                <div class=\"badges\">");
                if (isLatest) sb.AppendLine("                    <span class=\"badge badge-latest\">Latest Release</span>");
                else sb.AppendLine("                    <span class=\"badge badge-old\">Archived</span>");
                if (r.ForceUpdate) sb.AppendLine("                    <span class=\"badge badge-force\">Required Update</span>");
                sb.AppendLine("                </div>");
                sb.AppendLine("            </div>");

                sb.AppendLine("            <div class=\"apk-details\">");
                sb.AppendLine($"                <div class=\"detail-item\"><div class=\"detail-label\">Package Name</div><div class=\"detail-value\">{System.Net.WebUtility.HtmlEncode(apkName)}</div></div>");
                sb.AppendLine($"                <div class=\"detail-item\"><div class=\"detail-label\">File Size</div><div class=\"detail-value\">{sizeMb}</div></div>");
                sb.AppendLine($"                <div class=\"detail-item\"><div class=\"detail-label\">Release Date</div><div class=\"detail-value\">{r.CreatedAt:MMM dd, yyyy UTC}</div></div>");
                sb.AppendLine($"                <div class=\"detail-item\"><div class=\"detail-label\">SHA-256 Checksum</div><div class=\"detail-value hash-row\"><span>{shortHash}</span> <button class=\"btn-copy-sm\" onclick=\"copyText('{System.Net.WebUtility.HtmlEncode(r.Sha256 ?? "")}', 'SHA-256 copied!')\" title=\"Copy full SHA-256\">Copy</button></div></div>");
                sb.AppendLine("            </div>");

                var notesContent = string.IsNullOrWhiteSpace(r.ReleaseNotes) ? "Bug fixes and performance improvements." : r.ReleaseNotes;
                var formattedNotes = System.Net.WebUtility.HtmlEncode(notesContent).Replace("\n", "<br>");

                sb.AppendLine("            <div class=\"release-notes-box\">");
                sb.AppendLine("                <div class=\"release-notes-title\">");
                sb.AppendLine("                    <svg width=\"14\" height=\"14\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.5\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M14 2H6a2 2 0 0 2-2 2v16a2 2 0 0 2 2 2h12a2 2 0 0 2 2-2V8z\"></path><polyline points=\"14 2 14 8 20 8\"></polyline><line x1=\"16\" y1=\"13\" x2=\"8\" y2=\"13\"></line><line x1=\"16\" y1=\"17\" x2=\"8\" y2=\"17\"></line></svg>");
                sb.AppendLine("                    Release Note Summary");
                sb.AppendLine("                </div>");
                sb.AppendLine($"                <div>{formattedNotes}</div>");
                sb.AppendLine("            </div>");

                sb.AppendLine("            <div class=\"action-row\">");
                sb.AppendLine($"                <a href=\"{downloadUrl}\" class=\"btn-download\" download=\"{apkName}\">");
                sb.AppendLine("                    <svg width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.5\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4\"></path><polyline points=\"7 10 12 15 17 10\"></polyline><line x1=\"12\" y1=\"15\" x2=\"12\" y2=\"3\"></line></svg>");
                sb.AppendLine($"                    Download {System.Net.WebUtility.HtmlEncode(apkName)}");
                sb.AppendLine("                </a>");
                sb.AppendLine($"                <button class=\"btn-secondary\" onclick=\"copyText('{downloadUrl}', 'Download link copied!')\">");
                sb.AppendLine("                    <svg width=\"16\" height=\"16\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><rect x=\"9\" y=\"9\" width=\"13\" height=\"13\" rx=\"2\" ry=\"2\"></rect><path d=\"M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1\"></path></svg>");
                sb.AppendLine("                    Copy Link");
                sb.AppendLine("                </button>");
                sb.AppendLine("            </div>");
                sb.AppendLine("        </div>");
            }
        }

        sb.AppendLine("        <footer>FeedPilot - High-Performance Automation Platform</footer>");
        sb.AppendLine("    </div>");

        sb.AppendLine("    <div id=\"toast\" class=\"toast\">Copied to clipboard!</div>");

        sb.AppendLine("    <script>");
        sb.AppendLine("        function copyText(text, message) {");
        sb.AppendLine("            if(!text) return;");
        sb.AppendLine("            navigator.clipboard.writeText(text).then(() => {");
        sb.AppendLine("                const toast = document.getElementById('toast');");
        sb.AppendLine("                toast.innerText = message || 'Copied!';");
        sb.AppendLine("                toast.classList.add('show');");
        sb.AppendLine("                setTimeout(() => toast.classList.remove('show'), 2200);");
        sb.AppendLine("            });");
        sb.AppendLine("        }");
        sb.AppendLine("    </script>");
        sb.AppendLine("</body>");
        sb.AppendLine("</html>");

        return Content(sb.ToString(), "text/html");
    }

    private static string ExtractApkName(string apkUrl, string versionName)
    {
        if (string.IsNullOrWhiteSpace(apkUrl)) return $"FeedPilot-v{versionName}.apk";
        try
        {
            var uri = new Uri(apkUrl, UriKind.RelativeOrAbsolute);
            var filename = System.IO.Path.GetFileName(uri.IsAbsoluteUri ? uri.AbsolutePath : uri.OriginalString);
            if (!string.IsNullOrWhiteSpace(filename) && filename.EndsWith(".apk", StringComparison.OrdinalIgnoreCase))
                return filename;
        }
        catch { }

        return $"FeedPilot-v{versionName}.apk";
    }
}
