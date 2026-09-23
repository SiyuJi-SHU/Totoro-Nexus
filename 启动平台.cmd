@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-platform.ps1"
set "TOTORO_START_EXIT=%ERRORLEVEL%"
echo.
echo Current Totoro Nexus container status:
docker ps -a --filter "name=totoro-nexus-" --format "table {{.Names}}\t{{.Status}}"
echo.
echo Startup exit code: %TOTORO_START_EXIT%
echo Closing this window does not stop the background services or tunnel.
pause
exit /b %TOTORO_START_EXIT%
