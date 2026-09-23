# Project Agent Guidelines

## Android & ADB Configuration
- **Wireless ADB**: Always use wireless ADB for this project.
- **Target Device IP / Port**: `192.168.0.102:5555`.
- **ADB Command Routing**:
  - Always ensure connection to the wireless device: `adb connect 192.168.0.102:5555`.
  - When issuing `adb` commands (e.g. `adb logcat`, `adb install`, `adb shell`), explicitly target the wireless device using `-s 192.168.0.102:5555` or set `ANDROID_SERIAL=192.168.0.102:5555` to prevent conflicts when multiple devices are listed.
  - When running Gradle install/debug tasks (`./gradlew installDebug`), ensure `ANDROID_SERIAL=192.168.0.102:5555` is set in the environment so Gradle deploys directly over Wi-Fi.
- Helper scripts `connect-adb.bat` and `connect-adb.ps1` are available in the root folder to quickly reconnect.

## APK Archiving & Build Preservation
- **Never Overwrite APKs**: Always archive previous APKs into an `apks/` directory with a timestamp, branch name, or feature description before generating or copying new builds. Never delete or blindly overwrite existing APK binaries.

