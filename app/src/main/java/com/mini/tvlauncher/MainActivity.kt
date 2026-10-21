package com.mini.tvlauncher

import android.app.Activity
import android.app.AlertDialog
import android.app.SearchManager
import android.content.*
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

data class AppEntry(
    val defaultLabel: String,
    val label: String,
    val pkg: String,
    val intent: Intent,
    val banner: Drawable?,
    val icon: Drawable?,
    val averageColor: Int,
    val launchCount: Int = 0,
    val hasCustomImage: Boolean = false,
    val appInfo: ApplicationInfo? = null
)

data class ToolTile(
    val id: String,
    val label: String,
    val iconRes: Int,
    val intent: Intent
)

data class CategoryRowItem(
    val title: String,
    val apps: List<AppEntry>
)

private data class ShortcutCandidate(
    val id: String,
    val label: String,
    val iconRes: Int,
    val averageColor: Int,
    val candidateIntents: List<Intent>
)

class MainActivity : Activity() {

    companion object {
        @Volatile var activeInstance: WeakReference<MainActivity>? = null
            private set
    }

    @Volatile private var rawUnprocessedApps: List<AppEntry> = emptyList()
    @Volatile private var allInstalledApps: List<AppEntry> = emptyList()
    @Volatile private var currentVisibleApps: List<AppEntry> = emptyList()

    private lateinit var wallpaper: ImageView
    private lateinit var mainScrollView: NestedScrollView
    private lateinit var rowsContainer: RecyclerView
    private lateinit var toolsRow: RecyclerView

    private val sharedViewPool = RecyclerView.RecycledViewPool().apply {
        setMaxRecycledViews(0, 20)
    }

    private lateinit var searchPillBtn: TextView
    private lateinit var searchPanel: FrameLayout
    private lateinit var searchInput: EditText
    private lateinit var searchAppResults: LinearLayout

    private lateinit var homeQrCard: LinearLayout
    private lateinit var homeQrImageView: ImageView
    private lateinit var homeQrCaption: TextView
    private lateinit var homeQrOfflineText: TextView
    private var homeQrBitmap: Bitmap? = null

    private val qrHandler = Handler(Looper.getMainLooper())
    private val qrRefreshRunnable = object : Runnable {
        override fun run() {
            refreshHomeQrCode()
            qrHandler.postDelayed(this, 90_000L)
        }
    }

    private val prefs by lazy { getSharedPreferences("launcher", MODE_PRIVATE) }
    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = loadApps()
    }

    private val prefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "user_name" || key == "clock_is_12h" || key == "clock_show_seconds" || key == "clock_show_date" || key == "favorite_pkgs_json" || key == "hidden_pkgs_json" || key == "app_rename_map" || key == "app_category_map" || key == "group_by_category" || key == "app_launch_counts" || key == "app_sort_mode") {
            runOnUiThread { applySettings(); reloadAppsAndRefresh() }
        } else if (key == "preset" || key == "custom" || key == "wallpaper_version") {
            runOnUiThread { applyWallpaper() }
        } else if (key == "home_qr_mode" || key == "paired_devices_json") {
            runOnUiThread { updateHomeQrVisibility(); refreshToolsRow() }
        }
    }

    @Volatile private var activeToken: String? = null
    @Volatile private var activeTokenExpiry: Long = 0L
    private var currentQrBitmap: Bitmap? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { updateNetworkStatus(); refreshHomeQrCode() }
        override fun onLost(network: Network) { updateNetworkStatus(); refreshHomeQrCode() }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { updateNetworkStatus() }
    }

    private val presets = listOf(
        "Dark gradient" to intArrayOf(0xFF0B0F1A.toInt(), 0xFF1B2440.toInt()),
        "Charcoal" to intArrayOf(0xFF101010.toInt(), 0xFF2A2A2A.toInt()),
        "Deep teal" to intArrayOf(0xFF041A1F.toInt(), 0xFF0E4B52.toInt())
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs.registerOnSharedPreferenceChangeListener(prefChangeListener)

        wallpaper = findViewById(R.id.wallpaper)
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

        searchPillBtn = findViewById(R.id.searchPillBtn)
        searchPanel = findViewById(R.id.searchPanel)
        searchInput = findViewById(R.id.searchInput)
        searchAppResults = findViewById(R.id.searchAppResults)

        searchPillBtn.setOnClickListener { openSearchPanel() }
        searchPillBtn.setOnFocusChangeListener { v, focused ->
            v.animate().scaleX(if (focused) 1.08f else 1.0f).scaleY(if (focused) 1.08f else 1.0f).setDuration(160).start()
        }

        findViewById<View>(R.id.closeSearchPanelBtn).setOnClickListener { closeSearchPanel() }
        findViewById<View>(R.id.searchWebBtn).setOnClickListener { executeWebSearch(searchInput.text.toString()) }
        findViewById<View>(R.id.searchYoutubeBtn).setOnClickListener { executeYoutubeSearch(searchInput.text.toString()) }

        homeQrCard = findViewById(R.id.homeQrCard)
        homeQrImageView = findViewById(R.id.homeQrImageView)
        homeQrCaption = findViewById(R.id.homeQrCaption)
        homeQrOfflineText = findViewById(R.id.homeQrOfflineText)

        homeQrCard.setOnClickListener { openPairingPanel() }
        homeQrCard.setOnFocusChangeListener { v, focused ->
            v.animate().scaleX(if (focused) 1.08f else 1.0f).scaleY(if (focused) 1.08f else 1.0f).setDuration(160).start()
        }

        // Setup Tools Row
        toolsRow.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        refreshToolsRow()

        val wallpaperBtn = findViewById<View>(R.id.wallpaperBtn)
        wallpaperBtn.setOnClickListener { chooseWallpaper() }
        wallpaperBtn.setOnFocusChangeListener { v, focused ->
            v.animate().scaleX(if (focused) 1.08f else 1.0f).scaleY(if (focused) 1.08f else 1.0f).setDuration(160).start()
        }

        findViewById<View>(R.id.closePanelBtn).apply {
            setOnClickListener { closePairingPanel() }
            setOnFocusChangeListener { v, focused ->
                v.animate().scaleX(if (focused) 1.08f else 1.0f).scaleY(if (focused) 1.08f else 1.0f).setDuration(160).start()
            }
        }

        findViewById<View>(R.id.removeAllBtn).apply {
            setOnClickListener { removeAllPairedDevices() }
            setOnFocusChangeListener { v, focused ->
                v.animate().scaleX(if (focused) 1.08f else 1.0f).scaleY(if (focused) 1.08f else 1.0f).setDuration(160).start()
            }
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

        applySettings()
        applyWallpaper()
        loadApps()
        startRemoteService()
        requestNotificationPermissionIfNeeded()
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
        prefs.unregisterOnSharedPreferenceChangeListener(prefChangeListener)
        qrHandler.removeCallbacks(qrRefreshRunnable)
        homeQrBitmap?.recycle()
        homeQrBitmap = null
        if (activeInstance?.get() == this) {
            activeInstance = null
        }
        super.onDestroy()
    }

    // ---- Search Panel Methods ----
    fun isSearchPanelOpen(): Boolean = searchPanel.visibility == View.VISIBLE

    private fun openSearchPanel() {
        searchPanel.visibility = View.VISIBLE
        searchInput.requestFocus()
        filterSearchApps(searchInput.text.toString())

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterSearchApps(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun closeSearchPanel() {
        searchPanel.visibility = View.GONE
        searchInput.setText("")
        searchPillBtn.requestFocus()
    }

    fun handleLiveTypeFromRemote(text: String, action: String) {
        runOnUiThread {
            if (action == "clear") {
                searchInput.setText("")
            } else {
                searchInput.setText(text)
                if (text.isNotEmpty()) searchInput.setSelection(text.length)
            }

            if (action == "enter") {
                val query = searchInput.text.toString().trim()
                val matches = allInstalledApps.filter { it.label.contains(query, ignoreCase = true) }
                if (matches.isNotEmpty()) {
                    launch(matches[0])
                    closeSearchPanel()
                } else {
                    executeWebSearch(query)
                }
            }
        }
    }

    private fun filterSearchApps(query: String) {
        searchAppResults.removeAllViews()
        val q = query.trim().lowercase(Locale.US)
        val hiddenPkgs = getHiddenPkgs()
        val matches = if (q.isEmpty()) emptyList() else allInstalledApps.filter {
            !hiddenPkgs.contains(it.pkg) && it.label.lowercase(Locale.US).contains(q)
        }.take(5)

        val inflater = LayoutInflater.from(this)
        for (app in matches) {
            val item = inflater.inflate(R.layout.item_hidden_app, searchAppResults, false)
            item.findViewById<TextView>(R.id.appName).text = app.label
            val btn = item.findViewById<TextView>(R.id.unhideBtn)
            btn.text = "Open"
            btn.setOnClickListener {
                launch(app)
                closeSearchPanel()
            }
            btn.setOnFocusChangeListener { v, focused ->
                v.animate().scaleX(if (focused) 1.08f else 1.0f).scaleY(if (focused) 1.08f else 1.0f).setDuration(160).start()
            }
            searchAppResults.addView(item)
        }
    }

    private fun executeWebSearch(query: String) {
        val q = query.trim()
        if (q.isEmpty()) {
            Toast.makeText(this, "Type search query first", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(SearchManager.QUERY, q)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(this, "Cannot open web browser", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun executeYoutubeSearch(query: String) {
        val q = query.trim()
        if (q.isEmpty()) {
            Toast.makeText(this, "Type search query first", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = Intent(Intent.ACTION_SEARCH).apply {
                setPackage("com.google.android.youtube.tv")
                putExtra(SearchManager.QUERY, q)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(this, "Cannot search YouTube", Toast.LENGTH_SHORT).show()
            }
        }
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
        toolsRow.adapter = ToolsAdapter(toolTiles) { tile ->
            when (tile.id) {
                "hidden_apps" -> openHiddenAppsPanel()
                "power" -> handlePowerClick()
                "pair_phone" -> openPairingPanel()
                else -> {
                    try {
                        tile.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(tile.intent)
                    } catch (_: Exception) {
                        Toast.makeText(this, "Cannot open ${tile.label}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun refreshHomeQrCode() {
        val token = generatePairingToken()
        val ip = localIp()
        val port = RemoteService.serverPort.takeIf { it != 0 } ?: 8080

        updateHomeQrVisibility()

        if (ip == null) {
            homeQrImageView.visibility = View.GONE
            homeQrCaption.visibility = View.GONE
            homeQrOfflineText.visibility = View.VISIBLE
        } else {
            homeQrImageView.visibility = View.VISIBLE
            homeQrCaption.visibility = View.VISIBLE
            homeQrOfflineText.visibility = View.GONE

            val url = "http://$ip:$port/?t=$token"
            val newBmp = generateQrBitmap(url, 240)
            if (newBmp != null) {
                val oldBmp = homeQrBitmap
                homeQrBitmap = newBmp
                homeQrImageView.setImageBitmap(newBmp)
                oldBmp?.recycle()
            }
        }
    }

    private fun updateHomeQrVisibility() {
        val mode = prefs.getString("home_qr_mode", "always") ?: "always"
        val hasPaired = getPairedDevicesList().isNotEmpty()

        val isVisible = when (mode) {
            "off" -> false
            "until_paired" -> !hasPaired
            else -> true
        }

        homeQrCard.visibility = if (isVisible) View.VISIBLE else View.GONE
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

    // ---- Favorites & Hidden Management ----
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

    private fun getHiddenPkgs(): Set<String> {
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
        val hiddenPkgs = getHiddenPkgs()
        val visibleApps = allInstalledApps.filter { !hiddenPkgs.contains(it.pkg) }
        currentVisibleApps = visibleApps

        val favPkgs = getFavoritePkgs()
        val favApps = favPkgs.mapNotNull { pkg -> visibleApps.firstOrNull { it.pkg == pkg } }

        val groupByCategory = prefs.getBoolean("group_by_category", true)
        val rowItems = ArrayList<CategoryRowItem>()

        if (groupByCategory) {
            // Categorized Mode: Favorites -> Entertainment -> Games -> Productivity & Tools -> Others
            if (favApps.isNotEmpty()) {
                rowItems.add(CategoryRowItem(AppUtils.CAT_FAVORITES, favApps))
            }

            val nonFavVisible = visibleApps.filter { !favPkgs.contains(it.pkg) }

            val entApps = nonFavVisible.filter { AppUtils.getAppCategory(it, prefs) == AppUtils.CAT_ENTERTAINMENT }
            if (entApps.isNotEmpty()) rowItems.add(CategoryRowItem(AppUtils.CAT_ENTERTAINMENT, entApps))

            val gameApps = nonFavVisible.filter { AppUtils.getAppCategory(it, prefs) == AppUtils.CAT_GAMES }
            if (gameApps.isNotEmpty()) rowItems.add(CategoryRowItem(AppUtils.CAT_GAMES, gameApps))

            val prodApps = nonFavVisible.filter { AppUtils.getAppCategory(it, prefs) == AppUtils.CAT_PRODUCTIVITY }
            if (prodApps.isNotEmpty()) rowItems.add(CategoryRowItem(AppUtils.CAT_PRODUCTIVITY, prodApps))

            val otherApps = nonFavVisible.filter { AppUtils.getAppCategory(it, prefs) == AppUtils.CAT_OTHERS }
            if (otherApps.isNotEmpty()) rowItems.add(CategoryRowItem(AppUtils.CAT_OTHERS, otherApps))
        } else {
            // Single A-Z / Most used row
            if (favApps.isNotEmpty()) {
                rowItems.add(CategoryRowItem(AppUtils.CAT_FAVORITES, favApps))
            }
            val remainingApps = visibleApps.filter { !favPkgs.contains(it.pkg) }
            if (remainingApps.isNotEmpty()) {
                rowItems.add(CategoryRowItem("Apps", remainingApps))
            }
        }

        rowsContainer.adapter = CategoryRowAdapter(rowItems, sharedViewPool, onAppClick = { launch(it) }, onAppLongClick = { entry, view -> showAppContextMenu(entry, view) })
        refreshHiddenAppsUi()
    }

    // ---- App Options Context Menu ----
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

    // ---- Hidden Apps Screen / Panel ----
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

        // 1. Power
        result.add(ToolTile("power", "Power", R.drawable.ic_tool_power, Intent()))

        // 2. Settings
        checkAndAdd("settings", "Settings", R.drawable.ic_tool_settings, Intent(Settings.ACTION_SETTINGS))

        // 3. Wi-Fi
        checkAndAdd("wifi", "Wi-Fi", R.drawable.ic_tool_wifi, Intent(Settings.ACTION_WIFI_SETTINGS))

        // 4. Bluetooth (Pair Speaker)
        checkAndAdd("bluetooth", "Pair Speaker", R.drawable.ic_tool_bluetooth, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))

        // 5. Display
        checkAndAdd("display", "Display", R.drawable.ic_tool_display, Intent(Settings.ACTION_DISPLAY_SETTINGS))

        // 6. Pair Phone (If Home QR tile is hidden)
        val mode = prefs.getString("home_qr_mode", "always") ?: "always"
        val hasPaired = getPairedDevicesList().isNotEmpty()
        if (mode == "off" || (mode == "until_paired" && hasPaired)) {
            result.add(ToolTile("pair_phone", "Pair Phone", R.drawable.ic_tool_wifi, Intent()))
        }

        // 7. Hidden Apps
        result.add(ToolTile("hidden_apps", "Hidden Apps", R.drawable.ic_tool_hidden, Intent()))

        // 8. Original Launcher (Fallback to Settings > Apps)
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

    private fun updateNetworkStatus() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val iconView = findViewById<ImageView>(R.id.netIcon) ?: return
        val activeNetwork = cm.activeNetwork
        val caps = if (activeNetwork != null) cm.getNetworkCapabilities(activeNetwork) else null

        val resId = when {
            caps == null -> R.drawable.ic_net_offline
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> R.drawable.ic_net_wifi
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> R.drawable.ic_net_ethernet
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> R.drawable.ic_net_wifi
            else -> R.drawable.ic_net_offline
        }

        runOnUiThread {
            iconView.setImageResource(resId)
        }
    }

    private fun applySettings() {
        val userName = prefs.getString("user_name", "")?.trim() ?: ""
        val is12h = prefs.getBoolean("clock_is_12h", true)
        val showSeconds = prefs.getBoolean("clock_show_seconds", false)
        val showDate = prefs.getBoolean("clock_show_date", true)

        updateGreeting(userName)

        val clock = findViewById<TextClock>(R.id.clock)
        val pattern = if (is12h) {
            if (showSeconds) "h:mm:ss a" else "h:mm a"
        } else {
            if (showSeconds) "HH:mm:ss" else "HH:mm"
        }
        clock.format12Hour = pattern
        clock.format24Hour = pattern

        val date = findViewById<TextClock>(R.id.date)
        date.visibility = if (showDate) View.VISIBLE else View.GONE
    }

    private fun updateGreeting(userName: String = prefs.getString("user_name", "")?.trim() ?: "") {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val timeGreeting = when (hour) {
            in 4..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..21 -> "Good evening"
            else -> "Good night"
        }
        val text = if (userName.isNotEmpty()) "$timeGreeting, $userName" else timeGreeting
        findViewById<TextView>(R.id.greeting).text = text
    }

    // ---- Pairing Panel & QR Code ----
    private fun openPairingPanel() {
        val token = generatePairingToken()
        val ip = localIp() ?: "127.0.0.1"
        val port = RemoteService.serverPort.takeIf { it != 0 } ?: 8080
        val url = "http://$ip:$port/?t=$token"

        findViewById<TextView>(R.id.pairingInfo).text = "Scan QR code or open in phone browser:\nhttp://$ip:$port   PIN: ${pin()}"

        currentQrBitmap?.recycle()
        currentQrBitmap = generateQrBitmap(url, 300)
        findViewById<ImageView>(R.id.qrImageView).setImageBitmap(currentQrBitmap)

        refreshPairedDevicesUi()
        val panel = findViewById<View>(R.id.pairingPanel)
        panel.visibility = View.VISIBLE
        findViewById<View>(R.id.closePanelBtn).requestFocus()
    }

    private fun closePairingPanel() {
        activeToken = null
        currentQrBitmap?.recycle()
        currentQrBitmap = null
        findViewById<ImageView>(R.id.qrImageView).setImageBitmap(null)
        findViewById<View>(R.id.pairingPanel).visibility = View.GONE
        refreshHomeQrCode()
    }

    private fun generatePairingToken(): String {
        val token = UUID.randomUUID().toString().replace("-", "")
        activeToken = token
        activeTokenExpiry = System.currentTimeMillis() + 120_000L // 2 minutes
        return token
    }

    fun exchangeToken(token: String): String? {
        val current = System.currentTimeMillis()
        val validToken = activeToken
        if (validToken != null && validToken == token && current <= activeTokenExpiry) {
            activeToken = null // Single-use: consume immediately
            val deviceKey = UUID.randomUUID().toString()
            val hash = hashKey(deviceKey)
            val name = "Phone (" + SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date()) + ")"
            addPairedDevice(hash, name)
            runOnUiThread { refreshHomeQrCode() }
            return deviceKey
        }
        return null
    }

    private fun hashKey(key: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun getPairedDevicesList(): List<JSONObject> {
        val jsonStr = prefs.getString("paired_devices_json", "[]") ?: "[]"
        return try {
            val arr = JSONArray(jsonStr)
            val list = ArrayList<JSONObject>()
            for (i in 0 until arr.length()) {
                list.add(arr.getJSONObject(i))
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun savePairedDevicesList(list: List<JSONObject>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs.edit().putString("paired_devices_json", arr.toString()).apply()
        runOnUiThread {
            refreshPairedDevicesUi()
            updateHomeQrVisibility()
            refreshToolsRow()
        }
    }

    private fun addPairedDevice(hash: String, name: String) {
        val list = getPairedDevicesList().toMutableList()
        val id = UUID.randomUUID().toString()
        val obj = JSONObject().apply {
            put("id", id)
            put("hash", hash)
            put("name", name)
            put("pairedAt", System.currentTimeMillis())
        }
        list.add(obj)
        savePairedDevicesList(list)
    }

    private fun removePairedDevice(id: String) {
        val list = getPairedDevicesList().filter { it.optString("id") != id }
        savePairedDevicesList(list)
    }

    private fun removeAllPairedDevices() {
        savePairedDevicesList(emptyList())
    }

    private fun refreshPairedDevicesUi() {
        val container = findViewById<LinearLayout>(R.id.pairedDevicesList)
        val noDevicesText = findViewById<TextView>(R.id.noDevicesText)
        container.removeAllViews()

        val devices = getPairedDevicesList()
        if (devices.isEmpty()) {
            noDevicesText.visibility = View.VISIBLE
        } else {
            noDevicesText.visibility = View.GONE
            val inflater = LayoutInflater.from(this)
            for (dev in devices) {
                val id = dev.optString("id")
                val name = dev.optString("name", "Paired Phone")
                val item = inflater.inflate(R.layout.item_paired_device, container, false)
                item.findViewById<TextView>(R.id.deviceName).text = name
                val removeBtn = item.findViewById<View>(R.id.removeBtn)
                removeBtn.setOnClickListener { removePairedDevice(id) }
                removeBtn.setOnFocusChangeListener { v, focused ->
                    v.animate().scaleX(if (focused) 1.08f else 1.0f).scaleY(if (focused) 1.08f else 1.0f).setDuration(160).start()
                }
                container.addView(item)
            }
        }
    }

    private fun generateQrBitmap(contents: String, size: Int = 300): Bitmap? {
        return try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(contents, BarcodeFormat.QR_CODE, size, size)
            val w = bitMatrix.width
            val h = bitMatrix.height
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            for (x in 0 until w) {
                for (y in 0 until h) {
                    bmp.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bmp
        } catch (_: Exception) {
            null
        }
    }

    private fun pin(): String {
        var p = prefs.getString("pin", null)
        if (p == null) { p = (1000 + java.util.Random().nextInt(9000)).toString(); prefs.edit().putString("pin", p).apply() }
        return p
    }

    private fun startRemoteService() {
        RemoteService.startService(this)
    }

    private fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }?.hostAddress
    } catch (_: Exception) { null }

    override fun onStart() {
        super.onStart()
        activeInstance = WeakReference(this)
        applySettings()

        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        cm?.let {
            try {
                val request = NetworkRequest.Builder().build()
                it.registerNetworkCallback(request, networkCallback)
            } catch (_: Exception) {}
        }
        updateNetworkStatus()

        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED); addDataScheme("package")
        }
        registerReceiver(pkgReceiver, f)

        qrHandler.removeCallbacks(qrRefreshRunnable)
        qrHandler.post(qrRefreshRunnable)
    }

    override fun onStop() {
        qrHandler.removeCallbacks(qrRefreshRunnable)
        if (activeInstance?.get() == this) {
            activeInstance = null
        }
        super.onStop()
        unregisterReceiver(pkgReceiver)
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        cm?.let {
            try {
                it.unregisterNetworkCallback(networkCallback)
            } catch (_: Exception) {}
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (searchPanel.visibility == View.VISIBLE) {
            closeSearchPanel()
            return
        }
        val hiddenPanel = findViewById<View>(R.id.hiddenAppsPanel)
        if (hiddenPanel.visibility == View.VISIBLE) {
            closeHiddenAppsPanel()
            return
        }
        val pairingPanel = findViewById<View>(R.id.pairingPanel)
        if (pairingPanel.visibility == View.VISIBLE) {
            closePairingPanel()
            return
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        mainScrollView.smoothScrollTo(0, 0)
    }

    private fun launch(e: AppEntry) {
        AppUtils.incrementLaunchCount(prefs, e.pkg)
        reloadAppsAndRefresh()
        try { startActivity(e.intent) } catch (_: Exception) {
            Toast.makeText(this, "Can't open ${e.label}", Toast.LENGTH_SHORT).show()
        }
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

    private fun loadApps() {
        thread {
            val pm = packageManager
            val seen = HashSet<String>()
            val list = ArrayList<AppEntry>()

            // 1. Resolve Projector Shortcut Candidates
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

            // 2. Resolve Installed TV & Mobile Launcher Apps
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

    // ---- Wallpaper ----
    private fun applyWallpaper() {
        val f = File(filesDir, "wallpaper.jpg")
        val idx = prefs.getInt("preset", 0)
        if (prefs.getBoolean("custom", false) && f.exists()) {
            wallpaper.setImageBitmap(BitmapFactory.decodeFile(f.path))
        } else {
            val c = presets[idx.coerceIn(0, presets.size - 1)].second
            wallpaper.setImageDrawable(GradientDrawable(GradientDrawable.Orientation.TL_BR, c))
        }
    }

    private fun chooseWallpaper() {
        val names = presets.map { it.first } + "Pick image from storage"
        AlertDialog.Builder(this).setTitle("Wallpaper")
            .setItems(names.toTypedArray()) { _, which ->
                if (which < presets.size) {
                    prefs.edit().putInt("preset", which).putBoolean("custom", false).putLong("wallpaper_version", System.currentTimeMillis()).apply()
                    applyWallpaper()
                } else pickImage()
            }.show()
    }

    private fun pickImage() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"
            }, 1)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No file picker on this device", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveCustomWallpaper(bytes: ByteArray): Boolean {
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
            runOnUiThread { applyWallpaper() }
            true
        } catch (_: Exception) { false }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri: Uri = data?.data ?: return
        if (req != 1 || res != RESULT_OK) return
        thread {
            val ok = try {
                contentResolver.openInputStream(uri)?.use { saveCustomWallpaper(it.readBytes()) } ?: false
            } catch (_: Exception) { false }
            if (!ok) runOnUiThread { Toast.makeText(this, "Couldn't load image", Toast.LENGTH_SHORT).show() }
        }
    }
}

class CategoryRowAdapter(
    private val rows: List<CategoryRowItem>,
    private val sharedViewPool: RecyclerView.RecycledViewPool,
    private val onAppClick: (AppEntry) -> Unit,
    private val onAppLongClick: (AppEntry, View) -> Unit
) : RecyclerView.Adapter<CategoryRowAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val rowTitle: TextView = v.findViewById(R.id.rowTitle)
        val rowRecyclerView: RecyclerView = v.findViewById(R.id.rowRecyclerView)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_category_row, parent, false)
        val h = VH(v)
        h.rowRecyclerView.layoutManager = LinearLayoutManager(parent.context, LinearLayoutManager.HORIZONTAL, false).apply {
            initialPrefetchItemCount = 2
        }
        h.rowRecyclerView.setRecycledViewPool(sharedViewPool)
        h.rowRecyclerView.setHasFixedSize(true)
        h.rowRecyclerView.isNestedScrollingEnabled = false
        return h
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = rows[position]
        holder.rowTitle.text = row.title.uppercase(Locale.US)
        val adapter = AppAdapter(holder.rowRecyclerView, onAppClick, onAppLongClick)
        holder.rowRecyclerView.adapter = adapter
        adapter.submit(row.apps)
    }

    override fun getItemCount() = rows.size
}

class ToolsAdapter(
    private val tiles: List<ToolTile>,
    private val onClick: (ToolTile) -> Unit
) : RecyclerView.Adapter<ToolsAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val cardContainer: FrameLayout = v.findViewById(R.id.cardContainer)
        val toolIcon: ImageView = v.findViewById(R.id.toolIcon)
        val toolLabel: TextView = v.findViewById(R.id.toolLabel)
        val cardBorder: View = v.findViewById(R.id.cardBorder)

        init {
            cardContainer.outlineProvider = ViewOutlineProvider.BACKGROUND
            cardContainer.clipToOutline = true
        }
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
        val v = LayoutInflater.from(p.context).inflate(R.layout.item_tool_tile, p, false)
        val h = VH(v)

        v.setOnFocusChangeListener { _, focused ->
            val duration = 160L
            val density = v.resources.displayMetrics.density
            val scale = if (focused) 1.1f else 1.0f
            val alpha = if (focused) 1f else 0f

            h.cardContainer.animate()
                .scaleX(scale)
                .scaleY(scale)
                .translationZ(if (focused) 6f * density else 0f)
                .setDuration(duration)
                .start()

            h.cardBorder.animate()
                .alpha(alpha)
                .setDuration(duration)
                .start()
        }

        return h
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = tiles[pos]
        h.toolLabel.text = item.label
        h.toolIcon.setImageResource(item.iconRes)
        h.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount() = tiles.size
}

class AppAdapter(
    private val recyclerView: RecyclerView,
    private val onAppClick: (AppEntry) -> Unit,
    private val onAppLongClick: (AppEntry, View) -> Unit
) : RecyclerView.Adapter<AppAdapter.VH>() {

    private var items: List<AppEntry> = emptyList()
    private var isFirstLoad = true

    fun submit(l: List<AppEntry>) {
        items = l
        notifyDataSetChanged()
        if (isFirstLoad && l.isNotEmpty()) {
            isFirstLoad = false
            recyclerView.alpha = 0f
            recyclerView.translationY = 60f * recyclerView.resources.displayMetrics.density
            recyclerView.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(350)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    recyclerView.post {
                        val vh = recyclerView.findViewHolderForAdapterPosition(0)
                        vh?.itemView?.requestFocus()
                    }
                }
                .start()
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val cardContainer: FrameLayout = v.findViewById(R.id.cardContainer)
        val cardImage: ImageView = v.findViewById(R.id.cardImage)
        val iconLayout: FrameLayout = v.findViewById(R.id.iconLayout)
        val cardIcon: ImageView = v.findViewById(R.id.cardIcon)
        val cardBorder: View = v.findViewById(R.id.cardBorder)
        val label: TextView = v.findViewById(R.id.label)

        init {
            cardContainer.outlineProvider = ViewOutlineProvider.BACKGROUND
            cardContainer.clipToOutline = true
        }
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
        val v = LayoutInflater.from(p.context).inflate(R.layout.item_app, p, false)
        val h = VH(v)

        v.setOnFocusChangeListener { _, focused ->
            val duration = 160L
            val density = v.resources.displayMetrics.density
            val scale = if (focused) 1.1f else 1.0f
            val alpha = if (focused) 1f else 0f

            h.cardContainer.animate()
                .scaleX(scale)
                .scaleY(scale)
                .translationZ(if (focused) 8f * density else 0f)
                .setDuration(duration)
                .start()

            h.cardBorder.animate()
                .alpha(alpha)
                .setDuration(duration)
                .start()

            h.label.animate()
                .alpha(alpha)
                .setDuration(duration)
                .start()
        }

        v.setOnLongClickListener {
            val pos = h.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION && pos < items.size) {
                onAppLongClick(items[pos], v)
            }
            true
        }

        return h
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val e = items[pos]
        h.label.text = e.label

        if (e.banner != null) {
            h.cardImage.setImageDrawable(e.banner)
            h.cardImage.visibility = View.VISIBLE
            h.iconLayout.visibility = View.GONE
            h.cardContainer.setBackgroundResource(R.drawable.card_bg)
        } else {
            h.cardImage.visibility = View.GONE
            h.iconLayout.visibility = View.VISIBLE
            h.cardIcon.setImageDrawable(e.icon)
            val gradient = createIconGradient(e.averageColor, h.itemView.resources.displayMetrics.density)
            h.cardContainer.background = gradient
        }

        h.itemView.setOnClickListener { onAppClick(e) }
    }

    private fun createIconGradient(avgColor: Int, density: Float): GradientDrawable {
        val hsv = FloatArray(3)
        Color.colorToHSV(avgColor, hsv)
        val topColor = Color.HSVToColor(floatArrayOf(hsv[0], (hsv[1] * 0.75f).coerceIn(0f, 1f), (hsv[2] * 0.65f).coerceIn(0.2f, 0.85f)))
        val bottomColor = Color.HSVToColor(floatArrayOf(hsv[0], (hsv[1] * 0.85f).coerceIn(0f, 1f), (hsv[2] * 0.25f).coerceIn(0.1f, 0.45f)))
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(topColor, bottomColor)).apply {
            cornerRadius = 18f * density
        }
    }

    override fun getItemCount() = items.size
}
