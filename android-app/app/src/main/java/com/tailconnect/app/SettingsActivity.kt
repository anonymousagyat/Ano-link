package com.tailconnect.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.*
import java.net.NetworkInterface
import java.util.*

class SettingsActivity : AppCompatActivity() {

    // Target PC Configurations
    private lateinit var etPcHost: EditText
    private lateinit var etPcPort: EditText
    private lateinit var btnSavePcConfig: Button
    private lateinit var tvTestResult: TextView

    // PC To Phone Control Permission Items & Status Icons
    private lateinit var itemAccessibility: View
    private lateinit var ivStatusAccessibility: ImageView
    private lateinit var itemStorage: View
    private lateinit var ivStatusStorage: ImageView
    private lateinit var itemPhoneSms: View
    private lateinit var ivStatusPhoneSms: ImageView
    private lateinit var itemCameraMic: View
    private lateinit var ivStatusCameraMic: ImageView
    private lateinit var itemBattery: View
    private lateinit var ivStatusBattery: ImageView

    // Phone Daemon Status
    private lateinit var tvPhoneTailscaleIp: TextView
    private lateinit var tvPhoneDaemonState: TextView
    private lateinit var btnRestartDaemon: Button

    private val activityScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge: Cyber background stretches behind camera notch & nav bar
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        setContentView(R.layout.activity_settings)

        // Ensure header sits cleanly below camera notch
        val llSettingsContent = findViewById<View>(R.id.llSettingsContent)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootScrollSettings)) { _, insets ->
            val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout())
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val density = resources.displayMetrics.density
            val padH = (20 * density).toInt()
            val padTop = statusBar.top + (14 * density).toInt()
            val padBottom = navBar.bottom + (32 * density).toInt()
            llSettingsContent.setPadding(padH, padTop, padH, padBottom)
            insets
        }

        initViews()
        loadPcConfig()
        detectPhoneIp()
        updatePermissionStatus()
    }

    private fun initViews() {
        findViewById<View>(R.id.btnSettingsBack).setOnClickListener {
            finish()
        }

        // Target PC Configurations
        etPcHost = findViewById(R.id.etPcHost)
        etPcPort = findViewById(R.id.etPcPort)
        btnSavePcConfig = findViewById(R.id.btnSavePcConfig)
        tvTestResult = findViewById(R.id.tvTestResult)

        btnSavePcConfig.setOnClickListener {
            saveAndTestPcConfig()
        }

        // PC to Phone Control Permission Items
        itemAccessibility = findViewById(R.id.itemAccessibility)
        ivStatusAccessibility = findViewById(R.id.ivStatusAccessibility)
        itemStorage = findViewById(R.id.itemStorage)
        ivStatusStorage = findViewById(R.id.ivStatusStorage)
        itemPhoneSms = findViewById(R.id.itemPhoneSms)
        ivStatusPhoneSms = findViewById(R.id.ivStatusPhoneSms)
        itemCameraMic = findViewById(R.id.itemCameraMic)
        ivStatusCameraMic = findViewById(R.id.ivStatusCameraMic)
        itemBattery = findViewById(R.id.itemBattery)
        ivStatusBattery = findViewById(R.id.ivStatusBattery)

        itemAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Enable 'Ano-Link Remote Control' under Installed Services", Toast.LENGTH_LONG).show()
        }

        itemStorage.setOnClickListener {
            requestAllFilesAccess()
        }

        itemPhoneSms.setOnClickListener {
            requestPhoneSmsPermissions()
        }

        itemCameraMic.setOnClickListener {
            requestCameraMicPermissions()
        }

        itemBattery.setOnClickListener {
            requestIgnoreBatteryOptimization()
        }

        // Phone Daemon Status
        tvPhoneTailscaleIp = findViewById(R.id.tvPhoneTailscaleIp)
        tvPhoneDaemonState = findViewById(R.id.tvPhoneDaemonState)
        btnRestartDaemon = findViewById(R.id.btnRestartDaemon)

        btnRestartDaemon.setOnClickListener {
            restartDaemon()
            Toast.makeText(this, "Background daemon restarted on port 8081", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadPcConfig() {
        val currentHost = PcRemoteClient.getPcHost(this)
        etPcHost.setText(currentHost)
        etPcPort.setText("8080")
    }

    private fun saveAndTestPcConfig() {
        val host = etPcHost.text.toString().trim()
        val portStr = etPcPort.text.toString().trim()
        val port = portStr.toIntOrNull() ?: 8080

        if (host.isEmpty()) {
            Toast.makeText(this, "Please enter a valid PC IP", Toast.LENGTH_SHORT).show()
            return
        }

        PcRemoteClient.setPcHost(this, host, port)
        tvTestResult.text = "Testing connection to $host:$port..."
        tvTestResult.setTextColor(Color.parseColor("#60A5FA"))

        activityScope.launch(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            var reachable = false
            try {
                val socket = java.net.Socket()
                socket.connect(java.net.InetSocketAddress(host, port), 2500)
                socket.close()
                reachable = true
            } catch (_: Exception) {}

            val elapsed = System.currentTimeMillis() - startTime
            withContext(Dispatchers.Main) {
                if (reachable) {
                    tvTestResult.text = "Connected to PC (${elapsed} ms). Saved successfully!"
                    tvTestResult.setTextColor(Color.parseColor("#10B981"))
                    Toast.makeText(this@SettingsActivity, "PC Connection Verified!", Toast.LENGTH_SHORT).show()
                } else {
                    tvTestResult.text = "Could not reach PC at $host:$port (Timed out). Configuration saved."
                    tvTestResult.setTextColor(Color.parseColor("#EF4444"))
                }
            }
        }
    }

    private fun detectPhoneIp() {
        var ip: String? = null
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val name = intf.name.lowercase(Locale.ROOT)
                if (name.contains("tailscale") || name.contains("tun") || name.contains("vpn")) {
                    for (addr in Collections.list(intf.inetAddresses)) {
                        if (!addr.isLoopbackAddress && addr.hostAddress?.contains(".") == true) {
                            val hostAddr = addr.hostAddress ?: ""
                            if (hostAddr.startsWith("100.")) {
                                ip = hostAddr
                                break
                            }
                        }
                    }
                }
                if (ip != null) break
            }
        } catch (_: Exception) {}

        if (ip != null) {
            tvPhoneTailscaleIp.text = "Phone IP: $ip"
        } else {
            tvPhoneTailscaleIp.text = "Phone IP: (No active 100.x tunnel detected)"
        }
        tvPhoneDaemonState.text = "Port 8081: Standby Host Active"
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
        detectPhoneIp()
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }

    private fun updatePermissionStatus() {
        // 1. Accessibility Service
        val isA11yEnabled = RemoteAccessibilityService.instance != null
        ivStatusAccessibility.setImageResource(
            if (isA11yEnabled) R.drawable.ic_check_tick else R.drawable.ic_cross_mark
        )

        // 2. Storage
        val isStorageGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
        ivStatusStorage.setImageResource(
            if (isStorageGranted) R.drawable.ic_check_tick else R.drawable.ic_cross_mark
        )

        // 3. Phone & SMS
        val phonePermissions = listOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS
        )
        val isPhoneSmsGranted = phonePermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        ivStatusPhoneSms.setImageResource(
            if (isPhoneSmsGranted) R.drawable.ic_check_tick else R.drawable.ic_cross_mark
        )

        // 4. Camera & Mic
        val isCameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val isMicGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ivStatusCameraMic.setImageResource(
            if (isCameraGranted && isMicGranted) R.drawable.ic_check_tick else R.drawable.ic_cross_mark
        )

        // 5. Battery Optimization Exemption
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val isBatteryExempt = pm.isIgnoringBatteryOptimizations(packageName)
        ivStatusBattery.setImageResource(
            if (isBatteryExempt) R.drawable.ic_check_tick else R.drawable.ic_cross_mark
        )
    }

    private fun requestPhoneSmsPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS
        )
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1001)
        } else {
            Toast.makeText(this, "Phone & SMS permissions already granted", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestCameraMicPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1002)
        } else {
            Toast.makeText(this, "Camera & Microphone permissions already granted", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updatePermissionStatus()
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    val fallbackIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(fallbackIntent)
                }
            } else {
                Toast.makeText(this, "All Files Access already granted", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestIgnoreBatteryOptimization() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } else {
            Toast.makeText(this, "Battery Optimization already disabled", Toast.LENGTH_SHORT).show()
        }
    }

    private fun restartDaemon() {
        val intent = Intent(this, TailConnectService::class.java)
        stopService(intent)
        startForegroundService(intent)
    }
}
