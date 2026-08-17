@echo off
setlocal enabledelayedexpansion

set "SCRIPT_DIR=%~dp0"
cd /d "%SCRIPT_DIR%"

echo =========================================
echo 1. Running fix.bat (Cleaning locked processes and build files)
echo =========================================
call "%SCRIPT_DIR%fix.bat"
if errorlevel 1 (
    echo [ERROR] fix.bat failed. Aborting build.
    exit /b 1
)

echo.
echo =========================================
echo 2. Building Release APK (assembleRelease)
echo =========================================
call "%SCRIPT_DIR%gradlew.bat" assembleRelease --no-build-cache --no-configuration-cache --rerun-tasks --console=plain
if errorlevel 1 (
    echo [ERROR] Release APK build failed!
    exit /b 1
)

set "APK_PATH=%SCRIPT_DIR%app\build\outputs\apk\release\app-release.apk"
if not exist "%APK_PATH%" (
    echo [ERROR] APK not found at %APK_PATH%
    exit /b 1
)

echo.
echo [SUCCESS] Release APK built at: %APK_PATH%

echo.
echo =========================================
echo 3. Checking for connected ADB devices...
echo =========================================
where adb >nul 2>&1
if errorlevel 1 (
    echo [INFO] adb tool not found in PATH. Skipping install and launch.
    goto END
)

set "ADB_DEVICE="
for /f "tokens=1,2" %%A in ('adb devices') do (
    if "%%B"=="device" (
        set "ADB_DEVICE=%%A"
    )
)

if "%ADB_DEVICE%"=="" (
    echo [INFO] No connected ADB device found. Skipping install and launch.
    goto END
)

echo [INFO] Found connected ADB device: %ADB_DEVICE%
echo.
echo =========================================
echo 4. Installing Release APK on %ADB_DEVICE%...
echo =========================================
adb -s %ADB_DEVICE% install -r "%APK_PATH%"
if errorlevel 1 (
    echo [ERROR] Failed to install APK on device %ADB_DEVICE%.
    exit /b 1
)

echo.
echo =========================================
echo 5. Launching FeedPilot on %ADB_DEVICE%...
echo =========================================
adb -s %ADB_DEVICE% shell am start -n "com.feedpilot.client/com.feedpilot.client.MainActivity" -a android.intent.action.MAIN -c android.intent.category.LAUNCHER
if errorlevel 1 (
    echo [WARNING] Could not launch app automatically.
) else (
    echo [SUCCESS] App launched successfully!
)

:END
endlocal
exit /b 0
