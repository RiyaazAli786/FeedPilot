@echo off
setlocal enabledelayedexpansion

set "ROOT=%~dp0"
cd /d "%ROOT%"

echo =======================================================
echo FeedPilot - Push and Auto Release APK
echo =======================================================
echo.

set "MSG=%~1"
if "!MSG!"=="" (
    set /p MSG="Enter commit message: "
)
if "!MSG!"=="" set "MSG=Update FeedPilot and release new APK"

echo.
echo [1/3] Adding changes to Git...
git add -A

echo [2/3] Committing changes...
git commit -m "!MSG!"

echo [3/3] Pushing to origin main...
git push origin main
if errorlevel 1 (
    echo.
    echo [ERROR] Git push failed. Please check your network and git credentials.
    exit /b 1
)

echo.
echo =======================================================
echo SUCCESS: Code pushed to main!
echo.
echo GitHub Actions will now automatically:
echo   1. Build the signed release APK
echo   2. Bump version and publish GitHub Release
echo   3. Deploy APK to https://bridge.reelsflow.io/apk/
echo.
echo All running FeedPilot apps will automatically detect
echo and download the new update!
echo =======================================================
echo.
endlocal

