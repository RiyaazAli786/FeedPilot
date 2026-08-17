param(
    [string]$BackendUrl = "https://feedpilot-api-ount.onrender.com",
    [string]$AdminKey = "",
    [string]$ReleaseNotes = "",
    [string]$ForceUpdate = ""
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Net.Http

function Get-HmacSha256Base64([string]$Key, [string]$NonceBase64) {
    $keyBytes = [Text.Encoding]::UTF8.GetBytes($Key)
    $nonceBytes = [Convert]::FromBase64String($NonceBase64)
    $hmac = [System.Security.Cryptography.HMACSHA256]::new($keyBytes)
    try {
        return [Convert]::ToBase64String($hmac.ComputeHash($nonceBytes))
    }
    finally {
        $hmac.Dispose()
    }
}

function Get-AdminSession([string]$BaseUrl, [string]$Key) {
    $challenge = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/admin/auth/challenge"
    $hash = Get-HmacSha256Base64 -Key $Key -NonceBase64 $challenge.nonce
    $body = @{
        token = $challenge.token
        hash = $hash
    } | ConvertTo-Json

    $login = Invoke-RestMethod `
        -Method Post `
        -Uri "$BaseUrl/api/admin/auth/login" `
        -ContentType "application/json" `
        -Body $body

    return $login.sessionToken
}

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$androidDir = Join-Path $root "android-client"
$updateOut = Join-Path $androidDir "app\build\outputs\update\release"

$BackendUrl = $BackendUrl.TrimEnd("/")

if ([string]::IsNullOrWhiteSpace($AdminKey)) {
    $AdminKey = [Environment]::GetEnvironmentVariable("FEEDPILOT_ADMIN_KEY")
}
if ([string]::IsNullOrWhiteSpace($AdminKey)) {
    $secure = Read-Host "Render admin key" -AsSecureString
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try {
        $AdminKey = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    }
    finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    }
}

if ([string]::IsNullOrWhiteSpace($ReleaseNotes)) {
    $ReleaseNotes = Read-Host "Release notes (Enter for default)"
    if ([string]::IsNullOrWhiteSpace($ReleaseNotes)) {
        $ReleaseNotes = "Bug fixes and stability improvements."
    }
}

if ([string]::IsNullOrWhiteSpace($ForceUpdate)) {
    $ForceUpdate = Read-Host "Force update? true/false (Enter for false)"
    if ([string]::IsNullOrWhiteSpace($ForceUpdate)) {
        $ForceUpdate = "false"
    }
}

Write-Host "======================================================="
Write-Host "FeedPilot - Render B2 Auto Update Deploy"
Write-Host "======================================================="
Write-Host "Backend : $BackendUrl"
Write-Host "Force   : $ForceUpdate"
Write-Host ""

Write-Host "[1/4] Building release package..."
Push-Location $androidDir
try {
    & .\gradlew.bat --stop
    & .\gradlew.bat clean prepareReleaseUpdate "-PupdateBaseApkUrl=$BackendUrl/api/apk" "-PupdateReleaseNotes=$ReleaseNotes" "-PupdateForce=$ForceUpdate" --no-configuration-cache --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle auto-update build failed."
    }
}
finally {
    Pop-Location
}

$apk = Get-ChildItem -Path $updateOut -Filter "feedpilot-*.apk" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($null -eq $apk) {
    throw "No generated FeedPilot APK found in $updateOut."
}

$metadataPath = Join-Path $updateOut "update-metadata.json"
if (!(Test-Path $metadataPath)) {
    throw "No update-metadata.json found in $updateOut."
}
$metadata = Get-Content -Raw $metadataPath | ConvertFrom-Json

Write-Host "[2/4] Authenticating with Render backend admin..."
$session = Get-AdminSession -BaseUrl $BackendUrl -Key $AdminKey

Write-Host "[3/4] Uploading APK to Render backend. Backend will place it in B2..."
$client = [System.Net.Http.HttpClient]::new()
$content = [System.Net.Http.MultipartFormDataContent]::new()
$fileStream = [System.IO.File]::OpenRead($apk.FullName)
try {
    $client.DefaultRequestHeaders.Add("X-Admin-Session", $session)

    $fileContent = [System.Net.Http.StreamContent]::new($fileStream)
    $fileContent.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse("application/vnd.android.package-archive")
    $content.Add($fileContent, "apk", $apk.Name)
    $content.Add([System.Net.Http.StringContent]::new("$($metadata.versionCode)"), "versionCode")
    $content.Add([System.Net.Http.StringContent]::new("$($metadata.versionName)"), "versionName")
    $content.Add([System.Net.Http.StringContent]::new("$($metadata.releaseNotes)"), "releaseNotes")
    $content.Add([System.Net.Http.StringContent]::new("$($metadata.forceUpdate)"), "forceUpdate")

    $response = $client.PostAsync("$BackendUrl/api/admin/app-releases/upload", $content).GetAwaiter().GetResult()
    $responseText = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    if (-not $response.IsSuccessStatusCode) {
        throw "Upload failed: HTTP $([int]$response.StatusCode) $($response.ReasonPhrase) $responseText"
    }
    $release = $responseText | ConvertFrom-Json
}
finally {
    $content.Dispose()
    $client.Dispose()
    $fileStream.Dispose()
}

Write-Host "[4/4] Done."
Write-Host ""
Write-Host "Version : $($release.versionName) ($($release.versionCode))"
Write-Host "APK URL : $($release.apkUrl)"
Write-Host "API     : $BackendUrl/api/version"
Write-Host ""
Write-Host "Commit the bumped version file:"
Write-Host "  git add android-client\release-update.properties"
Write-Host "  git commit -m `"Prepare auto update $($release.versionName)`""
Write-Host "  git push origin main"
