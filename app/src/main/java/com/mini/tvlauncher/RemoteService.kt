package com.mini.tvlauncher

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*

class RemoteService : Service(), RemoteHost {

    companion object {
        private const val CHANNEL_ID = "remote_service_channel"
        private const val NOTIF_ID = 1001

        @Volatile var serverPort: Int = 0
            private set

        @Volatile var sleepTimerManager: SleepTimerManager? = null
            private set

        @Volatile var nowPlayingManager: NowPlayingManager? = null
            private set

        fun startService(context: Context) {
            val intent = Intent(context, RemoteService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private var server: RemoteServer? = null
    private val prefs by lazy { getSharedPreferences("launcher", MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
        startForegroundSafely()

        val npm = NowPlayingManager(this).also { it.start() }
        nowPlayingManager = npm
        sleepTimerManager = SleepTimerManager(this, prefs, npm).also { it.restoreIfSaved() }

        startServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundSafely()
        if (server == null) {
            startServer()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        serverPort = 0
        nowPlayingManager?.stop()
        nowPlayingManager = null
        sleepTimerManager = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundSafely() {
        try {
            createNotificationChannel()
            startForeground(NOTIF_ID, buildNotification())
        } catch (_: Exception) {}
    }

    private fun startServer() {
        var pin = prefs.getString("pin", null)
        if (pin == null) {
            pin = (1000 + Random().nextInt(9000)).toString()
            prefs.edit().putString("pin", pin).apply()
        }
        val s = RemoteServer(pin, this)
        if (s.start()) {
            server = s
            serverPort = s.port
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Phone Remote Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps phone remote active while other apps are running"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Mini Launcher Remote")
            .setContentText("Phone remote service active")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()
    }

    // ---- RemoteHost Implementation ----
    override fun openSearch(): String? {
        val main = MainActivity.activeInstance?.get()
        if (main != null) {
            main.runOnUiThread { main.openSearchPanel() }
            return null
        }
        return "Search opens on the launcher home screen"
    }

    override fun getDeviceReport(includeUserApps: Boolean): String {
        return DeviceReportCollector.generateReport(this, includeUserApps)
    }

    override fun getKeyLogJson(): String {
        return KeyEventBuffer.getJson()
    }

    override fun clearKeyLog(): Boolean {
        KeyEventBuffer.clear()
        return true
    }

    override fun getServiceStatusJson(): String {
        val obj = JSONObject().apply {
            put("remoteService", serverPort != 0)
            put("accessibilityService", RemoteAccessibilityService.instance != null)
            put("homeKeyService", HomeKeyService.instance != null)
            put("mediaListenerService", NowPlayingManager.isNotificationListenerEnabled(this@RemoteService))
            put("postNotificationsPermission", if (Build.VERSION.SDK_INT >= 33) checkCallingOrSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED else true)
        }
        return obj.toString()
    }

    override fun openSettingsTarget(target: String): Boolean {
        return try {
            val intent = when (target.lowercase(Locale.US)) {
                "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                "notifications" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                "home" -> Intent(Settings.ACTION_HOME_SETTINGS)
                else -> Intent(Settings.ACTION_SETTINGS)
            }.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            true
        } catch (_: Exception) { false }
    }

    override fun getErrorsText(): String {
        val sb = StringBuilder()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        // 1. Uncaught Exception Crashes
        val crashFile = File(filesDir, "last_crash.txt")
        if (crashFile.exists()) {
            sb.append("=== Recent Uncaught Exceptions (filesDir/last_crash.txt) ===\n")
            sb.append(crashFile.readText().trim()).append("\n\n")
        }

        // 2. ApplicationExitInfo
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                val exitInfos = am?.getHistoricalProcessExitReasons(packageName, 0, 10)
                if (exitInfos != null && exitInfos.isNotEmpty()) {
                    sb.append("=== ApplicationExitInfo (last 10) ===\n")
                    exitInfos.forEach { ei ->
                        val importanceStr = when (ei.importance) {
                            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "FOREGROUND"
                            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "FOREGROUND_SERVICE"
                            ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "VISIBLE"
                            ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "PERCEPTIBLE"
                            ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND -> "BACKGROUND"
                            ActivityManager.RunningAppProcessInfo.IMPORTANCE_EMPTY -> "EMPTY"
                            else -> "IMPORTANCE_${ei.importance}"
                        }

                        val reasonStr = when (ei.reason) {
                            ApplicationExitInfo.REASON_ANR -> "ANR"
                            ApplicationExitInfo.REASON_CRASH -> "CRASH"
                            ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
                            ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
                            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
                            ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
                            ApplicationExitInfo.REASON_OTHER -> "OTHER"
                            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
                            ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
                            ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
                            ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
                            else -> "REASON_${ei.reason}"
                        }

                        sb.append("- [").append(sdf.format(Date(ei.timestamp))).append("]")
                        sb.append(" Reason=").append(reasonStr)
                        sb.append(", Importance=").append(importanceStr)
                        sb.append(", Description=").append(ei.description ?: "none").append("\n")

                        try {
                            val traceStream = ei.traceInputStream
                            if (traceStream != null) {
                                val traceText = traceStream.bufferedReader().use { it.readText() }.trim()
                                if (traceText.isNotEmpty()) {
                                    sb.append("  Trace:\n  ").append(traceText.replace("\n", "\n  ")).append("\n")
                                }
                            }
                        } catch (_: Exception) {}
                    }
                    sb.append("\n")
                }
            } catch (_: Exception) {}
        }

        // 3. Logcat Fatal / Runtime Errors
        sb.append("=== Recent Fatal / Runtime Errors (Logcat) ===\n")
        try {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "300"))
            process.inputStream.bufferedReader().useLines { lines ->
                lines.filter { it.contains("AndroidRuntime", true) || it.contains("FATAL", true) }
                    .take(20)
                    .forEach { sb.append(it).append("\n") }
            }
        } catch (_: Exception) {
            sb.append("Logcat unavailable\n")
        }

        return sb.toString()
    }

    override fun exportSettingsJson(): String {
        val json = JSONObject()
        val allEntries = prefs.all
        allEntries.forEach { (k, v) ->
            if (k != "user_name" && k != "weather_city" && k != "pin") {
                json.put(k, v)
            }
        }
        return json.toString()
    }

    override fun importSettingsJson(jsonStr: String): Boolean {
        return try {
            val obj = JSONObject(jsonStr)
            val editor = prefs.edit()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k != "user_name" && k != "weather_city" && k != "pin") {
                    when (val v = obj.get(k)) {
                        is Boolean -> editor.putBoolean(k, v)
                        is Int -> editor.putInt(k, v)
                        is Long -> editor.putLong(k, v)
                        is Float -> editor.putFloat(k, v.toFloat())
                        is String -> editor.putString(k, v)
                    }
                }
            }
            editor.apply()
            val main = MainActivity.activeInstance?.get()
            main?.runOnUiThread { main.applyWallpaper(); main.reloadAppsAndRefresh() }
            true
        } catch (_: Exception) { false }
    }

    override fun setCustomHomeKey(code: Int): Boolean {
        prefs.edit().putInt("custom_home_keycode", code).apply()
        return true
    }

    override fun getNowPlayingJson(): String {
        return nowPlayingManager?.getNowPlayingJson() ?: "{}"
    }

    override fun handleMediaAction(action: String, posParam: String?): Boolean {
        return nowPlayingManager?.handleMediaAction(action, posParam) ?: false
    }

    override fun getSleepStatusJson(): String {
        return sleepTimerManager?.getSleepStatusJson() ?: "{}"
    }

    override fun setSleepTimer(minutes: Int, videoEnd: Boolean): Boolean {
        val stm = sleepTimerManager ?: return false
        if (videoEnd) {
            stm.startVideoEndTimer()
        } else {
            stm.startTimerMinutes(minutes)
        }
        return true
    }

    override fun cancelSleepTimer(): Boolean {
        sleepTimerManager?.cancelTimer()
        return true
    }

    override fun toggleSetupPattern(): Boolean {
        ProjectorSetupActivity.toggleOrNextPattern()
        return true
    }

    override fun toggleControlCenter(): String? {
        val acc = RemoteAccessibilityService.instance
        if (acc != null) {
            acc.controlCenterOverlayManager.toggleOrShow()
            return null
        }
        return "Please enable Mini Launcher Remote Service in Accessibility Settings"
    }

    override fun apps(): List<AppEntry> {
        val pm = packageManager
        val seen = HashSet<String>()
        val list = ArrayList<AppEntry>()
        @Suppress("DEPRECATION")
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val q = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (ri in pm.queryIntentActivities(q, 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg == packageName || !seen.add(pkg)) continue
                val i = Intent(Intent.ACTION_MAIN).addCategory(cat)
                    .setClassName(pkg, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                val label = ri.loadLabel(pm).toString()
                list.add(AppEntry(label, label, pkg, i, null, null, 0, appInfo = ri.activityInfo.applicationInfo))
            }
        }
        return AppUtils.processAppList(list, prefs, filesDir)
    }

    override fun sendKey(name: String): String? {
        val keyName = name.lowercase(Locale.US)
        val acc = RemoteAccessibilityService.instance

        val mainActivity = MainActivity.activeInstance?.get()
        mainActivity?.resetIdleTimer()

        if (keyName == "back") {
            if (acc == null) return "accessibility_off"
            return if (acc.performBack()) null else "back_failed"
        }
        if (keyName == "home") {
            return if (acc != null) {
                if (acc.performHome()) null else "home_failed"
            } else {
                try {
                    val intent = Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    null
                } catch (_: Exception) { "home_failed" }
            }
        }

        if (mainActivity != null) {
            val code = when (keyName) {
                "up" -> KeyEvent.KEYCODE_DPAD_UP
                "down" -> KeyEvent.KEYCODE_DPAD_DOWN
                "left" -> KeyEvent.KEYCODE_DPAD_LEFT
                "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
                "ok" -> KeyEvent.KEYCODE_DPAD_CENTER
                else -> return "invalid_key"
            }

            mainActivity.runOnUiThread {
                val t = SystemClock.uptimeMillis()
                mainActivity.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
                mainActivity.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
            }
            return null
        }

        if (acc == null) {
            return "accessibility_off"
        }

        return when (keyName) {
            "up", "down", "left", "right" -> {
                val (ok, err) = acc.navigate(keyName)
                if (ok) null else (err ?: "no_focusable_node")
            }
            "ok" -> {
                val (ok, err) = acc.select()
                if (ok) null else (err ?: "no_focusable_node")
            }
            else -> "invalid_key"
        }
    }

    override fun getAppIconPng(pkg: String): ByteArray? {
        return try {
            val pm = packageManager
            val iconDrawable = try {
                pm.getApplicationIcon(pkg)
            } catch (_: Exception) {
                val intent = pm.getLaunchIntentForPackage(pkg) ?: pm.getLeanbackLaunchIntentForPackage(pkg)
                if (intent != null) {
                    @Suppress("DEPRECATION")
                    val ri = pm.resolveActivity(intent, 0)
                    ri?.loadIcon(pm)
                } else null
            } ?: return null

            val width = 96
            val height = 96
            val bitmap = if (iconDrawable is android.graphics.drawable.BitmapDrawable && iconDrawable.bitmap != null) {
                Bitmap.createScaledBitmap(iconDrawable.bitmap, width, height, true)
            } else {
                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bmp)
                iconDrawable.setBounds(0, 0, width, height)
                iconDrawable.draw(canvas)
                bmp
            }

            val baos = java.io.ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
            bitmap.recycle()
            baos.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    override fun typeText(text: String, action: String): Boolean {
        val main = MainActivity.activeInstance?.get()
        main?.resetIdleTimer()
        if (main != null && main.isSearchPanelOpen()) {
            main.handleLiveTypeFromRemote(text, action)
            return true
        }
        return RemoteAccessibilityService.instance?.typeText(text, action) ?: false
    }

    override fun triggerVoice(): String? {
        val main = MainActivity.activeInstance?.get()
        main?.resetIdleTimer()
        if (main != null) {
            return main.triggerVoiceSearch()
        }
        return try {
            val pm = packageManager
            val intent = Intent("android.speech.action.VOICE_SEARCH_HANDS_FREE").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            @Suppress("DEPRECATION")
            val matches = pm.queryIntentActivities(intent, 0)
            if (matches.isNotEmpty()) {
                startActivity(intent)
                null
            } else {
                "Use the mic on your phone keyboard"
            }
        } catch (_: Exception) {
            "Use the mic on your phone keyboard"
        }
    }

    override fun openLink(url: String): String? {
        val main = MainActivity.activeInstance?.get()
        main?.resetIdleTimer()
        val trimmed = url.trim()
        if (trimmed.length > 2000) return "Link is too long (max 2000 characters)"
        if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
            return "Only http and https links are supported"
        }

        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(trimmed)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            @Suppress("DEPRECATION")
            val matches = packageManager.queryIntentActivities(intent, 0)
            if (matches.isEmpty()) {
                "No app on the TV can open this link"
            } else {
                startActivity(intent)
                null
            }
        } catch (_: Exception) {
            "No app on the TV can open this link"
        }
    }

    override fun performPower(): String? {
        val main = MainActivity.activeInstance?.get()
        main?.resetIdleTimer()
        val acc = RemoteAccessibilityService.instance
        if (acc == null) {
            return "Accessibility service not enabled on TV"
        }
        val ok = acc.performPowerDialog()
        return if (ok) null else "Power menu not supported on this device"
    }

    override fun renameApp(pkg: String, newName: String): Boolean {
        AppUtils.renameApp(prefs, pkg, newName)
        val main = MainActivity.activeInstance?.get()
        main?.resetIdleTimer()
        main?.runOnUiThread { main.reloadAppsAndRefresh() }
        return true
    }

    override fun setUserCategory(pkg: String, category: String): Boolean {
        AppUtils.setUserCategory(prefs, pkg, category)
        val main = MainActivity.activeInstance?.get()
        main?.resetIdleTimer()
        main?.runOnUiThread { main.reloadAppsAndRefresh() }
        return true
    }

    override fun saveCardImage(pkg: String, bytes: ByteArray): Boolean {
        val ok = AppUtils.downscaleAndSaveCardImage(filesDir, pkg, bytes)
        if (ok) {
            val main = MainActivity.activeInstance?.get()
            main?.resetIdleTimer()
            main?.runOnUiThread { main.reloadAppsAndRefresh() }
        }
        return ok
    }

    override fun resetCardImage(pkg: String): Boolean {
        val ok = AppUtils.resetCardImage(filesDir, pkg)
        val main = MainActivity.activeInstance?.get()
        main?.resetIdleTimer()
        main?.runOnUiThread { main.reloadAppsAndRefresh() }
        return ok
    }

    override fun resetUsageStats(): Boolean {
        AppUtils.resetLaunchCounts(prefs)
        val main = MainActivity.activeInstance?.get()
        main?.resetIdleTimer()
        main?.runOnUiThread { main.reloadAppsAndRefresh() }
        return true
    }

    override fun launchPkg(pkg: String): Boolean {
        AppUtils.incrementLaunchCount(prefs, pkg)
        val main = MainActivity.activeInstance?.get()
        main?.resetIdleTimer()
        main?.runOnUiThread { main.reloadAppsAndRefresh() }

        return try {
            val pm = packageManager
            var intent = pm.getLeanbackLaunchIntentForPackage(pkg)
            if (intent == null) {
                intent = pm.getLaunchIntentForPackage(pkg)
            }
            if (intent == null) {
                intent = apps().firstOrNull { it.pkg == pkg }?.intent
            }
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                true
            } else {
                false
            }
        } catch (_: Exception) { false }
    }

    override fun getWallpapersJson(): String {
        val files = AppUtils.getWallpaperFiles(filesDir)
        val arr = JSONArray()
        files.forEach { file ->
            val id = file.nameWithoutExtension
            arr.put(JSONObject().apply {
                put("id", id)
                put("filename", file.name)
            })
        }
        return arr.toString()
    }

    override fun deleteWallpaper(id: String): Boolean {
        val ok = AppUtils.deleteWallpaperPhoto(filesDir, id)
        if (ok) {
            prefs.edit().putLong("wallpaper_version", System.currentTimeMillis()).apply()
            val main = MainActivity.activeInstance?.get()
            main?.resetIdleTimer()
            main?.runOnUiThread { main.applyWallpaper() }
        }
        return ok
    }

    override fun saveWallpaper(bytes: ByteArray): Boolean {
        val savedId = AppUtils.saveWallpaperPhoto(filesDir, null, bytes) ?: return false
        prefs.edit()
            .putBoolean("custom", true)
            .putLong("wallpaper_version", System.currentTimeMillis())
            .apply()

        val main = MainActivity.activeInstance?.get()
        main?.resetIdleTimer()
        main?.runOnUiThread { main.applyWallpaper() }
        return true
    }

    override fun pageHtml(): ByteArray {
        return assets.open("remote.html").use { it.readBytes() }
    }

    override fun getSettingsJson(): String {
        val json = JSONObject().apply {
            put("userName", prefs.getString("user_name", "") ?: "")
            put("is12h", prefs.getBoolean("clock_is_12h", true))
            put("showSeconds", prefs.getBoolean("clock_show_seconds", false))
            put("showDate", prefs.getBoolean("clock_show_date", true))
            put("sortMode", prefs.getString("app_sort_mode", "alphabetical") ?: "alphabetical")
            put("homeQrMode", prefs.getString("home_qr_mode", "always") ?: "always")
            put("groupByCategory", prefs.getBoolean("group_by_category", true))
            put("slideshowInterval", prefs.getInt("wallpaper_slideshow_interval", 0))
            put("screensaverTimeout", prefs.getInt("screensaver_timeout_minutes", 0))
            put("weatherCity", prefs.getString("weather_city", "") ?: "")
            put("weatherUnit", prefs.getString("weather_unit", "c") ?: "c")
            put("displayMode", prefs.getString("display_mode", "standard") ?: "standard")
            put("displayAutoStart", prefs.getString("display_auto_start", "08:00") ?: "08:00")
            put("displayAutoEnd", prefs.getString("display_auto_end", "18:00") ?: "18:00")
            put("lowRamMode", prefs.getBoolean("low_ram_mode", false))
            put("accessibilityEnabled", RemoteAccessibilityService.isEnabled(this@RemoteService))
        }
        return json.toString()
    }

    override fun updateSettings(jsonStr: String): Boolean {
        return try {
            val json = JSONObject(jsonStr)
            val editor = prefs.edit()
            if (json.has("userName")) editor.putString("user_name", json.getString("userName").trim())
            if (json.has("is12h")) editor.putBoolean("clock_is_12h", json.getBoolean("is12h"))
            if (json.has("showSeconds")) editor.putBoolean("clock_show_seconds", json.getBoolean("showSeconds"))
            if (json.has("showDate")) editor.putBoolean("clock_show_date", json.getBoolean("showDate"))
            if (json.has("sortMode")) editor.putString("app_sort_mode", json.getString("sortMode"))
            if (json.has("homeQrMode")) editor.putString("home_qr_mode", json.getString("homeQrMode"))
            if (json.has("groupByCategory")) editor.putBoolean("group_by_category", json.getBoolean("groupByCategory"))
            if (json.has("slideshowInterval")) editor.putInt("wallpaper_slideshow_interval", json.getInt("slideshowInterval"))
            if (json.has("screensaverTimeout")) editor.putInt("screensaver_timeout_minutes", json.getInt("screensaverTimeout"))
            if (json.has("weatherCity")) editor.putString("weather_city", json.getString("weatherCity").trim())
            if (json.has("weatherUnit")) editor.putString("weather_unit", json.getString("weatherUnit"))
            if (json.has("displayMode")) editor.putString("display_mode", json.getString("displayMode"))
            if (json.has("displayAutoStart")) editor.putString("display_auto_start", json.getString("displayAutoStart"))
            if (json.has("displayAutoEnd")) editor.putString("display_auto_end", json.getString("displayAutoEnd"))
            if (json.has("lowRamMode")) editor.putBoolean("low_ram_mode", json.getBoolean("lowRamMode"))
            editor.apply()
            val main = MainActivity.activeInstance?.get()
            main?.resetIdleTimer()
            main?.runOnUiThread { main.applyWallpaper(); main.reloadAppsAndRefresh() }
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun exchangeToken(token: String): String? {
        val launcherActivity = MainActivity.activeInstance?.get()
        launcherActivity?.resetIdleTimer()
        return launcherActivity?.exchangeToken(token)
    }

    override fun isValidKey(key: String): Boolean {
        val hash = hashKey(key)
        val jsonStr = prefs.getString("paired_devices_json", "[]") ?: "[]"
        return try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                if (arr.getJSONObject(i).optString("hash") == hash) return true
            }
            false
        } catch (_: Exception) { false }
    }

    override fun adjustVolume(action: String): Boolean {
        return try {
            val main = MainActivity.activeInstance?.get()
            main?.resetIdleTimer()
            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
            val flags = AudioManager.FLAG_SHOW_UI
            when (action.lowercase(Locale.US)) {
                "up", "raise", "vol_up" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, flags)
                "down", "lower", "vol_down" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, flags)
                "mute", "toggle_mute" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, flags)
                    } else {
                        val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                        if (current > 0) {
                            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, flags)
                        } else {
                            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, flags)
                        }
                    }
                }
                else -> return false
            }
            true
        } catch (_: Throwable) { false }
    }

    override fun getFavoritesJson(): String {
        val main = MainActivity.activeInstance?.get()
        if (main != null) return main.getFavoritesJson()
        val jsonStr = prefs.getString("favorite_pkgs_json", "[]") ?: "[]"
        return jsonStr
    }

    override fun updateFavorite(pkg: String, pinned: Boolean): Boolean {
        val main = MainActivity.activeInstance?.get()
        if (main != null) {
            main.resetIdleTimer()
            return main.updateFavorite(pkg, pinned)
        }
        return try {
            val jsonStr = prefs.getString("favorite_pkgs_json", "[]") ?: "[]"
            val list = ArrayList<String>()
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) list.add(arr.getString(i))

            if (pinned) {
                if (!list.contains(pkg)) list.add(pkg)
            } else {
                list.remove(pkg)
            }

            val newArr = JSONArray()
            list.forEach { newArr.put(it) }
            prefs.edit().putString("favorite_pkgs_json", newArr.toString()).apply()
            true
        } catch (_: Exception) { false }
    }

    override fun getHiddenJson(): String {
        val main = MainActivity.activeInstance?.get()
        if (main != null) return main.getHiddenJson()
        val jsonStr = prefs.getString("hidden_pkgs_json", "[]") ?: "[]"
        return jsonStr
    }

    override fun updateHidden(pkg: String, hidden: Boolean): Boolean {
        val main = MainActivity.activeInstance?.get()
        if (main != null) {
            main.resetIdleTimer()
            return main.updateHidden(pkg, hidden)
        }
        return try {
            val jsonStr = prefs.getString("hidden_pkgs_json", "[]") ?: "[]"
            val set = HashSet<String>()
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) set.add(arr.getString(i))

            if (hidden) set.add(pkg) else set.remove(pkg)

            val newArr = JSONArray()
            set.forEach { newArr.put(it) }
            prefs.edit().putString("hidden_pkgs_json", newArr.toString()).apply()
            true
        } catch (_: Exception) { false }
    }

    private fun hashKey(key: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
