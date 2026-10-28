package com.mini.tvlauncher

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.InputDevice
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object DeviceReportCollector {

    fun generateReport(context: Context, includeUserApps: Boolean = false): String {
        val sb = StringBuilder()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US)

        sb.append("==================================================\n")
        sb.append("MINI TV LAUNCHER - DEVICE REPORT\n")
        sb.append("Generated at: ").append(sdf.format(Date())).append("\n")
        sb.append("==================================================\n\n")

        // 1. Build
        sb.append("--- [1. BUILD INFORMATION] ---\n")
        try {
            sb.append("Manufacturer: ").append(Build.MANUFACTURER).append("\n")
            sb.append("Brand: ").append(Build.BRAND).append("\n")
            sb.append("Model: ").append(Build.MODEL).append("\n")
            sb.append("Device: ").append(Build.DEVICE).append("\n")
            sb.append("Product: ").append(Build.PRODUCT).append("\n")
            sb.append("Board: ").append(Build.BOARD).append("\n")
            sb.append("Hardware: ").append(Build.HARDWARE).append("\n")
            sb.append("Fingerprint: ").append(Build.FINGERPRINT).append("\n")
            sb.append("Display: ").append(Build.DISPLAY).append("\n")
            sb.append("Type: ").append(Build.TYPE).append("\n")
            sb.append("Tags: ").append(Build.TAGS).append("\n")
            sb.append("OS Release: ").append(Build.VERSION.RELEASE).append("\n")
            sb.append("SDK Int: ").append(Build.VERSION.SDK_INT).append("\n")
            if (Build.VERSION.SDK_INT >= 23) {
                sb.append("Security Patch: ").append(Build.VERSION.SECURITY_PATCH).append("\n")
            }
            sb.append("Incremental: ").append(Build.VERSION.INCREMENTAL).append("\n")
            sb.append("Supported ABIs: ").append(Build.SUPPORTED_ABIS.joinToString(", ")).append("\n")
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    sb.append("SoC Manufacturer: ").append(Build.SOC_MANUFACTURER).append("\n")
                    sb.append("SoC Model: ").append(Build.SOC_MODEL).append("\n")
                } catch (_: Exception) {}
            }

            // Selected Getprop properties
            sb.append("\n[Getprop Properties]\n")
            val propLines = runGetpropFiltered()
            if (propLines.isNotEmpty()) {
                propLines.forEach { sb.append(it).append("\n") }
            } else {
                sb.append("unavailable: no matching getprop properties found\n")
            }
        } catch (e: Exception) {
            sb.append("unavailable: ").append(e.message).append("\n")
        }
        sb.append("\n")

        // 2. Hardware
        sb.append("--- [2. HARDWARE & MEMORY] ---\n")
        try {
            sb.append("CPU Cores: ").append(Runtime.getRuntime().availableProcessors()).append("\n")

            sb.append("\n[/proc/cpuinfo (first 40 lines)]\n")
            val cpuLines = readProcFile("/proc/cpuinfo", 40)
            cpuLines.forEach { sb.append(it).append("\n") }

            sb.append("\n[/proc/meminfo (first 10 lines)]\n")
            val memLines = readProcFile("/proc/meminfo", 10)
            memLines.forEach { sb.append(it).append("\n") }

            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am != null) {
                val memInfo = ActivityManager.MemoryInfo()
                am.getMemoryInfo(memInfo)
                sb.append("\n[ActivityManager MemoryInfo]\n")
                sb.append("Total RAM: ").append(memInfo.totalMem / (1024 * 1024)).append(" MB\n")
                sb.append("Available RAM: ").append(memInfo.availMem / (1024 * 1024)).append(" MB\n")
                sb.append("Low Memory Threshold: ").append(memInfo.threshold / (1024 * 1024)).append(" MB\n")
                sb.append("Is Low Memory State: ").append(memInfo.lowMemory).append("\n")
                sb.append("Is Low RAM Device: ").append(am.isLowRamDevice).append("\n")
                sb.append("Memory Class: ").append(am.memoryClass).append(" MB\n")
                sb.append("Large Memory Class: ").append(am.largeMemoryClass).append(" MB\n")
            }

            // StatFs Storage
            sb.append("\n[Storage StatFs]\n")
            try {
                val dataFs = StatFs(Environment.getDataDirectory().path)
                val dataTotal = (dataFs.blockCountLong * dataFs.blockSizeLong) / (1024 * 1024)
                val dataAvail = (dataFs.availableBlocksLong * dataFs.blockSizeLong) / (1024 * 1024)
                sb.append("Data Total: ").append(dataTotal).append(" MB, Available: ").append(dataAvail).append(" MB\n")
            } catch (_: Exception) {}

            try {
                val cacheFs = StatFs(Environment.getDownloadCacheDirectory().path)
                val cacheTotal = (cacheFs.blockCountLong * cacheFs.blockSizeLong) / (1024 * 1024)
                val cacheAvail = (cacheFs.availableBlocksLong * cacheFs.blockSizeLong) / (1024 * 1024)
                sb.append("Cache Total: ").append(cacheTotal).append(" MB, Available: ").append(cacheAvail).append(" MB\n")
            } catch (_: Exception) {}

            // Display
            sb.append("\n[Display & Metrics]\n")
            val dm = context.resources.displayMetrics
            sb.append("Resolution: ").append(dm.widthPixels).append("x").append(dm.heightPixels).append("\n")
            sb.append("Density: ").append(dm.density).append(" (dpi: ").append(dm.densityDpi).append(")\n")

            val dmService = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display = dmService?.getDisplay(Display.DEFAULT_DISPLAY)
            if (display != null) {
                sb.append("Refresh Rate: ").append(display.refreshRate).append(" Hz\n")
                if (Build.VERSION.SDK_INT >= 23) {
                    val modes = display.supportedModes.joinToString(", ") { "${it.physicalWidth}x${it.physicalHeight}@${it.refreshRate}Hz" }
                    sb.append("Supported Modes: ").append(modes).append("\n")
                }
                if (Build.VERSION.SDK_INT >= 24) {
                    val hdr = display.hdrCapabilities
                    if (hdr != null) {
                        val types = hdr.supportedHdrTypes.joinToString(", ")
                        sb.append("HDR Supported Types: ").append(if (types.isEmpty()) "None" else types).append("\n")
                    }
                }
            }

            // Thermal
            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    if (pm != null) {
                        sb.append("Thermal Status: ").append(pm.currentThermalStatus).append("\n")
                    }
                } catch (_: Exception) {}
            }

            // Process Memory
            sb.append("\n[Launcher Process Memory]\n")
            sb.append("Allocated Native Heap: ").append(Debug.getNativeHeapAllocatedSize() / 1024).append(" KB\n")
            sb.append("JVM Heap Total: ").append(Runtime.getRuntime().totalMemory() / 1024).append(" KB\n")
            sb.append("JVM Heap Free: ").append(Runtime.getRuntime().freeMemory() / 1024).append(" KB\n")
        } catch (e: Exception) {
            sb.append("unavailable: ").append(e.message).append("\n")
        }
        sb.append("\n")

        // 3. Features
        sb.append("--- [3. SYSTEM FEATURES] ---\n")
        try {
            val pm = context.packageManager
            val features = pm.systemAvailableFeatures
            if (features != null && features.isNotEmpty()) {
                features.filter { it.name != null }.sortedBy { it.name }.forEach { f ->
                    sb.append("- ").append(f.name).append("\n")
                }
            } else {
                sb.append("unavailable: no features returned\n")
            }
        } catch (e: Exception) {
            sb.append("unavailable: ").append(e.message).append("\n")
        }
        sb.append("\n")

        // 4. Launchers
        sb.append("--- [4. LAUNCHER ACTIVITIES & ROLES] ---\n")
        try {
            val pm = context.packageManager
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val homeMatches = pm.queryIntentActivities(homeIntent, PackageManager.MATCH_ALL)
            sb.append("Discovered HOME Activities (").append(homeMatches.size).append("):\n")
            homeMatches.forEach { ri ->
                sb.append(" - ").append(ri.activityInfo.packageName).append("/").append(ri.activityInfo.name).append("\n")
            }

            val resolvedDefault = pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            val defaultPkg = resolvedDefault?.activityInfo?.packageName ?: "None"
            sb.append("Default Resolved Home Package: ").append(defaultPkg).append("\n")

            if (Build.VERSION.SDK_INT >= 29) {
                val rm = context.getSystemService(RoleManager::class.java)
                if (rm != null) {
                    val available = rm.isRoleAvailable(RoleManager.ROLE_HOME)
                    val held = rm.isRoleHeld(RoleManager.ROLE_HOME)
                    sb.append("RoleManager Home Role Available: ").append(available).append(", Held by us: ").append(held).append("\n")
                }
            }
        } catch (e: Exception) {
            sb.append("unavailable: ").append(e.message).append("\n")
        }
        sb.append("\n")

        // 5. Intent Probes
        sb.append("--- [5. INTENT PROBES] ---\n")
        try {
            val pm = context.packageManager
            val probeActions = listOf(
                "android.settings.SETTINGS",
                "android.settings.WIFI_SETTINGS",
                "android.settings.BLUETOOTH_SETTINGS",
                "android.settings.DISPLAY_SETTINGS",
                "android.settings.SOUND_SETTINGS",
                "android.settings.HOME_SETTINGS",
                "android.settings.APPLICATION_SETTINGS",
                "android.settings.MANAGE_ALL_APPLICATIONS_SETTINGS",
                "android.settings.ACCESSIBILITY_SETTINGS",
                "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS",
                "android.settings.CAST_SETTINGS",
                "android.settings.INPUT_METHOD_SETTINGS",
                "android.intent.action.ASSIST",
                "android.intent.action.VOICE_COMMAND",
                "android.intent.action.WEB_SEARCH",
                "android.speech.action.RECOGNIZE_SPEECH",
                "com.android.tv.action.VIEW_INPUTS"
            )

            probeActions.forEach { action ->
                val intent = Intent(action)
                val matches = pm.queryIntentActivities(intent, 0)
                if (matches.isNotEmpty()) {
                    val matchStr = matches.joinToString(", ") { "${it.activityInfo.packageName}/${it.activityInfo.name}" }
                    sb.append(action).append(" -> ").append(matchStr).append("\n")
                } else {
                    sb.append(action).append(" -> NONE\n")
                }
            }
        } catch (e: Exception) {
            sb.append("unavailable: ").append(e.message).append("\n")
        }
        sb.append("\n")

        // 6. Packages
        sb.append("--- [6. INSTALLED PACKAGES] ---\n")
        try {
            val pm = context.packageManager
            val packages = pm.getInstalledPackages(PackageManager.GET_ACTIVITIES)
            val leanbackIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

            val leanbackPkgs = pm.queryIntentActivities(leanbackIntent, 0).map { it.activityInfo.packageName }.toSet()
            val launcherPkgs = pm.queryIntentActivities(launcherIntent, 0).map { it.activityInfo.packageName }.toSet()

            var count = 0
            packages.sortedBy { it.packageName }.forEach { pkg ->
                val isSystem = (pkg.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                if (isSystem || includeUserApps) {
                    count++
                    val label = try { pkg.applicationInfo.loadLabel(pm).toString() } catch (_: Exception) { pkg.packageName }
                    val type = if (isSystem) "SYSTEM" else "USER"
                    val enabled = pkg.applicationInfo.enabled
                    val hasLb = leanbackPkgs.contains(pkg.packageName)
                    val hasLch = launcherPkgs.contains(pkg.packageName)
                    val sourceDir = pkg.applicationInfo.sourceDir ?: ""

                    var catStr = "NONE"
                    if (Build.VERSION.SDK_INT >= 26) {
                        catStr = when (pkg.applicationInfo.category) {
                            ApplicationInfo.CATEGORY_VIDEO -> "VIDEO"
                            ApplicationInfo.CATEGORY_AUDIO -> "AUDIO"
                            ApplicationInfo.CATEGORY_GAME -> "GAME"
                            ApplicationInfo.CATEGORY_IMAGE -> "IMAGE"
                            ApplicationInfo.CATEGORY_SOCIAL -> "SOCIAL"
                            ApplicationInfo.CATEGORY_NEWS -> "NEWS"
                            ApplicationInfo.CATEGORY_MAPS -> "MAPS"
                            ApplicationInfo.CATEGORY_PRODUCTIVITY -> "PRODUCTIVITY"
                            else -> "UNDEFINED"
                        }
                    }

                    sb.append("- ").append(pkg.packageName).append(" [").append(label).append("]\n")
                    sb.append("  ver=").append(pkg.versionName ?: "null").append(", type=").append(type)
                    sb.append(", enabled=").append(enabled).append(", category=").append(catStr)
                    sb.append(", leanback=").append(hasLb).append(", launcher=").append(hasLch).append("\n")
                    sb.append("  path=").append(sourceDir).append("\n")
                }
            }
            sb.append("Total Printed Packages: ").append(count).append("\n")
        } catch (e: Exception) {
            sb.append("unavailable: ").append(e.message).append("\n")
        }
        sb.append("\n")

        // 7. Input
        sb.append("--- [7. INPUT DEVICES & KEY LAYOUTS] ---\n")
        try {
            val deviceIds = InputDevice.getDeviceIds()
            sb.append("Input Devices (").append(deviceIds.size).append("):\n")
            deviceIds.forEach { id ->
                val dev = InputDevice.getDevice(id)
                if (dev != null) {
                    sb.append(" - ID ").append(dev.id).append(": ").append(dev.name)
                    sb.append(" (sources=0x").append(Integer.toHexString(dev.sources))
                    sb.append(", vendor=0x").append(Integer.toHexString(dev.vendorId))
                    sb.append(", product=0x").append(Integer.toHexString(dev.productId))
                    sb.append(", kbType=").append(dev.keyboardType)
                    sb.append(", isVirtual=").append(dev.isVirtual).append(")\n")
                }
            }

            sb.append("\n[Keylayout Files]\n")
            listOf("/system/usr/keylayout", "/vendor/usr/keylayout", "/odm/usr/keylayout").forEach { dirPath ->
                val dir = File(dirPath)
                if (dir.exists() && dir.isDirectory) {
                    sb.append("Directory ").append(dirPath).append(":\n")
                    dir.listFiles()?.take(20)?.forEach { f ->
                        sb.append("  ").append(f.name).append(" (").append(f.length()).append(" B)\n")
                        val keyLines = readMatchingKeylayoutLines(f)
                        if (keyLines.isNotEmpty()) {
                            keyLines.forEach { line -> sb.append("    -> ").append(line).append("\n") }
                        }
                    }
                }
            }

            sb.append("\n[Recent Ring Buffer Key Events (last 30)]\n")
            val keyLogs = KeyEventBuffer.getFormattedLines()
            if (keyLogs.isNotEmpty()) {
                keyLogs.forEach { sb.append(it).append("\n") }
            } else {
                sb.append("No key events logged yet\n")
            }
        } catch (e: Exception) {
            sb.append("unavailable: ").append(e.message).append("\n")
        }
        sb.append("\n")

        // 8. Settings State
        sb.append("--- [8. SETTINGS & SERVICE STATE] ---\n")
        try {
            val accServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "none"
            val notifListeners = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: "none"
            val adb = Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0)
            val devSettings = Settings.Global.getInt(context.contentResolver, "development_settings_enabled", 0)

            sb.append("Accessibility Services Enabled: ").append(accServices).append("\n")
            sb.append("Notification Listeners Enabled: ").append(notifListeners).append("\n")
            sb.append("ADB Enabled: ").append(adb == 1).append("\n")
            sb.append("Developer Settings Enabled: ").append(devSettings == 1).append("\n")

            sb.append("\n[Launcher Service Status]\n")
            sb.append("RemoteAccessibilityService: ").append(if (RemoteAccessibilityService.instance != null) "RUNNING" else "OFF").append("\n")
            sb.append("HomeKeyService: ").append(if (HomeKeyService.instance != null) "RUNNING" else "OFF").append("\n")
            sb.append("RemoteService: ").append(if (RemoteService.serverPort != 0) "RUNNING (port ${RemoteService.serverPort})" else "OFF").append("\n")
            sb.append("MediaListenerService: ").append(if (NowPlayingManager.isNotificationListenerEnabled(context)) "ENABLED" else "DISABLED").append("\n")

            sb.append("\n[Granted Permissions]\n")
            listOf(
                android.Manifest.permission.INTERNET,
                android.Manifest.permission.ACCESS_NETWORK_STATE,
                android.Manifest.permission.QUERY_ALL_PACKAGES,
                android.Manifest.permission.FOREGROUND_SERVICE,
                "android.permission.POST_NOTIFICATIONS"
            ).forEach { perm ->
                val granted = context.checkCallingOrSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
                sb.append(" - ").append(perm).append(": ").append(if (granted) "GRANTED" else "DENIED").append("\n")
            }
        } catch (e: Exception) {
            sb.append("unavailable: ").append(e.message).append("\n")
        }
        sb.append("\n")

        // 9. Our App Info
        sb.append("--- [9. LAUNCHER APP METADATA] ---\n")
        try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            sb.append("Package Name: ").append(context.packageName).append("\n")
            sb.append("Version Name: ").append(pInfo.versionName).append("\n")
            @Suppress("DEPRECATION")
            sb.append("Version Code: ").append(if (Build.VERSION.SDK_INT >= 28) pInfo.longVersionCode else pInfo.versionCode.toLong()).append("\n")
            sb.append("Target SDK: ").append(context.applicationInfo.targetSdkVersion).append("\n")
            sb.append("First Install Time: ").append(sdf.format(Date(pInfo.firstInstallTime))).append("\n")
            sb.append("Last Update Time: ").append(sdf.format(Date(pInfo.lastUpdateTime))).append("\n")
        } catch (e: Exception) {
            sb.append("unavailable: ").append(e.message).append("\n")
        }
        sb.append("\n")

        // 10. Crash History
        sb.append("--- [10. CRASH & LOGCAT HISTORY] ---\n")
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                val exitInfos = am?.getHistoricalProcessExitReasons(context.packageName, 0, 10)
                if (exitInfos != null && exitInfos.isNotEmpty()) {
                    sb.append("ApplicationExitInfo (last 10):\n")
                    exitInfos.forEach { ei ->
                        sb.append(" - [").append(sdf.format(Date(ei.timestamp))).append("] Reason=").append(ei.reason)
                        sb.append(", Description=").append(ei.description ?: "none").append("\n")
                    }
                } else {
                    sb.append("ApplicationExitInfo: None recorded\n")
                }
            }

            sb.append("\n[Logcat Runtime & Fatal Lines (last 300)]\n")
            val logcatLines = runLogcatFiltered()
            if (logcatLines.isNotEmpty()) {
                logcatLines.forEach { sb.append(it).append("\n") }
            } else {
                sb.append("No FATAL/AndroidRuntime lines found in logcat\n")
            }
        } catch (e: Exception) {
            sb.append("unavailable: ").append(e.message).append("\n")
        }

        // Apply Privacy Filters and Cap to 200 KB
        return sanitizeAndCap(sb.toString(), 200 * 1024)
    }

    private fun readProcFile(path: String, maxLines: Int): List<String> {
        return try {
            val file = File(path)
            if (!file.exists()) return listOf("file not found")
            file.useLines { lines -> lines.take(maxLines).toList() }
        } catch (e: Exception) {
            listOf("unavailable: ${e.message}")
        }
    }

    private fun readMatchingKeylayoutLines(file: File): List<String> {
        val keywords = listOf("HOME", "MENU", "SETTINGS", "ALL_APPS", "INPUT", "ASSIST", "SEARCH", "TV", "GUIDE", "APP_SWITCH")
        return try {
            val result = ArrayList<String>()
            file.useLines { lines ->
                lines.forEach { line ->
                    if (keywords.any { line.contains(it, ignoreCase = true) }) {
                        result.add(line.trim())
                    }
                }
            }
            result.take(15)
        } catch (_: Exception) { emptyList() }
    }

    private fun runGetpropFiltered(): List<String> {
        return try {
            val process = Runtime.getRuntime().exec("getprop")
            val completed = process.waitFor(3, TimeUnit.SECONDS)
            if (!completed) {
                process.destroy()
                return listOf("unavailable: getprop timeout")
            }

            val prefixList = listOf(
                "ro.product.", "ro.build.", "ro.board.", "ro.hardware",
                "ro.soc.", "ro.config.low_ram", "ro.boot.verifiedbootstate",
                "ro.boot.flash.locked", "ro.oem_unlock_supported",
                "ro.treble.enabled", "dalvik.vm.heapsize", "ro.zygote"
            )

            process.inputStream.bufferedReader().useLines { lines ->
                lines.filter { line ->
                    prefixList.any { prefix -> line.trim().startsWith("[$prefix") || line.trim().startsWith(prefix) }
                }.take(100).toList()
            }
        } catch (e: Exception) {
            listOf("unavailable: ${e.message}")
        }
    }

    private fun runLogcatFiltered(): List<String> {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "300"))
            val completed = process.waitFor(3, TimeUnit.SECONDS)
            if (!completed) {
                process.destroy()
                return listOf("unavailable: logcat timeout")
            }

            process.inputStream.bufferedReader().useLines { lines ->
                lines.filter { line ->
                    line.contains("AndroidRuntime", ignoreCase = true) || line.contains("FATAL", ignoreCase = true)
                }.take(50).toList()
            }
        } catch (e: Exception) {
            listOf("unavailable: ${e.message}")
        }
    }

    private fun sanitizeAndCap(text: String, maxBytes: Int): String {
        val dropKeywords = listOf("serial", "imei", "meid", "android_id", "macaddr", "ssid", "bssid", "password", "token", "secret")
        val macRegex = Regex("(?i)([0-9a-f]{2}[:-]){5}[0-9a-f]{2}")
        val ipRegex = Regex("(192\\.168\\.\\d{1,3}\\.\\d{1,3}|10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|172\\.(1[6-9]|2\\d|3[01])\\.\\d{1,3}\\.\\d{1,3})")

        val lines = text.split("\n")
        val sanitizedSb = StringBuilder()

        for (line in lines) {
            val lower = line.lowercase(Locale.US)
            if (dropKeywords.any { lower.contains(it) }) continue

            var cleanLine = line
            cleanLine = macRegex.replace(cleanLine, "xx:xx:xx:xx:xx:xx")
            cleanLine = ipRegex.replace(cleanLine, "xxx.xxx.x.x")

            sanitizedSb.append(cleanLine).append("\n")
        }

        var result = sanitizedSb.toString()
        val resultBytes = result.toByteArray(Charsets.UTF_8)
        if (resultBytes.size > maxBytes) {
            val truncatedStr = String(resultBytes, 0, maxBytes, Charsets.UTF_8)
            result = truncatedStr + "\n\n--- [REPORT TRUNCATED AT 200 KB CAP] ---\n"
        }

        return result
    }
}
