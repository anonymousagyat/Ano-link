package com.tailconnect.app

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.JsonObject
import kotlinx.coroutines.*

class PcStartMenuActivity : AppCompatActivity(), PcRemoteEventListener {

    private val TAG = "PcStartMenuActivity"
    private val activityScope = CoroutineScope(Dispatchers.Main + Job())

    // Views
    private lateinit var btnBack: ImageButton
    private lateinit var editSearchApp: EditText
    private lateinit var btnClearSearch: ImageView
    private lateinit var btnToggleViewMode: ImageButton
    private lateinit var recyclerApps: RecyclerView
    private lateinit var gridLayoutManager: GridLayoutManager
    private lateinit var appAdapter: UnifiedAppAdapter

    // State
    private var isGridView = true // Default mode: Grid View (2-column tactile big-logo blocks)
    private val allApps = mutableListOf<StartAppItem>()
    private val displayApps = mutableListOf<StartAppItem>()

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

        setContentView(R.layout.activity_pc_start_menu)

        val rlStartMenuHeader = findViewById<View>(R.id.rlStartMenuHeader)
        val recyclerApps = findViewById<View>(R.id.recyclerApps)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootLayoutStartMenu)) { _, insets ->
            val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout())
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val density = resources.displayMetrics.density
            val padTop = statusBar.top + (4 * density).toInt()
            rlStartMenuHeader.setPadding(0, padTop, 0, 0)
            recyclerApps.setPadding((10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt(), navBar.bottom + (24 * density).toInt())
            insets
        }

        initViews()
        setupSearch()
        loadInitialApps()
        observeAppUpdates()

        // Ensure background preload is active
        PcAppsRepository.preload(this)

        // Connect PcRemoteClient WebSocket for instant 1-tap launching
        PcRemoteClient.connect(this)
        PcRemoteClient.setListener(this)
    }

    override fun onResume() {
        super.onResume()
        PcRemoteClient.setListener(this)
    }

    private fun initViews() {
        btnBack = findViewById(R.id.btnBack)
        editSearchApp = findViewById(R.id.editSearchApp)
        btnClearSearch = findViewById(R.id.btnClearSearch)
        btnToggleViewMode = findViewById(R.id.btnToggleViewMode)
        recyclerApps = findViewById(R.id.recyclerApps)

        btnBack.setOnClickListener {
            performHaptic()
            finish()
        }

        btnClearSearch.setOnClickListener {
            performHaptic()
            editSearchApp.text.clear()
        }

        btnToggleViewMode.setOnClickListener {
            performHaptic()
            toggleViewMode()
        }

        gridLayoutManager = GridLayoutManager(this, 2)
        recyclerApps.layoutManager = gridLayoutManager

        appAdapter = UnifiedAppAdapter(displayApps) { app, itemView ->
            animateTileClick(itemView)
            performHaptic()
            launchPcApp(app.name, app.path)
        }
        recyclerApps.adapter = appAdapter

        updateToggleIcon()
    }

    private fun toggleViewMode() {
        isGridView = !isGridView
        gridLayoutManager.spanCount = if (isGridView) 2 else 1
        updateToggleIcon()
        appAdapter.notifyDataSetChanged()
    }

    private fun updateToggleIcon() {
        if (isGridView) {
            btnToggleViewMode.setImageResource(R.drawable.ic_view_list)
        } else {
            btnToggleViewMode.setImageResource(R.drawable.ic_view_grid)
        }
    }

    private fun loadInitialApps() {
        val cached = PcAppsRepository.appsList
        allApps.clear()
        allApps.addAll(cached)
        refreshDisplayList()
    }

    private fun observeAppUpdates() {
        activityScope.launch {
            PcAppsRepository.appsFlow.collect { updatedList ->
                allApps.clear()
                allApps.addAll(updatedList)
                refreshDisplayList()
            }
        }
    }

    private fun setupSearch() {
        editSearchApp.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim() ?: ""
                btnClearSearch.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
                filterApps(query)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun filterApps(query: String) {
        displayApps.clear()
        if (query.isEmpty()) {
            displayApps.addAll(allApps)
        } else {
            val cleanQuery = query.lowercase().trim()
            for (app in allApps) {
                val exeName = app.path.substringAfterLast('\\').substringAfterLast('/').substringBeforeLast('.').lowercase().trim()
                val nameMatch = app.name.lowercase().contains(cleanQuery)
                val exeMatch = exeName.isNotEmpty() && exeName.contains(cleanQuery)
                val catMatch = app.category.lowercase().contains(cleanQuery)
                if (nameMatch || exeMatch || catMatch) {
                    displayApps.add(app)
                }
            }
        }
        appAdapter.notifyDataSetChanged()
    }

    private fun refreshDisplayList() {
        val query = editSearchApp.text.toString().trim()
        filterApps(query)
    }

    private fun animateTileClick(view: View) {
        view.animate()
            .scaleX(0.93f)
            .scaleY(0.93f)
            .setDuration(90)
            .withEndAction {
                view.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(160)
                    .setInterpolator(OvershootInterpolator(2.0f))
                    .start()
            }
            .start()
    }

    private fun launchPcApp(name: String, path: String) {
        Toast.makeText(this, "Opening $name on PC...", Toast.LENGTH_SHORT).show()
        PcRemoteClient.launchApp(path)
    }

    private fun performHaptic() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val vibrator = getSystemService(Vibrator::class.java)
                vibrator?.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        } catch (_: Exception) {}
    }

    // PcRemoteEventListener implementation
    override fun onTaskbarApps(appsJson: String) {}

    override fun onPcScreenFrame(base64Jpeg: String) {}

    override fun onPcScreenBinaryFrame(jpegBytes: ByteArray) {}

    override fun onPcMessage(type: String, json: JsonObject) {
        when (type) {
            "start_apps" -> {
                PcAppsRepository.handleRemoteJson(json)
            }
            "launch_status" -> {
                val success = json.get("success")?.asBoolean ?: false
                val target = json.get("target")?.asString ?: ""
                Log.d(TAG, "PC launch status for $target: success=$success")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
        if (PcRemoteClient.isConnected()) {
            PcRemoteClient.setListener(null)
        }
    }

    // Unified Adapter supporting both Grid View (big logos only) and List View (horizontal card with name + type)
    inner class UnifiedAppAdapter(
        private val items: List<StartAppItem>,
        private val onClick: (StartAppItem, View) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val VIEW_TYPE_GRID = 1
        private val VIEW_TYPE_LIST = 2

        override fun getItemViewType(position: Int): Int {
            return if (isGridView) VIEW_TYPE_GRID else VIEW_TYPE_LIST
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == VIEW_TYPE_GRID) {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_start_menu_grid_tile, parent, false)
                GridViewHolder(view)
            } else {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_start_menu_search, parent, false)
                ListViewHolder(view)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val app = items[position]

            if (holder is GridViewHolder) {
                bindIcon(holder.imgIcon, app)
                holder.itemView.setOnClickListener {
                    onClick(app, holder.itemView)
                }
            } else if (holder is ListViewHolder) {
                bindIcon(holder.imgIcon, app)
                holder.txtName.text = app.name
                holder.txtCategory.text = app.category
                holder.itemView.setOnClickListener {
                    onClick(app, holder.itemView)
                }
            }
        }

        private fun bindIcon(imageView: ImageView, app: StartAppItem) {
            // Instant 0ms bind: No Base64 decoding, no image decompression on UI thread
            if (app.localDrawableRes != null) {
                imageView.setImageResource(app.localDrawableRes)
            } else if (app.iconBitmap != null) {
                imageView.setImageBitmap(app.iconBitmap)
            } else {
                imageView.setImageResource(R.drawable.ic_win_logo)
            }
        }

        override fun getItemCount(): Int = items.size

        inner class GridViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val imgIcon: ImageView = v.findViewById(R.id.imgGridIcon)
        }

        inner class ListViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val imgIcon: ImageView = v.findViewById(R.id.imgSearchIcon)
            val txtName: TextView = v.findViewById(R.id.txtSearchName)
            val txtCategory: TextView = v.findViewById(R.id.txtSearchCategory)
        }
    }
}
