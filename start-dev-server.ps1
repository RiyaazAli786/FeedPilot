# FeedPilot Backend Development Server Launcher
$ErrorActionPreference = "Stop"

Write-Host "====================================================" -ForegroundColor Cyan
Write-Host "🚀 Starting FeedPilot Backend Development Server..." -ForegroundColor Green
Write-Host "====================================================" -ForegroundColor Cyan

# Auto-free port 5000 if currently occupied by a background dev server instance
try {
    $portProcesses = Get-NetTCPConnection -LocalPort 5000 -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique
    foreach ($pidToKill in $portProcesses) {
        if ($pidToKill -gt 0 -and $pidToKill -ne $PID) {
            Write-Host "Freeing port 5000 (stopping existing PID $pidToKill)..." -ForegroundColor Yellow
            Stop-Process -Id $pidToKill -Force -ErrorAction SilentlyContinue
        }
    }
} catch {}

$env:ASPNETCORE_ENVIRONMENT = "Development"
$projectPath = Join-Path $PSScriptRoot "backend\src\FeedPilot.Api\FeedPilot.Api.csproj"

Write-Host "Environment : Development" -ForegroundColor Yellow
Write-Host "Project     : $projectPath" -ForegroundColor Yellow
Write-Host "Database    : feedpilot-dev.db (SQLite local)" -ForegroundColor Yellow
Write-Host "URL         : http://localhost:5000" -ForegroundColor Green
Write-Host "Dashboard   : http://localhost:5000/dashboard" -ForegroundColor Green
Write-Host "Swagger UI  : http://localhost:5000/swagger" -ForegroundColor Green
Write-Host "Order Fetch : SMM Panel auto-pull enabled" -ForegroundColor Green
Write-Host "====================================================" -ForegroundColor Cyan

Set-Location -Path (Join-Path $PSScriptRoot "backend\src\FeedPilot.Api")
dotnet run --project FeedPilot.Api.csproj
