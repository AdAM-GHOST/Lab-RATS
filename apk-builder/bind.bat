@echo off
setlocal EnableDelayedExpansion
set "SCRIPT_DIR=%~dp0"
powershell -ExecutionPolicy Bypass -File "%SCRIPT_DIR%bind.ps1" %*
