@echo off
setlocal

rem Double-clickable launcher for test-servers.ps1.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0test-servers.ps1" %*
set "exitCode=%ERRORLEVEL%"

echo.
if not "%exitCode%"=="0" (
    echo Tests failed. Press any key to close this window.
) else (
    echo Tests finished. Press any key to close this window.
)
pause >nul
exit /b %exitCode%
