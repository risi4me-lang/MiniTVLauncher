package com.mini.tvlauncher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class HomeKeyService : AccessibilityService() {

    companion object {
        @Volatile var instance: HomeKeyService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        KeyEventBuffer.add(event, "HomeKeyService")

        val prefs = getSharedPreferences("launcher", MODE_PRIVATE)
        val customKey = prefs.getInt("custom_home_keycode", -1)

        val isHomeKey = event.keyCode == KeyEvent.KEYCODE_ALL_APPS ||
                event.keyCode == KeyEvent.KEYCODE_HOME ||
                (customKey != -1 && event.keyCode == customKey)

        if (event.action == KeyEvent.ACTION_UP && isHomeKey) {
            launchHome()
            return true
        }
        return super.onKeyEvent(event)
    }

    private fun launchHome() {
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
            startActivity(intent)
        } catch (_: Exception) {}
    }
}
