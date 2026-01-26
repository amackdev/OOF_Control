# OOF Control

A standalone Android app for controlling OOF ROM features.

## Features

### 🔧 OOF Utils
**Touch Settings**
- Double Tap to Wake (DT2W)
- High Touch Sampling Rate (480Hz)
- Touch Boost - CPU boost on touch input

**Display**
- Force Refresh Rate (60/90/120Hz)

**Spoofing** *(requires reboot)*
- PIF Spoof (`persist.sys.oof.pif`)
- BLS Spoof (`persist.sys.oof.blspoof`)
- Keybox Spoof (`persist.oof_keybox.enabled`)
- Spoof Provider (`persist.sys.oof-utils.spoofprovider`)
- Unlimited Google Photos (`persist.sys.oof-utils.unligphotos`)

### ⚡ Performance
- Xiaomi Performance Mode unlock
- CPU/GPU performance tweaks

### 🔋 Charging
**Smart Charging**
- 90W Sport Mode - Maximum fast charging
- Temperature-based current control
- Charge Limit (50-100%) - Protect battery health

**Battery Monitor**
- Real-time charging current display (mA)
- Screen on/off drain tracking
- Deep sleep percentage
- Wakelock history with duration & count
- AccuBattery-style statistics

### ⚙️ System
- Apply settings on boot
- Release signed builds
- Silent background services

## Requirements

- Root access (Magisk/KernelSU)
- Android 8.0+ (API 26+)
- JDK 17+ (for building)

## Supported Devices

- Xiaomi POCO F6 Pro (peridot)
- Xiaomi POCO F5 (marble)

## Building

### Android Studio
1. Open project in Android Studio
2. Sync Gradle
3. Build > Build APK(s)

### Command Line (Linux/macOS)
```bash
chmod +x gradlew build.sh
./build.sh release
```

### Command Line (Windows)
```batch
build.bat release
```

## Signing

The app includes a release keystore for signed builds:
- Keystore: `keystore/oof_release.jks`
- Password: `oofcontrol123`
- Key alias: `oof`

Both debug and release builds use the same signing key.

## APK Output

- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/apk/release/app-release.apk`

## Project Structure

```
OOFControl/
├── app/
│   ├── src/main/
│   │   ├── java/com/oof/control/
│   │   │   ├── MainActivity.kt
│   │   │   ├── fragments/
│   │   │   │   ├── DisplayFragment.kt    # OOF Utils
│   │   │   │   ├── PerformanceFragment.kt
│   │   │   │   ├── ChargingFragment.kt
│   │   │   │   └── AboutFragment.kt
│   │   │   ├── services/
│   │   │   │   ├── SmartChargingService.kt
│   │   │   │   ├── BatteryStatsService.kt
│   │   │   │   ├── ChargingControlService.kt
│   │   │   │   ├── ScreenStateReceiver.kt
│   │   │   │   └── BootReceiver.kt
│   │   │   └── utils/
│   │   │       ├── DeviceConfig.kt
│   │   │       ├── RootController.kt
│   │   │       ├── PrefsManager.kt
│   │   │       └── BatteryStatsTracker.kt
│   │   └── res/
│   └── build.gradle.kts
├── keystore/
│   └── oof_release.jks
├── build.gradle.kts
├── settings.gradle.kts
└── gradlew
```

## How It Works

- **Root commands** via `su` shell for system file access
- **System props** via `getprop`/`setprop` for spoofing toggles
- **System settings** via `settings put` for display options
- **Xiaomi touch service** for DT2W on supported devices
- **Background services** for charging control & battery stats
- **OS integration** reads charge limit from `regular_charge_protection_open_power_level`

## License

MIT License