@echo off
echo Connecting to Wireless ADB...
adb connect 192.168.0.100:5555
adb connect 192.168.0.102:5555
echo.
echo Connected ADB Devices:
adb devices
pause
