package com.mini.tvlauncher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

class ProjectorSetupActivity : Activity() {

    companion object {
        @Volatile var activeInstance: ProjectorSetupActivity? = null
            private set

        fun toggleOrNextPattern() {
            val inst = activeInstance
            if (inst != null) {
                inst.nextPattern()
            } else {
                val context = MainActivity.activeInstance?.get()
                if (context != null) {
                    val intent = Intent(context, ProjectorSetupActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
            }
        }
    }

    private lateinit var patternView: TestPatternView
    private lateinit var hintText: TextView
    private var currentPattern = 0
    private val totalPatterns = 8

    private val hintHandler = Handler(Looper.getMainLooper())
    private val hideHintRunnable = Runnable { hintText.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        patternView = TestPatternView(this)
        val root = FrameLayout(this)
        root.addView(patternView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        hintText = TextView(this).apply {
            text = "Use Left/Right to change pattern • Back to exit"
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC000000.toInt())
            textSize = 14f
            setPadding(24, 12, 24, 12)
        }
        val hintParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
            bottomMargin = 32
        }
        root.addView(hintText, hintParams)

        setContentView(root)
        showHint()
    }

    override fun onStart() {
        super.onStart()
        activeInstance = this
    }

    override fun onStop() {
        if (activeInstance == this) activeInstance = null
        super.onStop()
    }

    override fun onDestroy() {
        hintHandler.removeCallbacks(hideHintRunnable)
        if (activeInstance == this) activeInstance = null
        super.onDestroy()
    }

    fun nextPattern() {
        currentPattern = (currentPattern + 1) % totalPatterns
        patternView.setPattern(currentPattern)
        showHint()
    }

    fun prevPattern() {
        currentPattern = if (currentPattern - 1 < 0) totalPatterns - 1 else currentPattern - 1
        patternView.setPattern(currentPattern)
        showHint()
    }

    private fun showHint() {
        hintText.text = "Pattern ${currentPattern + 1}/$totalPatterns: ${patternView.getPatternName(currentPattern)} • Left/Right to switch • Back to exit"
        hintText.visibility = View.VISIBLE
        hintHandler.removeCallbacks(hideHintRunnable)
        hintHandler.postDelayed(hideHintRunnable, 3000L)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        showHint()
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> { nextPattern(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { prevPattern(); return true }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> { finish(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    class TestPatternView(context: Context) : View(context) {
        private var patternIndex = 0
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        fun setPattern(idx: Int) {
            patternIndex = idx
            invalidate()
        }

        fun getPatternName(idx: Int): String = when (idx) {
            0 -> "Alignment Grid & Corners"
            1 -> "Focus Chart"
            2 -> "Color Bars"
            3 -> "16-Step Grey Ramp"
            4 -> "Full White (100%)"
            5 -> "Full Black (0%)"
            6 -> "Overscan & 1px Frame"
            7 -> "Centered Circle (Keystone)"
            else -> "Pattern"
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0 || h <= 0) return

            when (patternIndex) {
                0 -> drawGrid(canvas, w, h)
                1 -> drawFocusChart(canvas, w, h)
                2 -> drawColorBars(canvas, w, h)
                3 -> drawGreyRamp(canvas, w, h)
                4 -> canvas.drawColor(Color.WHITE)
                5 -> canvas.drawColor(Color.BLACK)
                6 -> drawOverscan(canvas, w, h)
                7 -> drawCircle(canvas, w, h)
            }
        }

        private fun drawGrid(canvas: Canvas, w: Float, h: Float) {
            canvas.drawColor(Color.BLACK)
            paint.color = Color.WHITE
            paint.strokeWidth = 2f
            paint.style = Paint.Style.STROKE

            val cols = 8; val rows = 6
            for (i in 0..cols) {
                val x = w * i / cols
                canvas.drawLine(x, 0f, x, h, paint)
            }
            for (j in 0..rows) {
                val y = h * j / rows
                canvas.drawLine(0f, y, w, y, paint)
            }

            paint.color = Color.RED
            paint.strokeWidth = 4f
            val cornerLen = 60f
            // Corners
            canvas.drawLine(0f, 0f, cornerLen, 0f, paint)
            canvas.drawLine(0f, 0f, 0f, cornerLen, paint)

            canvas.drawLine(w - cornerLen, 0f, w, 0f, paint)
            canvas.drawLine(w, 0f, w, cornerLen, paint)

            canvas.drawLine(0f, h, cornerLen, h, paint)
            canvas.drawLine(0f, h - cornerLen, 0f, h, paint)

            canvas.drawLine(w - cornerLen, h, w, h, paint)
            canvas.drawLine(w, h - cornerLen, w, h, paint)
        }

        private fun drawFocusChart(canvas: Canvas, w: Float, h: Float) {
            canvas.drawColor(Color.BLACK)
            paint.color = Color.WHITE
            paint.style = Paint.Style.FILL
            paint.textAlign = Paint.Align.CENTER

            val sizes = listOf(14f, 18f, 24f, 32f, 48f, 64f)
            var y = h * 0.15f
            for (s in sizes) {
                paint.textSize = s * resources.displayMetrics.density / 2f
                canvas.drawText("PROJECTOR FOCUS CHART ${s.toInt()}pt • ABCDEFGHIJKLMNOPQRSTUVWXYZ 1234567890", w / 2f, y, paint)
                y += s * 2.2f
            }
        }

        private fun drawColorBars(canvas: Canvas, w: Float, h: Float) {
            val colors = intArrayOf(Color.WHITE, Color.YELLOW, Color.CYAN, Color.GREEN, Color.MAGENTA, Color.RED, Color.BLUE, Color.BLACK)
            val barW = w / colors.size
            for (i in colors.indices) {
                paint.color = colors[i]
                paint.style = Paint.Style.FILL
                canvas.drawRect(i * barW, 0f, (i + 1) * barW, h, paint)
            }
        }

        private fun drawGreyRamp(canvas: Canvas, w: Float, h: Float) {
            val steps = 16
            val stepW = w / steps
            paint.style = Paint.Style.FILL
            for (i in 0 until steps) {
                val v = (255 * i / (steps - 1))
                paint.color = Color.rgb(v, v, v)
                canvas.drawRect(i * stepW, 0f, (i + 1) * stepW, h, paint)
            }
        }

        private fun drawOverscan(canvas: Canvas, w: Float, h: Float) {
            canvas.drawColor(Color.BLACK)
            paint.color = Color.GREEN
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            canvas.drawRect(1f, 1f, w - 1f, h - 1f, paint)

            paint.color = Color.YELLOW
            paint.strokeWidth = 3f
            paint.style = Paint.Style.FILL
            paint.textSize = 24f
            paint.textAlign = Paint.Align.CENTER

            canvas.drawText("100% FULL FRAME OVERSCAN BOUNDARY", w / 2f, h / 2f, paint)
        }

        private fun drawCircle(canvas: Canvas, w: Float, h: Float) {
            canvas.drawColor(Color.BLACK)
            paint.color = Color.CYAN
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f

            val cx = w / 2f
            val cy = h / 2f
            val radius = (Math.min(w, h) / 2f) - 20f

            canvas.drawCircle(cx, cy, radius, paint)
            canvas.drawCircle(cx, cy, radius * 0.66f, paint)
            canvas.drawCircle(cx, cy, radius * 0.33f, paint)

            canvas.drawLine(cx, 0f, cx, h, paint)
            canvas.drawLine(0f, cy, w, cy, paint)
        }
    }
}
