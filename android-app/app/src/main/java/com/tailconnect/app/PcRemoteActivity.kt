package com.tailconnect.app

import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.children
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import java.util.*

class PcRemoteActivity : AppCompatActivity(), PcRemoteEventListener {

    private val TAG = "PcRemoteActivity"
    private val gson = Gson()
    private val activityScope = CoroutineScope(Dispatchers.Main + Job())

    // Views
    private lateinit var tvLivePing: TextView
    private lateinit var btnToggleScreenStream: View
    private lateinit var ivStreamStatusIcon: ImageView
    private lateinit var tvStreamStatusText: TextView
    private lateinit var ivPcScreen: ImageView
    private lateinit var flPcViewport: FrameLayout
    private lateinit var llViewportStandbyOverlay: LinearLayout
    private lateinit var btnOverlayResumeStream: Button
    private lateinit var ivVirtualCursor: ImageView
    private lateinit var llTaskbarApps: LinearLayout
    private lateinit var llTabsFlyout: LinearLayout
    private lateinit var tvFlyoutAppName: TextView
    private lateinit var llFlyoutTabsList: LinearLayout
    private lateinit var llWindowsKeyboard: LinearLayout
    private lateinit var tvStickyStatus: TextView
    private lateinit var etKbTextInput: EditText
    private lateinit var btnKbSend: Button
    private lateinit var btnCloseWinKb: TextView
    private lateinit var tvTrayClock: TextView
    private lateinit var ivTrayBattery: ImageView
    private lateinit var ivTrayBatteryCharging: ImageView
    private lateinit var tvTrayBattery: TextView
    private lateinit var btnStreamQuality: View
    private lateinit var tvStreamQualityText: TextView
    private lateinit var btnBackToHome: View
    private lateinit var btnTopKeyboardToggle: View

    // State
    private var isScreenStreaming = false
    private var streamInactivityJob: Job? = null
    private var taskbarSyncJob: Job? = null
    private val activeStickyKeys = mutableSetOf<String>()
    private val modifierButtons = mutableMapOf<String, MutableList<Button>>()

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false
    private var cursorX = 0f
    private var cursorY = 0f
    private var isCursorInitialized = false

    private var currentQualityIndex = 0
    private val qualityPresets = listOf(
        Triple("540p", 960, 540),
        Triple("480p", 854, 480),
        Triple("720p", 1280, 720)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemUI()
        setContentView(R.layout.activity_pc_remote)

        initViews()
        setupTouchViewport()
        setupRailControls()
        setupWindowsKeyboard()
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    private fun getCursorCenterOffset(): Float {
        return (ivVirtualCursor.width.takeIf { it > 0 }?.toFloat() ?: (24f * resources.displayMetrics.density)) / 2f
    }

    private fun initViews() {
        tvLivePing = findViewById(R.id.tvLivePing)
        btnToggleScreenStream = findViewById(R.id.btnToggleScreenStream)
        ivStreamStatusIcon = findViewById(R.id.ivStreamStatusIcon)
        tvStreamStatusText = findViewById(R.id.tvStreamStatusText)
        btnStreamQuality = findViewById(R.id.btnStreamQuality)
        tvStreamQualityText = findViewById(R.id.tvStreamQualityText)
        btnBackToHome = findViewById(R.id.btnBackToHome)
        btnTopKeyboardToggle = findViewById(R.id.btnTopKeyboardToggle)
        ivPcScreen = findViewById(R.id.ivPcScreen)
        flPcViewport = findViewById(R.id.flPcViewport)
        llViewportStandbyOverlay = findViewById(R.id.llViewportStandbyOverlay)
        btnOverlayResumeStream = findViewById(R.id.btnOverlayResumeStream)
        ivVirtualCursor = findViewById(R.id.ivVirtualCursor)
        llTaskbarApps = findViewById(R.id.llTaskbarApps)
        llTabsFlyout = findViewById(R.id.llTabsFlyout)
        tvFlyoutAppName = findViewById(R.id.tvFlyoutAppName)
        llFlyoutTabsList = findViewById(R.id.llFlyoutTabsList)
        llWindowsKeyboard = findViewById(R.id.llWindowsKeyboard)
        tvStickyStatus = findViewById(R.id.tvStickyStatus)
        etKbTextInput = findViewById(R.id.etKbTextInput)
        btnKbSend = findViewById(R.id.btnKbSend)
        btnCloseWinKb = findViewById(R.id.btnCloseWinKb)
        tvTrayClock = findViewById(R.id.tvTrayClock)
        ivTrayBattery = findViewById(R.id.ivTrayBattery)
        ivTrayBatteryCharging = findViewById(R.id.ivTrayBatteryCharging)
        tvTrayBattery = findViewById(R.id.tvTrayBattery)

        updateSystemTrayClock()

        btnStreamQuality.setOnClickListener {
            currentQualityIndex = (currentQualityIndex + 1) % qualityPresets.size
            val preset = qualityPresets[currentQualityIndex]
            tvStreamQualityText.text = preset.first.uppercase()
            if (isScreenStreaming) {
                PcRemoteClient.send("start_pc_screen_stream", mapOf(
                    "fps" to 15,
                    "width" to preset.second,
                    "height" to preset.third
                ))
            }
            showShortToast("Quality: ${preset.first} (${preset.second}x${preset.third})")
        }

        findViewById<View>(R.id.btnDockStartMenu)?.setOnClickListener {
            PcRemoteClient.sendKey("win")
            showShortToast("Windows Start Menu")
        }

        btnKbSend.setOnClickListener {
            sendKeyboardTextInput()
        }

        etKbTextInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendKeyboardTextInput()
                true
            } else false
        }

        btnCloseWinKb.setOnClickListener {
            llWindowsKeyboard.visibility = View.GONE
        }

        findViewById<TextView>(R.id.btnCloseFlyout).setOnClickListener {
            llTabsFlyout.visibility = View.GONE
        }
    }

    private fun updateSystemTrayClock() {
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        tvTrayClock.text = sdf.format(java.util.Date())
    }

    private fun sendKeyboardTextInput() {
        val text = etKbTextInput.text.toString()
        if (text.isNotBlank()) {
            PcRemoteClient.sendType(text)
            etKbTextInput.setText("")
            showShortToast("Typed to PC: \"$text\"")
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()

        // Cancel background 5-minute auto-stop countdown if user returned
        streamInactivityJob?.cancel()
        streamInactivityJob = null

        // 1. Register with PcRemoteClient
        PcRemoteClient.setListener(this)
        PcRemoteClient.setConnectionStateListener { isConnected, rttMs ->
            activityScope.launch {
                if (isConnected) {
                    if (rttMs > 0) {
                        tvLivePing.text = "● ${rttMs}ms"
                        tvLivePing.setTextColor(
                            when {
                                rttMs < 60 -> android.graphics.Color.parseColor("#10B981")
                                rttMs < 150 -> android.graphics.Color.parseColor("#00F2FE")
                                else -> android.graphics.Color.parseColor("#FBBF24")
                            }
                        )
                    } else {
                        tvLivePing.text = "● 0ms"
                        tvLivePing.setTextColor(android.graphics.Color.parseColor("#10B981"))
                    }
                } else {
                    tvLivePing.text = "● Standby"
                    tvLivePing.setTextColor(android.graphics.Color.parseColor("#94A3B8"))
                }
            }
        }
        PcRemoteClient.connect(this)

        if (isScreenStreaming) {
            // Activate Exclusive Mode (locks PC web dashboard while Phone controls PC)
            PcRemoteClient.send("exclusive_mode", mapOf("active" to true))
            TailConnectService.sendToPc(mapOf("action" to "exclusive_mode", "active" to true))

            val preset = qualityPresets[currentQualityIndex]
            PcRemoteClient.send("start_pc_screen_stream", mapOf(
                "fps" to 15,
                "width" to preset.second,
                "height" to preset.third
            ))
            llViewportStandbyOverlay.visibility = View.GONE
            updateStreamToggleUI(true)
        } else {
            llViewportStandbyOverlay.visibility = View.VISIBLE
            updateStreamToggleUI(false)
        }

        // 2. Also register with TailConnectService as secondary listener
        TailConnectService.setPcRemoteListener(this)
        TailConnectService.sendToPc(mapOf("action" to "pc_get_taskbar"))

        // 3. Immediately request taskbar from PC and start background sync loop
        PcRemoteClient.send("pc_get_taskbar")
        taskbarSyncJob?.cancel()
        taskbarSyncJob = activityScope.launch {
            while (isActive) {
                if (PcRemoteClient.isConnected()) {
                    PcRemoteClient.send("pc_get_taskbar")
                }
                delay(3500)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        taskbarSyncJob?.cancel()
        taskbarSyncJob = null

        // If app is closed or backgrounded, start 5-minute auto-stop countdown
        streamInactivityJob?.cancel()
        streamInactivityJob = CoroutineScope(Dispatchers.Main).launch {
            delay(300_000L) // 5 minutes
            Log.i(TAG, "5 minutes auto-stop triggered while app was backgrounded")
            toggleScreenStreaming(forceStop = true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        streamInactivityJob?.cancel()
        streamInactivityJob = null

        // Explicit exit: immediately stop streaming & release exclusive mode so PC dashboard is freed
        PcRemoteClient.stopStream()
        PcRemoteClient.send("exclusive_mode", mapOf("active" to false))
        TailConnectService.sendToPc(mapOf("action" to "stop_pc_screen_stream"))
        TailConnectService.sendToPc(mapOf("action" to "exclusive_mode", "active" to false))

        PcRemoteClient.setListener(null)
        PcRemoteClient.setConnectionStateListener(null)
        TailConnectService.setPcRemoteListener(null)
        activityScope.cancel()
    }

    private fun animateCursorClick() {
        val offset = getCursorCenterOffset()
        ivVirtualCursor.pivotX = offset
        ivVirtualCursor.pivotY = offset
        ivVirtualCursor.animate()
            .scaleX(0.75f)
            .scaleY(0.75f)
            .setDuration(60)
            .withEndAction {
                ivVirtualCursor.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(120)
                    .setInterpolator(android.view.animation.OvershootInterpolator(2.0f))
                    .start()
            }
            .start()
    }

    private fun getScreenRenderBounds(): RectF {
        val vw = ivPcScreen.width.toFloat()
        val vh = ivPcScreen.height.toFloat()
        if (vw <= 0f || vh <= 0f) return RectF(0f, 0f, 1f, 1f)

        val drawable = ivPcScreen.drawable
        val dw = drawable?.intrinsicWidth?.toFloat() ?: 16f
        val dh = drawable?.intrinsicHeight?.toFloat() ?: 9f
        if (dw <= 0f || dh <= 0f) return RectF(0f, 0f, vw, vh)

        val scale = Math.min(vw / dw, vh / dh)
        val rw = dw * scale
        val rh = dh * scale
        val left = ivPcScreen.left.toFloat() + (vw - rw) / 2f
        val top = ivPcScreen.top.toFloat() + (vh - rh) / 2f
        return RectF(left, top, left + rw, top + rh)
    }

    // -------------------------------------------------------------
    // Direct Touch & Trackpad Engine on Centered 16:9 PC Screen
    // -------------------------------------------------------------
    private fun setupTouchViewport() {
        flPcViewport.post {
            val bounds = getScreenRenderBounds()
            if (!isCursorInitialized && bounds.width() > 0 && bounds.height() > 0) {
                cursorX = bounds.centerX()
                cursorY = bounds.centerY()
                val offset = getCursorCenterOffset()
                ivVirtualCursor.pivotX = offset
                ivVirtualCursor.pivotY = offset
                ivVirtualCursor.translationX = cursorX - offset
                ivVirtualCursor.translationY = cursorY - offset
                isCursorInitialized = true
            }
        }

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // Precision Pinpoint Click at the crosshair (+) center
                val bounds = getScreenRenderBounds()
                if (bounds.width() > 0 && bounds.height() > 0) {
                    val nx = ((cursorX - bounds.left) / bounds.width()).coerceIn(0f, 1f)
                    val ny = ((cursorY - bounds.top) / bounds.height()).coerceIn(0f, 1f)
                    animateCursorClick()
                    PcRemoteClient.sendTap(nx, ny, "left", "click")
                } else {
                    animateCursorClick()
                    PcRemoteClient.sendClick("left", "click")
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                // Precision Pinpoint Double Click at the crosshair (+) center
                val bounds = getScreenRenderBounds()
                if (bounds.width() > 0 && bounds.height() > 0) {
                    val nx = ((cursorX - bounds.left) / bounds.width()).coerceIn(0f, 1f)
                    val ny = ((cursorY - bounds.top) / bounds.height()).coerceIn(0f, 1f)
                    animateCursorClick()
                    PcRemoteClient.sendTap(nx, ny, "left", "double")
                } else {
                    animateCursorClick()
                    PcRemoteClient.sendClick("left", "double")
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                // Precision Pinpoint Right-Click at the crosshair (+) center
                val bounds = getScreenRenderBounds()
                if (bounds.width() > 0 && bounds.height() > 0) {
                    val nx = ((cursorX - bounds.left) / bounds.width()).coerceIn(0f, 1f)
                    val ny = ((cursorY - bounds.top) / bounds.height()).coerceIn(0f, 1f)
                    animateCursorClick()
                    PcRemoteClient.sendTap(nx, ny, "right", "click")
                    showShortToast("Right Click")
                } else {
                    animateCursorClick()
                    PcRemoteClient.sendClick("right", "click")
                }
            }
        })

        flPcViewport.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)

            val pointerCount = event.pointerCount

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                    isDragging = true
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (pointerCount == 2) {
                        lastTouchY = (event.getY(0) + event.getY(1)) / 2f
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    if (pointerCount == 1) {
                        // Smooth Trackpad Cursor Movement: slide finger anywhere to move virtual cursor
                        val dx = (event.x - lastTouchX) * 1.35f
                        val dy = (event.y - lastTouchY) * 1.35f
                        lastTouchX = event.x
                        lastTouchY = event.y

                        val bounds = getScreenRenderBounds()
                        if (bounds.width() > 0 && bounds.height() > 0) {
                            cursorX = (cursorX + dx).coerceIn(bounds.left, bounds.right)
                            cursorY = (cursorY + dy).coerceIn(bounds.top, bounds.bottom)
                            val offset = getCursorCenterOffset()
                            ivVirtualCursor.translationX = cursorX - offset
                            ivVirtualCursor.translationY = cursorY - offset
                        }
                    } else if (pointerCount == 2) {
                        // Two-finger scroll
                        val currentMidY = (event.getY(0) + event.getY(1)) / 2f
                        val deltaY = (currentMidY - lastTouchY) * 2.5f
                        lastTouchY = currentMidY

                        if (Math.abs(deltaY) > 1f) {
                            PcRemoteClient.sendScroll((deltaY * 4).toInt())
                        }
                    }
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    if (pointerCount == 2) {
                        val bounds = getScreenRenderBounds()
                        if (bounds.width() > 0 && bounds.height() > 0) {
                            val nx = ((cursorX - bounds.left) / bounds.width()).coerceIn(0f, 1f)
                            val ny = ((cursorY - bounds.top) / bounds.height()).coerceIn(0f, 1f)
                            animateCursorClick()
                            PcRemoteClient.sendTap(nx, ny, "right", "click")
                        } else {
                            animateCursorClick()
                            PcRemoteClient.sendClick("right", "click")
                        }
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDragging = false
                }
            }
            true
        }
    }

    // -------------------------------------------------------------
    // Left Flank Rail Controls
    // -------------------------------------------------------------
    private fun setupRailControls() {
        btnBackToHome.setOnClickListener {
            finish()
        }

        btnToggleScreenStream.setOnClickListener {
            toggleScreenStreaming()
        }

        btnOverlayResumeStream.setOnClickListener {
            toggleScreenStreaming(forceStop = false)
        }

        btnTopKeyboardToggle.setOnClickListener {
            toggleKeyboardVisibility()
        }

        findViewById<View>(R.id.btnFlankAltTab).setOnClickListener {
            PcRemoteClient.sendHotkey(listOf("alt", "tab"))
            showShortToast("Alt + Tab sent")
        }

        findViewById<View>(R.id.btnFlankDesktop).setOnClickListener {
            PcRemoteClient.sendHotkey(listOf("win", "d"))
            showShortToast("Desktop toggled")
        }

        findViewById<View>(R.id.btnFlankPower).setOnClickListener {
            showPowerOptionsDialog()
        }
    }

    private fun updateStreamToggleUI(isStreaming: Boolean) {
        if (isStreaming) {
            btnToggleScreenStream.setBackgroundResource(R.drawable.bg_rail_tile_stream_stop)
            ivStreamStatusIcon.setImageResource(R.drawable.ic_stop)
            ivStreamStatusIcon.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#F43F5E"))
            tvStreamStatusText.text = "STOP"
            tvStreamStatusText.setTextColor(android.graphics.Color.parseColor("#F43F5E"))
        } else {
            btnToggleScreenStream.setBackgroundResource(R.drawable.bg_rail_tile_stream_start)
            ivStreamStatusIcon.setImageResource(R.drawable.ic_play)
            ivStreamStatusIcon.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#10B981"))
            tvStreamStatusText.text = "START"
            tvStreamStatusText.setTextColor(android.graphics.Color.parseColor("#10B981"))
        }
    }

    private fun toggleScreenStreaming(forceStop: Boolean? = null) {
        val shouldStop = forceStop ?: isScreenStreaming
        if (shouldStop) {
            isScreenStreaming = false
            updateStreamToggleUI(false)
            llViewportStandbyOverlay.visibility = View.VISIBLE
            PcRemoteClient.stopStream()
            PcRemoteClient.send("exclusive_mode", mapOf("active" to false))
            TailConnectService.sendToPc(mapOf("action" to "stop_pc_screen_stream"))
            TailConnectService.sendToPc(mapOf("action" to "exclusive_mode", "active" to false))
            showShortToast("PC Screen stopped • PC dashboard unlocked")
        } else {
            isScreenStreaming = true
            updateStreamToggleUI(true)
            llViewportStandbyOverlay.visibility = View.GONE
            PcRemoteClient.send("exclusive_mode", mapOf("active" to true))
            TailConnectService.sendToPc(mapOf("action" to "exclusive_mode", "active" to true))
            val preset = qualityPresets[currentQualityIndex]
            PcRemoteClient.send("start_pc_screen_stream", mapOf(
                "fps" to 15,
                "width" to preset.second,
                "height" to preset.third
            ))
            showShortToast("PC Screen streaming • PC dashboard locked")
        }
    }

    // -------------------------------------------------------------
    // Keyboard Engines
    // -------------------------------------------------------------
    private fun toggleKeyboardVisibility() {
        val visible = llWindowsKeyboard.visibility == View.VISIBLE
        llWindowsKeyboard.visibility = if (visible) View.GONE else View.VISIBLE
        if (!visible) {
            etKbTextInput.requestFocus()
        }
    }

    private fun setupWindowsKeyboard() {
        activeStickyKeys.clear()
        modifierButtons.clear()
        bindKeyboardButtons(llWindowsKeyboard)
        updateStickyStatus()
    }

    private fun toggleStickyKey(key: String) {
        if (activeStickyKeys.contains(key)) {
            activeStickyKeys.remove(key)
            modifierButtons[key]?.forEach { btn ->
                btn.backgroundTintList = null
                btn.setBackgroundResource(R.drawable.bg_kb_key_modifier)
                btn.setTextColor(android.graphics.Color.parseColor("#94A3B8"))
            }
        } else {
            activeStickyKeys.add(key)
            modifierButtons[key]?.forEach { btn ->
                btn.backgroundTintList = null
                btn.setBackgroundResource(R.drawable.bg_kb_key_sticky_active)
                btn.setTextColor(android.graphics.Color.parseColor("#10B981"))
                btn.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            }
        }
        updateStickyStatus()
    }

    private fun clearStickyKeys(preserveCaps: Boolean = true) {
        val toRemove = activeStickyKeys.filter { !preserveCaps || it != "caps" }
        for (key in toRemove) {
            activeStickyKeys.remove(key)
            modifierButtons[key]?.forEach { btn ->
                btn.backgroundTintList = null
                btn.setBackgroundResource(R.drawable.bg_kb_key_modifier)
                btn.setTextColor(android.graphics.Color.parseColor("#94A3B8"))
            }
        }
        updateStickyStatus()
    }

    private fun updateStickyStatus() {
        if (activeStickyKeys.isNotEmpty()) {
            val chain = activeStickyKeys.map { it.uppercase() }.sorted().joinToString(" + ")
            tvStickyStatus.text = "STICKY: $chain + ..."
        } else {
            tvStickyStatus.text = ""
        }
    }

    private fun bindKeyboardButtons(viewGroup: ViewGroup) {
        val modifierTags = setOf("alt", "ctrl", "shift", "win", "caps")
        for (child in viewGroup.children) {
            if (child is Button && child.tag != null) {
                child.backgroundTintList = null
                val key = child.tag.toString().lowercase()
                if (modifierTags.contains(key)) {
                    val list = modifierButtons.getOrPut(key) { mutableListOf() }
                    list.add(child)
                    child.setOnClickListener {
                        child.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        if (activeStickyKeys.contains(key)) {
                            // If already sticky, normal tap untoggles/clears it
                            toggleStickyKey(key)
                        } else {
                            // Normal tap: send the key directly to PC without latching sticky mode
                            PcRemoteClient.sendKey(key)
                        }
                    }
                    child.setOnLongClickListener {
                        // Long press: latch sticky modifier mode
                        toggleStickyKey(key)
                        true
                    }
                } else {
                    child.setOnClickListener {
                        child.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        if (activeStickyKeys.isNotEmpty()) {
                            val combo = (activeStickyKeys + key).toList()
                            PcRemoteClient.sendHotkey(combo)
                            showShortToast("Shortcut: " + combo.joinToString(" + ") { it.uppercase() })
                            clearStickyKeys(preserveCaps = true)
                        } else {
                            PcRemoteClient.sendKey(key)
                        }
                    }
                }
            } else if (child is ViewGroup) {
                bindKeyboardButtons(child)
            }
        }
    }


    // -------------------------------------------------------------
    // Power Options Modal
    // -------------------------------------------------------------
    private fun showPowerOptionsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_pc_power, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        dialogView.findViewById<View>(R.id.btnClosePowerDialog)?.setOnClickListener {
            dialog.dismiss()
        }

        dialogView.findViewById<View>(R.id.itemPowerLock)?.setOnClickListener {
            dialog.dismiss()
            PcRemoteClient.sendPower("lock")
            showShortToast("Lock command sent")
        }

        dialogView.findViewById<View>(R.id.itemPowerSleep)?.setOnClickListener {
            dialog.dismiss()
            PcRemoteClient.sendPower("sleep")
            showShortToast("Sleep command sent")
        }

        dialogView.findViewById<View>(R.id.itemPowerRestart)?.setOnClickListener {
            dialog.dismiss()
            PcRemoteClient.sendPower("restart")
            showShortToast("Restart command sent")
        }

        dialogView.findViewById<View>(R.id.itemPowerShutdown)?.setOnClickListener {
            dialog.dismiss()
            PcRemoteClient.sendPower("shutdown")
            showShortToast("Shutdown command sent")
        }

        dialog.show()
    }

    // -------------------------------------------------------------
    // PcRemoteEventListener: Incoming Data from PC
    // -------------------------------------------------------------
    override fun onTaskbarApps(appsJson: String) {
        activityScope.launch {
            try {
                val root = gson.fromJson(appsJson, JsonObject::class.java)
                val apps = root.getAsJsonArray("apps") ?: return@launch
                renderTaskbarDock(apps)
            } catch (e: Exception) {
                Log.w(TAG, "Error parsing taskbar apps: ${e.message}")
            }
        }
    }

    private fun renderTaskbarDock(apps: JsonArray) {
        llTaskbarApps.removeAllViews()

        for (elem in apps) {
            val appObj = elem.asJsonObject
            val appName = appObj.get("name")?.asString ?: "App"
            val count = appObj.get("count")?.asInt ?: 1
            val iconBase64 = appObj.get("icon")?.asString ?: ""
            val windows = appObj.getAsJsonArray("windows") ?: JsonArray()

            val card = createDockAppCard(appName, count, iconBase64, windows)
            llTaskbarApps.addView(card)
        }
    }

    private fun createDockAppCard(appName: String, count: Int, iconBase64: String, windows: JsonArray): View {
        val density = resources.displayMetrics.density
        val tileSize = (48 * density).toInt()

        val card = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.bg_rail_tile)
            isClickable = true
            isFocusable = true
            val params = LinearLayout.LayoutParams(tileSize, tileSize).apply {
                bottomMargin = (6 * density).toInt()
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
            layoutParams = params
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            layoutParams = lp
        }

        val ivIcon = ImageView(this).apply {
            val iconSize = (20 * density).toInt()
            val p = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                gravity = android.view.Gravity.CENTER
            }
            layoutParams = p
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        if (iconBase64.isNotBlank()) {
            try {
                val bytes = Base64.decode(iconBase64, Base64.DEFAULT)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) {
                    ivIcon.setImageBitmap(bmp)
                } else {
                    ivIcon.setImageResource(getAppLogoDrawable(appName))
                }
            } catch (e: Exception) {
                ivIcon.setImageResource(getAppLogoDrawable(appName))
            }
        } else {
            ivIcon.setImageResource(getAppLogoDrawable(appName))
        }
        content.addView(ivIcon)

        val tvName = TextView(this).apply {
            text = appName
            textSize = 7.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.parseColor("#94A3B8"))
            gravity = android.view.Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            val p = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (2 * density).toInt()
                leftMargin = (2 * density).toInt()
                rightMargin = (2 * density).toInt()
            }
            layoutParams = p
        }
        content.addView(tvName)
        card.addView(content)

        if (count > 1) {
            val badgeSize = (14 * density).toInt()
            val tvBadge = TextView(this).apply {
                text = "$count"
                textSize = 7.5f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(android.graphics.Color.WHITE)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(android.graphics.Color.parseColor("#A855F7"))
                }
                gravity = android.view.Gravity.CENTER
                val p = FrameLayout.LayoutParams(badgeSize, badgeSize).apply {
                    gravity = android.view.Gravity.TOP or android.view.Gravity.END
                    topMargin = (2 * density).toInt()
                    rightMargin = (2 * density).toInt()
                }
                layoutParams = p
            }
            card.addView(tvBadge)
        }

        card.setOnClickListener {
            if (count == 1 && windows.size() > 0) {
                val hwnd = windows[0].asJsonObject.get("hwnd").asLong
                PcRemoteClient.focusWindow(hwnd)
                llTabsFlyout.visibility = View.GONE
                showShortToast("Switched to $appName")
            } else if (count > 1) {
                showTabsFlyout(appName, windows)
            }
        }

        return card
    }

    private fun showTabsFlyout(appName: String, windows: JsonArray) {
        tvFlyoutAppName.text = "$appName (${windows.size()} Open Windows)"
        llFlyoutTabsList.removeAllViews()

        for (elem in windows) {
            val winObj = elem.asJsonObject
            val hwnd = winObj.get("hwnd").asLong
            val title = winObj.get("title")?.asString ?: appName

            val btnTab = Button(this).apply {
                text = title
                textSize = 11f
                setTextColor(android.graphics.Color.parseColor("#F8FAFC"))
                setBackgroundColor(android.graphics.Color.parseColor("#0F172A"))
                gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                setPadding(16, 10, 16, 10)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, 6)
                }
                layoutParams = params

                setOnClickListener {
                    PcRemoteClient.focusWindow(hwnd)
                    llTabsFlyout.visibility = View.GONE
                    showShortToast("Focused: $title")
                }
            }
            llFlyoutTabsList.addView(btnTab)
        }

        llTabsFlyout.visibility = View.VISIBLE
    }

    private fun getAppLogoDrawable(appName: String): Int {
        val lower = appName.lowercase(Locale.ROOT)
        return when {
            lower.contains("brave") -> R.drawable.ic_brand_brave
            lower.contains("chrome") -> R.drawable.ic_brand_chrome
            lower.contains("code") -> R.drawable.ic_brand_vscode
            lower.contains("explorer") || lower.contains("file") -> R.drawable.ic_brand_explorer
            lower.contains("spotify") -> R.drawable.ic_brand_spotify
            lower.contains("terminal") || lower.contains("powershell") || lower.contains("cmd") -> R.drawable.ic_brand_terminal
            lower.contains("discord") -> R.drawable.ic_brand_discord
            lower.contains("steam") -> R.drawable.ic_brand_steam
            lower.contains("notepad") -> R.drawable.ic_brand_notepad
            else -> R.drawable.ic_brand_generic
        }
    }

    override fun onPcScreenBinaryFrame(jpegBytes: ByteArray) {
        if (!isScreenStreaming) return
        activityScope.launch(Dispatchers.Default) {
            try {
                val bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                if (bmp != null) {
                    withContext(Dispatchers.Main) {
                        if (!isScreenStreaming) return@withContext
                        ivPcScreen.setImageBitmap(bmp)
                        findViewById<View>(R.id.tvViewportHint)?.visibility = View.GONE
                        if (!isCursorInitialized) {
                            val bounds = getScreenRenderBounds()
                            if (bounds.width() > 0 && bounds.height() > 0) {
                                cursorX = bounds.centerX()
                                cursorY = bounds.centerY()
                                val offset = getCursorCenterOffset()
                                ivVirtualCursor.pivotX = offset
                                ivVirtualCursor.pivotY = offset
                                ivVirtualCursor.translationX = cursorX - offset
                                ivVirtualCursor.translationY = cursorY - offset
                                isCursorInitialized = true
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Drop corrupted frame
            }
        }
    }

    override fun onPcScreenFrame(base64Jpeg: String) {
        if (!isScreenStreaming) return
        activityScope.launch(Dispatchers.Default) {
            try {
                val bytes = Base64.decode(base64Jpeg, Base64.DEFAULT)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) {
                    withContext(Dispatchers.Main) {
                        if (!isScreenStreaming) return@withContext
                        ivPcScreen.setImageBitmap(bmp)
                        findViewById<View>(R.id.tvViewportHint)?.visibility = View.GONE
                        if (!isCursorInitialized) {
                            val bounds = getScreenRenderBounds()
                            if (bounds.width() > 0 && bounds.height() > 0) {
                                cursorX = bounds.centerX()
                                cursorY = bounds.centerY()
                                val offset = getCursorCenterOffset()
                                ivVirtualCursor.pivotX = offset
                                ivVirtualCursor.pivotY = offset
                                ivVirtualCursor.translationX = cursorX - offset
                                ivVirtualCursor.translationY = cursorY - offset
                                isCursorInitialized = true
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Drop frame
            }
        }
    }

    override fun onPcMessage(type: String, json: JsonObject) {
        activityScope.launch {
            when (type) {
                "power_status" -> {
                    val action = json.get("action")?.asString ?: "power"
                    showShortToast("PC Power: $action executed")
                }
                "pc_battery_status" -> {
                    val percent = json.get("percent")?.asInt ?: 100
                    val isCharging = json.get("isCharging")?.asBoolean ?: false
                    updateBatteryUi(percent, isCharging)
                }
                "pc_hardware_stats" -> {
                    val data = json.getAsJsonObject("data")
                    if (data != null && data.has("battery")) {
                        val bat = data.getAsJsonObject("battery")
                        val percent = bat.get("percent")?.asInt ?: 100
                        val isCharging = bat.get("isCharging")?.asBoolean ?: false
                        updateBatteryUi(percent, isCharging)
                    }
                }
            }
        }
    }

    private fun updateBatteryUi(percent: Int, isCharging: Boolean) {
        tvTrayBattery.text = "$percent%"
        ivTrayBatteryCharging.visibility = if (isCharging) View.VISIBLE else View.GONE
        val batColor = when {
            percent <= 20 && !isCharging -> android.graphics.Color.parseColor("#F43F5E")
            percent <= 40 && !isCharging -> android.graphics.Color.parseColor("#FBBF24")
            else -> android.graphics.Color.parseColor("#34D399")
        }
        tvTrayBattery.setTextColor(batColor)
        ivTrayBattery.setColorFilter(batColor)
    }

    private fun showShortToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
