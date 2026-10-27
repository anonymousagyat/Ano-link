package com.tailconnect.app

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private lateinit var tvPcName: TextView
    private lateinit var tvViewportStatus: TextView
    private lateinit var tvViewportLatency: TextView
    private lateinit var dotStatusJewel: View
    private lateinit var ivWallpaperA: ImageView
    private lateinit var ivWallpaperB: ImageView
    private lateinit var cardPcViewport: View

    private var wallpaperFiles = listOf<String>()
    private var currentWallpaperIdx = 0
    private var slideshowJob: Job? = null
    private var activeLayerIsA = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge: Cyber void background stretches behind the camera notch & navigation bar
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        setContentView(R.layout.activity_main)

        // Ensure header and content sit cleanly below the camera cutout/notch
        val rootScrollMain = findViewById<View>(R.id.rootScrollMain)
        val llMainContent = findViewById<View>(R.id.llMainContent)
        ViewCompat.setOnApplyWindowInsetsListener(rootScrollMain) { _, insets ->
            val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout())
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val density = resources.displayMetrics.density
            val padH = (20 * density).toInt()
            val padTop = statusBar.top + (14 * density).toInt()
            val padBottom = navBar.bottom + (24 * density).toInt()
            llMainContent.setPadding(padH, padTop, padH, padBottom)
            insets
        }

        initViews()
        startDaemon()
        checkNotificationPermission()
        checkBatteryOptimizationExemption()
        setupFeatureCards()
        PcAppsRepository.preload(this)
    }

    private fun initViews() {
        tvPcName = findViewById(R.id.tvPcName)
        tvViewportStatus = findViewById(R.id.tvViewportStatus)
        tvViewportLatency = findViewById(R.id.tvViewportLatency)
        dotStatusJewel = findViewById(R.id.dotStatusJewel)
        ivWallpaperA = findViewById(R.id.ivWallpaperA)
        ivWallpaperB = findViewById(R.id.ivWallpaperB)
        cardPcViewport = findViewById(R.id.cardPcViewport)

        // Settings Gear Icon (Opens Page 3: Settings & Permissions)
        findViewById<View>(R.id.btnOpenSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Tapping PC Viewport Card launches Remote Screen feature (Landscape Viewport)
        cardPcViewport.setOnClickListener {
            if (!PcRemoteClient.hasConfiguredPc(this)) {
                showEditPcIpDialog(showPromptNotice = true)
                return@setOnClickListener
            }
            startActivity(Intent(this, PcRemoteActivity::class.java))
        }

        setupWallpaperSlideshow()
    }

    private fun setupFeatureCards() {
        // Module 1: Remote Screen (Opens Page 2: Landscape Viewport)
        findViewById<View>(R.id.btnLaunchPcRemote).setOnClickListener {
            if (!PcRemoteClient.hasConfiguredPc(this)) {
                showEditPcIpDialog(showPromptNotice = true)
                return@setOnClickListener
            }
            startActivity(Intent(this, PcRemoteActivity::class.java))
        }

        // Module 2: PC File Explorer (ZArchiver-style Explorer)
        findViewById<View>(R.id.btnLaunchPcFiles).setOnClickListener {
            if (!PcRemoteClient.hasConfiguredPc(this)) {
                showEditPcIpDialog(showPromptNotice = true)
                return@setOnClickListener
            }
            startActivity(Intent(this, PcFilesActivity::class.java))
        }

        // Module 3: Start Menu & Windows (Windows Blue - User's Authentic Design)
        findViewById<View>(R.id.btnHubStartMenu).setOnClickListener {
            if (!PcRemoteClient.hasConfiguredPc(this)) {
                showEditPcIpDialog(showPromptNotice = true)
                return@setOnClickListener
            }
            startActivity(Intent(this, PcStartMenuActivity::class.java))
        }

        // Module 4: System Info & Hardware Task Manager
        findViewById<View>(R.id.btnHubSystemInfo).setOnClickListener {
            if (!PcRemoteClient.hasConfiguredPc(this)) {
                showEditPcIpDialog(showPromptNotice = true)
                return@setOnClickListener
            }
            startActivity(Intent(this, PcHardwareActivity::class.java))
        }
    }

    private var connectingHudDialog: android.app.Dialog? = null

    private fun showConnectingHud(pin: String) {
        if (isFinishing || isDestroyed) return
        if (connectingHudDialog?.isShowing == true) return

        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_connecting_hud)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.window?.setWindowAnimations(R.style.CyberDialogAnimation)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.90).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.setCancelable(true)

        dialog.findViewById<TextView>(R.id.tvHudPcName)?.text = PcRemoteClient.getPairedPcName(this)
        dialog.findViewById<TextView>(R.id.tvHudPin)?.text = pin
        dialog.findViewById<TextView>(R.id.tvHudStatusDetail)?.text = "Connecting to Cloudflare Quick Tunnel..."

        connectingHudDialog = dialog
        dialog.show()
    }

    private fun updateConnectingHudStatus(status: String) {
        connectingHudDialog?.findViewById<TextView>(R.id.tvHudStatusDetail)?.text = status
    }

    private fun dismissConnectingHud() {
        connectingHudDialog?.let {
            if (it.isShowing) {
                try { it.dismiss() } catch (_: Exception) {}
            }
        }
        connectingHudDialog = null
    }

    private fun showEditPcIpDialog(showPromptNotice: Boolean = false) {
        val currentPin = PcRemoteClient.getPin(this)
        val isPaired = PcRemoteClient.isPaired(this)

        if (isPaired && !showPromptNotice) {
            showPairedPcDetailsDialog()
        } else {
            promptPin(currentPin)
        }
    }

    private fun showPairedPcDetailsDialog() {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_paired_pc_details)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.window?.setWindowAnimations(R.style.CyberDialogAnimation)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.90).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val pcName = PcRemoteClient.getPairedPcName(this)
        val pin = PcRemoteClient.getPin(this)
        val isConnected = PcRemoteClient.isConnected()

        dialog.findViewById<TextView>(R.id.tvDetailsPcName)?.text = pcName
        dialog.findViewById<TextView>(R.id.tvDetailsPin)?.text = pin
        val tvStatus = dialog.findViewById<TextView>(R.id.tvDetailsStatusBadge)
        val tvDetailsProtocol = dialog.findViewById<TextView>(R.id.tvDetailsProtocol)
        val tvDetailsTunnelUrl = dialog.findViewById<TextView>(R.id.tvDetailsTunnelUrl)

        if (isConnected) {
            tvStatus?.text = "● ONLINE"
            tvStatus?.setTextColor(android.graphics.Color.parseColor("#10B981"))
            if (PcRemoteClient.isUsingTunnel()) {
                tvDetailsProtocol?.text = "Cloudflare Quick Tunnel (WSS)"
                val url = PcRemoteClient.getTunnelUrl()
                val host = try {
                    if (url.isNotBlank()) java.net.URI(url).host else null
                } catch (_: Exception) { null }
                tvDetailsTunnelUrl?.text = host ?: (if (url.isNotBlank()) url else "*.trycloudflare.com")
            } else {
                tvDetailsProtocol?.text = "Pure WebRTC UDP Direct"
                tvDetailsTunnelUrl?.text = "LAN Direct P2P"
            }
        } else {
            tvStatus?.text = "● OFFLINE"
            tvStatus?.setTextColor(android.graphics.Color.parseColor("#94A3B8"))
            tvDetailsProtocol?.text = "Standby"
            tvDetailsTunnelUrl?.text = "Not Connected"
        }

        dialog.findViewById<View>(R.id.btnDetailsChangePin)?.setOnClickListener {
            dialog.dismiss()
            promptPin(pin)
        }

        dialog.findViewById<View>(R.id.btnDetailsForgetPc)?.setOnClickListener {
            dialog.dismiss()
            dismissConnectingHud()
            PcRemoteClient.forgetDevice(this)
            updatePcCardUi()
            Toast.makeText(this, "Device unlinked. You can now pair with a new PIN.", Toast.LENGTH_SHORT).show()
        }

        dialog.findViewById<View>(R.id.btnDetailsClose)?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun promptPin(currentPin: String) {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_pair_pin)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.window?.setWindowAnimations(R.style.CyberDialogAnimation)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val box1 = dialog.findViewById<EditText>(R.id.etPin1) ?: return
        val box2 = dialog.findViewById<EditText>(R.id.etPin2) ?: return
        val box3 = dialog.findViewById<EditText>(R.id.etPin3) ?: return
        val box4 = dialog.findViewById<EditText>(R.id.etPin4) ?: return
        val box5 = dialog.findViewById<EditText>(R.id.etPin5) ?: return
        val box6 = dialog.findViewById<EditText>(R.id.etPin6) ?: return
        val boxes = listOf(box1, box2, box3, box4, box5, box6)
        val btnConnect = dialog.findViewById<Button>(R.id.btnDialogConnect)
        val btnCancel = dialog.findViewById<Button>(R.id.btnDialogCancel)

        // Pre-fill if currentPin has 6 digits
        if (currentPin.length == 6) {
            for (i in 0 until 6) {
                boxes[i].setText(currentPin[i].toString())
            }
        }

        // Auto-advance and backspace handling across 6 boxes
        for (i in 0 until 6) {
            val box = boxes[i]
            box.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (!s.isNullOrEmpty() && s.length == 1) {
                        if (i < 5) {
                            boxes[i + 1].requestFocus()
                        }
                    }
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })

            box.setOnKeyListener { _, keyCode, event ->
                if (event.action == android.view.KeyEvent.ACTION_DOWN && keyCode == android.view.KeyEvent.KEYCODE_DEL) {
                    if (box.text.isNullOrEmpty() && i > 0) {
                        boxes[i - 1].requestFocus()
                        boxes[i - 1].setText("")
                        return@setOnKeyListener true
                    }
                }
                false
            }
        }

        btnCancel?.setOnClickListener {
            dialog.dismiss()
        }

        val btnPasteTunnelUrl = dialog.findViewById<TextView>(R.id.btnPasteTunnelUrl)
        btnPasteTunnelUrl?.setOnClickListener {
            dialog.dismiss()
            promptDirectTunnelUrl()
        }

        btnConnect?.setOnClickListener {
            val pin = boxes.joinToString("") { it.text.toString().trim() }
            if (pin.length == 6) {
                dialog.dismiss()
                showConnectingHud(pin)
                PcRemoteClient.pairWithPin(this, pin) { success, msg ->
                    runOnUiThread {
                        updatePcCardUi()
                        if (success) {
                            dismissConnectingHud()
                            Toast.makeText(this@MainActivity, "🟢 $msg", Toast.LENGTH_LONG).show()
                        } else {
                            updateConnectingHudStatus("❌ $msg")
                            Toast.makeText(this@MainActivity, "❌ $msg", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } else {
                Toast.makeText(this, "Please enter all 6 digits", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
        if (currentPin.length == 6) {
            boxes[5].requestFocus()
        } else {
            boxes[0].requestFocus()
        }
    }

    private fun promptDirectTunnelUrl() {
        val input = EditText(this).apply {
            hint = "https://*.trycloudflare.com"
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.parseColor("#94A3B8"))
            setBackgroundResource(R.drawable.bg_pin_digit_box)
            setPadding(36, 28, 36, 28)
            isSingleLine = true
        }

        val container = android.widget.FrameLayout(this).apply {
            setPadding(48, 24, 48, 12)
            addView(input)
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("Connect via Cloudflare URL")
            .setMessage("Paste the public WAN URL from your PC terminal or Web Dashboard:")
            .setView(container)
            .setPositiveButton("Connect") { _, _ ->
                val url = input.text.toString().trim()
                if (url.startsWith("http://", ignoreCase = true) ||
                    url.startsWith("https://", ignoreCase = true) ||
                    url.contains(".trycloudflare.com", ignoreCase = true)) {
                    showConnectingHud("WAN URL")
                    PcRemoteClient.setupManagerCallbacks(this)
                    AnoWebRtcManager.connect(this, url)
                } else {
                    Toast.makeText(this, "Please enter a valid Cloudflare URL", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Back") { _, _ ->
                promptPin(PcRemoteClient.getPin(this))
            }
            .show()
    }

    private fun showSystemInfoDialog() {
        val pin = PcRemoteClient.getPin(this).ifBlank { "Not Configured" }
        android.app.AlertDialog.Builder(this)
            .setTitle("System Info & Telemetry")
            .setMessage("Ano-Link Cross-Device Bridge\n\n• WAN Engine: Cloudflare Quick Tunnel\n• Local Engine: WebRTC P2P (UDP)\n• Standby Power: <1% Battery/Hour\n• Paired PIN: $pin")
            .setPositiveButton("Configure PIN") { _, _ ->
                showEditPcIpDialog()
            }
            .setNegativeButton("OK", null)
            .show()
    }

    private fun setupWallpaperSlideshow() {
        try {
            wallpaperFiles = assets.list("wallpapers")?.filter {
                it.endsWith(".jpg", ignoreCase = true) ||
                it.endsWith(".png", ignoreCase = true) ||
                it.endsWith(".webp", ignoreCase = true)
            }?.sorted() ?: emptyList()
        } catch (_: Exception) {
            wallpaperFiles = emptyList()
        }

        if (wallpaperFiles.isNotEmpty()) {
            val firstFile = wallpaperFiles[0]
            lifecycleScope.launch(Dispatchers.IO) {
                val bitmap = decodeSampledBitmap("wallpapers/$firstFile", 1080, 600)
                withContext(Dispatchers.Main) {
                    if (bitmap != null && !isFinishing && !isDestroyed) {
                        ivWallpaperA.setImageBitmap(bitmap)
                        ivWallpaperA.alpha = 1f
                        ivWallpaperB.alpha = 0f
                        activeLayerIsA = true
                    }
                }
            }
            startSlideshowTimer()
        }
    }

    private fun startSlideshowTimer() {
        slideshowJob?.cancel()
        slideshowJob = lifecycleScope.launch {
            while (isActive) {
                delay(5 * 60 * 1000L) // Rotate every 5 minutes (300,000 ms)
                if (wallpaperFiles.isEmpty()) continue
                currentWallpaperIdx = (currentWallpaperIdx + 1) % wallpaperFiles.size
                val nextFile = wallpaperFiles[currentWallpaperIdx]
                advanceWallpaperCrossFade(nextFile)
            }
        }
    }

    private fun advanceWallpaperCrossFade(fileName: String) {
        val targetView = if (activeLayerIsA) ivWallpaperB else ivWallpaperA
        val currentView = if (activeLayerIsA) ivWallpaperA else ivWallpaperB

        lifecycleScope.launch(Dispatchers.IO) {
            val bitmap = decodeSampledBitmap("wallpapers/$fileName", 1080, 600)
            withContext(Dispatchers.Main) {
                if (bitmap != null && !isFinishing && !isDestroyed) {
                    targetView.setImageBitmap(bitmap)
                    targetView.animate().alpha(1f).setDuration(1000).start()
                    currentView.animate().alpha(0f).setDuration(1000).withEndAction {
                        activeLayerIsA = !activeLayerIsA
                    }.start()
                }
            }
        }
    }

    private fun decodeSampledBitmap(assetPath: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            assets.open(assetPath).use {
                BitmapFactory.decodeStream(it, null, options)
            }
            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.RGB_565
            assets.open(assetPath).use {
                BitmapFactory.decodeStream(it, null, options)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }

    private fun updatePcCardUi() {
        val isPaired = PcRemoteClient.isPaired(this)
        val isConnected = PcRemoteClient.isConnected()

        if (isPaired) {
            val name = PcRemoteClient.getPairedPcName(this)
            tvPcName.text = name
            if (isConnected) {
                tvViewportStatus.text = "Live"
                tvViewportStatus.setTextColor(Color.parseColor("#34D399"))
                dotStatusJewel.setBackgroundResource(R.drawable.bg_dot_emerald)
            } else {
                tvViewportStatus.text = "Offline"
                tvViewportStatus.setTextColor(Color.parseColor("#94A3B8"))
                tvViewportLatency.text = "--"
            }
        } else {
            tvPcName.text = "No PC Paired"
            tvViewportStatus.text = "Unpaired"
            tvViewportStatus.setTextColor(Color.parseColor("#94A3B8"))
            tvViewportLatency.text = "--"
        }
    }

    override fun onResume() {
        super.onResume()
        AnoWebRtcManager.isAppInForeground = true

        updatePcCardUi()
        if (wallpaperFiles.isNotEmpty() && (slideshowJob == null || slideshowJob?.isActive != true)) {
            startSlideshowTimer()
        }

        if (!PcRemoteClient.hasConfiguredPc(this)) {
            showEditPcIpDialog(showPromptNotice = true)
        } else {
            val pin = PcRemoteClient.getPin(this)

            // Dynamic live PC Name update
            PcRemoteClient.onPcNameListener = { pcName ->
                runOnUiThread {
                    tvPcName.text = pcName
                    connectingHudDialog?.findViewById<TextView>(R.id.tvHudPcName)?.text = pcName
                }
            }

            PcRemoteClient.onStatusListener = { status ->
                runOnUiThread {
                    updateConnectingHudStatus(status)
                }
            }

            if (!PcRemoteClient.isConnected()) {
                showConnectingHud(pin)
            }

            PcAppsRepository.preload(this)

            // Real-time connection status listener
            PcRemoteClient.setConnectionStateListener { isConnected, rttMs ->
                runOnUiThread {
                    updatePcCardUi()
                    if (isConnected) {
                        dismissConnectingHud()
                        tvViewportStatus.text = "Live"
                        tvViewportStatus.setTextColor(Color.parseColor("#34D399"))
                        dotStatusJewel.setBackgroundResource(R.drawable.bg_dot_emerald)
                        tvViewportLatency.text = if (rttMs > 0) "${rttMs}ms" else "<10ms"
                        PcAppsRepository.preload(this@MainActivity)
                    } else {
                        tvViewportStatus.text = "Offline"
                        tvViewportStatus.setTextColor(Color.parseColor("#94A3B8"))
                        tvViewportLatency.text = "--"

                        val pairedPin = PcRemoteClient.getPin(this@MainActivity)
                        if (pairedPin.isNotBlank()) {
                            showConnectingHud(pairedPin)
                            updateConnectingHudStatus("PC offline. Waiting for server...")
                        }
                    }
                }
            }
            PcRemoteClient.connect(this)
        }
    }

    override fun onPause() {
        super.onPause()
        AnoWebRtcManager.isAppInForeground = false
        dismissConnectingHud()
        slideshowJob?.cancel()
        PcRemoteClient.stopPingLoop()
        PcRemoteClient.setConnectionStateListener(null)
        PcRemoteClient.onPcNameListener = null
        PcRemoteClient.onStatusListener = null
    }

    override fun onDestroy() {
        super.onDestroy()
        // Do NOT notify PC of closing or disconnect here.
        // The background service (TailConnectService) keeps the WebRTC tunnel active in the background.
        // When reopened from recents or launcher, connection remains live with zero reconnect delay.
    }

    private fun startDaemon() {
        val intent = Intent(this, TailConnectService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Notice starting daemon: ${e.message}")
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 206)
            }
        }
    }

    private fun checkBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val prefs = getSharedPreferences("anolink_sentinel_prefs", Context.MODE_PRIVATE)
                val hasPrompted = prefs.getBoolean("battery_prompted", false)
                if (!hasPrompted) {
                    prefs.edit().putBoolean("battery_prompted", true).apply()
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    } catch (_: Exception) {}
                }
            }
        }
    }
}
