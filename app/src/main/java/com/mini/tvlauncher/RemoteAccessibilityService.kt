package com.mini.tvlauncher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class RemoteAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: RemoteAccessibilityService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val expectedService = "${context.packageName}/${RemoteAccessibilityService::class.java.canonicalName}"
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            return enabledServices?.contains(expectedService) == true
        }
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

    fun performBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun performHome(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    fun performPowerDialog(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
    }

    fun navigate(direction: String): Pair<Boolean, String?> {
        val root = rootInActiveWindow ?: return Pair(false, "no_window")

        var currentFocus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (currentFocus == null) {
            currentFocus = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        }
        if (currentFocus == null) {
            currentFocus = findFirstFocusableNode(root)
        }

        val viewFocusDir = when (direction.lowercase()) {
            "up" -> View.FOCUS_UP
            "down" -> View.FOCUS_DOWN
            "left" -> View.FOCUS_LEFT
            "right" -> View.FOCUS_RIGHT
            else -> {
                currentFocus?.recycle()
                root.recycle()
                return Pair(false, "invalid_direction")
            }
        }

        if (currentFocus != null) {
            val nextNode = currentFocus.focusSearch(viewFocusDir)
            if (nextNode != null) {
                val ok = nextNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                nextNode.recycle()
                currentFocus.recycle()
                root.recycle()
                if (ok) return Pair(true, null)
            }
        }

        val scrollAction = if (direction == "down" || direction == "right") {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }

        val targetForScroll = currentFocus ?: root
        val scrolled = scrollNearestScrollable(targetForScroll, scrollAction)

        currentFocus?.recycle()
        root.recycle()

        return if (scrolled) {
            Pair(true, null)
        } else {
            Pair(false, "no_focusable_node")
        }
    }

    fun select(): Pair<Boolean, String?> {
        val root = rootInActiveWindow ?: return Pair(false, "no_window")

        var currentFocus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (currentFocus == null) {
            currentFocus = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        }
        if (currentFocus == null) {
            currentFocus = findFirstFocusableNode(root)
        }

        if (currentFocus == null) {
            root.recycle()
            return Pair(false, "no_focusable_node")
        }

        val clickableNode = findNearestClickableAncestor(currentFocus)
        if (clickableNode != null) {
            val ok = clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            clickableNode.recycle()
            currentFocus.recycle()
            root.recycle()
            if (ok) return Pair(true, null)
        }

        val rect = Rect()
        currentFocus.getBoundsInScreen(rect)
        val cx = rect.exactCenterX()
        val cy = rect.exactCenterY()

        var gestureOk = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && cx > 0 && cy > 0) {
            val path = Path().apply { moveTo(cx, cy) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 50)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            gestureOk = dispatchGesture(gesture, null, null)
        }

        currentFocus.recycle()
        root.recycle()

        return if (gestureOk) {
            Pair(true, null)
        } else {
            Pair(false, "no_focusable_node")
        }
    }

    fun typeText(text: String, action: String = ""): Boolean {
        val root = rootInActiveWindow ?: return false
        val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFocusedEditText(root)

        if (focusedNode != null) {
            if (text.isNotEmpty() || action.isEmpty()) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }

            if (action == "enter") {
                var enterDone = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val actionImeEnter = 0x00000010
                    enterDone = focusedNode.performAction(actionImeEnter)
                }
                if (!enterDone) {
                    val submitNode = findNearestClickableAncestor(focusedNode)
                    submitNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    submitNode?.recycle()
                }
            }

            focusedNode.recycle()
            root.recycle()
            return true
        }
        root.recycle()
        return false
    }

    private fun findFirstFocusableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isFocusable && node.isVisibleToUser) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val res = findFirstFocusableNode(child)
            child.recycle()
            if (res != null) return res
        }
        return null
    }

    private fun findNearestClickableAncestor(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        while (current != null) {
            if (current.isClickable) return current
            val parent = current.parent
            current.recycle()
            current = parent
        }
        return null
    }

    private fun scrollNearestScrollable(startNode: AccessibilityNodeInfo, action: Int): Boolean {
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(startNode)
        while (current != null) {
            if (current.isScrollable) {
                val ok = current.performAction(action)
                current.recycle()
                if (ok) return true
                return false
            }
            val parent = current.parent
            current.recycle()
            current = parent
        }
        return false
    }

    private fun findFocusedEditText(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isFocused && (node.isEditable || node.className?.toString()?.contains("EditText", true) == true)) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val res = findFocusedEditText(child)
            child.recycle()
            if (res != null) return res
        }
        return null
    }
}
