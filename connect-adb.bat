@echo off
set DEVICE_IP=192.168.0.102:5555
echo Connecting to Wireless ADB at %DEVICE_IP%...
adb connect %DEVICE_IP%
set ANDROID_SERIAL=%DEVICE_IP%
echo.
echo Connected ADB Devices:
adb devices
pause
