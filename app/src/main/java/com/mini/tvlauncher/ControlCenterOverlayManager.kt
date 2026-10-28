package com.mini.tvlauncher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.*
import android.widget.*

class ControlCenterOverlayManager(private val service: AccessibilityService) {

    private var wm: WindowManager? = null
    private var overlayView: View? = null
    private var blackOverlayView: View? = null

    private val idleHandler = Handler(Looper.getMainLooper())
    private val idleRunnable = Runnable { dismiss() }

    fun toggleOrShow() {
        if (overlayView != null) {
            dismiss()
        } else {
            show()
        }
    }

    fun show() {
        dismiss()
        wm = service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
        }

        val view = buildControlCenterView()
        overlayView = view

        try {
            wm?.addView(view, params)
            resetIdleTimer()
        } catch (_: Exception) {
            overlayView = null
        }
    }

    fun dismiss() {
        idleHandler.removeCallbacks(idleRunnable)
        val v = overlayView
        if (v != null && wm != null) {
            try {
                wm?.removeView(v)
            } catch (_: Exception) {}
            overlayView = null
        }
    }

    fun showBlackOverlay() {
        showStandaloneBlackOverlay(service)
    }

    private fun resetIdleTimer() {
        idleHandler.removeCallbacks(idleRunnable)
        idleHandler.postDelayed(idleRunnable, 12_000L)
    }

    private fun buildControlCenterView(): View {
        val root = FrameLayout(service).apply {
            setBackgroundColor(0xE60B0F1A.toInt())
            setPadding(32, 24, 32, 24)
        }

        val container = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Title Row
        val title = TextView(service).apply {
            text = "Control Center"
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        container.addView(title)

        // Volume Row
        val volLayout = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 16, 0, 16)
        }

        val am = service.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val maxVol = am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
        val curVol = am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 10

        val volText = TextView(service).apply {
            text = "Volume: $curVol"
            setTextColor(Color.WHITE)
            textSize = 14f
        }

        val volSeekBar = SeekBar(service).apply {
            max = maxVol
            progress = curVol
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                    if (fromUser) {
                        am?.setStreamVolume(AudioManager.STREAM_MUSIC, prog, AudioManager.FLAG_SHOW_UI)
                        volText.text = "Volume: $prog"
                        resetIdleTimer()
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }

        volLayout.addView(volText, LinearLayout.LayoutParams(160, LinearLayout.LayoutParams.WRAP_CONTENT))
        volLayout.addView(volSeekBar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        container.addView(volLayout)

        // Sleep Timer Chips
        val sleepTitle = TextView(service).apply {
            text = "Sleep Timer"
            setTextColor(0xFF8090A8.toInt())
            textSize = 12f
            setPadding(0, 8, 0, 8)
        }
        container.addView(sleepTitle)

        val sleepChipsLayout = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        fun addSleepChip(label: String, action: () -> Unit) {
            val chip = Button(service).apply {
                text = label
                setTextColor(Color.WHITE)
                setBackgroundResource(R.drawable.pill_bg)
                setOnClickListener {
                    action()
                    dismiss()
                }
                setOnFocusChangeListener { _, focused -> if (focused) resetIdleTimer() }
            }
            sleepChipsLayout.addView(chip, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 8 })
        }

        addSleepChip("+15m") {
            RemoteService.sleepTimerManager?.addMinutes(15)
        }
        addSleepChip("+30m") {
            RemoteService.sleepTimerManager?.addMinutes(30)
        }
        addSleepChip("+60m") {
            RemoteService.sleepTimerManager?.addMinutes(60)
        }
        addSleepChip("Cancel") {
            RemoteService.sleepTimerManager?.cancelTimer()
        }
        container.addView(sleepChipsLayout)

        // Shortcut Buttons
        val shortcutsLayout = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 0)
        }

        fun addShortcutBtn(label: String, intent: Intent) {
            val btn = Button(service).apply {
                text = label
                setTextColor(Color.WHITE)
                setBackgroundResource(R.drawable.pill_bg)
                setOnClickListener {
                    try {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        service.startActivity(intent)
                    } catch (_: Exception) {}
                    dismiss()
                }
                setOnFocusChangeListener { _, focused -> if (focused) resetIdleTimer() }
            }
            shortcutsLayout.addView(btn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 8 })
        }

        addShortcutBtn("Home", Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
        addShortcutBtn("Settings", Intent(Settings.ACTION_SETTINGS))
        addShortcutBtn("Wi-Fi", Intent(Settings.ACTION_WIFI_SETTINGS))
        addShortcutBtn("Bluetooth", Intent(Settings.ACTION_BLUETOOTH_SETTINGS))

        container.addView(shortcutsLayout)
        root.addView(container)

        root.isFocusable = true
        root.isFocusableInTouchMode = true
        root.setOnKeyListener { _, keyCode, event ->
            resetIdleTimer()
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_BACK) {
                dismiss()
                true
            } else false
        }

        return root
    }

    companion object {
        fun showStandaloneBlackOverlay(context: Context) {
            try {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    PixelFormat.OPAQUE
                )
                val v = View(context).apply {
                    setBackgroundColor(Color.BLACK)
                    setOnClickListener {
                        try { wm.removeView(this) } catch (_: Exception) {}
                    }
                }
                wm.addView(v, params)
            } catch (_: Exception) {}
        }
    }
}
