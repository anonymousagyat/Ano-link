package com.tailconnect.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

data class NodeDto(
    val id: Int,
    val bounds: List<Int>,
    val className: String,
    val text: String,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val children: List<NodeDto>?
)

class RemoteAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "RemoteAccessibility"
        var instance: RemoteAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        try {
            val info = serviceInfo ?: android.accessibilityservice.AccessibilityServiceInfo()
            info.eventTypes = android.view.accessibility.AccessibilityEvent.TYPES_ALL_MASK
            info.feedbackType = android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.flags = info.flags or
                    android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            serviceInfo = info
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply programmatic serviceInfo: ${e.message}")
        }
        Log.i(TAG, "RemoteAccessibilityService connected and active!")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // If an active session is in Skeleton Mode, capture the updated tree
        if (TailConnectService.isSkeletonModeActive()) {
            val (width, height) = getRealScreenMetrics()
            val root = getActiveRootNode(event) ?: return
            val tree = parseNode(root, isRoot = true) ?: return
            TailConnectService.broadcastSkeletonTree(tree, width, height)
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "RemoteAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(TAG, "RemoteAccessibilityService destroyed")
    }

    fun captureCurrentTree(): Pair<NodeDto?, Pair<Int, Int>> {
        val (width, height) = getRealScreenMetrics()
        val root = getActiveRootNode() ?: return Pair(null, Pair(width, height))
        return Pair(parseNode(root, isRoot = true), Pair(width, height))
    }

    private fun getActiveRootNode(event: AccessibilityEvent? = null): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { return it }
        try {
            val wins = windows
            if (!wins.isNullOrEmpty()) {
                val focused = wins.firstOrNull { it.isFocused }?.root
                if (focused != null) return focused
                val active = wins.firstOrNull { it.isActive }?.root
                if (active != null) return active
                for (w in wins) {
                    val r = w.root
                    if (r != null) return r
                }
            }
        } catch (_: Exception) {}

        // Fallback: Climb up to window root from event source
        var curr = event?.source
        while (curr != null) {
            val parent = curr.parent
            if (parent == null) return curr
            curr = parent
        }
        return null
    }

    private fun getRealScreenMetrics(): Pair<Int, Int> {
        return try {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (windowManager != null) {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(metrics)
                Pair(metrics.widthPixels, metrics.heightPixels)
            } else {
                val dm = resources.displayMetrics
                Pair(dm.widthPixels, dm.heightPixels)
            }
        } catch (_: Exception) {
            val dm = resources.displayMetrics
            Pair(dm.widthPixels, dm.heightPixels)
        }
    }

    private fun parseNode(node: AccessibilityNodeInfo, isRoot: Boolean = false): NodeDto? {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        // Drop non-root views that are not visible to the user (e.g. covered homescreen under app drawer or obscured)
        if (!isRoot && !node.isVisibleToUser) {
            return null
        }

        // For non-root leaf nodes, filter out if zero area
        if (!isRoot && node.childCount == 0 && (bounds.width() <= 0 || bounds.height() <= 0)) {
            return null
        }

        val childCount = node.childCount
        val childrenList = if (childCount > 0) {
            (0 until childCount).mapNotNull { idx ->
                try {
                    val child = node.getChild(idx)
                    child?.let { parseNode(it, isRoot = false) }
                } catch (_: Exception) {
                    null
                }
            }
        } else null

        val nodeText = node.text?.toString() ?: node.contentDescription?.toString() ?: ""

        // Skip non-actionable empty container ONLY if not root and has no children
        if (!isRoot && !node.isClickable && !node.isEditable && !node.isScrollable && nodeText.isBlank() && childrenList.isNullOrEmpty()) {
            return null
        }

        return NodeDto(
            id = node.hashCode(),
            bounds = listOf(bounds.left, bounds.top, bounds.right, bounds.bottom),
            className = node.className?.toString() ?: "",
            text = nodeText,
            isClickable = node.isClickable,
            isEditable = node.isEditable,
            isScrollable = node.isScrollable,
            children = childrenList
        )
    }

    // -----------------------------------------------------------------
    // Remote Gesture & Input Injection
    // -----------------------------------------------------------------

    fun injectTap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50L) // 50ms tap
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    fun injectSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val safeDuration = if (durationMs in 50..1000) durationMs else 250L
        val stroke = GestureDescription.StrokeDescription(path, 0, safeDuration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    fun injectGlobalAction(actionKey: String): Boolean {
        val action = when (actionKey.uppercase()) {
            "BACK" -> GLOBAL_ACTION_BACK
            "HOME" -> GLOBAL_ACTION_HOME
            "RECENTS" -> GLOBAL_ACTION_RECENTS
            "NOTIFICATIONS" -> GLOBAL_ACTION_NOTIFICATIONS
            "QUICK_SETTINGS" -> GLOBAL_ACTION_QUICK_SETTINGS
            "LOCK" -> GLOBAL_ACTION_LOCK_SCREEN
            else -> return false
        }
        return performGlobalAction(action)
    }

    fun injectText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }
}
