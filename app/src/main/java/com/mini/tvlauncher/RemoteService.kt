package com.mini.tvlauncher

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.*

class RemoteService : Service(), RemoteHost {

    companion object {
        private const val CHANNEL_ID = "remote_service_channel"
        private const val NOTIF_ID = 1001

        @Volatile var serverPort: Int = 0
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
        startForegroundSafely()
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
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundSafely() {
        try {
            createNotificationChannel()
            startForeground(NOTIF_ID, buildNotification())
        } catch (_: Exception) {
            // Handle missing POST_NOTIFICATIONS permission on Android 13+
        }
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
                list.add(AppEntry(label, label, pkg, i, null, null, 0))
            }
        }
        return AppUtils.processAppList(list, prefs, filesDir)
    }

    override fun sendKey(name: String): String? {
        val keyName = name.lowercase(Locale.US)
        val acc = RemoteAccessibilityService.instance

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

        // Route D-pad keys (up, down, left, right, ok) to MainActivity via WeakReference if in foreground
        val mainActivity = MainActivity.activeInstance?.get()
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

        // Outside launcher: require Accessibility Service
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
        if (main != null && main.isSearchPanelOpen()) {
            main.handleLiveTypeFromRemote(text, action)
            return true
        }
        return RemoteAccessibilityService.instance?.typeText(text, action) ?: false
    }

    override fun triggerVoice(): String? {
        val main = MainActivity.activeInstance?.get()
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
        main?.runOnUiThread { main.reloadAppsAndRefresh() }
        return true
    }

    override fun saveCardImage(pkg: String, bytes: ByteArray): Boolean {
        val ok = AppUtils.downscaleAndSaveCardImage(filesDir, pkg, bytes)
        if (ok) {
            val main = MainActivity.activeInstance?.get()
            main?.runOnUiThread { main.reloadAppsAndRefresh() }
        }
        return ok
    }

    override fun resetCardImage(pkg: String): Boolean {
        val ok = AppUtils.resetCardImage(filesDir, pkg)
        val main = MainActivity.activeInstance?.get()
        main?.runOnUiThread { main.reloadAppsAndRefresh() }
        return ok
    }

    override fun resetUsageStats(): Boolean {
        AppUtils.resetLaunchCounts(prefs)
        val main = MainActivity.activeInstance?.get()
        main?.runOnUiThread { main.reloadAppsAndRefresh() }
        return true
    }

    override fun launchPkg(pkg: String): Boolean {
        AppUtils.incrementLaunchCount(prefs, pkg)
        val main = MainActivity.activeInstance?.get()
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

    override fun saveWallpaper(bytes: ByteArray): Boolean {
        return try {
            val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, b)
            if (b.outWidth <= 0) return false
            var s = 1
            while (b.outWidth / (s * 2) >= 1920) s *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = s }) ?: return false
            File(filesDir, "wallpaper.jpg").outputStream().use {
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, it)
            }
            bmp.recycle()
            prefs.edit().putBoolean("custom", true).putLong("wallpaper_version", System.currentTimeMillis()).apply()
            true
        } catch (_: Exception) { false }
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
            editor.apply()
            val main = MainActivity.activeInstance?.get()
            main?.runOnUiThread { main.reloadAppsAndRefresh() }
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun exchangeToken(token: String): String? {
        val launcherActivity = MainActivity.activeInstance?.get()
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
        if (main != null) return main.updateFavorite(pkg, pinned)
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
        if (main != null) return main.updateHidden(pkg, hidden)
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
