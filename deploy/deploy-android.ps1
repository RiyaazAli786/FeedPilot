<#
 One-click Android release -> signed APK pointing at https://bridge.reelsflow.io, published for in-app updates.
 Usage:  .\deploy\deploy-android.ps1 [-ReleaseNotes "..."] [-ForceUpdate] [-SkipUpload]
 Output: artifacts\feedpilot-<version>-<code>.apk  (+ copy served at https://bridge.reelsflow.io/apk/)
#>
param(
    [string]$ReleaseNotes = "Moved to bridge.reelsflow.io.",
    [switch]$ForceUpdate,
    [switch]$SkipUpload
)
. "$PSScriptRoot\common.ps1"

$cfg = Get-DeployConfig
$domain = $cfg.DOMAIN
$androidDir = Join-Path $script:RepoRoot "android-client"
$updateOut = Join-Path $androidDir "app\build\outputs\update\release"
$force = if ($ForceUpdate) { "true" } else { "false" }

Write-Host "=== FeedPilot Android release (API https://$domain) ==="
Write-Host "[1/3] Building signed release APK..."
Push-Location $androidDir
try {
    & .\gradlew.bat clean prepareReleaseUpdate "-PupdateBaseApkUrl=https://$domain/apk" "-PupdateReleaseNotes=$ReleaseNotes" "-PupdateForce=$force" --no-configuration-cache --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle release build failed." }
}
finally { Pop-Location }

$apk = Get-ChildItem $updateOut -Filter "feedpilot-*.apk" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $apk) { throw "No APK produced in $updateOut" }
$meta = Join-Path $updateOut "update-metadata.json"

$artifacts = Join-Path $script:RepoRoot "artifacts"
New-Item -ItemType Directory -Force $artifacts | Out-Null
Copy-Item $apk.FullName $artifacts -Force
Copy-Item $meta (Join-Path $artifacts "update-metadata.json") -Force
Write-Host "[2/3] Release APK: $(Join-Path $artifacts $apk.Name)"

if ($SkipUpload) { Write-Host "Skipped upload (-SkipUpload)."; return }

Write-Host "[3/3] Publishing to the server for in-app updates..."
Invoke-Remote $cfg "mkdir -p $($cfg.APP_DIR)/apk && rm -f $($cfg.APP_DIR)/apk/feedpilot-*.apk"
Copy-ToRemote $cfg @($apk.FullName, $meta) "$($cfg.APP_DIR)/apk"
# The API seeds /api/version from apk/update-metadata.json on startup.
Invoke-Remote $cfg "docker restart $($cfg.APP_NAME) >/dev/null"
Start-Sleep -Seconds 8
$v = Invoke-RestMethod "https://$domain/api/version" -TimeoutSec 30
Write-Host ("Live version: " + ($v | ConvertTo-Json -Compress))
