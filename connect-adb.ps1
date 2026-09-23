<#
.SYNOPSIS
    Connects to Android device over Wireless ADB and sets default target device.
#>

param(
    [string[]]$CandidateIps = @("192.168.0.100:5555", "192.168.0.102:5555")
)

foreach ($ip in $CandidateIps) {
    Write-Host "Trying Wireless ADB connection at $ip..." -ForegroundColor Cyan
    adb connect $ip
}

$activeDevice = (adb devices | Select-String "192\.168\.0\.\d+:5555\s+device" | ForEach-Object { ($_ -split "\s+")[0] } | Select-Object -First 1)

if ($activeDevice) {
    [Environment]::SetEnvironmentVariable("ANDROID_SERIAL", $activeDevice, "Process")
    $env:ANDROID_SERIAL = $activeDevice
    Write-Host "`nActive Wireless Target: $activeDevice" -ForegroundColor Green
} else {
    Write-Host "`nNo active device found." -ForegroundColor Yellow
}

adb devices
