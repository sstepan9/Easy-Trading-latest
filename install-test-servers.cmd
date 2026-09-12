@echo off
setlocal

rem Double-clickable launcher for install-test-servers.ps1.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0install-test-servers.ps1" %*
set "exitCode=%ERRORLEVEL%"
echo.
if not "%exitCode%"=="0" (
    echo Installation finished with errors.
) else (
    echo Installation finished.
)
pause >nul
exit /b %exitCode%
