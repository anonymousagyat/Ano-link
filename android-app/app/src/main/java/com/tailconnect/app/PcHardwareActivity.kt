package com.tailconnect.app

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class PcHardwareActivity : AppCompatActivity(), PcRemoteEventListener {

    private val TAG = "PcHardwareActivity"
    private val activityScope = CoroutineScope(Dispatchers.Main + Job())
    private val gson = Gson()

    enum class HardwareCategory {
        CPU, MEMORY, DISK, WIFI
    }

    // Views - Header
    private lateinit var btnBack: ImageButton
    private lateinit var tvLiveStatus: TextView

    // Views - Tabs
    private lateinit var tabCpu: LinearLayout
    private lateinit var tvTabCpuLabel: TextView
    private lateinit var tvTabCpuVal: TextView

    private lateinit var tabMemory: LinearLayout
    private lateinit var tvTabMemLabel: TextView
    private lateinit var tvTabMemVal: TextView

    private lateinit var tabDisk: LinearLayout
    private lateinit var tvTabDiskLabel: TextView
    private lateinit var tvTabDiskVal: TextView

    private lateinit var tabWifi: LinearLayout
    private lateinit var tvTabWifiLabel: TextView
    private lateinit var tvTabWifiVal: TextView

    // Views - Hero Performance Card
    private lateinit var tvHeroCategory: TextView
    private lateinit var tvHeroSubTitle: TextView
    private lateinit var tvHeroValue: TextView
    private lateinit var tvHeroSecondary: TextView
    private lateinit var hardwareGraphView: HardwareGraphView

    // Views - Specifications Grid
    private lateinit var tvSpecLabel1: TextView
    private lateinit var tvSpecVal1: TextView
    private lateinit var tvSpecLabel2: TextView
    private lateinit var tvSpecVal2: TextView
    private lateinit var tvSpecLabel3: TextView
    private lateinit var tvSpecVal3: TextView
    private lateinit var tvSpecLabel4: TextView
    private lateinit var tvSpecVal4: TextView
    private lateinit var tvSpecLabel5: TextView
    private lateinit var tvSpecVal5: TextView
    private lateinit var tvSpecLabel6: TextView
    private lateinit var tvSpecVal6: TextView

    // Views - Summary Tiles
    private lateinit var summaryTile1: LinearLayout
    private lateinit var tvSumTitle1: TextView
    private lateinit var tvSumVal1: TextView
    private lateinit var pbSum1: ProgressBar

    private lateinit var summaryTile2: LinearLayout
    private lateinit var tvSumTitle2: TextView
    private lateinit var tvSumVal2: TextView
    private lateinit var pbSum2: ProgressBar

    private lateinit var summaryTile3: LinearLayout
    private lateinit var tvSumTitle3: TextView
    private lateinit var tvSumVal3: TextView
    private lateinit var pbSum3: ProgressBar

    // State
    private var currentCategory = HardwareCategory.CPU

    // Telemetry History Buffers (60 points each)
    private val cpuHistory = ArrayDeque<Float>(List(60) { 15f })
    private val memHistory = ArrayDeque<Float>(List(60) { 50f })
    private val diskHistory = ArrayDeque<Float>(List(60) { 20f })
    private val wifiHistory = ArrayDeque<Float>(List(60) { 30f })

    // Latest Raw Data Cache
    private var latestData: JsonObject? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge: Background stretches behind camera notch & nav bar
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        setContentView(R.layout.activity_pc_hardware)

        val rlHardwareHeader = findViewById<View>(R.id.rlHardwareHeader)
        val llHardwareScrollContent = findViewById<View>(R.id.llHardwareScrollContent)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootLayoutHardware)) { _, insets ->
            val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout())
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val density = resources.displayMetrics.density
            val padTop = statusBar.top + (4 * density).toInt()
            rlHardwareHeader.setPadding(0, padTop, 0, 0)
            val padH = (16 * density).toInt()
            val padBottom = navBar.bottom + (24 * density).toInt()
            llHardwareScrollContent?.setPadding(padH, 0, padH, padBottom)
            insets
        }

        initViews()
        setupListeners()
        selectCategory(HardwareCategory.CPU)

        // Connect PcRemoteClient WebSocket & Register as listener
        PcRemoteClient.connect(this)
        PcRemoteClient.setListener(this)

        // Instant Initial REST Fetch
        fetchInitialRestTelemetry()

        // Request WebSocket Hardware Pulse
        PcRemoteClient.send("pc_get_hardware")
    }

    override fun onResume() {
        super.onResume()
        PcRemoteClient.setListener(this)
        PcRemoteClient.send("pc_get_hardware")
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }

    private fun initViews() {
        btnBack = findViewById(R.id.btnBack)
        tvLiveStatus = findViewById(R.id.tvLiveStatus)

        tabCpu = findViewById(R.id.tabCpu)
        tvTabCpuLabel = findViewById(R.id.tvTabCpuLabel)
        tvTabCpuVal = findViewById(R.id.tvTabCpuVal)

        tabMemory = findViewById(R.id.tabMemory)
        tvTabMemLabel = findViewById(R.id.tvTabMemLabel)
        tvTabMemVal = findViewById(R.id.tvTabMemVal)

        tabDisk = findViewById(R.id.tabDisk)
        tvTabDiskLabel = findViewById(R.id.tvTabDiskLabel)
        tvTabDiskVal = findViewById(R.id.tvTabDiskVal)

        tabWifi = findViewById(R.id.tabWifi)
        tvTabWifiLabel = findViewById(R.id.tvTabWifiLabel)
        tvTabWifiVal = findViewById(R.id.tvTabWifiVal)

        tvHeroCategory = findViewById(R.id.tvHeroCategory)
        tvHeroSubTitle = findViewById(R.id.tvHeroSubTitle)
        tvHeroValue = findViewById(R.id.tvHeroValue)
        tvHeroSecondary = findViewById(R.id.tvHeroSecondary)
        hardwareGraphView = findViewById(R.id.hardwareGraphView)

        tvSpecLabel1 = findViewById(R.id.tvSpecLabel1)
        tvSpecVal1 = findViewById(R.id.tvSpecVal1)
        tvSpecLabel2 = findViewById(R.id.tvSpecLabel2)
        tvSpecVal2 = findViewById(R.id.tvSpecVal2)
        tvSpecLabel3 = findViewById(R.id.tvSpecLabel3)
        tvSpecVal3 = findViewById(R.id.tvSpecVal3)
        tvSpecLabel4 = findViewById(R.id.tvSpecLabel4)
        tvSpecVal4 = findViewById(R.id.tvSpecVal4)
        tvSpecLabel5 = findViewById(R.id.tvSpecLabel5)
        tvSpecVal5 = findViewById(R.id.tvSpecVal5)
        tvSpecLabel6 = findViewById(R.id.tvSpecLabel6)
        tvSpecVal6 = findViewById(R.id.tvSpecVal6)

        summaryTile1 = findViewById(R.id.summaryTile1)
        tvSumTitle1 = findViewById(R.id.tvSumTitle1)
        tvSumVal1 = findViewById(R.id.tvSumVal1)
        pbSum1 = findViewById(R.id.pbSum1)

        summaryTile2 = findViewById(R.id.summaryTile2)
        tvSumTitle2 = findViewById(R.id.tvSumTitle2)
        tvSumVal2 = findViewById(R.id.tvSumVal2)
        pbSum2 = findViewById(R.id.pbSum2)

        summaryTile3 = findViewById(R.id.summaryTile3)
        tvSumTitle3 = findViewById(R.id.tvSumTitle3)
        tvSumVal3 = findViewById(R.id.tvSumVal3)
        pbSum3 = findViewById(R.id.pbSum3)
    }

    private fun setupListeners() {
        btnBack.setOnClickListener {
            performHaptic()
            finish()
        }

        tabCpu.setOnClickListener {
            performHaptic()
            selectCategory(HardwareCategory.CPU)
        }

        tabMemory.setOnClickListener {
            performHaptic()
            selectCategory(HardwareCategory.MEMORY)
        }

        tabDisk.setOnClickListener {
            performHaptic()
            selectCategory(HardwareCategory.DISK)
        }

        tabWifi.setOnClickListener {
            performHaptic()
            selectCategory(HardwareCategory.WIFI)
        }

        // Summary Tiles allow fast direct jump to that category
        summaryTile1.setOnClickListener {
            performHaptic()
            selectCategory(HardwareCategory.MEMORY)
        }

        summaryTile2.setOnClickListener {
            performHaptic()
            selectCategory(HardwareCategory.DISK)
        }

        summaryTile3.setOnClickListener {
            performHaptic()
            selectCategory(HardwareCategory.WIFI)
        }
    }

    private fun selectCategory(category: HardwareCategory) {
        currentCategory = category

        // Reset all tab backgrounds to inactive
        tabCpu.setBackgroundResource(R.drawable.bg_hardware_tab_inactive)
        tvTabCpuLabel.setTextColor(android.graphics.Color.parseColor("#94A3B8"))
        tvTabCpuVal.setTextColor(android.graphics.Color.parseColor("#94A3B8"))

        tabMemory.setBackgroundResource(R.drawable.bg_hardware_tab_inactive)
        tvTabMemLabel.setTextColor(android.graphics.Color.parseColor("#94A3B8"))
        tvTabMemVal.setTextColor(android.graphics.Color.parseColor("#94A3B8"))

        tabDisk.setBackgroundResource(R.drawable.bg_hardware_tab_inactive)
        tvTabDiskLabel.setTextColor(android.graphics.Color.parseColor("#94A3B8"))
        tvTabDiskVal.setTextColor(android.graphics.Color.parseColor("#94A3B8"))

        tabWifi.setBackgroundResource(R.drawable.bg_hardware_tab_inactive)
        tvTabWifiLabel.setTextColor(android.graphics.Color.parseColor("#94A3B8"))
        tvTabWifiVal.setTextColor(android.graphics.Color.parseColor("#94A3B8"))

        // Set active tab styling
        when (category) {
            HardwareCategory.CPU -> {
                tabCpu.setBackgroundResource(R.drawable.bg_hardware_tab_active)
                tvTabCpuLabel.setTextColor(android.graphics.Color.parseColor("#FFFFFF"))
                tvTabCpuVal.setTextColor(android.graphics.Color.parseColor("#F59E0B"))
                hardwareGraphView.setHistory(cpuHistory.toList())
            }
            HardwareCategory.MEMORY -> {
                tabMemory.setBackgroundResource(R.drawable.bg_hardware_tab_active)
                tvTabMemLabel.setTextColor(android.graphics.Color.parseColor("#FFFFFF"))
                tvTabMemVal.setTextColor(android.graphics.Color.parseColor("#F59E0B"))
                hardwareGraphView.setHistory(memHistory.toList())
            }
            HardwareCategory.DISK -> {
                tabDisk.setBackgroundResource(R.drawable.bg_hardware_tab_active)
                tvTabDiskLabel.setTextColor(android.graphics.Color.parseColor("#FFFFFF"))
                tvTabDiskVal.setTextColor(android.graphics.Color.parseColor("#F59E0B"))
                hardwareGraphView.setHistory(diskHistory.toList())
            }
            HardwareCategory.WIFI -> {
                tabWifi.setBackgroundResource(R.drawable.bg_hardware_tab_active)
                tvTabWifiLabel.setTextColor(android.graphics.Color.parseColor("#FFFFFF"))
                tvTabWifiVal.setTextColor(android.graphics.Color.parseColor("#F59E0B"))
                hardwareGraphView.setHistory(wifiHistory.toList())
            }
        }

        // Refresh Hero and Specs UI
        renderCurrentView()
    }

    private fun fetchInitialRestTelemetry() {
        activityScope.launch(Dispatchers.IO) {
            try {
                val host = PcRemoteClient.getPcHost(this@PcHardwareActivity)
                val url = URL("http://$host:8080/api/pc/hardware")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.requestMethod = "GET"

                if (conn.responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val jsonStr = reader.readText()
                    reader.close()
                    val json = gson.fromJson(jsonStr, JsonObject::class.java)
                    withContext(Dispatchers.Main) {
                        applyTelemetryData(json)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Initial REST fetch failed (will rely on WS): ${e.message}")
            }
        }
    }

    override fun onPcMessage(type: String, json: JsonObject) {
        if (type == "pc_hardware_stats") {
            val data = json.getAsJsonObject("data") ?: json
            activityScope.launch(Dispatchers.Main) {
                applyTelemetryData(data)
            }
        }
    }

    override fun onTaskbarApps(appsJson: String) {}
    override fun onPcScreenFrame(base64Jpeg: String) {}
    override fun onPcScreenBinaryFrame(jpegBytes: ByteArray) {}

    private fun applyTelemetryData(data: JsonObject) {
        latestData = data

        // 1. Extract CPU Stats
        val cpu = data.getAsJsonObject("cpu")
        val cpuPercent = cpu?.get("percent")?.asInt ?: 0
        tvTabCpuVal.text = "$cpuPercent%"
        appendHistory(cpuHistory, cpuPercent.toFloat())

        // 2. Extract Memory Stats
        val mem = data.getAsJsonObject("memory")
        val memPercent = mem?.get("percent")?.asInt ?: 0
        tvTabMemVal.text = "$memPercent%"
        appendHistory(memHistory, memPercent.toFloat())

        // 3. Extract Disk Stats
        val disk = data.getAsJsonObject("disk")
        val diskPercent = disk?.get("percent")?.asInt ?: 0
        tvTabDiskVal.text = "$diskPercent%"
        appendHistory(diskHistory, diskPercent.toFloat())

        // 4. Extract Wi-Fi Stats
        val wifi = data.getAsJsonObject("wifi")
        val wifiStatus = wifi?.get("status")?.asString ?: "Connected"
        tvTabWifiVal.text = if (wifiStatus.equals("Operational", true)) "ON" else "OFF"
        appendHistory(wifiHistory, if (wifiStatus.equals("Operational", true)) 65f else 0f)

        // 5. Update graph for active category
        when (currentCategory) {
            HardwareCategory.CPU -> hardwareGraphView.addPoint(cpuPercent.toFloat())
            HardwareCategory.MEMORY -> hardwareGraphView.addPoint(memPercent.toFloat())
            HardwareCategory.DISK -> hardwareGraphView.addPoint(diskPercent.toFloat())
            HardwareCategory.WIFI -> hardwareGraphView.addPoint(if (wifiStatus.equals("Operational", true)) 65f else 0f)
        }

        // 6. Update Bottom Summary Overview Cards
        val memUsed = mem?.get("usedGB")?.asString ?: "--"
        val memTotal = mem?.get("totalGB")?.asString ?: "--"
        tvSumVal1.text = "$memUsed / $memTotal GB ($memPercent%)"
        pbSum1.progress = memPercent

        val diskFree = disk?.get("freeGB")?.asString ?: "--"
        tvSumVal2.text = "$diskFree GB Free ($diskPercent%)"
        pbSum2.progress = diskPercent

        val wifiIp = wifi?.get("ip")?.asString ?: "192.168.1.2"
        tvSumVal3.text = "$wifiStatus ($wifiIp)"
        pbSum3.progress = 85

        // 7. Update Hero Card & Specifications Grid
        renderCurrentView()
    }

    private fun appendHistory(deque: ArrayDeque<Float>, value: Float) {
        if (deque.size >= 60) {
            deque.removeFirst()
        }
        deque.addLast(value)
    }

    private fun renderCurrentView() {
        val data = latestData ?: return

        when (currentCategory) {
            HardwareCategory.CPU -> {
                val cpu = data.getAsJsonObject("cpu")
                val percent = cpu?.get("percent")?.asInt ?: 0
                val speed = cpu?.get("speedGHz")?.asString ?: "--"
                val model = cpu?.get("model")?.asString ?: "Unknown Processor"
                val cores = cpu?.get("cores")?.asInt ?: 4
                val logical = cpu?.get("logicalProcessors")?.asInt ?: 4
                val processes = cpu?.get("processes")?.asInt ?: 180
                val threads = cpu?.get("threads")?.asInt ?: 2400
                val uptime = cpu?.get("uptime")?.asString ?: "--:--:--"

                tvHeroCategory.text = "CPU"
                tvHeroSubTitle.text = "% Utilization over 60 seconds"
                tvHeroValue.text = "$percent%"
                tvHeroSecondary.text = "$speed GHz"

                tvSpecLabel1.text = "Processor"
                tvSpecVal1.text = model

                tvSpecLabel2.text = "Base Speed"
                tvSpecVal2.text = "$speed GHz"

                tvSpecLabel3.text = "Cores / Logical"
                tvSpecVal3.text = "$cores ($logical Logical)"

                tvSpecLabel4.text = "Processes"
                tvSpecVal4.text = "$processes"

                tvSpecLabel5.text = "Threads"
                tvSpecVal5.text = "$threads"

                tvSpecLabel6.text = "Up Time"
                tvSpecVal6.text = uptime
            }
            HardwareCategory.MEMORY -> {
                val mem = data.getAsJsonObject("memory")
                val percent = mem?.get("percent")?.asInt ?: 0
                val used = mem?.get("usedGB")?.asString ?: "--"
                val total = mem?.get("totalGB")?.asString ?: "--"
                val free = mem?.get("freeGB")?.asString ?: "--"
                val speed = mem?.get("speedMHz")?.asString ?: "3200 MHz"
                val slots = mem?.get("slots")?.asString ?: "2 of 2"

                tvHeroCategory.text = "Memory"
                tvHeroSubTitle.text = "Memory usage over 60 seconds"
                tvHeroValue.text = "$percent%"
                tvHeroSecondary.text = "$used / $total GB"

                tvSpecLabel1.text = "In Use (Compressed)"
                tvSpecVal1.text = "$used GB"

                tvSpecLabel2.text = "Available"
                tvSpecVal2.text = "$free GB"

                tvSpecLabel3.text = "Total Committed"
                tvSpecVal3.text = "$total GB"

                tvSpecLabel4.text = "Speed"
                tvSpecVal4.text = speed

                tvSpecLabel5.text = "Slots Used"
                tvSpecVal5.text = slots

                tvSpecLabel6.text = "Form Factor"
                tvSpecVal6.text = "SODIMM / DIMM"
            }
            HardwareCategory.DISK -> {
                val disk = data.getAsJsonObject("disk")
                val percent = disk?.get("percent")?.asInt ?: 0
                val driveName = disk?.get("name")?.asString ?: "Local Disk (C:)"
                val total = disk?.get("totalGB")?.asString ?: "--"
                val used = disk?.get("usedGB")?.asString ?: "--"
                val free = disk?.get("freeGB")?.asString ?: "--"
                val fsType = disk?.get("fsType")?.asString ?: "NTFS"

                tvHeroCategory.text = "Disk"
                tvHeroSubTitle.text = "Active time over 60 seconds"
                tvHeroValue.text = "$percent%"
                tvHeroSecondary.text = "$free GB Free"

                tvSpecLabel1.text = "Drive Name"
                tvSpecVal1.text = driveName

                tvSpecLabel2.text = "Total Capacity"
                tvSpecVal2.text = "$total GB"

                tvSpecLabel3.text = "Used Space"
                tvSpecVal3.text = "$used GB"

                tvSpecLabel4.text = "Free Space"
                tvSpecVal4.text = "$free GB"

                tvSpecLabel5.text = "File System"
                tvSpecVal5.text = fsType

                tvSpecLabel6.text = "System Disk"
                tvSpecVal6.text = "Yes (Windows 11)"
            }
            HardwareCategory.WIFI -> {
                val wifi = data.getAsJsonObject("wifi")
                val name = wifi?.get("name")?.asString ?: "Wi-Fi"
                val ip = wifi?.get("ip")?.asString ?: "192.168.1.2"
                val type = wifi?.get("type")?.asString ?: "Wi-Fi (802.11ac)"
                val status = wifi?.get("status")?.asString ?: "Operational"

                tvHeroCategory.text = "Wi-Fi"
                tvHeroSubTitle.text = "Throughput over 60 seconds"
                tvHeroValue.text = "CONNECTED"
                tvHeroSecondary.text = ip

                tvSpecLabel1.text = "Adapter Name"
                tvSpecVal1.text = name

                tvSpecLabel2.text = "Connection Type"
                tvSpecVal2.text = type

                tvSpecLabel3.text = "IPv4 Address"
                tvSpecVal3.text = ip

                tvSpecLabel4.text = "Link Status"
                tvSpecVal4.text = status

                tvSpecLabel5.text = "Band / Channel"
                tvSpecVal5.text = "5 GHz (80 MHz)"

                tvSpecLabel6.text = "Security Type"
                tvSpecVal6.text = "WPA2-Personal"
            }
        }
    }

    private fun performHaptic() {
        try {
            val vibrator = getSystemService(Vibrator::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(12)
            }
        } catch (_: Exception) {}
    }
}
