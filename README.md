# MiniTVLauncher

A lightweight, Apple TV-style home launcher for Android TV, built for low-RAM devices like the WZATCO Yuva Go Max projector (Android TV 13, 1 GB RAM). Written in Kotlin with plain Views and no heavy libraries.

## Features
- Dark home screen with greeting, clock, and date
- Horizontal row of rounded app cards with TV banners or icon gradients, plus a smooth focus effect
- Tools row: Settings, Wi-Fi, Bluetooth, Display, power menu, original launcher
- Favorites, hidden apps, app renaming, and sort by most used
- Custom wallpaper (presets and photo upload)
- Network status indicator (Wi-Fi, Ethernet, offline)
- Phone remote over local Wi-Fi: D-pad, swipe pad, volume, app launcher, wallpaper upload, send link to TV, settings
- QR-code pairing with device keys, plus a PIN fallback
- Optional accessibility service for Back, Home, and typing from your phone

## Requirements
- Android Studio (Hedgehog or newer), JDK 17 or newer
- A device or emulator with Android 5.0+ (Android 13 TV image recommended)

## Build
1. Clone the repo and open it in Android Studio. Let Gradle sync.
2. Run the app on a TV emulator, or build with `./gradlew assembleDebug`.
3. Install on a device: `adb connect <device-ip>` then `adb install -r app/build/outputs/apk/debug/app-debug.apk`

## Set as default launcher
Press Home on the remote. If Android asks, choose Mini Launcher and tap Always. Some firmware blocks replacing the stock launcher. In that case, open Mini Launcher from the app list.

## Phone remote
Open Pair Phone on the TV and scan the QR code with your phone camera. Your phone must be on the same Wi-Fi. A 4-digit PIN works as a fallback.

## Notes
- D-pad control works on the launcher screen. Back, Home, and typing in other apps need the accessibility service enabled in Settings.
- The phone remote is for home networks only. Don't expose its port to the internet.

## Status
Early testing. Debug builds only.
