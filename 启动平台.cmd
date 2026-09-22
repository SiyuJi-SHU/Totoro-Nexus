@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start.ps1" -SkipBuild
if errorlevel 1 (
  pause
  exit /b 1
)
start "" "http://localhost:9900/"
