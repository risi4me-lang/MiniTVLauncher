package com.mini.tvlauncher

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Color
import android.view.View
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DisplayModeManager {

    fun getEffectiveMode(prefs: SharedPreferences): String {
        val mode = prefs.getString("display_mode", "standard") ?: "standard"
        if (mode == "bright") return "bright"
        if (mode == "auto") {
            val autoStart = prefs.getString("display_auto_start", "08:00") ?: "08:00"
            val autoEnd = prefs.getString("display_auto_end", "18:00") ?: "18:00"
            val nowStr = SimpleDateFormat("HH:mm", Locale.US).format(Date())
            val isBetween = if (autoStart <= autoEnd) {
                nowStr in autoStart..autoEnd
            } else {
                nowStr >= autoStart || nowStr <= autoEnd
            }
            if (isBetween) return "bright"
        }
        return "standard"
    }

    fun applyDisplayMode(activity: Activity, prefs: SharedPreferences) {
        val mode = getEffectiveMode(prefs)
        val isBright = mode == "bright"

        val mainScrim = activity.findViewById<View>(R.id.mainScrim)
        if (mainScrim != null) {
            mainScrim.setBackgroundColor(if (isBright) 0xB3000000.toInt() else 0x4D000000.toInt())
        }

        val greeting = activity.findViewById<TextView>(R.id.greeting)
        if (greeting != null) {
            if (isBright) {
                greeting.setTextColor(Color.WHITE)
                greeting.setShadowLayer(4f, 0f, 2f, 0x80000000.toInt())
            } else {
                greeting.setTextColor(0xFFB0C0E0.toInt())
                greeting.setShadowLayer(0f, 0f, 0f, 0)
            }
        }

        val topShelfTitle = activity.findViewById<TextView>(R.id.topShelfTitle)
        if (topShelfTitle != null) {
            if (isBright) {
                topShelfTitle.setShadowLayer(6f, 0f, 2f, 0x99000000.toInt())
            } else {
                topShelfTitle.setShadowLayer(0f, 0f, 0f, 0)
            }
        }
    }
}
