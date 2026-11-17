<#
.SYNOPSIS
  One step for the preview loop: restarts Android Auto on the phone, starts its head unit
  server, forwards the port over adb and launches Google's Desktop Head Unit (DHU).

.EXAMPLE
  .\scripts\dhu.ps1                 # restart Android Auto, start the server, launch DHU (rotary profile)
  .\scripts\dhu.ps1 -NoRestart      # leave Android Auto running; only forward and launch
  .\scripts\dhu.ps1 -Touch          # DHU's default touch-screen profile
  .\scripts\dhu.ps1 -SdkPath C:\Android
#>
param(
    [string]$SdkPath,
    [string]$Config = (Join-Path $PSScriptRoot 'dhu-rotary.ini'),
    [switch]$Touch,
    [switch]$NoRestart
)

$ErrorActionPreference = 'Stop'
$pkg = 'com.google.android.projection.gearhead'

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

if (-not $NoRestart) {
    Write-Host 'Restarting Android Auto so it re-reads its flags...'
    adb shell "su -c 'am force-stop $pkg'" | Out-Null
    Start-Sleep -Milliseconds 1200

    # The head unit server's class name changes between Android Auto versions, so look it up.
    $services = @(adb shell "dumpsys package $pkg" |
        Select-String -Pattern "$([regex]::Escape($pkg))/[A-Za-z0-9_.$]*HeadUnit[A-Za-z0-9_.$]*" -AllMatches |
        ForEach-Object { $_.Matches.Value } | Sort-Object -Unique)
    $service = ($services | Where-Object { $_ -match 'Developer' } | Select-Object -First 1)
    if (-not $service) { $service = $services | Select-Object -First 1 }

    if ($service) {
        Write-Host "Starting head unit server: $service"
        adb shell "su -c 'am start-foreground-service -n $service || am startservice -n $service'"
        Start-Sleep -Milliseconds 800
    } else {
        Write-Warning 'Could not find the head unit server. Start it on the phone: Android Auto settings > three-dot menu > Start head unit server.'
        Read-Host 'Press Enter once it is running'
    }
} else {
    Write-Host 'On the phone the head unit server must be running: Android Auto settings > three-dot menu > Start head unit server.'
}

adb forward tcp:5277 tcp:5277 | Out-Null
Write-Host 'Forwarded tcp:5277. Starting DHU...'

Push-Location (Split-Path $dhu)
try {
    if ($Touch) { & $dhu } else { & $dhu -c $Config }
} finally {
    Pop-Location
    adb forward --remove tcp:5277 2>$null | Out-Null
}
