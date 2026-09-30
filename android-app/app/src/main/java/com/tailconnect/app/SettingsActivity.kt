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
import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.view.Window
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.*
import java.net.NetworkInterface
import java.util.*

class SettingsActivity : AppCompatActivity() {

    // Target PC Configurations (WebRTC P2P)
    private lateinit var tvSettingsPcName: TextView
    private lateinit var tvSettingsPin: TextView
    private lateinit var tvSettingsStatus: TextView
    private lateinit var tvWebRtcBadge: TextView
    private lateinit var btnChangePin: Button
    private lateinit var btnForgetPc: Button
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

        // Target PC Configurations (WebRTC P2P)
        tvSettingsPcName = findViewById(R.id.tvSettingsPcName)
        tvSettingsPin = findViewById(R.id.tvSettingsPin)
        tvSettingsStatus = findViewById(R.id.tvSettingsStatus)
        tvWebRtcBadge = findViewById(R.id.tvWebRtcBadge)
        btnChangePin = findViewById(R.id.btnChangePin)
        btnForgetPc = findViewById(R.id.btnForgetPc)
        tvTestResult = findViewById(R.id.tvTestResult)

        btnChangePin.setOnClickListener {
            showPinDialog()
        }

        btnForgetPc.setOnClickListener {
            PcRemoteClient.forgetDevice(this)
            loadPcConfig()
            Toast.makeText(this, "PC unlinked. Enter PIN to connect again.", Toast.LENGTH_SHORT).show()
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
        val pin = PcRemoteClient.getPin(this)
        val isPaired = PcRemoteClient.isPaired(this)
        val pcName = PcRemoteClient.getPairedPcName(this)
        val isConnected = PcRemoteClient.isConnected()

        if (isPaired) {
            tvSettingsPcName.text = pcName
            tvSettingsPin.text = pin
            if (isConnected) {
                tvSettingsStatus.text = "ONLINE (P2P Direct)"
                tvSettingsStatus.setTextColor(Color.parseColor("#10B981"))
                tvWebRtcBadge.text = "CONNECTED"
                tvWebRtcBadge.setTextColor(Color.parseColor("#10B981"))
            } else {
                tvSettingsStatus.text = "OFFLINE"
                tvSettingsStatus.setTextColor(Color.parseColor("#94A3B8"))
                tvWebRtcBadge.text = "OFFLINE"
                tvWebRtcBadge.setTextColor(Color.parseColor("#94A3B8"))
            }
        } else {
            tvSettingsPcName.text = "No PC Paired"
            tvSettingsPin.text = "------"
            tvSettingsStatus.text = "NOT PAIRED"
            tvSettingsStatus.setTextColor(Color.parseColor("#F43F5E"))
            tvWebRtcBadge.text = "DISCONNECTED"
            tvWebRtcBadge.setTextColor(Color.parseColor("#F43F5E"))
        }
    }

    private fun showPinDialog() {
        val currentPin = PcRemoteClient.getPin(this)
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_pair_pin)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
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

        if (currentPin.length == 6) {
            for (i in 0 until 6) {
                boxes[i].setText(currentPin[i].toString())
            }
        }

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
                Toast.makeText(this, "Connecting WebRTC P2P (PIN: $pin)...", Toast.LENGTH_SHORT).show()
                PcRemoteClient.pairWithPin(this, pin) { success, msg ->
                    runOnUiThread {
                        loadPcConfig()
                        if (success) {
                            Toast.makeText(this@SettingsActivity, "🟢 $msg", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this@SettingsActivity, "❌ $msg", Toast.LENGTH_LONG).show()
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
        tvPhoneDaemonState.text = "Port 8081: WebRTC Service Active"
    }

    override fun onResume() {
        super.onResume()
        loadPcConfig()
        updatePermissionStatus()
        detectPhoneIp()

        PcRemoteClient.onPcNameListener = {
            runOnUiThread { loadPcConfig() }
        }
        PcRemoteClient.setConnectionStateListener { _, _ ->
            runOnUiThread { loadPcConfig() }
        }
    }

    override fun onPause() {
        super.onPause()
        PcRemoteClient.setConnectionStateListener(null)
        PcRemoteClient.onPcNameListener = null
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
