# Prompt for Gemini in Android Studio

I have an Android project, "MiniTVLauncher": a lightweight Android TV home launcher for a WZATCO Yuva Go Max projector
(Android TV OS 13, only 1 GB RAM). It uses Kotlin and plain Views (no Compose), minSdk 21, targetSdk 33, AGP 8.2.2, Kotlin 1.9.22, Gradle 8.2, JDK 17. Only dependency: androidx.recyclerview.

Features already written:
- Dark home screen with clock/date (TextClock) and a RecyclerView grid of installed apps (leanback + normal launcher intents), D-pad focus highlight.
- Wallpaper chooser (3 gradients or an image, downscaled to <=1920px).
- Declared as HOME launcher in AndroidManifest (HOME + DEFAULT, LEANBACK_LAUNCHER).
- Phone remote: a dependency-free HTTP server (RemoteServer.kt) on port 8080-8090 serving assets/remote.html. A phone on the same Wi-Fi opens http://<tv-ip>:<port>, enters the 4-digit PIN shown on the TV, then gets D-pad/OK buttons, an app list to launch, and wallpaper upload.

Your tasks:
1. Sync Gradle and fix any build errors WITHOUT adding heavy libraries (no Compose, Glide, Retrofit, etc.). Keep memory use minimal.
2. Make sure the app builds with `./gradlew assembleDebug` and generate the Gradle wrapper if missing.
3. Add a 320x180 TV banner (android:banner) and an app icon so it shows properly in the TV app list.
4. Do not remove the PIN check in RemoteServer.
5. Explain any change you make in one or two lines.

Next features (do only when I ask): weather via the Open-Meteo API (no API key), a favorites row, remote-settings from the phone.
