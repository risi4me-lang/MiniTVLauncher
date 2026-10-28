package com.mini.tvlauncher

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView

class TopShelfManager(
    private val activity: Activity,
    private val prefs: SharedPreferences,
    private val container: View,
    private val bannerView: ImageView,
    private val bannerNextView: ImageView,
    private val titleView: TextView,
    private val subtitleView: TextView
) {
    private val debounceHandler = Handler(Looper.getMainLooper())
    private var pendingRunnable: Runnable? = null
    private var currentFocusedPkg: String? = null

    fun updateFocusedApp(app: AppEntry?) {
        pendingRunnable?.let { debounceHandler.removeCallbacks(it) }

        val runnable = Runnable {
            if (app == null) {
                showNeutralState()
            } else {
                if (currentFocusedPkg != app.pkg) {
                    currentFocusedPkg = app.pkg
                    showAppHero(app)
                }
            }
        }
        pendingRunnable = runnable
        debounceHandler.postDelayed(runnable, 120L)
    }

    private fun showNeutralState() {
        currentFocusedPkg = null
        titleView.text = "Welcome to Mini Launcher"
        subtitleView.text = "Select an app or tool to get started"

        bannerView.setImageDrawable(null)
        bannerNextView.setImageDrawable(null)
        bannerNextView.alpha = 0f
    }

    private fun showAppHero(app: AppEntry) {
        titleView.text = app.label
        subtitleView.text = AppUtils.formatLastLaunchedSubtitle(app, prefs)

        val isLowRam = prefs.getBoolean("low_ram_mode", false)
        if (isLowRam) {
            bannerView.setImageDrawable(null)
            bannerNextView.setImageDrawable(null)
            bannerNextView.alpha = 0f
            return
        }

        val nextDrawable: Drawable = if (app.banner != null) {
            app.banner
        } else {
            createColorGradient(app.averageColor, activity.resources.displayMetrics.density)
        }

        bannerNextView.setImageDrawable(nextDrawable)
        bannerNextView.alpha = 0f

        bannerNextView.animate()
            .alpha(1f)
            .setDuration(150)
            .withEndAction {
                bannerView.setImageDrawable(nextDrawable)
                bannerNextView.alpha = 0f
            }
            .start()
    }

    private fun createColorGradient(avgColor: Int, density: Float): GradientDrawable {
        val hsv = FloatArray(3)
        Color.colorToHSV(avgColor, hsv)
        val topColor = Color.HSVToColor(floatArrayOf(hsv[0], (hsv[1] * 0.8f).coerceIn(0f, 1f), (hsv[2] * 0.5f).coerceIn(0.1f, 0.7f)))
        val bottomColor = Color.HSVToColor(floatArrayOf(hsv[0], (hsv[1] * 0.9f).coerceIn(0f, 1f), (hsv[2] * 0.2f).coerceIn(0.05f, 0.3f)))
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(topColor, bottomColor))
    }
}
