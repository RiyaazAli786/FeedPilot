<#
 One-click backend deploy -> https://bridge.reelsflow.io
 Builds nothing locally: it ships backend/ source + the env file, and the server builds the Docker image,
 creates the `bridgefeed` database, (re)starts the container, wires nginx and TLS.
 Usage:  .\deploy\deploy-backend.ps1 [-EnvFile <path>]
#>
param([string]$EnvFile = "")
. "$PSScriptRoot\common.ps1"

$cfg = Get-DeployConfig
if ([string]::IsNullOrWhiteSpace($EnvFile)) { $EnvFile = Join-Path $script:RepoRoot "feedpilot-api-ount.env" }
if (!(Test-Path $EnvFile)) { throw "Env file not found: $EnvFile" }

Write-Host "=== FeedPilot backend -> https://$($cfg.DOMAIN) ($($cfg.SERVER_IP):$($cfg.APP_DIR)) ==="

$tmp = Join-Path ([IO.Path]::GetTempPath()) "bridgefeed-deploy"
Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory $tmp | Out-Null
$tar = Join-Path $tmp "backend.tar.gz"

Write-Host "[1/4] Packaging backend source..."
& (Join-Path $env:SystemRoot "System32\tar.exe") -czf $tar -C (Join-Path $script:RepoRoot "backend") --exclude=bin --exclude=obj --exclude=.vs --exclude="*.db" --exclude="*.db-shm" --exclude="*.db-wal" .
if ($LASTEXITCODE -ne 0) { throw "tar failed" }
# setup.sh must have LF endings to run on Linux.
$setup = Join-Path $tmp "setup.sh"
[IO.File]::WriteAllText($setup, ((Get-Content -Raw (Join-Path $PSScriptRoot "remote\setup.sh")) -replace "`r`n", "`n"))
$envBase = Join-Path $tmp ".env.base"
# `docker --env-file` keeps literal quotes (unlike dotenv), so strip a matching pair around each value:
# KEY="abc" must reach the container as abc, not "abc".
$envLines = foreach ($line in (Get-Content -LiteralPath $EnvFile)) {
    if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)=(["''])(.*)\2\s*$') { "$($Matches[1])=$($Matches[3])" } else { $line.TrimEnd() }
}
[IO.File]::WriteAllText($envBase, (($envLines -join "`n") + "`n"))

Write-Host "[2/4] Uploading..."
Invoke-Remote $cfg "mkdir -p $($cfg.APP_DIR)"
Copy-ToRemote $cfg @($tar, $setup, $envBase) $cfg.APP_DIR

Write-Host "[3/4] Building and starting on the server..."
$run = "cd $($cfg.APP_DIR) && chmod 600 .env.base && " +
       "DOMAIN=$($cfg.DOMAIN) APP_NAME=$($cfg.APP_NAME) APP_DIR=$($cfg.APP_DIR) DB_NAME=$($cfg.DB_NAME) DB_USER=$($cfg.DB_USER) APP_PORT=$($cfg.APP_PORT) bash setup.sh"
Invoke-Remote $cfg $run
Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue

Write-Host "[4/4] Verifying https://$($cfg.DOMAIN) ..."
$resp = Invoke-RestMethod "https://$($cfg.DOMAIN)/" -TimeoutSec 30
Write-Host ("Backend OK: " + ($resp | ConvertTo-Json -Compress))
Write-Host "Dashboard: https://$($cfg.DOMAIN)/dashboard"
