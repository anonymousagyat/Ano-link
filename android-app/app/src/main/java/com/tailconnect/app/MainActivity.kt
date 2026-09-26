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

    private fun showEditPcIpDialog(showPromptNotice: Boolean = false) {
        val currentHost = PcRemoteClient.getPcHost(this)
        val input = EditText(this).apply {
            setText(currentHost)
            hint = "e.g. 192.168.1.50 or 100.x.y.z"
            setSingleLine(true)
            setPadding(48, 32, 48, 32)
        }

        val title = if (showPromptNotice || !PcRemoteClient.hasConfiguredPc(this)) {
            "Connect Your PC Device"
        } else {
            "Configure Target PC"
        }

        val message = if (showPromptNotice || !PcRemoteClient.hasConfiguredPc(this)) {
            "No PC device is added yet.\n\n1. Run the PC Controller on your Windows PC (run.bat)\n2. Enter the IP address shown in your PC console below:"
        } else {
            "Enter the Local Wi-Fi IP or Tailscale IP (100.x.y.z) of your Windows PC:"
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setView(input)
            .setPositiveButton("Save & Connect") { _, _ ->
                val newHost = input.text.toString().trim()
                if (newHost.isNotEmpty()) {
                    PcRemoteClient.setPcHost(this, newHost)
                    updatePcCardUi()
                    Toast.makeText(this, "Connecting to PC at $newHost:8080...", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun showSystemInfoDialog() {
        val pcHost = PcRemoteClient.getPcHost(this).ifBlank { "Not Configured" }
        AlertDialog.Builder(this)
            .setTitle("System Info & Telemetry")
            .setMessage("Ano-Link Cross-Device Bridge\n\n• Target PC: $pcHost\n• PC Address: $pcHost:8080\n• Phone Daemon: Port 8081 Active\n• Standby Power: <1% Battery/Hour")
            .setPositiveButton("Configure PC IP") { _, _ ->
                showEditPcIpDialog()
            }
            .setNegativeButton("OK", null)
            .show()
    }

    private fun updatePcCardUi() {
        val pcHost = PcRemoteClient.getPcHost(this)
        if (pcHost.isNotBlank()) {
            tvPcName.text = "Connected PC"
            tvTailscaleIp.text = "$pcHost:8080"
        } else {
            tvPcName.text = "No PC Connected"
            tvTailscaleIp.text = "Tap to add your PC IP"
            tvDaemonStatus.text = "NOT ADDED"
            tvDaemonStatus.setTextColor(android.graphics.Color.parseColor("#F43F5E"))
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
            PcAppsRepository.preload(this)

            // Connect PcRemoteClient to target PC
            PcRemoteClient.setConnectionStateListener { isConnected, rttMs ->
                runOnUiThread {
                    if (isConnected) {
                        tvDaemonStatus.text = "ONLINE"
                        tvDaemonStatus.setTextColor(android.graphics.Color.parseColor("#10B981"))
                        tvTriadPing.text = if (rttMs > 0) "${rttMs}ms" else "<10ms"
                        tvTriadPing.setTextColor(android.graphics.Color.parseColor("#10B981"))
                        PcAppsRepository.preload(this@MainActivity)
                    } else {
                        tvDaemonStatus.text = "STANDBY"
                        tvDaemonStatus.setTextColor(android.graphics.Color.parseColor("#FBBF24"))
                        tvTriadPing.text = "--"
                        tvTriadPing.setTextColor(android.graphics.Color.parseColor("#94A3B8"))
                    }
                }
            }
            PcRemoteClient.connect(this)
        }
    }

    override fun onPause() {
        super.onPause()
        PcRemoteClient.setConnectionStateListener(null)
    }

    private fun startDaemon() {
        val intent = Intent(this, TailConnectService::class.java)
        startForegroundService(intent)
    }
}
