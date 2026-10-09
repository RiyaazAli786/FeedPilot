@echo off
REM One-click deploy of FeedPilot to https://bridge.reelsflow.io (backend, then Android release APK).
REM Secrets: put SSH_PASSWORD in deploy\.deploy-secrets.env, or you'll be prompted.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy\deploy-backend.ps1" %* || exit /b 1
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy\deploy-android.ps1" || exit /b 1
echo.
echo Done. Release APK is in %~dp0artifacts
