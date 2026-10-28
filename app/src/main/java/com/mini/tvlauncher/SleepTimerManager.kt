package com.mini.tvlauncher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import org.json.JSONObject

class SleepTimerManager(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val nowPlayingManager: NowPlayingManager
) {
    private val handler = Handler(Looper.getMainLooper())
    private var originalVolume = -1
    private var isVideoEndMode = false
    private var noMediaCounterMs = 0L

    private val timerRunnable = object : Runnable {
        override fun run() {
            tick()
        }
    }

    fun startTimerMinutes(minutes: Int) {
        cancelTimer()
        isVideoEndMode = false
        noMediaCounterMs = 0L
        saveOriginalVolume()

        val endTime = System.currentTimeMillis() + (minutes * 60 * 1000L)
        prefs.edit().putLong("sleep_timer_end_time", endTime).putBoolean("sleep_timer_video_end", false).apply()

        handler.post(timerRunnable)
        notifyUi()
    }

    fun startVideoEndTimer() {
        cancelTimer()
        isVideoEndMode = true
        noMediaCounterMs = 0L
        saveOriginalVolume()

        prefs.edit().putLong("sleep_timer_end_time", -1L).putBoolean("sleep_timer_video_end", true).apply()

        handler.post(timerRunnable)
        notifyUi()
    }

    fun addMinutes(minutes: Int) {
        val currentEnd = prefs.getLong("sleep_timer_end_time", 0L)
        val now = System.currentTimeMillis()
        val baseTime = if (currentEnd > now) currentEnd else now
        val newEnd = baseTime + (minutes * 60 * 1000L)
        prefs.edit().putLong("sleep_timer_end_time", newEnd).putBoolean("sleep_timer_video_end", false).apply()
        isVideoEndMode = false
        handler.removeCallbacks(timerRunnable)
        handler.post(timerRunnable)
        notifyUi()
    }

    fun cancelTimer() {
        handler.removeCallbacks(timerRunnable)
        prefs.edit().remove("sleep_timer_end_time").remove("sleep_timer_video_end").apply()
        restoreOriginalVolume()
        isVideoEndMode = false
        noMediaCounterMs = 0L
        notifyUi()
    }

    fun restoreIfSaved() {
        val isVideoEnd = prefs.getBoolean("sleep_timer_video_end", false)
        val endTime = prefs.getLong("sleep_timer_end_time", 0L)
        val now = System.currentTimeMillis()

        if (isVideoEnd) {
            isVideoEndMode = true
            handler.post(timerRunnable)
        } else if (endTime > now) {
            handler.post(timerRunnable)
        } else if (endTime > 0L) {
            cancelTimer()
        }
    }

    private fun saveOriginalVolume() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (originalVolume == -1) {
            originalVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        }
    }

    private fun restoreOriginalVolume() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (originalVolume != -1) {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
            originalVolume = -1
        }
    }

    private fun tick() {
        if (isVideoEndMode) {
            val state = nowPlayingManager.currentState
            if (!state.isPlaying) {
                noMediaCounterMs += 1000L
                if (noMediaCounterMs >= 120_000L) {
                    triggerSleepAction()
                    return
                }
            } else {
                noMediaCounterMs = 0L
            }
            handler.postDelayed(timerRunnable, 1000L)
            notifyUi()
            return
        }

        val endTime = prefs.getLong("sleep_timer_end_time", 0L)
        val now = System.currentTimeMillis()
        val remainingMs = endTime - now

        if (remainingMs <= 0) {
            triggerSleepAction()
            return
        }

        // Fade volume down in last 5 minutes
        if (remainingMs <= 5 * 60 * 1000L && originalVolume > 0) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (am != null) {
                val ratio = remainingMs.toFloat() / (5 * 60 * 1000L)
                val targetVol = Math.round(originalVolume * ratio)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
            }
        }

        handler.postDelayed(timerRunnable, 1000L)
        notifyUi()
    }

    private fun triggerSleepAction() {
        cancelTimer()

        // 1. Send Pause Media Key
        nowPlayingManager.handleMediaAction("pause", null)

        // 2. Try Lock Screen -> Power Dialog -> Overlay Fallback
        val acc = RemoteAccessibilityService.instance
        if (acc != null) {
            var handled = false
            if (Build.VERSION.SDK_INT >= 28) {
                handled = acc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            }
            if (!handled) {
                handled = acc.performPowerDialog()
            }
            if (!handled) {
                acc.showBlackOverlayFallback()
            }
        } else {
            val main = MainActivity.activeInstance?.get()
            main?.runOnUiThread {
                ControlCenterOverlayManager.showStandaloneBlackOverlay(context)
            }
        }
    }

    private fun notifyUi() {
        val main = MainActivity.activeInstance?.get()
        main?.runOnUiThread {
            main.reloadAppsAndRefresh()
        }
    }

    fun getSleepStatusJson(): String {
        val isVideoEnd = prefs.getBoolean("sleep_timer_video_end", false)
        val endTime = prefs.getLong("sleep_timer_end_time", 0L)
        val now = System.currentTimeMillis()
        val remainingSec = if (isVideoEnd) -1L else ((endTime - now) / 1000L).coerceAtLeast(0L)
        val active = isVideoEnd || (endTime > now)

        val obj = JSONObject().apply {
            put("active", active)
            put("isVideoEnd", isVideoEnd)
            put("remainingSec", remainingSec)
        }
        return obj.toString()
    }
}
