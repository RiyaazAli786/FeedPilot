@echo off
title FeedPilot Development Backend Server
echo ====================================================
echo Starting FeedPilot Backend Development Server...
echo ====================================================

:: Clean up any previous instance holding port 5000
for /f "tokens=5" %%a in ('netstat -aon ^| findstr :5000 ^| findstr LISTENING') do (
    echo Freeing port 5000 - stopping PID %%a
    taskkill /f /pid %%a >nul 2>&1
)

set ASPNETCORE_ENVIRONMENT=Development
cd /d "%~dp0backend\src\FeedPilot.Api"
dotnet run --project FeedPilot.Api.csproj
pause
