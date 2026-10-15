@echo off
powershell.exe -NoLogo -NoProfile -ExecutionPolicy RemoteSigned -File "%~dp0backend.ps1" %*
exit /b %ERRORLEVEL%
