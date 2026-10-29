package com.mini.tvlauncher

import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.util.*

object AppUtils {

    const val CAT_FAVORITES = "Favorites"
    const val CAT_ENTERTAINMENT = "Entertainment"
    const val CAT_GAMES = "Games"
    const val CAT_PRODUCTIVITY = "Productivity & Tools"
    const val CAT_OTHERS = "Others"

    fun getRenameMap(prefs: SharedPreferences): Map<String, String> {
        val jsonStr = prefs.getString("app_rename_map", "{}") ?: "{}"
        return try {
            val obj = JSONObject(jsonStr)
            val map = HashMap<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = obj.getString(k)
            }
            map
        } catch (_: Exception) { emptyMap() }
    }

    fun renameApp(prefs: SharedPreferences, pkg: String, newName: String) {
        val map = getRenameMap(prefs).toMutableMap()
        if (newName.isBlank()) {
            map.remove(pkg)
        } else {
            map[pkg] = newName.trim()
        }
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit().putString("app_rename_map", obj.toString()).apply()
    }

    fun getCategoryMap(prefs: SharedPreferences): Map<String, String> {
        val jsonStr = prefs.getString("app_category_map", "{}") ?: "{}"
        return try {
            val obj = JSONObject(jsonStr)
            val map = HashMap<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = obj.getString(k)
            }
            map
        } catch (_: Exception) { emptyMap() }
    }

    fun setUserCategory(prefs: SharedPreferences, pkg: String, category: String) {
        val map = getCategoryMap(prefs).toMutableMap()
        if (category.isBlank() || category.lowercase(Locale.US) == "automatic") {
            map.remove(pkg)
        } else {
            map[pkg] = category.trim()
        }
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit().putString("app_category_map", obj.toString()).apply()
    }

    fun getAppCategory(app: AppEntry, prefs: SharedPreferences): String {
        val categoryMap = getCategoryMap(prefs)
        val customCat = categoryMap[app.pkg]
        if (!customCat.isNullOrBlank()) return customCat

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val appInfo = app.appInfo
            if (appInfo != null) {
                when (appInfo.category) {
                    ApplicationInfo.CATEGORY_VIDEO, ApplicationInfo.CATEGORY_AUDIO -> return CAT_ENTERTAINMENT
                    ApplicationInfo.CATEGORY_GAME -> return CAT_GAMES
                    ApplicationInfo.CATEGORY_PRODUCTIVITY, ApplicationInfo.CATEGORY_NEWS,
                    ApplicationInfo.CATEGORY_SOCIAL, ApplicationInfo.CATEGORY_MAPS,
                    ApplicationInfo.CATEGORY_IMAGE -> return CAT_PRODUCTIVITY
                }
            }
        }

        val nameLower = (app.defaultLabel + " " + app.pkg).lowercase(Locale.US)

        if (nameLower.contains("netflix") || nameLower.contains("hotstar") || nameLower.contains("prime") ||
            nameLower.contains("youtube") || nameLower.contains("spotify") || nameLower.contains("sonyliv") ||
            nameLower.contains("zee5") || nameLower.contains("jio") || nameLower.contains("plex") ||
            nameLower.contains("kodi") || nameLower.contains("tv") || nameLower.contains("media") ||
            nameLower.contains("player") || nameLower.contains("video") || nameLower.contains("music") ||
            nameLower.contains("audio") || nameLower.contains("stream") || nameLower.contains("disney") ||
            nameLower.contains("hulu") || nameLower.contains("vlc") || nameLower.contains("mxplayer") ||
            nameLower.contains("vboot")) {
            return CAT_ENTERTAINMENT
        }

        if (nameLower.contains("game") || nameLower.contains("arcade") || nameLower.contains("puzzle") ||
            nameLower.contains("racing") || nameLower.contains("chess") || nameLower.contains("asphalt") ||
            nameLower.contains("retro")) {
            return CAT_GAMES
        }

        if (nameLower.contains("browser") || nameLower.contains("file") || nameLower.contains("manager") ||
            nameLower.contains("docs") || nameLower.contains("office") || nameLower.contains("installer") ||
            nameLower.contains("chrome") || nameLower.contains("firefox") || nameLower.contains("calc") ||
            nameLower.contains("tool") || nameLower.contains("cleaner") || nameLower.contains("drive") ||
            nameLower.contains("pdf") || nameLower.contains("terminal")) {
            return CAT_PRODUCTIVITY
        }

        return CAT_OTHERS
    }

    fun getLaunchCounts(prefs: SharedPreferences): Map<String, Int> {
        val jsonStr = prefs.getString("app_launch_counts", "{}") ?: "{}"
        return try {
            val obj = JSONObject(jsonStr)
            val map = HashMap<String, Int>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = obj.getInt(k)
            }
            map
        } catch (_: Exception) { emptyMap() }
    }

    fun getLastLaunchedMap(prefs: SharedPreferences): Map<String, Long> {
        val jsonStr = prefs.getString("app_last_launched_map", "{}") ?: "{}"
        return try {
            val obj = JSONObject(jsonStr)
            val map = HashMap<String, Long>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = obj.getLong(k)
            }
            map
        } catch (_: Exception) { emptyMap() }
    }

    fun incrementLaunchCount(prefs: SharedPreferences, pkg: String) {
        val map = getLaunchCounts(prefs).toMutableMap()
        map[pkg] = (map[pkg] ?: 0) + 1
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }

        val lastMap = getLastLaunchedMap(prefs).toMutableMap()
        lastMap[pkg] = System.currentTimeMillis()
        val lastObj = JSONObject()
        lastMap.forEach { (k, v) -> lastObj.put(k, v) }

        prefs.edit()
            .putString("app_launch_counts", obj.toString())
            .putString("app_last_launched_map", lastObj.toString())
            .apply()
    }

    fun resetLaunchCounts(prefs: SharedPreferences) {
        prefs.edit().remove("app_launch_counts").remove("app_last_launched_map").apply()
    }

    fun formatLastLaunchedSubtitle(app: AppEntry, prefs: SharedPreferences): String {
        val category = getAppCategory(app, prefs)
        val count = app.launchCount
        val timesStr = if (count == 1) "opened 1 time" else "opened $count times"

        val lastMs = app.lastLaunchedMs
        val lastStr = if (lastMs <= 0L) {
            "never opened"
        } else {
            val diffMs = System.currentTimeMillis() - lastMs
            val diffMinutes = diffMs / (60 * 1000L)
            val diffHours = diffMs / (60 * 60 * 1000L)
            val diffDays = diffMs / (24 * 60 * 60 * 1000L)

            when {
                diffMinutes < 1 -> "opened just now"
                diffMinutes < 60 -> "opened ${diffMinutes}m ago"
                diffHours < 24 -> "opened ${diffHours}h ago"
                diffDays == 1L -> "last used yesterday"
                else -> "last used $diffDays days ago"
            }
        }

        return "$category · $timesStr · $lastStr"
    }

    fun processAppList(
        rawApps: List<AppEntry>,
        prefs: SharedPreferences,
        filesDir: File
    ): List<AppEntry> {
        val renameMap = getRenameMap(prefs)
        val launchCounts = getLaunchCounts(prefs)
        val lastLaunchedMap = getLastLaunchedMap(prefs)
        val sortMode = prefs.getString("app_sort_mode", "alphabetical") ?: "alphabetical"
        val cardsDir = File(filesDir, "cards")

        val processed = rawApps.map { app ->
            val customName = renameMap[app.pkg]
            val displayName = if (!customName.isNullOrBlank()) customName else app.defaultLabel
            val count = launchCounts[app.pkg] ?: 0
            val lastLaunched = lastLaunchedMap[app.pkg] ?: 0L
            val customCardFile = File(cardsDir, "${app.pkg}.jpg")
            val hasCustomImage = customCardFile.exists()

            val customBannerDrawable = if (hasCustomImage) {
                try {
                    val bmp = BitmapFactory.decodeFile(customCardFile.path)
                    if (bmp != null) BitmapDrawable(null, bmp) else null
                } catch (_: Exception) { null }
            } else null

            val finalBanner = customBannerDrawable ?: app.banner

            app.copy(
                label = displayName,
                banner = finalBanner,
                launchCount = count,
                lastLaunchedMs = lastLaunched,
                hasCustomImage = hasCustomImage
            )
        }

        return if (sortMode == "most_used") {
            processed.sortedWith(
                compareByDescending<AppEntry> { it.launchCount }
                    .thenBy { it.label.lowercase(Locale.US) }
            )
        } else {
            processed.sortedBy { it.label.lowercase(Locale.US) }
        }
    }

    fun downscaleAndSaveCardImage(filesDir: File, pkg: String, bytes: ByteArray): Boolean {
        return try {
            val cardsDir = File(filesDir, "cards")
            if (!cardsDir.exists()) cardsDir.mkdirs()

            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) return false

            var sampleSize = 1
            while (options.outWidth / (sampleSize * 2) >= 480 && options.outHeight / (sampleSize * 2) >= 270) {
                sampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val decodedBmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts) ?: return false

            val scaledBmp = Bitmap.createScaledBitmap(decodedBmp, 480, 270, true)
            if (scaledBmp != decodedBmp) decodedBmp.recycle()

            val targetFile = File(cardsDir, "$pkg.jpg")
            var quality = 85
            do {
                targetFile.outputStream().use { out ->
                    scaledBmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
                }
                quality -= 10
            } while (targetFile.length() > 400 * 1024 && quality >= 20)

            scaledBmp.recycle()
            true
        } catch (_: Exception) { false }
    }

    fun resetCardImage(filesDir: File, pkg: String): Boolean {
        return try {
            val file = File(filesDir, "cards/$pkg.jpg")
            if (file.exists()) file.delete() else true
        } catch (_: Exception) { false }
    }

    // ---- Multi-Wallpaper Helpers ----
    fun getWallpaperDir(filesDir: File): File {
        val dir = File(filesDir, "wallpapers")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getWallpaperFiles(filesDir: File): List<File> {
        val dir = getWallpaperDir(filesDir)
        val files = dir.listFiles { _, name -> name.startsWith("wp_") && name.endsWith(".jpg") } ?: emptyArray()
        return files.sortedBy { it.name }
    }

    fun saveWallpaperPhoto(filesDir: File, targetId: String?, bytes: ByteArray): String? {
        val existingFiles = getWallpaperFiles(filesDir)
        val idToUse = if (!targetId.isNullOrBlank()) {
            targetId
        } else {
            var foundId: String? = null
            for (i in 1..8) {
                val candidate = "wp_$i"
                if (existingFiles.none { it.name == "$candidate.jpg" }) {
                    foundId = candidate
                    break
                }
            }
            foundId ?: return null
        }

        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) return null

            var sampleSize = 1
            while (options.outWidth / (sampleSize * 2) >= 1280) sampleSize *= 2

            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val decodedBmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts) ?: return null

            val dir = getWallpaperDir(filesDir)
            val file = File(dir, "$idToUse.jpg")
            var quality = 85
            do {
                file.outputStream().use { out ->
                    decodedBmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
                }
                quality -= 10
            } while (file.length() > 400 * 1024 && quality >= 20)

            decodedBmp.recycle()
            idToUse
        } catch (_: Exception) {
            null
        }
    }

    fun deleteWallpaperPhoto(filesDir: File, id: String): Boolean {
        return try {
            val dir = getWallpaperDir(filesDir)
            val file = File(dir, "$id.jpg")
            if (file.exists()) file.delete() else true
        } catch (_: Exception) { false }
    }
}
