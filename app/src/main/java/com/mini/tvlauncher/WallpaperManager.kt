package com.mini.tvlauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import android.widget.Toast
import java.io.File
import kotlin.concurrent.thread

class WallpaperManager(
    private val activity: Activity,
    private val prefs: SharedPreferences,
    private val filesDir: File,
    private val wallpaper: ImageView,
    private val wallpaperNext: ImageView
) {
    val presets = listOf(
        "Dark gradient" to intArrayOf(0xFF0B0F1A.toInt(), 0xFF1B2440.toInt()),
        "Charcoal" to intArrayOf(0xFF101010.toInt(), 0xFF2A2A2A.toInt()),
        "Deep teal" to intArrayOf(0xFF041A1F.toInt(), 0xFF0E4B52.toInt()),
        "Midnight blue" to intArrayOf(0xFF030A16.toInt(), 0xFF122240.toInt()),
        "Dark violet" to intArrayOf(0xFF12031A.toInt(), 0xFF320E42.toInt()),
        "Obsidian emerald" to intArrayOf(0xFF03160C.toInt(), 0xFF0E3821.toInt()),
        "Ember dark" to intArrayOf(0xFF1A0A03.toInt(), 0xFF421D0E.toInt()),
        "Cosmic indigo" to intArrayOf(0xFF08031A.toInt(), 0xFF210E42.toInt()),
        "Slate graphite" to intArrayOf(0xFF13171F.toInt(), 0xFF2D3545.toInt()),
        "Deep wine" to intArrayOf(0xFF1A030A.toInt(), 0xFF420E1C.toInt())
    )

    private val slideshowHandler = Handler(Looper.getMainLooper())
    private var slideshowIndex = 0
    private var currentBmp: Bitmap? = null

    private val slideshowRunnable = object : Runnable {
        override fun run() {
            performSlideshowTransition()
            val minutes = prefs.getInt("wallpaper_slideshow_interval", 0)
            if (minutes > 0) {
                slideshowHandler.postDelayed(this, minutes * 60 * 1000L)
            }
        }
    }

    fun start() {
        slideshowHandler.removeCallbacks(slideshowRunnable)
        val intervalMinutes = prefs.getInt("wallpaper_slideshow_interval", 0)
        if (intervalMinutes > 0) {
            slideshowHandler.postDelayed(slideshowRunnable, intervalMinutes * 60 * 1000L)
        }
    }

    fun stop() {
        slideshowHandler.removeCallbacks(slideshowRunnable)
    }

    fun destroy() {
        stop()
        currentBmp?.recycle()
        currentBmp = null
    }

    fun applyWallpaper() {
        slideshowHandler.removeCallbacks(slideshowRunnable)
        val customFiles = AppUtils.getWallpaperFiles(filesDir)
        val isCustom = prefs.getBoolean("custom", false)

        val nextBmp: Bitmap?
        val drawable: Drawable?

        if (isCustom && customFiles.isNotEmpty()) {
            val file = customFiles[slideshowIndex.coerceIn(0, customFiles.size - 1)]
            nextBmp = BitmapFactory.decodeFile(file.path)
            drawable = if (nextBmp != null) BitmapDrawable(activity.resources, nextBmp) else null
        } else {
            nextBmp = null
            val idx = prefs.getInt("preset", 0).coerceIn(0, presets.size - 1)
            val c = presets[idx].second
            drawable = GradientDrawable(GradientDrawable.Orientation.TL_BR, c)
        }

        wallpaper.setImageDrawable(drawable)
        wallpaperNext.animate().cancel()
        wallpaperNext.alpha = 0f

        val oldBmp = currentBmp
        currentBmp = nextBmp
        if (oldBmp != null && oldBmp != nextBmp && !oldBmp.isRecycled) {
            oldBmp.recycle()
        }

        val intervalMinutes = prefs.getInt("wallpaper_slideshow_interval", 0)
        if (intervalMinutes > 0) {
            slideshowHandler.postDelayed(slideshowRunnable, intervalMinutes * 60 * 1000L)
        }
    }

    private fun performSlideshowTransition() {
        val customFiles = AppUtils.getWallpaperFiles(filesDir)
        val isCustom = prefs.getBoolean("custom", false)

        val nextBmp: Bitmap?
        val nextDrawable: Drawable?

        if (isCustom && customFiles.isNotEmpty()) {
            slideshowIndex = (slideshowIndex + 1) % customFiles.size
            val file = customFiles[slideshowIndex]
            nextBmp = BitmapFactory.decodeFile(file.path)
            nextDrawable = if (nextBmp != null) BitmapDrawable(activity.resources, nextBmp) else null
        } else {
            nextBmp = null
            slideshowIndex = (slideshowIndex + 1) % presets.size
            val c = presets[slideshowIndex].second
            nextDrawable = GradientDrawable(GradientDrawable.Orientation.TL_BR, c)
        }

        if (nextDrawable == null) return

        wallpaperNext.setImageDrawable(nextDrawable)
        wallpaperNext.alpha = 0f

        wallpaperNext.animate()
            .alpha(1f)
            .setDuration(1000)
            .withEndAction {
                wallpaper.setImageDrawable(nextDrawable)
                wallpaperNext.alpha = 0f

                val oldBmp = currentBmp
                currentBmp = nextBmp
                if (oldBmp != null && oldBmp != nextBmp && !oldBmp.isRecycled) {
                    oldBmp.recycle()
                }
            }
            .start()
    }

    fun chooseWallpaper() {
        val names = presets.map { it.first } + "Pick image from storage"
        AlertDialog.Builder(activity).setTitle("Wallpaper")
            .setItems(names.toTypedArray()) { _, which ->
                if (which < presets.size) {
                    prefs.edit().putInt("preset", which).putBoolean("custom", false).putLong("wallpaper_version", System.currentTimeMillis()).apply()
                    applyWallpaper()
                } else pickImage()
            }.show()
    }

    private fun pickImage() {
        try {
            activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"
            }, 1)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, "No file picker on this device", Toast.LENGTH_LONG).show()
        }
    }

    fun saveCustomWallpaper(bytes: ByteArray): Boolean {
        AppUtils.saveWallpaperPhoto(filesDir, null, bytes) ?: return false
        prefs.edit().putBoolean("custom", true).putLong("wallpaper_version", System.currentTimeMillis()).apply()
        activity.runOnUiThread { applyWallpaper() }
        return true
    }

    fun handleActivityResult(req: Int, res: Int, data: Intent?) {
        val uri: Uri = data?.data ?: return
        if (req != 1 || res != Activity.RESULT_OK) return
        thread {
            val ok = try {
                activity.contentResolver.openInputStream(uri)?.use { saveCustomWallpaper(it.readBytes()) } ?: false
            } catch (_: Exception) { false }
            if (!ok) activity.runOnUiThread { Toast.makeText(activity, "Couldn't load image", Toast.LENGTH_SHORT).show() }
        }
    }
}
