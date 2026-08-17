@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
set "BUILD_DIR=%SCRIPT_DIR%app\build"

echo Stopping Gradle daemons...
call "%SCRIPT_DIR%gradlew.bat" --stop

echo Closing Android JDK Java processes that can lock Gradle build files...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "Get-Process java -ErrorAction SilentlyContinue | Where-Object { $_.Path -like '*Android\openjdk*' } | Stop-Process -Force"

echo Verifying build directory...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$root = [IO.Path]::GetFullPath('%SCRIPT_DIR%');" ^
  "$target = [IO.Path]::GetFullPath('%BUILD_DIR%');" ^
  "if (-not $target.StartsWith($root, [StringComparison]::OrdinalIgnoreCase)) { throw \"Refusing to delete outside project: $target\" };" ^
  "if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Recurse -Force; Write-Host \"Deleted $target\" } else { Write-Host \"Nothing to delete: $target\" }"

if errorlevel 1 (
  echo Failed to remove app build directory.
  exit /b 1
)

echo Running Gradle clean...
call "%SCRIPT_DIR%gradlew.bat" clean --no-daemon --console=plain
if errorlevel 1 (
  echo Gradle clean failed after removing the locked build directory.
  exit /b 1
)

endlocal
exit /b 0
