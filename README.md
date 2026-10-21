# MiniTVLauncher

A lightweight, Apple TV-style home launcher for Android TV, built specifically for low-RAM devices like the WZATCO Yuva Go Max projector (Android TV 13, 1 GB RAM). Written in Kotlin with plain Views and no heavy libraries.

## Screenshots

> *Screenshots placeholder — Add device screenshots or Compose/View previews here.*

| TV Launcher Home Screen | Phone Remote Web App |
| :---: | :---: |
| *(Add home screen screenshot)* | *(Add phone remote screenshot)* |

## Features
- **Liquid Glass UI**: Modern translucent surfaces, smooth 250ms D-pad focus scaling, and scrim background gradients.
- **Categorized App Rows**: Auto-categorization (Favorites, Entertainment, Games, Productivity & Tools, Others) with shared view pooling for minimal RAM usage.
- **Open-Meteo Live Weather**: Real-time temperature & condition vector icons next to clock (no API key required) with offline caching and °C/°F choices.
- **Phone Remote Web App**: Full D-pad controls, hold-to-repeat arrow keys, gesture swipe pad, live typing, voice search, link sharing, and multi-wallpaper management over Wi-Fi.
- **Multi-Wallpaper Slideshow**: 10 dark gradient presets, custom photo uploads (up to 8 photos, deletable from phone), and crossfade rotation.
- **In-Launcher Screensaver**: Configurable idle timeout (Off / 5 / 10 / 20 min) with drifting clock animation to prevent static OLED/projector burn-in.
- **Home Key Remapping & Tools**: Home key remapping accessibility service, Remote Key Tester, Default Home selector, and quick system tools.
- **QR Code Pairing**: Single-scan QR pairing with cryptographic device keys and 4-digit PIN fallback.

## Requirements
- Android Studio (Hedgehog or newer), JDK 17
- Device or emulator with minSdk 21 (Android 5.0+) up to Android 13+

## Build
1. Clone the repository and open it in Android Studio.
2. Build debug APK using Gradle:
   ```bash
   ./gradlew assembleDebug
   ```
3. Install on TV or projector via ADB:
   ```bash
   adb connect <device-ip>
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

## Phone Remote
Open the **Pair Phone** tile or scan the on-screen TV QR code using your phone camera. Both devices must be on the same Wi-Fi network.

## License
[MIT License](LICENSE)
