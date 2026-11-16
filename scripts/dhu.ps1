<#
.SYNOPSIS
  Forwards the head unit port over adb and starts Google's Desktop Head Unit (DHU).

.EXAMPLE
  .\scripts\dhu.ps1
  .\scripts\dhu.ps1 -Touch                 # default touch-screen profile instead of the rotary one
  .\scripts\dhu.ps1 -SdkPath C:\Android
#>
param(
    [string]$SdkPath,
    [string]$Config = (Join-Path $PSScriptRoot 'dhu-rotary.ini'),
    [switch]$Touch
)

$ErrorActionPreference = 'Stop'

# Locate the Android SDK.
$candidates = @($SdkPath, $env:ANDROID_HOME, $env:ANDROID_SDK_ROOT,
                (Join-Path $env:LOCALAPPDATA 'Android\Sdk'), 'C:\Android') | Where-Object { $_ }
$dhu = $null
foreach ($sdk in $candidates) {
    $exe = Join-Path $sdk 'extras\google\auto\desktop-head-unit.exe'
    if (Test-Path $exe) { $dhu = $exe; break }
}
if (-not $dhu) {
    Write-Error "desktop-head-unit.exe not found. Install 'Android Auto Desktop Head Unit Emulator' in the SDK Manager, or pass -SdkPath."
}

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    Write-Error "adb is not on PATH. Add <sdk>\platform-tools to PATH."
}

# Exactly one authorised device must be connected.
$devices = @(adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\tdevice$' })
if ($devices.Count -ne 1) {
    Write-Error "Expected exactly one authorised adb device, found $($devices.Count). Check 'adb devices'."
}

Write-Host 'On the phone: Android Auto settings > overflow menu > Start head unit server (must be running).'
adb forward tcp:5277 tcp:5277 | Out-Null
Write-Host 'Forwarded tcp:5277. Starting DHU...'

Push-Location (Split-Path $dhu)
try {
    if ($Touch) { & $dhu } else { & $dhu -c $Config }
} finally {
    Pop-Location
    adb forward --remove tcp:5277 2>$null | Out-Null
}
