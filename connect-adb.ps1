<#
.SYNOPSIS
    Connects to Android device over Wireless ADB and sets default target device.
#>

param(
    [string]$DeviceIp = "192.168.0.102:5555"
)

Write-Host "Connecting to Wireless ADB at $DeviceIp..." -ForegroundColor Cyan
adb connect $DeviceIp

[Environment]::SetEnvironmentVariable("ANDROID_SERIAL", $DeviceIp, "Process")
$env:ANDROID_SERIAL = $DeviceIp

Write-Host "`nConnected ADB Devices:" -ForegroundColor Green
adb devices
