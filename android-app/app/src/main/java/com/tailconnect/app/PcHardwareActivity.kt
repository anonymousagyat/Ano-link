package com.tailconnect.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
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
        CPU, MEMORY, DISK, WIFI, PROCESSES
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

    private lateinit var tabProcesses: LinearLayout
    private lateinit var tvTabProcLabel: TextView
    private lateinit var tvTabProcVal: TextView

    // Views - Containers
    private lateinit var scrollHardwarePerformance: ScrollView
    private lateinit var containerProcessesView: FrameLayout

    // Views - Processes View
    private lateinit var etProcSearch: EditText
    private lateinit var btnProcSearchClear: TextView
    private lateinit var chipAll: TextView
    private lateinit var chipHighRam: TextView
    private lateinit var chipHighCpu: TextView
    private lateinit var chipUserApps: TextView
    private lateinit var tvProcSummaryCount: TextView
    private lateinit var tvProcSummaryRam: TextView
    private lateinit var rvProcesses: RecyclerView
    private lateinit var llProcEmptyState: LinearLayout
    private lateinit var particleOverlayView: ParticleDisintegrationView

    // State - Processes
    private var activeFilter = "all"
    private var searchQuery = ""
    private val allProcesses = mutableListOf<PcProcessItem>()
    private val displayedProcesses = mutableListOf<PcProcessItem>()
    private lateinit var processAdapter: PcProcessAdapter

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
            findViewById<View>(R.id.containerProcessesView)?.setPadding(0, 0, 0, navBar.bottom)
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
        if (currentCategory == HardwareCategory.PROCESSES) {
            PcRemoteClient.send("pc_get_processes")
            fetchProcessesRest()
        }
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

        tabProcesses = findViewById(R.id.tabProcesses)
        tvTabProcLabel = findViewById(R.id.tvTabProcLabel)
        tvTabProcVal = findViewById(R.id.tvTabProcVal)

        scrollHardwarePerformance = findViewById(R.id.scrollHardwarePerformance)
        containerProcessesView = findViewById(R.id.containerProcessesView)

        etProcSearch = findViewById(R.id.etProcSearch)
        btnProcSearchClear = findViewById(R.id.btnProcSearchClear)
        chipAll = findViewById(R.id.chipAll)
        chipHighRam = findViewById(R.id.chipHighRam)
        chipHighCpu = findViewById(R.id.chipHighCpu)
        chipUserApps = findViewById(R.id.chipUserApps)
        tvProcSummaryCount = findViewById(R.id.tvProcSummaryCount)
        tvProcSummaryRam = findViewById(R.id.tvProcSummaryRam)
        rvProcesses = findViewById(R.id.rvProcesses)
        llProcEmptyState = findViewById(R.id.llProcEmptyState)
        particleOverlayView = findViewById(R.id.particleOverlayView)

        processAdapter = PcProcessAdapter(
            displayedProcesses,
            onItemClick = { item, cardView, position ->
                performHaptic()
                showProcessInspectorDialog(item, cardView, position)
            },
            onEndTaskClick = { item, cardView, position ->
                terminateProcess(item, cardView)
            }
        )
        rvProcesses.layoutManager = LinearLayoutManager(this)
        rvProcesses.adapter = processAdapter

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

        tabProcesses.setOnClickListener {
            performHaptic()
            selectCategory(HardwareCategory.PROCESSES)
        }

        btnProcSearchClear.setOnClickListener {
            etProcSearch.setText("")
        }

        etProcSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString()?.trim()?.lowercase() ?: ""
                btnProcSearchClear.visibility = if (searchQuery.isNotEmpty()) View.VISIBLE else View.GONE
                applyProcessFilters()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        chipAll.setOnClickListener {
            performHaptic()
            setActiveChip("all")
        }
        chipHighRam.setOnClickListener {
            performHaptic()
            setActiveChip("high-ram")
        }
        chipHighCpu.setOnClickListener {
            performHaptic()
            setActiveChip("high-cpu")
        }
        chipUserApps.setOnClickListener {
            performHaptic()
            setActiveChip("apps")
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

    private fun setActiveChip(filter: String) {
        activeFilter = filter
        chipAll.setBackgroundResource(if (filter == "all") R.drawable.bg_filter_chip_active else R.drawable.bg_filter_chip_inactive)
        chipAll.setTextColor(if (filter == "all") Color.WHITE else Color.parseColor("#94A3B8"))

        chipHighRam.setBackgroundResource(if (filter == "high-ram") R.drawable.bg_filter_chip_active else R.drawable.bg_filter_chip_inactive)
        chipHighRam.setTextColor(if (filter == "high-ram") Color.WHITE else Color.parseColor("#94A3B8"))

        chipHighCpu.setBackgroundResource(if (filter == "high-cpu") R.drawable.bg_filter_chip_active else R.drawable.bg_filter_chip_inactive)
        chipHighCpu.setTextColor(if (filter == "high-cpu") Color.WHITE else Color.parseColor("#94A3B8"))

        chipUserApps.setBackgroundResource(if (filter == "apps") R.drawable.bg_filter_chip_active else R.drawable.bg_filter_chip_inactive)
        chipUserApps.setTextColor(if (filter == "apps") Color.WHITE else Color.parseColor("#94A3B8"))

        applyProcessFilters()
    }

    private fun applyProcessFilters() {
        val query = searchQuery.trim().lowercase()
        val filtered = allProcesses.filter { p ->
            val matchesQuery = query.isEmpty() ||
                    p.name.lowercase().contains(query) ||
                    p.exe.lowercase().contains(query) ||
                    p.pid.toString().contains(query)
            if (!matchesQuery) return@filter false

            if (activeFilter == "apps") p.type.equals("app", true) else true
        }

        val sorted = when (activeFilter) {
            "high-ram" -> filtered.sortedByDescending { it.mem }
            "high-cpu" -> filtered.sortedByDescending { it.cpu }
            else -> filtered
        }

        // Partition into Apps vs Background Processes
        val userApps = sorted.filter { it.type.equals("app", true) }
        val bgProcesses = if (activeFilter == "apps") emptyList() else sorted.filter { !it.type.equals("app", true) }

        val sectionedList = mutableListOf<PcProcessItem>()
        if (userApps.isNotEmpty()) {
            sectionedList.add(PcProcessItem.createHeader("APPS (${userApps.size})", isAppSection = true))
            sectionedList.addAll(userApps)
        }
        if (bgProcesses.isNotEmpty()) {
            sectionedList.add(PcProcessItem.createHeader("BACKGROUND PROCESSES (${bgProcesses.size})", isAppSection = false))
            sectionedList.addAll(bgProcesses)
        }

        displayedProcesses.clear()
        displayedProcesses.addAll(sectionedList)
        processAdapter.notifyDataSetChanged()

        updateProcessSummary(userApps.size + bgProcesses.size)
        llProcEmptyState.visibility = if (displayedProcesses.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateProcessSummary(matchedCount: Int = allProcesses.size) {
        tvProcSummaryCount.text = "SHOWING $matchedCount ACTIVE PROCESSES"
        val totalRamMb = allProcesses.sumOf { it.mem.toDouble() }
        val ramGb = totalRamMb / 1024.0
        tvProcSummaryRam.text = String.format("RAM: %.1f GB", ramGb)
    }

    private fun selectCategory(category: HardwareCategory) {
        currentCategory = category

        // Reset all tab backgrounds to inactive
        tabCpu.setBackgroundResource(R.drawable.bg_hardware_tab_inactive)
        tvTabCpuLabel.setTextColor(Color.parseColor("#94A3B8"))
        tvTabCpuVal.setTextColor(Color.parseColor("#94A3B8"))

        tabMemory.setBackgroundResource(R.drawable.bg_hardware_tab_inactive)
        tvTabMemLabel.setTextColor(Color.parseColor("#94A3B8"))
        tvTabMemVal.setTextColor(Color.parseColor("#94A3B8"))

        tabDisk.setBackgroundResource(R.drawable.bg_hardware_tab_inactive)
        tvTabDiskLabel.setTextColor(Color.parseColor("#94A3B8"))
        tvTabDiskVal.setTextColor(Color.parseColor("#94A3B8"))

        tabWifi.setBackgroundResource(R.drawable.bg_hardware_tab_inactive)
        tvTabWifiLabel.setTextColor(Color.parseColor("#94A3B8"))
        tvTabWifiVal.setTextColor(Color.parseColor("#94A3B8"))

        tabProcesses.setBackgroundResource(R.drawable.bg_hardware_tab_inactive)
        tvTabProcLabel.setTextColor(Color.parseColor("#94A3B8"))
        tvTabProcVal.setTextColor(Color.parseColor("#94A3B8"))

        if (category == HardwareCategory.PROCESSES) {
            scrollHardwarePerformance.visibility = View.GONE
            containerProcessesView.visibility = View.VISIBLE

            tabProcesses.setBackgroundResource(R.drawable.bg_hardware_tab_active)
            tvTabProcLabel.setTextColor(Color.parseColor("#FFFFFF"))
            tvTabProcVal.setTextColor(Color.parseColor("#F59E0B"))

            // Fetch live processes
            PcRemoteClient.send("pc_get_processes")
            fetchProcessesRest()
        } else {
            scrollHardwarePerformance.visibility = View.VISIBLE
            containerProcessesView.visibility = View.GONE

            when (category) {
                HardwareCategory.CPU -> {
                    tabCpu.setBackgroundResource(R.drawable.bg_hardware_tab_active)
                    tvTabCpuLabel.setTextColor(Color.parseColor("#FFFFFF"))
                    tvTabCpuVal.setTextColor(Color.parseColor("#F59E0B"))
                    hardwareGraphView.setHistory(cpuHistory.toList())
                }
                HardwareCategory.MEMORY -> {
                    tabMemory.setBackgroundResource(R.drawable.bg_hardware_tab_active)
                    tvTabMemLabel.setTextColor(Color.parseColor("#FFFFFF"))
                    tvTabMemVal.setTextColor(Color.parseColor("#F59E0B"))
                    hardwareGraphView.setHistory(memHistory.toList())
                }
                HardwareCategory.DISK -> {
                    tabDisk.setBackgroundResource(R.drawable.bg_hardware_tab_active)
                    tvTabDiskLabel.setTextColor(Color.parseColor("#FFFFFF"))
                    tvTabDiskVal.setTextColor(Color.parseColor("#F59E0B"))
                    hardwareGraphView.setHistory(diskHistory.toList())
                }
                HardwareCategory.WIFI -> {
                    tabWifi.setBackgroundResource(R.drawable.bg_hardware_tab_active)
                    tvTabWifiLabel.setTextColor(Color.parseColor("#FFFFFF"))
                    tvTabWifiVal.setTextColor(Color.parseColor("#F59E0B"))
                    hardwareGraphView.setHistory(wifiHistory.toList())
                }
                else -> {}
            }

            renderCurrentView()
        }
    }

    private fun fetchProcessesRest() {
        val tunnelUrl = PcRemoteClient.getTunnelUrl().trimEnd('/')
        if (tunnelUrl.isBlank()) return
        activityScope.launch(Dispatchers.IO) {
            try {
                val httpBase = if (tunnelUrl.startsWith("ws://", true)) tunnelUrl.replace("ws://", "http://", true)
                               else if (tunnelUrl.startsWith("wss://", true)) tunnelUrl.replace("wss://", "https://", true)
                               else tunnelUrl
                val url = URL("$httpBase/api/pc/processes")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 4000
                conn.requestMethod = "GET"
                val token = PcRemoteClient.getDeviceToken(this@PcHardwareActivity)
                if (token.isNotBlank()) {
                    conn.setRequestProperty("X-Device-Token", token)
                }

                if (conn.responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val jsonStr = reader.readText()
                    reader.close()
                    val json = gson.fromJson(jsonStr, JsonObject::class.java)
                    withContext(Dispatchers.Main) {
                        handleProcessesListPayload(json)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun handleProcessesListPayload(json: JsonObject) {
        val procArr = json.getAsJsonArray("processes") ?: return
        activityScope.launch(Dispatchers.Default) {
            val list = mutableListOf<PcProcessItem>()
            for (elem in procArr) {
                val obj = elem.asJsonObject
                val pid = obj.get("pid")?.asInt ?: 0
                val name = obj.get("name")?.asString ?: "Process"
                val exe = obj.get("exe")?.asString ?: "process.exe"
                val cpu = obj.get("cpu")?.asFloat ?: 0f
                val mem = obj.get("mem")?.asFloat ?: 0f
                val type = obj.get("type")?.asString ?: "app"
                val icon = obj.get("icon")?.asString

                val iconKey = exe.lowercase()
                var bmp: Bitmap? = PcProcessAdapter.iconCache.get(iconKey)
                if (bmp == null && !icon.isNullOrEmpty()) {
                    try {
                        val cleanBase64 = if (icon.contains(",")) icon.substringAfter(",") else icon
                        val bytes = Base64.decode(cleanBase64, Base64.DEFAULT)
                        bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) {
                            PcProcessAdapter.iconCache.put(iconKey, bmp)
                        }
                    } catch (_: Exception) {}
                }

                list.add(PcProcessItem(pid, name, exe, cpu, mem, type, icon, bmp))
            }

            withContext(Dispatchers.Main) {
                allProcesses.clear()
                allProcesses.addAll(list)
                tvTabProcVal.text = allProcesses.size.toString()
                chipAll.text = "All (${allProcesses.size})"
                applyProcessFilters()
            }
        }
    }

    private fun fetchInitialRestTelemetry() {
        val tunnelUrl = PcRemoteClient.getTunnelUrl().trimEnd('/')
        if (tunnelUrl.isBlank()) return
        activityScope.launch(Dispatchers.IO) {
            try {
                val httpBase = if (tunnelUrl.startsWith("ws://", true)) tunnelUrl.replace("ws://", "http://", true)
                               else if (tunnelUrl.startsWith("wss://", true)) tunnelUrl.replace("wss://", "https://", true)
                               else tunnelUrl
                val url = URL("$httpBase/api/pc/hardware")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 4000
                conn.requestMethod = "GET"
                val token = PcRemoteClient.getDeviceToken(this@PcHardwareActivity)
                if (token.isNotBlank()) {
                    conn.setRequestProperty("X-Device-Token", token)
                }

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
        } else if (type == "pc_processes_list") {
            activityScope.launch(Dispatchers.Main) {
                handleProcessesListPayload(json)
            }
        } else if (type == "pc_kill_result") {
            val success = json.get("success")?.asBoolean ?: true
            if (!success) {
                val err = json.get("error")?.asString ?: "Failed to terminate process"
                activityScope.launch(Dispatchers.Main) {
                    Toast.makeText(this@PcHardwareActivity, err, Toast.LENGTH_SHORT).show()
                }
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

        val procsCount = cpu?.get("processes")?.asInt ?: 0
        if (allProcesses.isEmpty() && procsCount > 0) {
            tvTabProcVal.text = procsCount.toString()
        }

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
            else -> {}
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
            else -> {}
        }
    }

    private fun terminateProcess(item: PcProcessItem, cardView: View) {
        performHaptic()
        particleOverlayView.startDissolve(cardView) {
            allProcesses.removeAll { it.pid == item.pid || (it.exe.isNotBlank() && it.exe.equals(item.exe, ignoreCase = true)) }
            applyProcessFilters()
        }

        val killMsg = JsonObject().apply {
            addProperty("action", "pc_kill_process")
            addProperty("pid", item.pid)
            addProperty("exe", item.exe)
        }
        PcRemoteClient.send(killMsg.toString())

        Toast.makeText(
            this@PcHardwareActivity,
            "Terminated ${item.name} • Freed ${item.mem.toInt()} MB",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showProcessInspectorDialog(item: PcProcessItem, cardView: View, position: Int) {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_process_inspector, null)
        dialog.setContentView(view)

        val ivIcon = view.findViewById<ImageView>(R.id.ivInspectorIcon)
        val tvTitle = view.findViewById<TextView>(R.id.tvInspectorTitle)
        val tvSubtitle = view.findViewById<TextView>(R.id.tvInspectorSubtitle)
        val tvCpu = view.findViewById<TextView>(R.id.tvInspectorCpu)
        val tvMem = view.findViewById<TextView>(R.id.tvInspectorMem)
        val tvType = view.findViewById<TextView>(R.id.tvInspectorType)
        val btnFocus = view.findViewById<View>(R.id.btnInspectorFocus)
        val btnEndTask = view.findViewById<View>(R.id.btnInspectorEndTask)

        tvTitle.text = item.name
        val exeDisplay = if (item.exe.isNotBlank()) item.exe else "${item.name}.exe"
        tvSubtitle.text = "$exeDisplay • PID ${item.pid}"

        tvCpu.text = String.format("%.1f%%", item.cpu)
        tvMem.text = if (item.mem >= 1000f) String.format("%.1f GB", item.mem / 1024f) else String.format("%.0f MB", item.mem)

        val isApp = item.type.equals("app", true)
        tvType.text = if (isApp) "USER APP" else "BACKGROUND"
        tvType.setTextColor(if (isApp) Color.parseColor("#10B981") else Color.parseColor("#94A3B8"))

        val iconKey = item.exe.lowercase()
        val bmp = item.iconBitmap ?: PcProcessAdapter.iconCache.get(iconKey)
        if (bmp != null) {
            ivIcon.setImageBitmap(bmp)
        } else {
            ivIcon.setImageResource(PcProcessAdapter.getVectorFallback(iconKey))
        }

        btnFocus.setOnClickListener {
            dialog.dismiss()
            performHaptic()
            val msg = JsonObject().apply {
                addProperty("action", "pc_focus_window")
                addProperty("pid", item.pid)
                addProperty("exe", item.exe)
            }
            PcRemoteClient.send(msg.toString())
            Toast.makeText(this@PcHardwareActivity, "Focusing ${item.name}...", Toast.LENGTH_SHORT).show()
        }

        btnEndTask.setOnClickListener {
            dialog.dismiss()
            terminateProcess(item, cardView)
        }

        dialog.show()
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
