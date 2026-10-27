package com.tailconnect.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

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
        var isSilentStreaming: Boolean = false
            private set
    }

    private val isSilentStreamingActive = AtomicBoolean(false)
    private var streamingScope: CoroutineScope? = null
    private var currentIntervalMs = 95L // 95ms interval = target ~10 FPS (OS-compliant)
    private val reusableStream = ByteArrayOutputStream(32768)
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        try {
            val info = serviceInfo ?: android.accessibilityservice.AccessibilityServiceInfo()
            // Listen specifically to window and scroll changes; DO NOT use TYPES_ALL_MASK as that includes
            // raw text input / typing events which trigger OEM anti-keylogger defenses (e.g. Vivo iManager / AppLock).
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_SCROLLED or
                    AccessibilityEvent.TYPE_WINDOWS_CHANGED
            info.feedbackType = android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.flags = android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            serviceInfo = info
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to apply programmatic serviceInfo: ${t.message}")
        }
        Log.i(TAG, "RemoteAccessibilityService connected and active!")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            // If an active session is in Skeleton Mode, capture the updated tree
            if (TailConnectService.isSkeletonModeActive()) {
                val (width, height) = getRealScreenMetrics()
                val root = getActiveRootNode(event) ?: return
                val tree = parseNode(root, isRoot = true) ?: return
                TailConnectService.broadcastSkeletonTree(tree, width, height)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Protected onAccessibilityEvent from crash: ${t.message}")
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "RemoteAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSilentScreenStreaming()
        instance = null
        Log.i(TAG, "RemoteAccessibilityService destroyed")
    }

    fun captureCurrentTree(): Pair<NodeDto?, Pair<Int, Int>> {
        return try {
            val (width, height) = getRealScreenMetrics()
            val root = getActiveRootNode() ?: return Pair(null, Pair(width, height))
            Pair(parseNode(root, isRoot = true), Pair(width, height))
        } catch (t: Throwable) {
            Log.w(TAG, "captureCurrentTree failed: ${t.message}")
            val (width, height) = getRealScreenMetrics()
            Pair(null, Pair(width, height))
        }
    }

    private fun getActiveRootNode(event: AccessibilityEvent? = null): AccessibilityNodeInfo? {
        try {
            rootInActiveWindow?.let { return it }
        } catch (t: Throwable) {
            Log.d(TAG, "rootInActiveWindow access error: ${t.message}")
        }

        try {
            val wins = windows
            if (!wins.isNullOrEmpty()) {
                val focused = wins.firstOrNull {
                    try { it.isFocused } catch (_: Throwable) { false }
                }?.let {
                    try { it.root } catch (_: Throwable) { null }
                }
                if (focused != null) return focused

                val active = wins.firstOrNull {
                    try { it.isActive } catch (_: Throwable) { false }
                }?.let {
                    try { it.root } catch (_: Throwable) { null }
                }
                if (active != null) return active

                for (w in wins) {
                    val r = try { w.root } catch (_: Throwable) { null }
                    if (r != null) return r
                }
            }
        } catch (t: Throwable) {
            Log.d(TAG, "windows traversal error: ${t.message}")
        }

        // Fallback: Climb up to window root from event source
        try {
            var curr = event?.source
            var depth = 0
            while (curr != null && depth < 30) {
                depth++
                val parent = try { curr.parent } catch (_: Throwable) { null }
                if (parent == null) return curr
                curr = parent
            }
            return curr
        } catch (t: Throwable) {
            Log.d(TAG, "Climbing hierarchy fallback error: ${t.message}")
        }
        return null
    }

    fun getRealScreenMetrics(): Pair<Int, Int> {
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

    private fun parseNode(node: AccessibilityNodeInfo?, isRoot: Boolean = false, depth: Int = 0): NodeDto? {
        if (node == null || depth > 40) return null

        val bounds = Rect()
        try {
            node.getBoundsInScreen(bounds)
        } catch (_: Throwable) {
            return null
        }

        // Drop non-root views that are not visible to the user (e.g. covered homescreen under app drawer or obscured)
        val isVisible = try { node.isVisibleToUser } catch (_: Throwable) { true }
        if (!isRoot && !isVisible) {
            return null
        }

        val childCount = try { node.childCount } catch (_: Throwable) { 0 }

        // For non-root leaf nodes, filter out if zero area
        if (!isRoot && childCount == 0 && (bounds.width() <= 0 || bounds.height() <= 0)) {
            return null
        }

        val childrenList = if (childCount > 0) {
            (0 until childCount).mapNotNull { idx ->
                try {
                    val child = node.getChild(idx)
                    child?.let { parseNode(it, isRoot = false, depth = depth + 1) }
                } catch (_: Throwable) {
                    null
                }
            }
        } else null

        val isPassword = try { node.isPassword } catch (_: Throwable) { false }
        val nodeText = if (isPassword) {
            // Mask password contents to satisfy OEM security watchdogs, while preserving clickable button & input boundaries for Skeleton mode
            "••••••"
        } else {
            try {
                node.text?.toString() ?: node.contentDescription?.toString() ?: ""
            } catch (_: Throwable) {
                ""
            }
        }

        val isClickable = try { node.isClickable } catch (_: Throwable) { false }
        val isEditable = try { node.isEditable } catch (_: Throwable) { false }
        val isScrollable = try { node.isScrollable } catch (_: Throwable) { false }
        val className = try { node.className?.toString() ?: "" } catch (_: Throwable) { "" }

        // Skip non-actionable empty container ONLY if not root and has no children
        if (!isRoot && !isClickable && !isEditable && !isScrollable && nodeText.isBlank() && childrenList.isNullOrEmpty()) {
            return null
        }

        val nodeId = try { node.hashCode() } catch (_: Throwable) { 0 }

        return NodeDto(
            id = nodeId,
            bounds = listOf(bounds.left, bounds.top, bounds.right, bounds.bottom),
            className = className,
            text = nodeText,
            isClickable = isClickable,
            isEditable = isEditable,
            isScrollable = isScrollable,
            children = childrenList
        )
    }

    // -----------------------------------------------------------------
    // Remote Gesture & Input Injection
    // -----------------------------------------------------------------

    fun injectTap(x: Float, y: Float): Boolean {
        return try {
            val (realW, realH) = getRealScreenMetrics()
            val maxX = (realW - 1).toFloat().coerceAtLeast(0f)
            val maxY = (realH - 1).toFloat().coerceAtLeast(0f)
            val clampedX = x.coerceIn(0f, maxX)
            val clampedY = y.coerceIn(0f, maxY)

            val path = Path().apply {
                moveTo(clampedX, clampedY)
                lineTo(clampedX, clampedY)
            }
            // 100ms matches native Android View tap timeout (ViewConfiguration.getTapTimeout())
            val stroke = GestureDescription.StrokeDescription(path, 0, 100L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "injectTap completed at ($clampedX, $clampedY)")
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "injectTap cancelled at ($clampedX, $clampedY)")
                }
            }, null)
        } catch (t: Throwable) {
            Log.w(TAG, "injectTap error: ${t.message}")
            false
        }
    }

    fun injectNormalizedTap(nx: Float, ny: Float): Boolean {
        val (realW, realH) = getRealScreenMetrics()
        val targetX = nx.coerceIn(0f, 1f) * (realW - 1).coerceAtLeast(0)
        val targetY = ny.coerceIn(0f, 1f) * (realH - 1).coerceAtLeast(0)
        return injectTap(targetX, targetY)
    }

    fun injectSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long): Boolean {
        return try {
            val (realW, realH) = getRealScreenMetrics()
            val maxX = (realW - 1).toFloat().coerceAtLeast(0f)
            val maxY = (realH - 1).toFloat().coerceAtLeast(0f)

            val clampedStartX = startX.coerceIn(0f, maxX)
            val clampedStartY = startY.coerceIn(0f, maxY)
            val clampedEndX = endX.coerceIn(0f, maxX)
            val clampedEndY = endY.coerceIn(0f, maxY)

            val path = Path().apply {
                moveTo(clampedStartX, clampedStartY)
                lineTo(clampedEndX, clampedEndY)
            }
            val safeDuration = if (durationMs in 80..1500) durationMs else 250L
            val stroke = GestureDescription.StrokeDescription(path, 0, safeDuration)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "injectSwipe completed from ($clampedStartX, $clampedStartY) to ($clampedEndX, $clampedEndY)")
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "injectSwipe cancelled")
                }
            }, null)
        } catch (t: Throwable) {
            Log.w(TAG, "injectSwipe error: ${t.message}")
            false
        }
    }

    fun injectNormalizedSwipe(startNx: Float, startNy: Float, endNx: Float, endNy: Float, durationMs: Long): Boolean {
        val (realW, realH) = getRealScreenMetrics()
        val maxX = (realW - 1).coerceAtLeast(0)
        val maxY = (realH - 1).coerceAtLeast(0)
        val startX = startNx.coerceIn(0f, 1f) * maxX
        val startY = startNy.coerceIn(0f, 1f) * maxY
        val endX = endNx.coerceIn(0f, 1f) * maxX
        val endY = endNy.coerceIn(0f, 1f) * maxY
        return injectSwipe(startX, startY, endX, endY, durationMs)
    }

    fun injectGlobalAction(actionKey: String): Boolean {
        return try {
            val action = when (actionKey.uppercase()) {
                "BACK" -> GLOBAL_ACTION_BACK
                "HOME" -> GLOBAL_ACTION_HOME
                "RECENTS" -> GLOBAL_ACTION_RECENTS
                "NOTIFICATIONS" -> GLOBAL_ACTION_NOTIFICATIONS
                "QUICK_SETTINGS" -> GLOBAL_ACTION_QUICK_SETTINGS
                "LOCK" -> GLOBAL_ACTION_LOCK_SCREEN
                else -> return false
            }
            performGlobalAction(action)
        } catch (t: Throwable) {
            Log.w(TAG, "injectGlobalAction error: ${t.message}")
            false
        }
    }

    fun injectText(text: String): Boolean {
        return try {
            val root = rootInActiveWindow ?: return false
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false

            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (t: Throwable) {
            Log.w(TAG, "injectText error: ${t.message}")
            false
        }
    }

    // -----------------------------------------------------------------
    // High-Efficiency Silent Screen Streaming Engine (OS-Compliant ~10 FPS)
    // -----------------------------------------------------------------

    fun startSilentScreenStreaming(targetFps: Int = 10): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "Silent screen capture requires Android 11+ (API 30+)")
            return false
        }
        if (isSilentStreamingActive.get()) {
            return true
        }

        isSilentStreamingActive.set(true)
        isSilentStreaming = true
        currentIntervalMs = (1000L / targetFps.coerceIn(8, 15)).coerceIn(80L, 120L) // ~95-100ms for 10 FPS
        streamingScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        var lastReportedW = 0
        var lastReportedH = 0

        val (realW, realH) = getRealScreenMetrics()
        lastReportedW = realW
        lastReportedH = realH
        TailConnectService.sendToPc(mapOf(
            "type" to "screen_metrics",
            "screenWidth" to realW,
            "screenHeight" to realH
        ))

        Log.i(TAG, "Starting high-efficiency silent screen stream at ~${targetFps} FPS (interval: ${currentIntervalMs}ms)...")

        fun captureFrame() {
            if (!isSilentStreamingActive.get()) return
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    ContextCompat.getMainExecutor(this@RemoteAccessibilityService),
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshotResult: ScreenshotResult) {
                            if (!isSilentStreamingActive.get()) {
                                try { screenshotResult.hardwareBuffer.close() } catch (_: Throwable) {}
                                return
                            }

                            val hwBuffer = screenshotResult.hardwareBuffer
                            val colorSpace = screenshotResult.colorSpace

                            streamingScope?.launch(Dispatchers.Default) {
                                try {
                                    val origW = hwBuffer.width
                                    val origH = hwBuffer.height

                                    if (origW > 0 && origH > 0 && (origW != lastReportedW || origH != lastReportedH)) {
                                        lastReportedW = origW
                                        lastReportedH = origH
                                        TailConnectService.sendToPc(mapOf(
                                            "type" to "screen_metrics",
                                            "screenWidth" to origW,
                                            "screenHeight" to origH
                                        ))
                                    }

                                    // Dynamic aspect ratio calculation:
                                    // Target ~540px on the short edge for optimal speed & crisp text (~25-35KB/frame)
                                    val targetW: Int
                                    val targetH: Int
                                    if (origW <= origH) {
                                        targetW = 540
                                        targetH = (origH.toFloat() * (540f / origW.coerceAtLeast(1).toFloat())).toInt().coerceAtLeast(540)
                                    } else {
                                        targetH = 540
                                        targetW = (origW.toFloat() * (540f / origH.coerceAtLeast(1).toFloat())).toInt().coerceAtLeast(540)
                                    }

                                    val rawBitmap = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                                    if (rawBitmap != null) {
                                        var encoded = false
                                        try {
                                            // Step 1: Read back GPU HardwareBuffer to CPU ARGB_8888 RAM
                                            val softBitmap = rawBitmap.copy(Bitmap.Config.ARGB_8888, false)

                                            // Step 2: Immediately release GPU HardwareBuffer back to SurfaceFlinger (<1.5ms)
                                            rawBitmap.recycle()
                                            try { hwBuffer.close() } catch (_: Throwable) {}

                                            if (softBitmap != null) {
                                                // Step 3: Software-safe downscale
                                                val scaledBitmap = if (softBitmap.width == targetW && softBitmap.height == targetH) {
                                                    softBitmap
                                                } else {
                                                    Bitmap.createScaledBitmap(softBitmap, targetW, targetH, true).also {
                                                        if (it != softBitmap) softBitmap.recycle()
                                                    }
                                                }

                                                // Step 4: JPEG Compression & Transmission
                                                synchronized(reusableStream) {
                                                    reusableStream.reset()
                                                    scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 68, reusableStream)
                                                    if (scaledBitmap != softBitmap) scaledBitmap.recycle() else softBitmap.recycle()
                                                    val bytes = reusableStream.toByteArray()
                                                    TailConnectService.broadcastVideoFrame(bytes)
                                                    encoded = true
                                                }
                                            }
                                        } catch (copyErr: Throwable) {
                                            Log.w(TAG, "Notice on softBitmap processing: ${copyErr.message}")
                                        }

                                        // Step 5: Direct compression fallback (if copy failed on exotic hardware)
                                        if (!encoded) {
                                            try {
                                                synchronized(reusableStream) {
                                                    reusableStream.reset()
                                                    rawBitmap.compress(Bitmap.CompressFormat.JPEG, 68, reusableStream)
                                                    val bytes = reusableStream.toByteArray()
                                                    TailConnectService.broadcastVideoFrame(bytes)
                                                }
                                            } catch (_: Throwable) {}
                                            rawBitmap.recycle()
                                            try { hwBuffer.close() } catch (_: Throwable) {}
                                        }
                                    } else {
                                        try { hwBuffer.close() } catch (_: Throwable) {}
                                    }
                                } catch (e: Throwable) {
                                    Log.w(TAG, "Silent frame process error: ${e.message}")
                                    try { hwBuffer.close() } catch (_: Throwable) {}
                                }

                                // Adaptive rate governor: smoothly recover towards base interval (~95ms)
                                if (currentIntervalMs > 95L) {
                                    currentIntervalMs = (currentIntervalMs - 3L).coerceAtLeast(95L)
                                }

                                if (isSilentStreamingActive.get()) {
                                    mainHandler.postDelayed({
                                        if (isSilentStreamingActive.get()) {
                                            captureFrame()
                                        }
                                    }, currentIntervalMs)
                                }
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            if (!isSilentStreamingActive.get()) return
                            Log.w(TAG, "takeScreenshot onFailure errorCode: $errorCode")

                            // Code 2 = ERROR_TAKE_SCREENSHOT_SECURE_WINDOW (FLAG_SECURE, e.g. App Lock / password screen)
                            // Crucial: Back off so we don't spam capture attempts against secure windows, avoiding OEM kill
                            if (errorCode == 2) {
                                Log.i(TAG, "FLAG_SECURE window detected (errorCode 2). Backing off silent capture for 1.5s (Skeleton mode continues).")
                                mainHandler.postDelayed({
                                    if (isSilentStreamingActive.get()) {
                                        captureFrame()
                                    }
                                }, 1500L)
                                return
                            }

                            // Code 3 = ERROR_TAKE_SCREENSHOT_INTERVAL_RIGID (OS rate limit)
                            if (errorCode == 3) {
                                currentIntervalMs = (currentIntervalMs + 15L).coerceAtMost(160L)
                            }
                            mainHandler.postDelayed({
                                if (isSilentStreamingActive.get()) {
                                    captureFrame()
                                }
                            }, currentIntervalMs)
                        }
                    }
                )
            } catch (e: Throwable) {
                Log.w(TAG, "takeScreenshot exception: ${e.message}")
                mainHandler.postDelayed({
                    if (isSilentStreamingActive.get()) captureFrame()
                }, 120L)
            }
        }

        mainHandler.post { captureFrame() }
        return true
    }

    fun stopSilentScreenStreaming() {
        isSilentStreamingActive.set(false)
        isSilentStreaming = false
        mainHandler.removeCallbacksAndMessages(null)
        streamingScope?.cancel()
        streamingScope = null
        Log.i(TAG, "Silent screen streaming stopped")
    }
}
