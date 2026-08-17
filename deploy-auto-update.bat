@echo off
setlocal enabledelayedexpansion

set "ROOT=%~dp0"
set "ANDROID_DIR=%ROOT%android-client"
set "UPDATE_OUT=%ANDROID_DIR%\app\build\outputs\update\release"
set "BACKEND_APK_DIR=%ROOT%backend\src\FeedPilot.Api\wwwroot\apk"
set "BASE_APK_URL=https://feedpilot-api-ount.onrender.com/apk"

echo =======================================================
echo FeedPilot - Render Auto Update Deploy File
echo =======================================================
echo This builds the next release APK, copies it into the
echo backend /apk static folder, and writes update metadata.
echo Render will serve it after you commit, push, and redeploy.
echo.

set "RELEASE_NOTES="
set /p RELEASE_NOTES="Release notes (Enter for default): "
if "!RELEASE_NOTES!"=="" set "RELEASE_NOTES=Bug fixes and stability improvements."

set "FORCE_UPDATE=false"
set /p FORCE_INPUT="Force update? true/false (Enter for false): "
if /i "!FORCE_INPUT!"=="true" set "FORCE_UPDATE=true"

echo.
echo Base APK URL : %BASE_APK_URL%
echo Force update : !FORCE_UPDATE!
echo Notes        : !RELEASE_NOTES!
echo.

echo [1/4] Building release auto-update package...
cd /d "%ANDROID_DIR%"
call gradlew.bat --stop
call gradlew.bat clean prepareReleaseUpdate "-PupdateBaseApkUrl=%BASE_APK_URL%" "-PupdateReleaseNotes=!RELEASE_NOTES!" "-PupdateForce=!FORCE_UPDATE!" --no-configuration-cache --no-daemon --console=plain
if errorlevel 1 (
    echo.
    echo [ERROR] Gradle auto-update build failed.
    exit /b 1
)

echo.
echo [2/4] Preparing backend static APK folder...
cd /d "%ROOT%"
if not exist "%BACKEND_APK_DIR%" mkdir "%BACKEND_APK_DIR%"
del /q "%BACKEND_APK_DIR%\*.apk" >nul 2>&1
del /q "%BACKEND_APK_DIR%\update-metadata.json" >nul 2>&1

echo.
echo [3/4] Copying APK and metadata into backend...
copy /y "%UPDATE_OUT%\feedpilot-*.apk" "%BACKEND_APK_DIR%\" >nul
if errorlevel 1 (
    echo [ERROR] Could not copy generated APK from %UPDATE_OUT%.
    exit /b 1
)
copy /y "%UPDATE_OUT%\update-metadata.json" "%BACKEND_APK_DIR%\update-metadata.json" >nul
if errorlevel 1 (
    echo [ERROR] Could not copy update metadata.
    exit /b 1
)

echo.
echo [4/4] Auto-update package is ready for Render.
echo.
echo Files prepared:
dir /b "%BACKEND_APK_DIR%"
echo.
echo Next deploy commands:
echo   git add android-client\release-update.properties backend\src\FeedPilot.Api\wwwroot\apk backend\src\FeedPilot.Api\Program.cs .gitignore deploy-auto-update.bat
echo   git commit -m "Prepare FeedPilot auto update release"
echo   git push origin main
echo.
echo Render will redeploy the backend, serve /apk/*.apk, and seed /api/version from update-metadata.json.
echo Latest version endpoint: https://feedpilot-api-ount.onrender.com/api/version
echo Latest APK redirect    : https://feedpilot-api-ount.onrender.com/api/apk/latest
echo.
endlocal
