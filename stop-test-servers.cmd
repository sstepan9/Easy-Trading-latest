@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop-test-servers.ps1" %*
exit /b %ERRORLEVEL%
