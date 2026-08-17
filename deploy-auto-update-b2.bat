@echo off
setlocal

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy-auto-update-b2.ps1"
exit /b %ERRORLEVEL%
