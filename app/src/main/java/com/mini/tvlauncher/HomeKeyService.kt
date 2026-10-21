package com.mini.tvlauncher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class HomeKeyService : AccessibilityService() {

    private val homeKeyCodes = setOf(
        KeyEvent.KEYCODE_ALL_APPS, // 284
        KeyEvent.KEYCODE_HOME      // 3
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && homeKeyCodes.contains(event.keyCode)) {
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
