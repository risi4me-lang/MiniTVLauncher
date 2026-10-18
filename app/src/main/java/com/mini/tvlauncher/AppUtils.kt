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

    fun incrementLaunchCount(prefs: SharedPreferences, pkg: String) {
        val map = getLaunchCounts(prefs).toMutableMap()
        map[pkg] = (map[pkg] ?: 0) + 1
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit().putString("app_launch_counts", obj.toString()).apply()
    }

    fun resetLaunchCounts(prefs: SharedPreferences) {
        prefs.edit().remove("app_launch_counts").apply()
    }

    fun processAppList(
        rawApps: List<AppEntry>,
        prefs: SharedPreferences,
        filesDir: File
    ): List<AppEntry> {
        val renameMap = getRenameMap(prefs)
        val launchCounts = getLaunchCounts(prefs)
        val sortMode = prefs.getString("app_sort_mode", "alphabetical") ?: "alphabetical"
        val cardsDir = File(filesDir, "cards")

        val processed = rawApps.map { app ->
            val customName = renameMap[app.pkg]
            val displayName = if (!customName.isNullOrBlank()) customName else app.defaultLabel
            val count = launchCounts[app.pkg] ?: 0
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
            targetFile.outputStream().use { out ->
                scaledBmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
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
}
