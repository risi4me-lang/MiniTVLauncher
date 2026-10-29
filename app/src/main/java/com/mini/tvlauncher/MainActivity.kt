package com.mini.tvlauncher

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.*
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.util.*
import kotlin.concurrent.thread

class MainActivity : Activity() {

    companion object {
        @Volatile var activeInstance: WeakReference<MainActivity>? = null
            private set
    }

    @Volatile private var rawUnprocessedApps: List<AppEntry> = emptyList()
    @Volatile private var allInstalledApps: List<AppEntry> = emptyList()
    @Volatile private var currentVisibleApps: List<AppEntry> = emptyList()

    private lateinit var mainScrollView: NestedScrollView
    private lateinit var rowsContainer: RecyclerView
    private lateinit var toolsRow: RecyclerView

    private val sharedViewPool = RecyclerView.RecycledViewPool().apply {
        setMaxRecycledViews(0, 20)
    }

    private lateinit var networkIndicator: NetworkIndicator
    private lateinit var settingsApplier: SettingsApplier
    private lateinit var searchPanelManager: SearchPanelManager
    private lateinit var qrPairingManager: QrPairingManager
    private lateinit var wallpaperManager: WallpaperManager
    private lateinit var categoryRowsManager: CategoryRowsManager
    private lateinit var topShelfManager: TopShelfManager

    private lateinit var keyTesterPanel: FrameLayout
    private lateinit var keyTesterCodeText: TextView
    private lateinit var keyTesterScanText: TextView
    private lateinit var keyTesterNameText: TextView

    private lateinit var screensaverPanel: FrameLayout
    private lateinit var screensaverClockContainer: LinearLayout

    private val idleHandler = Handler(Looper.getMainLooper())
    private val idleRunnable = Runnable { showScreensaver() }

    private val driftHandler = Handler(Looper.getMainLooper())
    private val driftRunnable = object : Runnable {
        override fun run() {
            if (screensaverPanel.visibility == View.VISIBLE) {
                val isLowRam = prefs.getBoolean("low_ram_mode", false)
                if (!isLowRam) {
                    animateClockDrift()
                }
                driftHandler.postDelayed(this, 12_000L)
            }
        }
    }

    private val prefs by lazy { getSharedPreferences("launcher", MODE_PRIVATE) }
    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = loadApps()
    }

    private val prefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "user_name" || key == "clock_is_12h" || key == "clock_show_seconds" || key == "clock_show_date" || key == "favorite_pkgs_json" || key == "hidden_pkgs_json" || key == "app_rename_map" || key == "app_category_map" || key == "group_by_category" || key == "app_launch_counts" || key == "app_sort_mode" || key == "display_mode" || key == "display_auto_start" || key == "display_auto_end" || key == "low_ram_mode") {
            runOnUiThread {
                try {
                    settingsApplier.applySettings()
                    DisplayModeManager.applyDisplayMode(this, prefs)
                    reloadAppsAndRefresh()
                } catch (_: Exception) {}
            }
        } else if (key == "preset" || key == "custom" || key == "wallpaper_version" || key == "wallpaper_slideshow_interval") {
            runOnUiThread { try { wallpaperManager.applyWallpaper() } catch (_: Exception) {} }
        } else if (key == "home_qr_mode" || key == "paired_devices_json") {
            runOnUiThread { try { qrPairingManager.updateHomeQrVisibility(); refreshToolsRow() } catch (_: Exception) {} }
        } else if (key == "screensaver_timeout_minutes") {
            runOnUiThread { try { resetIdleTimer() } catch (_: Exception) {} }
        } else if (key == "weather_city" || key == "weather_unit") {
            runOnUiThread { try { settingsApplier.fetchWeatherBackground() } catch (_: Exception) {} }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashHandler.install(this)
        setContentView(R.layout.activity_main)

        try { prefs.registerOnSharedPreferenceChangeListener(prefChangeListener) } catch (_: Exception) {}

        mainScrollView = findViewById(R.id.mainScrollView)
        toolsRow = findViewById(R.id.toolsRow)
        rowsContainer = findViewById(R.id.rowsContainer)

        rowsContainer.layoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
        rowsContainer.isNestedScrollingEnabled = false

        mainScrollView.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
            if (newFocus != null && isChildOf(mainScrollView, newFocus)) {
                val rect = Rect()
                newFocus.getDrawingRect(rect)
                mainScrollView.offsetDescendantRectToMyCoords(newFocus, rect)

                rect.top = (rect.top - (24 * resources.displayMetrics.density).toInt()).coerceAtLeast(0)
                rect.bottom = rect.bottom + (24 * resources.displayMetrics.density).toInt()

                val scrollY = mainScrollView.scrollY
                val height = mainScrollView.height

                if (rect.bottom > scrollY + height) {
                    val targetY = rect.bottom - height
                    mainScrollView.smoothScrollTo(0, targetY)
                } else if (rect.top < scrollY) {
                    val targetY = rect.top
                    mainScrollView.smoothScrollTo(0, targetY)
                }
            }
        }

        wallpaperManager = WallpaperManager(
            this, prefs, filesDir,
            findViewById(R.id.wallpaper),
            findViewById(R.id.wallpaperNext)
        )

        settingsApplier = SettingsApplier(
            this, prefs,
            findViewById(R.id.greeting),
            findViewById(R.id.clock),
            findViewById(R.id.date),
            findViewById(R.id.weatherContainer),
            findViewById(R.id.weatherIcon),
            findViewById(R.id.weatherText)
        )

        networkIndicator = NetworkIndicator(this, findViewById(R.id.netIcon)) {
            try { qrPairingManager.refreshHomeQrCode() } catch (_: Exception) {}
            try { settingsApplier.fetchWeatherBackground() } catch (_: Exception) {}
        }

        topShelfManager = TopShelfManager(
            this, prefs,
            findViewById(R.id.topShelfContainer),
            findViewById(R.id.topShelfBanner),
            findViewById(R.id.topShelfBannerNext),
            findViewById(R.id.topShelfTitle),
            findViewById(R.id.topShelfSubtitle)
        )

        searchPanelManager = SearchPanelManager(
            this,
            findViewById(R.id.searchPillBtn),
            findViewById(R.id.searchPanel),
            findViewById(R.id.searchInput),
            findViewById(R.id.searchAppResults),
            findViewById(R.id.searchWebBtn),
            findViewById(R.id.searchYoutubeBtn),
            findViewById(R.id.closeSearchPanelBtn),
            getInstalledApps = { allInstalledApps },
            getHiddenPkgs = { getHiddenPkgs() },
            onAppClick = { launch(it) }
        )

        qrPairingManager = QrPairingManager(
            this, prefs,
            findViewById(R.id.homeQrCard),
            findViewById(R.id.homeQrImageView),
            findViewById(R.id.homeQrCaption),
            findViewById(R.id.homeQrOfflineText),
            findViewById(R.id.pairingPanel),
            findViewById(R.id.pairingInfo),
            findViewById(R.id.qrImageView),
            findViewById(R.id.pairedDevicesList),
            findViewById(R.id.noDevicesText),
            findViewById(R.id.closePanelBtn),
            findViewById(R.id.removeAllBtn),
            onPairedDevicesChanged = { refreshToolsRow() }
        )

        categoryRowsManager = CategoryRowsManager(
            prefs, rowsContainer, sharedViewPool,
            onAppClick = { launch(it) },
            onAppLongClick = { entry, view -> showAppContextMenu(entry, view) },
            onAppFocused = { app -> topShelfManager.updateFocusedApp(app) }
        )

        keyTesterPanel = findViewById(R.id.keyTesterPanel)
        keyTesterCodeText = findViewById(R.id.keyTesterCodeText)
        keyTesterScanText = findViewById(R.id.keyTesterScanText)
        keyTesterNameText = findViewById(R.id.keyTesterNameText)
        findViewById<View>(R.id.closeKeyTesterPanelBtn).setOnClickListener { closeKeyTesterPanel() }

        screensaverPanel = findViewById(R.id.screensaverPanel)
        screensaverClockContainer = findViewById(R.id.screensaverClockContainer)
        screensaverPanel.setOnClickListener { dismissScreensaver() }

        toolsRow.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        try { refreshToolsRow() } catch (_: Exception) {}

        val wallpaperBtn = findViewById<View>(R.id.wallpaperBtn)
        wallpaperBtn.setOnClickListener { wallpaperManager.chooseWallpaper() }
        wallpaperBtn.setOnFocusChangeListener { v, focused ->
            if (focused) topShelfManager.updateFocusedApp(null)
            v.animate().scaleX(if (focused) 1.08f else 1.0f).scaleY(if (focused) 1.08f else 1.0f).setDuration(160).start()
        }

        findViewById<View>(R.id.searchPillBtn).setOnFocusChangeListener { v, focused ->
            if (focused) topShelfManager.updateFocusedApp(null)
            v.animate().scaleX(if (focused) 1.08f else 1.0f).scaleY(if (focused) 1.08f else 1.0f).setDuration(160).start()
        }

        findViewById<View>(R.id.homeQrCard).setOnFocusChangeListener { v, focused ->
            if (focused) topShelfManager.updateFocusedApp(null)
            v.animate().scaleX(if (focused) 1.08f else 1.0f).scaleY(if (focused) 1.08f else 1.0f).setDuration(160).start()
        }

        findViewById<View>(R.id.closeHiddenPanelBtn).apply {
            setOnClickListener { closeHiddenAppsPanel() }
            setOnFocusChangeListener { v, focused ->
                v.animate().scaleX(if (focused) 1.08f else 1.0f).scaleY(if (focused) 1.08f else 1.0f).setDuration(160).start()
            }
        }

        findViewById<View>(R.id.unhideAllBtn).apply {
            setOnClickListener { unhideAllApps() }
            setOnFocusChangeListener { v, focused ->
                v.animate().scaleX(if (focused) 1.08f else 1.0f).scaleY(if (focused) 1.08f else 1.0f).setDuration(160).start()
            }
        }

        try { settingsApplier.applySettings() } catch (_: Exception) {}
        try { DisplayModeManager.applyDisplayMode(this, prefs) } catch (_: Exception) {}
        try { wallpaperManager.applyWallpaper() } catch (_: Exception) {}
        try { loadApps() } catch (_: Exception) {}
        try { startRemoteService() } catch (_: Exception) {}
        try { requestNotificationPermissionIfNeeded() } catch (_: Exception) {}
        try { checkAndSuggestLowRamMode() } catch (_: Exception) {}
    }

    private fun checkAndSuggestLowRamMode() {
        if (prefs.contains("low_ram_dismissed") || prefs.getBoolean("low_ram_mode", false)) return
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val isLowRam = am.isLowRamDevice || memInfo.lowMemory || (memInfo.availMem < memInfo.threshold)
        if (isLowRam) {
            AlertDialog.Builder(this)
                .setTitle("Low-RAM Device Detected")
                .setMessage("Would you like to enable Low-RAM Mode for smoother performance and reduced memory footprint?")
                .setPositiveButton("Enable") { _, _ ->
                    prefs.edit().putBoolean("low_ram_mode", true).putBoolean("low_ram_dismissed", true).apply()
                    reloadAppsAndRefresh()
                }
                .setNegativeButton("Not Now") { _, _ ->
                    prefs.edit().putBoolean("low_ram_dismissed", true).apply()
                }
                .show()
        }
    }

    fun openSearchPanel() {
        searchPanelManager.open()
    }

    fun launch(e: AppEntry) {
        try { AppUtils.incrementLaunchCount(prefs, e.pkg) } catch (_: Exception) {}
        try { reloadAppsAndRefresh() } catch (_: Exception) {}
        try { startActivity(e.intent) } catch (_: Exception) {
            Toast.makeText(this, "Can't open ${e.label}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        KeyEventBuffer.add(event)
        resetIdleTimer()
        if (screensaverPanel.visibility == View.VISIBLE) {
            dismissScreensaver()
            return true
        }
        if (keyTesterPanel.visibility == View.VISIBLE) {
            keyTesterCodeText.text = "KeyCode: ${event.keyCode}"
            keyTesterScanText.text = "ScanCode: ${event.scanCode}"
            keyTesterNameText.text = "KeyName: ${KeyEvent.keyCodeToString(event.keyCode)}"
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        resetIdleTimer()
        if (screensaverPanel.visibility == View.VISIBLE) {
            dismissScreensaver()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    fun resetIdleTimer() {
        runOnUiThread {
            try {
                if (screensaverPanel.visibility == View.VISIBLE) {
                    dismissScreensaver()
                }
                idleHandler.removeCallbacks(idleRunnable)
                val timeoutMinutes = prefs.getInt("screensaver_timeout_minutes", 0)
                if (timeoutMinutes > 0) {
                    idleHandler.postDelayed(idleRunnable, timeoutMinutes * 60 * 1000L)
                }
            } catch (_: Exception) {}
        }
    }

    private fun showScreensaver() {
        screensaverPanel.visibility = View.VISIBLE
        screensaverClockContainer.translationX = 0f
        screensaverClockContainer.translationY = 0f
        driftHandler.removeCallbacks(driftRunnable)
        driftHandler.post(driftRunnable)
    }

    private fun dismissScreensaver() {
        screensaverPanel.visibility = View.GONE
        driftHandler.removeCallbacks(driftRunnable)
        resetIdleTimer()
    }

    private fun animateClockDrift() {
        val maxDx = (160 * resources.displayMetrics.density).toInt()
        val maxDy = (100 * resources.displayMetrics.density).toInt()
        val random = Random()
        val targetX = (random.nextInt(maxDx * 2) - maxDx).toFloat()
        val targetY = (random.nextInt(maxDy * 2) - maxDy).toFloat()

        screensaverClockContainer.animate()
            .translationX(targetX)
            .translationY(targetY)
            .setDuration(3000)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun isChildOf(parent: ViewGroup, child: View): Boolean {
        var p = child.parent
        while (p != null) {
            if (p == parent) return true
            p = p.parent
        }
        return false
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                if (!prefs.getBoolean("notification_perm_requested", false)) {
                    prefs.edit().putBoolean("notification_perm_requested", true).apply()
                    try {
                        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    override fun onDestroy() {
        try { prefs.unregisterOnSharedPreferenceChangeListener(prefChangeListener) } catch (_: Exception) {}
        try { qrPairingManager.stop() } catch (_: Exception) {}
        try { wallpaperManager.destroy() } catch (_: Exception) {}
        try { settingsApplier.stopWeatherPolling() } catch (_: Exception) {}
        idleHandler.removeCallbacks(idleRunnable)
        driftHandler.removeCallbacks(driftRunnable)
        if (activeInstance?.get() == this) {
            activeInstance = null
        }
        super.onDestroy()
    }

    private fun openKeyTesterPanel() {
        keyTesterPanel.visibility = View.VISIBLE
        findViewById<View>(R.id.closeKeyTesterPanelBtn).requestFocus()
    }

    private fun closeKeyTesterPanel() {
        keyTesterPanel.visibility = View.GONE
        toolsRow.requestFocus()
    }

    private fun openDeviceReportDialog() {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)
        val ramMb = (memInfo.totalMem) / (1024 * 1024)

        val summary = "Device Model: ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                "Android Version: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n" +
                "Total RAM: ${ramMb} MB\n" +
                "Total Apps Installed: ${allInstalledApps.size}\n\n" +
                "👉 Open phone remote → Developer → Device report to copy or download full text report."

        AlertDialog.Builder(this)
            .setTitle("Device Report Summary")
            .setMessage(summary)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun openDefaultHomeChooser() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val rm = getSystemService(RoleManager::class.java)
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) {
                    val intent = rm.createRequestRoleIntent(RoleManager.ROLE_HOME)
                    startActivity(intent)
                    return
                }
            }
            val intent = Intent(Settings.ACTION_HOME_SETTINGS)
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "Not supported on this device", Toast.LENGTH_SHORT).show()
        }
    }

    fun isSearchPanelOpen(): Boolean = searchPanelManager.isOpen()

    fun handleLiveTypeFromRemote(text: String, action: String) {
        searchPanelManager.handleLiveTypeFromRemote(text, action)
    }

    fun triggerVoiceSearch(): String? {
        val pm = packageManager
        val intents = listOf(
            Intent("android.speech.action.VOICE_SEARCH_HANDS_FREE"),
            Intent("android.intent.action.VOICE_COMMAND"),
            Intent(Intent.ACTION_ASSIST)
        )
        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            @Suppress("DEPRECATION")
            val matches = pm.queryIntentActivities(intent, 0)
            if (matches.isNotEmpty()) {
                return try {
                    startActivity(intent)
                    null
                } catch (_: Exception) { null }
            }
        }
        return "Use the mic on your phone keyboard"
    }

    private fun refreshToolsRow() {
        val toolTiles = getAvailableToolTiles()
        toolsRow.adapter = ToolsAdapter(toolTiles, onClick = { tile ->
            when (tile.id) {
                "hidden_apps" -> openHiddenAppsPanel()
                "power" -> handlePowerClick()
                "pair_phone" -> qrPairingManager.openPairingPanel()
                "default_home" -> openDefaultHomeChooser()
                "key_tester" -> openKeyTesterPanel()
                "projector_setup" -> startActivity(Intent(this, ProjectorSetupActivity::class.java))
                "device_report" -> openDeviceReportDialog()
                "media_access" -> {
                    try {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) {
                        Toast.makeText(this, "Enable via ADB: adb shell cmd notification allow_listener ...", Toast.LENGTH_LONG).show()
                    }
                }
                else -> {
                    try {
                        tile.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(tile.intent)
                    } catch (_: Exception) {
                        Toast.makeText(this, "Cannot open ${tile.label}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }, onToolFocused = {
            topShelfManager.updateFocusedApp(null)
        })
    }

    fun reloadAppsAndRefresh() {
        allInstalledApps = AppUtils.processAppList(rawUnprocessedApps, prefs, filesDir)
        refreshAppLists()
    }

    private fun handlePowerClick() {
        val acc = RemoteAccessibilityService.instance
        if (acc != null) {
            val ok = acc.performPowerDialog()
            if (!ok) {
                Toast.makeText(this, "Power menu not supported on this device", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Please enable Mini Launcher Remote Service in Accessibility settings", Toast.LENGTH_SHORT).show()
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(this, "Cannot open Accessibility Settings", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun openLink(url: String): String? {
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

    private fun getFavoritePkgs(): List<String> {
        val jsonStr = prefs.getString("favorite_pkgs_json", "[]") ?: "[]"
        return try {
            val arr = JSONArray(jsonStr)
            val list = ArrayList<String>()
            for (i in 0 until arr.length()) list.add(arr.getString(i))
            list
        } catch (_: Exception) { emptyList() }
    }

    private fun saveFavoritePkgs(list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs.edit().putString("favorite_pkgs_json", arr.toString()).apply()
        runOnUiThread { reloadAppsAndRefresh() }
    }

    fun getFavoritesJson(): String {
        val favPkgs = getFavoritePkgs()
        val arr = JSONArray()
        favPkgs.forEach { pkg ->
            val entry = allInstalledApps.firstOrNull { it.pkg == pkg }
            if (entry != null) {
                arr.put(JSONObject().apply {
                    put("defaultLabel", entry.defaultLabel)
                    put("label", entry.label)
                    put("pkg", entry.pkg)
                    put("launches", entry.launchCount)
                    put("hasCustomImage", entry.hasCustomImage)
                })
            }
        }
        return arr.toString()
    }

    fun updateFavorite(pkg: String, pinned: Boolean): Boolean {
        val list = getFavoritePkgs().toMutableList()
        if (pinned) {
            if (!list.contains(pkg)) list.add(pkg)
        } else {
            list.remove(pkg)
        }
        saveFavoritePkgs(list)
        return true
    }

    fun getHiddenPkgs(): Set<String> {
        val jsonStr = prefs.getString("hidden_pkgs_json", "[]") ?: "[]"
        return try {
            val arr = JSONArray(jsonStr)
            val set = HashSet<String>()
            for (i in 0 until arr.length()) set.add(arr.getString(i))
            set
        } catch (_: Exception) { emptySet() }
    }

    private fun saveHiddenPkgs(set: Set<String>) {
        val arr = JSONArray()
        set.forEach { arr.put(it) }
        prefs.edit().putString("hidden_pkgs_json", arr.toString()).apply()
        runOnUiThread { reloadAppsAndRefresh() }
    }

    fun getHiddenJson(): String {
        val hiddenPkgs = getHiddenPkgs()
        val arr = JSONArray()
        hiddenPkgs.forEach { pkg ->
            val entry = allInstalledApps.firstOrNull { it.pkg == pkg }
            val label = entry?.label ?: pkg
            arr.put(JSONObject().apply {
                put("defaultLabel", entry?.defaultLabel ?: pkg)
                put("label", label)
                put("pkg", pkg)
                put("launches", entry?.launchCount ?: 0)
                put("hasCustomImage", entry?.hasCustomImage ?: false)
            })
        }
        return arr.toString()
    }

    fun updateHidden(pkg: String, hidden: Boolean): Boolean {
        val set = getHiddenPkgs().toMutableSet()
        if (hidden) set.add(pkg) else set.remove(pkg)
        saveHiddenPkgs(set)
        return true
    }

    private fun refreshAppLists() {
        try {
            categoryRowsManager.refreshAppLists(allInstalledApps, ::getFavoritePkgs, ::getHiddenPkgs)
            refreshHiddenAppsUi()
        } catch (_: Exception) {}
    }

    private fun showAppContextMenu(appEntry: AppEntry, anchorView: View) {
        val isFav = getFavoritePkgs().contains(appEntry.pkg)
        val favActionText = if (isFav) "Unpin from favorites" else "Pin to favorites"

        @Suppress("DEPRECATION")
        val isSystem = try {
            val appInfo = packageManager.getApplicationInfo(appEntry.pkg, 0)
            (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (_: Exception) { false }

        val imageActionText = if (appEntry.hasCustomImage) "Reset custom card image" else "Change card image"
        val options = arrayOf(favActionText, "Move to category", "Rename", imageActionText, "Hide", "App info", "Uninstall")

        val builder = AlertDialog.Builder(this)
            .setTitle(appEntry.label)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> updateFavorite(appEntry.pkg, !isFav)
                    1 -> showMoveCategoryDialog(appEntry)
                    2 -> showRenameDialog(appEntry)
                    3 -> {
                        if (appEntry.hasCustomImage) {
                            AppUtils.resetCardImage(filesDir, appEntry.pkg)
                            reloadAppsAndRefresh()
                            Toast.makeText(this, "Card image reset", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "Upload custom image from phone remote page", Toast.LENGTH_LONG).show()
                        }
                    }
                    4 -> updateHidden(appEntry.pkg, true)
                    5 -> openAppInfo(appEntry.pkg)
                    6 -> {
                        if (isSystem) {
                            Toast.makeText(this, "System app cannot be uninstalled", Toast.LENGTH_SHORT).show()
                        } else {
                            uninstallApp(appEntry.pkg)
                        }
                    }
                }
            }

        val dialog = builder.create()
        dialog.setOnDismissListener {
            anchorView.requestFocus()
        }
        dialog.show()
    }

    private fun showMoveCategoryDialog(appEntry: AppEntry) {
        val categories = arrayOf("Automatic (Default)", AppUtils.CAT_ENTERTAINMENT, AppUtils.CAT_GAMES, AppUtils.CAT_PRODUCTIVITY, AppUtils.CAT_OTHERS)
        AlertDialog.Builder(this)
            .setTitle("Move ${appEntry.label} to Category")
            .setItems(categories) { _, which ->
                val selected = if (which == 0) "" else categories[which]
                AppUtils.setUserCategory(prefs, appEntry.pkg, selected)
                reloadAppsAndRefresh()
                Toast.makeText(this, "Category updated", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showRenameDialog(appEntry: AppEntry) {
        val input = EditText(this).apply {
            setText(appEntry.label)
            setSelection(text.length)
        }
        val container = FrameLayout(this).apply {
            val p = (16 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("Rename ${appEntry.defaultLabel}")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                AppUtils.renameApp(prefs, appEntry.pkg, newName)
                reloadAppsAndRefresh()
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Reset to default") { _, _ ->
                AppUtils.renameApp(prefs, appEntry.pkg, "")
                reloadAppsAndRefresh()
            }
            .show()
    }

    private fun openAppInfo(pkg: String) {
        try {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (_: Exception) {
            Toast.makeText(this, "Cannot open App Info", Toast.LENGTH_SHORT).show()
        }
    }

    private fun uninstallApp(pkg: String) {
        try {
            val i = Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (_: Exception) {
            Toast.makeText(this, "Cannot uninstall app", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openHiddenAppsPanel() {
        refreshHiddenAppsUi()
        val panel = findViewById<View>(R.id.hiddenAppsPanel)
        panel.visibility = View.VISIBLE
        findViewById<View>(R.id.closeHiddenPanelBtn).requestFocus()
    }

    private fun closeHiddenAppsPanel() {
        findViewById<View>(R.id.hiddenAppsPanel).visibility = View.GONE
        toolsRow.requestFocus()
    }

    private fun unhideAllApps() {
        saveHiddenPkgs(emptySet())
    }

    private fun refreshHiddenAppsUi() {
        val container = findViewById<LinearLayout>(R.id.hiddenAppsList) ?: return
        val noHiddenText = findViewById<TextView>(R.id.noHiddenAppsText) ?: return
        container.removeAllViews()

        val hiddenPkgs = getHiddenPkgs()
        if (hiddenPkgs.isEmpty()) {
            noHiddenText.visibility = View.VISIBLE
        } else {
            noHiddenText.visibility = View.GONE
            val inflater = LayoutInflater.from(this)
            for (pkg in hiddenPkgs) {
                val entry = allInstalledApps.firstOrNull { it.pkg == pkg }
                val label = entry?.label ?: pkg
                val item = inflater.inflate(R.layout.item_hidden_app, container, false)
                item.findViewById<TextView>(R.id.appName).text = label
                val unhideBtn = item.findViewById<View>(R.id.unhideBtn)
                unhideBtn.setOnClickListener { updateHidden(pkg, false) }
                unhideBtn.setOnFocusChangeListener { v, focused ->
                    v.animate().scaleX(if (focused) 1.08f else 1.0f).scaleY(if (focused) 1.08f else 1.0f).setDuration(160).start()
                }
                container.addView(item)
            }
        }
    }

    private fun getAvailableToolTiles(): List<ToolTile> {
        val pm = packageManager
        val result = ArrayList<ToolTile>()

        fun checkAndAdd(id: String, label: String, iconRes: Int, intent: Intent) {
            try {
                @Suppress("DEPRECATION")
                val matches = pm.queryIntentActivities(intent, 0)
                if (matches.isNotEmpty()) {
                    val resolvedIntent = Intent(intent)
                    if (resolvedIntent.component == null) {
                        val ri = matches[0]
                        resolvedIntent.setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
                    }
                    resolvedIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    result.add(ToolTile(id, label, iconRes, resolvedIntent))
                }
            } catch (_: Exception) {}
        }

        result.add(ToolTile("power", "Power", R.drawable.ic_tool_power, Intent()))
        result.add(ToolTile("projector_setup", "Projector Setup", R.drawable.ic_tool_display, Intent(this, ProjectorSetupActivity::class.java)))
        result.add(ToolTile("device_report", "Device Report", R.drawable.ic_tool_settings, Intent()))
        result.add(ToolTile("media_access", "Enable Media Access", R.drawable.ic_tool_settings, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)))
        result.add(ToolTile("default_home", "Default Home", R.drawable.ic_tool_launcher, Intent()))
        result.add(ToolTile("key_tester", "Key Tester", R.drawable.ic_tool_settings, Intent()))

        checkAndAdd("settings", "Settings", R.drawable.ic_tool_settings, Intent(Settings.ACTION_SETTINGS))
        checkAndAdd("wifi", "Wi-Fi", R.drawable.ic_tool_wifi, Intent(Settings.ACTION_WIFI_SETTINGS))
        checkAndAdd("bluetooth", "Pair Speaker", R.drawable.ic_tool_bluetooth, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        checkAndAdd("display", "Display", R.drawable.ic_tool_display, Intent(Settings.ACTION_DISPLAY_SETTINGS))

        val mode = prefs.getString("home_qr_mode", "always") ?: "always"
        val hasPaired = qrPairingManager.getPairedDevicesList().isNotEmpty()
        if (mode == "off" || (mode == "until_paired" && hasPaired)) {
            result.add(ToolTile("pair_phone", "Pair Phone", R.drawable.ic_tool_wifi, Intent()))
        }

        result.add(ToolTile("hidden_apps", "Hidden Apps", R.drawable.ic_tool_hidden, Intent()))

        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        @Suppress("DEPRECATION")
        val homeMatches = pm.queryIntentActivities(homeIntent, 0)
        val originalMatch = homeMatches.firstOrNull { it.activityInfo.packageName != packageName }

        if (originalMatch != null) {
            val launcherIntent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setClassName(originalMatch.activityInfo.packageName, originalMatch.activityInfo.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            result.add(ToolTile("original_launcher", "Original Launcher", R.drawable.ic_tool_launcher, launcherIntent))
        } else {
            checkAndAdd("original_launcher", "Original Launcher", R.drawable.ic_tool_launcher, Intent(Settings.ACTION_APPLICATION_SETTINGS))
        }

        return result
    }

    private fun getShortcutCandidates(): List<ShortcutCandidate> {
        return listOf(
            ShortcutCandidate(
                id = "source",
                label = "Source / Input",
                iconRes = R.drawable.ic_shortcut_source,
                averageColor = 0xFF2A52BE.toInt(),
                candidateIntents = listOf(
                    Intent("android.intent.action.TV_INPUT"),
                    Intent().setComponent(ComponentName("com.android.tv.settings", "com.android.tv.settings.inputs.InputsActivity")),
                    Intent("android.settings.INPUT_SETTINGS"),
                    Intent().setClassName("com.mstar.tv.tvplayer.ui", "com.mstar.tv.tvplayer.ui.RootActivity"),
                    Intent().setClassName("com.droidlogic.otv", "com.droidlogic.otv.MainActivity"),
                    Intent().setClassName("com.softwinner.source", "com.softwinner.source.MainActivity"),
                    Intent().setClassName("com.projector.source", "com.projector.source.MainActivity")
                )
            ),
            ShortcutCandidate(
                id = "keystone",
                label = "Keystone",
                iconRes = R.drawable.ic_shortcut_keystone,
                averageColor = 0xFF8E44AD.toInt(),
                candidateIntents = listOf(
                    Intent().setClassName("com.projector.keystone", "com.projector.keystone.MainActivity"),
                    Intent().setClassName("com.softwinner.keystone", "com.softwinner.keystone.MainActivity"),
                    Intent().setClassName("com.mstar.keystone", "com.mstar.keystone.MainActivity"),
                    Intent().setClassName("com.droidlogic.keystone", "com.droidlogic.keystone.MainActivity"),
                    Intent("com.android.tv.settings.KEYSTONE"),
                    Intent().setClassName("com.android.tv.settings", "com.android.tv.settings.device.display.daydream.KeystoneActivity")
                )
            ),
            ShortcutCandidate(
                id = "focus",
                label = "Focus",
                iconRes = R.drawable.ic_shortcut_focus,
                averageColor = 0xFF16A085.toInt(),
                candidateIntents = listOf(
                    Intent().setClassName("com.projector.focus", "com.projector.focus.MainActivity"),
                    Intent().setClassName("com.softwinner.focus", "com.softwinner.focus.MainActivity"),
                    Intent().setClassName("com.mstar.focus", "com.mstar.focus.MainActivity"),
                    Intent().setClassName("com.droidlogic.focus", "com.droidlogic.focus.MainActivity"),
                    Intent("com.android.tv.settings.FOCUS")
                )
            ),
            ShortcutCandidate(
                id = "picture",
                label = "Picture Mode",
                iconRes = R.drawable.ic_shortcut_picture,
                averageColor = 0xFFD35400.toInt(),
                candidateIntents = listOf(
                    Intent().setClassName("com.android.tv.settings", "com.android.tv.settings.device.display.PictureModeActivity"),
                    Intent("android.settings.DISPLAY_SETTINGS"),
                    Intent().setClassName("com.mstar.tv.settings", "com.mstar.tv.settings.PictureModeActivity"),
                    Intent().setClassName("com.droidlogic.tvsettings", "com.droidlogic.tvsettings.display.PictureModeActivity")
                )
            )
        )
    }

    private fun resolveIntent(pm: PackageManager, intent: Intent): Intent? {
        return try {
            @Suppress("DEPRECATION")
            val matches = pm.queryIntentActivities(intent, 0)
            if (matches.isNotEmpty()) {
                val ri = matches[0]
                val resolved = Intent(intent)
                if (resolved.component == null) {
                    resolved.setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
                }
                resolved.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                resolved
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun applyWallpaper() {
        try { wallpaperManager.applyWallpaper() } catch (_: Exception) {}
    }

    fun exchangeToken(token: String): String? {
        return try { qrPairingManager.exchangeToken(token) } catch (_: Exception) { null }
    }

    private fun startRemoteService() {
        try { RemoteService.startService(this) } catch (_: Exception) {}
    }

    override fun onStart() {
        super.onStart()
        activeInstance = WeakReference(this)
        try { settingsApplier.applySettings() } catch (_: Exception) {}
        try { DisplayModeManager.applyDisplayMode(this, prefs) } catch (_: Exception) {}
        try { networkIndicator.start() } catch (_: Exception) {}
        try { qrPairingManager.start() } catch (_: Exception) {}
        try { wallpaperManager.start() } catch (_: Exception) {}
        try { settingsApplier.startWeatherPolling() } catch (_: Exception) {}

        try {
            val f = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED); addDataScheme("package")
            }
            registerReceiver(pkgReceiver, f)
        } catch (_: Exception) {}

        try { resetIdleTimer() } catch (_: Exception) {}
    }

    override fun onStop() {
        try { qrPairingManager.stop() } catch (_: Exception) {}
        try { wallpaperManager.stop() } catch (_: Exception) {}
        try { settingsApplier.stopWeatherPolling() } catch (_: Exception) {}
        try { networkIndicator.stop() } catch (_: Exception) {}
        idleHandler.removeCallbacks(idleRunnable)
        driftHandler.removeCallbacks(driftRunnable)
        if (activeInstance?.get() == this) {
            activeInstance = null
        }
        super.onStop()
        try { unregisterReceiver(pkgReceiver) } catch (_: Exception) {}
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (screensaverPanel.visibility == View.VISIBLE) {
            dismissScreensaver()
            return
        }
        if (keyTesterPanel.visibility == View.VISIBLE) {
            closeKeyTesterPanel()
            return
        }
        if (searchPanelManager.isOpen()) {
            searchPanelManager.close()
            return
        }
        val hiddenPanel = findViewById<View>(R.id.hiddenAppsPanel)
        if (hiddenPanel.visibility == View.VISIBLE) {
            closeHiddenAppsPanel()
            return
        }
        if (qrPairingManager.isPairingPanelOpen()) {
            qrPairingManager.closePairingPanel()
            return
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        resetIdleTimer()
        mainScrollView.smoothScrollTo(0, 0)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        try { wallpaperManager.handleActivityResult(req, res, data) } catch (_: Exception) {}
    }

    private fun loadApps() {
        thread {
            try {
                val pm = packageManager
                val seen = HashSet<String>()
                val list = ArrayList<AppEntry>()

                for (candidate in getShortcutCandidates()) {
                    for (intent in candidate.candidateIntents) {
                        val target = resolveIntent(pm, intent)
                        if (target != null) {
                            val iconDrawable = try {
                                resources.getDrawable(candidate.iconRes, theme)
                            } catch (_: Exception) { null }
                            list.add(
                                AppEntry(
                                    defaultLabel = candidate.label,
                                    label = candidate.label,
                                    pkg = target.component?.packageName ?: candidate.id,
                                    intent = target,
                                    banner = null,
                                    icon = iconDrawable,
                                    averageColor = candidate.averageColor,
                                    appInfo = target.component?.let { pm.getApplicationInfo(it.packageName, 0) }
                                )
                            )
                            break
                        }
                    }
                }

                val installedApps = ArrayList<AppEntry>()
                for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
                    val q = Intent(Intent.ACTION_MAIN).addCategory(cat)
                    @Suppress("DEPRECATION")
                    for (ri in pm.queryIntentActivities(q, 0)) {
                        val pkg = ri.activityInfo.packageName
                        if (pkg == packageName || !seen.add(pkg)) continue
                        val i = Intent(Intent.ACTION_MAIN).addCategory(cat)
                            .setClassName(pkg, ri.activityInfo.name)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                        val banner = try {
                            ri.activityInfo.loadBanner(pm)
                        } catch (_: Throwable) { null }

                        val icon = try {
                            ri.loadIcon(pm) ?: pm.getApplicationIcon(pkg)
                        } catch (_: Exception) { null }

                        val avgColor = if (banner == null && icon != null) extractAverageColor(icon) else 0xFF1B2440.toInt()

                        val defaultLabel = ri.loadLabel(pm).toString()
                        installedApps.add(AppEntry(defaultLabel, defaultLabel, pkg, i, banner, icon, avgColor, appInfo = ri.activityInfo.applicationInfo))
                    }
                }
                list.addAll(installedApps)

                rawUnprocessedApps = list
                allInstalledApps = AppUtils.processAppList(list, prefs, filesDir)
                runOnUiThread { refreshAppLists() }
            } catch (_: Exception) {}
        }
    }

    private fun extractAverageColor(drawable: Drawable): Int {
        return try {
            val bmp = drawableToBitmap(drawable)
            var r = 0L; var g = 0L; var b = 0L; var count = 0L
            val w = bmp.width; val h = bmp.height
            val pixels = IntArray(w * h)
            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
            val step = (pixels.size / 200).coerceAtLeast(1)
            var i = 0
            while (i < pixels.size) {
                val c = pixels[i]
                val alpha = (c ushr 24) and 0xFF
                if (alpha > 100) {
                    r += (c ushr 16) and 0xFF
                    g += (c ushr 8) and 0xFF
                    b += c and 0xFF
                    count++
                }
                i += step
            }
            if (count == 0L) return 0xFF1B2440.toInt()
            Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
        } catch (_: Exception) {
            0xFF1B2440.toInt()
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap
        }
        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }
}
