@echo off
echo =======================================================
echo Building, Deploying, and Launching FeedPilot on Genymotion
echo =======================================================
cd /d "%~dp0android-client"
call gradlew.bat installDebug
if %ERRORLEVEL% EQU 0 (
    echo.
    echo SUCCESS: App installed on Genymotion! Opening app...
    "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" shell am start -n com.feedpilot.client.debug/com.feedpilot.client.MainActivity
) else (
    echo.
    echo FAILED: Build or deployment encountered an error.
)
