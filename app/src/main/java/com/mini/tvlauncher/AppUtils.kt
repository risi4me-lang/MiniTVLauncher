package com.mini.tvlauncher

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import org.json.JSONObject
import java.io.File
import java.util.*

object AppUtils {

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
