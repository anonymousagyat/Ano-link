package com.tailconnect.app

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvPcName: TextView
    private lateinit var tvTailscaleIp: TextView
    private lateinit var tvDaemonStatus: TextView
    private lateinit var tvTriadPing: TextView
    private lateinit var llDaemonStatusPill: View
    private lateinit var viewStatusDot: View
    private lateinit var viewPingLiveDot: View

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
        setupFeatureCards()
        PcAppsRepository.preload(this)
    }

    private fun initViews() {
        tvPcName = findViewById(R.id.tvPcName)
        tvTailscaleIp = findViewById(R.id.tvTailscaleIp)
        tvDaemonStatus = findViewById(R.id.tvDaemonStatus)
        tvTriadPing = findViewById(R.id.tvTriadPing)
        llDaemonStatusPill = findViewById(R.id.llDaemonStatusPill)
        viewStatusDot = findViewById(R.id.viewStatusDot)
        viewPingLiveDot = findViewById(R.id.viewPingLiveDot)

        // Settings Gear Icon (Opens Page 3: Settings & Permissions)
        findViewById<View>(R.id.btnOpenSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Tapping PC Card also allows configuring target PC
        findViewById<View>(R.id.llPcStatusCard)?.setOnClickListener {
            showEditPcIpDialog()
        }
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

        // Module 2: PC File Explorer (Opens ZArchiver-style Explorer)
        findViewById<View>(R.id.btnLaunchPcFiles).setOnClickListener {
            if (!PcRemoteClient.hasConfiguredPc(this)) {
                showEditPcIpDialog(showPromptNotice = true)
                return@setOnClickListener
            }
            startActivity(Intent(this, PcFilesActivity::class.java))
        }

        // Module 3: Start Menu & Windows
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
        dialog.findViewById<TextView>(R.id.tvHudStatusDetail)?.text = "Negotiating P2P UDP tunnel..."

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
        if (isConnected) {
            tvStatus?.text = "● P2P DIRECT ONLINE"
            tvStatus?.setTextColor(android.graphics.Color.parseColor("#10B981"))
        } else {
            tvStatus?.text = "● P2P OFFLINE"
            tvStatus?.setTextColor(android.graphics.Color.parseColor("#94A3B8"))
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

    private fun showSystemInfoDialog() {
        val pin = PcRemoteClient.getPin(this).ifBlank { "Not Configured" }
        android.app.AlertDialog.Builder(this)
            .setTitle("System Info & Telemetry")
            .setMessage("Ano-Link WebRTC Cross-Device Bridge\n\n• Protocol: Pure WebRTC P2P (UDP)\n• Mode: Direct P2P (LAN & WAN)\n• Background Power: <1% Battery/Hour\n• Paired PIN: $pin")
            .setPositiveButton("Configure PIN") { _, _ ->
                showEditPcIpDialog()
            }
            .setNegativeButton("OK", null)
            .show()
    }

    private fun updatePcCardUi() {
        val isPaired = PcRemoteClient.isPaired(this)
        if (isPaired) {
            val name = PcRemoteClient.getPairedPcName(this)
            tvPcName.text = name
            tvTailscaleIp.text = "Pure WebRTC P2P Direct"
        } else {
            tvPcName.text = "No PC Paired"
            tvTailscaleIp.text = "Tap to pair with 6-Digit PIN"
            tvDaemonStatus.text = "OFFLINE"
            tvDaemonStatus.setTextColor(android.graphics.Color.parseColor("#94A3B8"))
            llDaemonStatusPill.setBackgroundResource(R.drawable.bg_status_offline)
            viewStatusDot.setBackgroundResource(R.drawable.dot_status_offline)
            viewPingLiveDot.visibility = View.GONE
            tvTriadPing.text = "--"
            tvTriadPing.setTextColor(android.graphics.Color.parseColor("#94A3B8"))
        }
    }

    override fun onResume() {
        super.onResume()

        updatePcCardUi()

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

            // Real-time connection status listener (Strictly ONLINE / OFFLINE)
            PcRemoteClient.setConnectionStateListener { isConnected, rttMs ->
                runOnUiThread {
                    updatePcCardUi()
                    if (isConnected) {
                        dismissConnectingHud()
                        tvDaemonStatus.text = "ONLINE"
                        tvDaemonStatus.setTextColor(android.graphics.Color.parseColor("#10B981"))
                        llDaemonStatusPill.setBackgroundResource(R.drawable.bg_status_online)
                        viewStatusDot.setBackgroundResource(R.drawable.dot_status_online)
                        viewPingLiveDot.visibility = View.VISIBLE
                        tvTriadPing.text = if (rttMs > 0) "${rttMs}ms" else "<10ms"
                        tvTriadPing.setTextColor(android.graphics.Color.parseColor("#10B981"))
                        PcAppsRepository.preload(this@MainActivity)
                    } else {
                        tvDaemonStatus.text = "OFFLINE"
                        tvDaemonStatus.setTextColor(android.graphics.Color.parseColor("#94A3B8"))
                        llDaemonStatusPill.setBackgroundResource(R.drawable.bg_status_offline)
                        viewStatusDot.setBackgroundResource(R.drawable.dot_status_offline)
                        viewPingLiveDot.visibility = View.GONE
                        tvTriadPing.text = "--"
                        tvTriadPing.setTextColor(android.graphics.Color.parseColor("#94A3B8"))

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
        dismissConnectingHud()
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
        startForegroundService(intent)
    }
}
