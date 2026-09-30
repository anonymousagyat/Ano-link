package com.tailconnect.app

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Window
import android.view.WindowManager
import android.os.Environment
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*

class PcFilesActivity : AppCompatActivity(), PcRemoteEventListener {

    private val TAG = "PcFilesActivity"
    private val gson = Gson()
    private val activityScope = CoroutineScope(Dispatchers.Main + Job())

    // Views
    private lateinit var tvFilesSubtitle: TextView
    private lateinit var llBreadcrumbs: LinearLayout
    private lateinit var lvPcFiles: ListView
    private lateinit var gvPcDrives: GridView
    private lateinit var llFilesLoading: View
    private lateinit var tvFilesEmpty: View
    private lateinit var llDownloadProgressOverlay: View
    private lateinit var tvDownloadFileName: TextView
    private lateinit var pbDownloadProgress: ProgressBar
    private lateinit var tvDownloadPercent: TextView
    private lateinit var btnCancelDownload: Button

    // State
    private var currentPath: String = ""
    private var parentPath: String = ""
    private var isRoot: Boolean = true
    private val fileItems = ArrayList<PcFileItem>()
    private val driveItems = ArrayList<PcFileItem>()
    private lateinit var filesAdapter: PcFilesAdapter
    private lateinit var drivesAdapter: PcDrivesGridAdapter

    private var activeDownloadJob: Job? = null
    private var isDownloading = false
    private var currentDownloadId: String? = null
    private var downloadDestFile: File? = null
    private var downloadOutputStream: FileOutputStream? = null
    private var downloadTotalSize: Long = 0L
    private var downloadAutoOpen: Boolean = false
    private var lastProgressUpdateTime = 0L

    data class PcFileItem(
        val name: String,
        val path: String,
        val isDirectory: Boolean,
        val isDrive: Boolean = false,
        val size: Long = 0,
        val mtime: Long = 0,
        val ext: String = ""
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge: Cyber void background stretches behind camera notch & nav bar
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        setContentView(R.layout.activity_pc_files)

        val llFilesHeader = findViewById<View>(R.id.llFilesHeader)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootLayoutFiles)) { _, insets ->
            val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout())
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val density = resources.displayMetrics.density
            val padH = (16 * density).toInt()
            val padTop = statusBar.top + (10 * density).toInt()
            val padBottom = (12 * density).toInt()
            llFilesHeader.setPadding(padH, padTop, padH, padBottom)
            findViewById<View>(R.id.lvPcFiles)?.setPadding((8 * density).toInt(), 0, (8 * density).toInt(), navBar.bottom + (16 * density).toInt())
            findViewById<View>(R.id.gvPcDrives)?.setPadding(padH, (16 * density).toInt(), padH, navBar.bottom + (16 * density).toInt())
            insets
        }

        initViews()
        loadDirectory("")
    }

    private fun initViews() {
        findViewById<View>(R.id.btnFilesBack).setOnClickListener {
            handleBackNavigation()
        }

        findViewById<View>(R.id.btnFilesRefresh).setOnClickListener {
            loadDirectory(currentPath)
        }

        tvFilesSubtitle = findViewById(R.id.tvFilesSubtitle)
        llBreadcrumbs = findViewById(R.id.llBreadcrumbs)
        lvPcFiles = findViewById(R.id.lvPcFiles)
        gvPcDrives = findViewById(R.id.gvPcDrives)
        llFilesLoading = findViewById(R.id.llFilesLoading)
        tvFilesEmpty = findViewById(R.id.tvFilesEmpty)

        llDownloadProgressOverlay = findViewById(R.id.llDownloadProgressOverlay)
        tvDownloadFileName = findViewById(R.id.tvDownloadFileName)
        pbDownloadProgress = findViewById(R.id.pbDownloadProgress)
        tvDownloadPercent = findViewById(R.id.tvDownloadPercent)
        btnCancelDownload = findViewById(R.id.btnCancelDownload)

        btnCancelDownload.setOnClickListener {
            cancelCurrentDownload()
        }

        filesAdapter = PcFilesAdapter(this, fileItems)
        lvPcFiles.adapter = filesAdapter

        drivesAdapter = PcDrivesGridAdapter(this, driveItems)
        gvPcDrives.adapter = drivesAdapter

        gvPcDrives.setOnItemClickListener { _, _, position, _ ->
            val drive = driveItems[position]
            loadDirectory(drive.path)
        }

        lvPcFiles.setOnItemClickListener { _, _, position, _ ->
            val item = fileItems[position]
            if (item.name == ".. [Parent Directory]") {
                loadDirectory(parentPath)
            } else if (item.isDirectory) {
                loadDirectory(item.path)
            } else {
                showFileActionsDialog(item)
            }
        }

        lvPcFiles.setOnItemLongClickListener { _, _, position, _ ->
            val item = fileItems[position]
            if (item.name != ".. [Parent Directory]" && !item.isDirectory) {
                showFileActionsDialog(item)
                true
            } else {
                false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        PcRemoteClient.setListener(this)
        PcRemoteClient.setConnectionStateListener { isConnected, _ ->
            if (isConnected) {
                runOnUiThread {
                    if (fileItems.isEmpty() && driveItems.isEmpty()) {
                        loadDirectory(currentPath)
                    }
                }
            }
        }
        PcRemoteClient.connect(this)
        if (PcRemoteClient.isConnected()) {
            loadDirectory(currentPath)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isDownloading) {
            cancelCurrentDownload()
        }
        PcRemoteClient.setListener(null)
        PcRemoteClient.setConnectionStateListener(null)
        activityScope.cancel()
    }

    override fun onTaskbarApps(appsJson: String) {}
    override fun onPcScreenFrame(base64Jpeg: String) {}
    override fun onPcScreenBinaryFrame(jpegBytes: ByteArray) {}

    override fun onPcMessage(type: String, json: JsonObject) {
        when (type) {
            "pc_files_response" -> {
                runOnUiThread {
                    handlePcFilesResponse(json)
                }
            }
            "pc_file_action_result" -> {
                val success = json.get("success")?.asBoolean ?: false
                val action = json.get("action")?.asString ?: ""
                runOnUiThread {
                    if (success) {
                        Toast.makeText(this@PcFilesActivity, "Action completed: $action", Toast.LENGTH_SHORT).show()
                        loadDirectory(currentPath)
                    } else {
                        val err = json.get("error")?.asString ?: "Action failed"
                        Toast.makeText(this@PcFilesActivity, "Error: $err", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            "file_download_chunk" -> {
                handleFileDownloadChunk(json)
            }
            "file_download_complete" -> {
                runOnUiThread {
                    handleFileDownloadComplete(json)
                }
            }
            "file_download_error" -> {
                runOnUiThread {
                    handleFileDownloadError(json)
                }
            }
        }
    }

    private fun loadDirectory(targetPath: String) {
        llFilesLoading.visibility = View.VISIBLE
        tvFilesEmpty.visibility = View.GONE

        // Dispatch directory request over 100% pure WebRTC DataChannel
        PcRemoteClient.send(gson.toJson(mapOf(
            "type" to "get_pc_files",
            "path" to targetPath
        )))
    }

    private fun handlePcFilesResponse(json: JsonObject) {
        try {
            if (json.has("error") && !json.get("error").isJsonNull) {
                llFilesLoading.visibility = View.GONE
                Toast.makeText(this, json.get("error").asString, Toast.LENGTH_SHORT).show()
                return
            }

            val newCurrent = json.get("currentPath")?.asString ?: ""
            val newParent = if (json.has("parentPath") && !json.get("parentPath").isJsonNull) {
                json.get("parentPath").asString
            } else ""
            val newIsRoot = json.get("isRoot")?.asBoolean ?: (newCurrent.isEmpty())

            val itemsArr = json.getAsJsonArray("items")
            val newItems = ArrayList<PcFileItem>()

            // Add ".." Parent Directory row if not at root
            if (!newIsRoot) {
                newItems.add(
                    PcFileItem(
                        name = ".. [Parent Directory]",
                        path = newParent,
                        isDirectory = true
                    )
                )
            }

            if (itemsArr != null) {
                for (el in itemsArr) {
                    val obj = el.asJsonObject
                    newItems.add(
                        PcFileItem(
                            name = obj.get("name")?.asString ?: "Unknown",
                            path = obj.get("path")?.asString ?: "",
                            isDirectory = obj.get("isDirectory")?.asBoolean ?: false,
                            isDrive = obj.get("isDrive")?.asBoolean ?: false,
                            size = obj.get("size")?.asLong ?: 0L,
                            mtime = obj.get("mtime")?.asLong ?: 0L,
                            ext = obj.get("ext")?.asString ?: ""
                        )
                    )
                }
            }

            currentPath = newCurrent
            parentPath = newParent
            isRoot = newIsRoot

            if (isRoot) {
                driveItems.clear()
                val drivesOnly = newItems.filter { it.isDrive }
                if (drivesOnly.isNotEmpty()) {
                    driveItems.addAll(drivesOnly)
                } else {
                    driveItems.addAll(newItems)
                }
                drivesAdapter.notifyDataSetChanged()

                gvPcDrives.visibility = View.VISIBLE
                lvPcFiles.visibility = View.GONE
                tvFilesEmpty.visibility = if (driveItems.isEmpty()) View.VISIBLE else View.GONE
                tvFilesSubtitle.text = "PC STORAGE VOLUMES • DRIVES"
            } else {
                fileItems.clear()
                fileItems.addAll(newItems)
                filesAdapter.notifyDataSetChanged()

                gvPcDrives.visibility = View.GONE
                lvPcFiles.visibility = View.VISIBLE
                tvFilesEmpty.visibility = if (newItems.isEmpty()) View.VISIBLE else View.GONE
                tvFilesSubtitle.text = currentPath
            }

            renderBreadcrumbs(currentPath)
            llFilesLoading.visibility = View.GONE
        } catch (e: Exception) {
            llFilesLoading.visibility = View.GONE
            Log.e(TAG, "Error parsing pc_files_response: ${e.message}", e)
        }
    }

    private fun renderBreadcrumbs(pathStr: String) {
        llBreadcrumbs.removeAllViews()

        // Root crumb
        val tvRoot = TextView(this).apply {
            text = "DRIVES"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (pathStr.isEmpty()) Color.parseColor("#C084FC") else Color.parseColor("#94A3B8"))
            setPadding(8, 4, 8, 4)
            setOnClickListener {
                loadDirectory("")
            }
        }
        llBreadcrumbs.addView(tvRoot)

        if (pathStr.isBlank()) return

        val normalized = pathStr.replace("/", "\\")
        val parts = normalized.split("\\").filter { it.isNotBlank() }

        var accPath = ""
        for (i in parts.indices) {
            val part = parts[i]
            if (i == 0) {
                accPath = "$part\\"
            } else {
                accPath = "$accPath$part\\"
            }
            val crumbPath = accPath

            // Separator
            val tvSep = TextView(this).apply {
                text = " ➔ "
                textSize = 10f
                setTextColor(Color.parseColor("#475569"))
            }
            llBreadcrumbs.addView(tvSep)

            val tvCrumb = TextView(this).apply {
                text = part
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (i == parts.size - 1) Color.parseColor("#C084FC") else Color.parseColor("#94A3B8"))
                setPadding(6, 4, 6, 4)
                setOnClickListener {
                    loadDirectory(crumbPath)
                }
            }
            llBreadcrumbs.addView(tvCrumb)
        }
    }

    private fun showFileActionsDialog(item: PcFileItem) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_pc_file_actions)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val width = (resources.displayMetrics.widthPixels * 0.92).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)

        val ivIcon = dialog.findViewById<ImageView>(R.id.ivDialogFileIcon)
        val tvName = dialog.findViewById<TextView>(R.id.tvDialogFileName)
        val tvDetails = dialog.findViewById<TextView>(R.id.tvDialogFileDetails)
        val btnDownload = dialog.findViewById<View>(R.id.btnActionDownload)
        val btnOpenPhone = dialog.findViewById<View>(R.id.btnActionOpenPhone)
        val btnOpenPc = dialog.findViewById<View>(R.id.btnActionOpenPc)
        val btnClose = dialog.findViewById<Button>(R.id.btnDialogClose)

        tvName.text = item.name
        val sizeFormatted = formatFileSize(item.size)
        tvDetails.text = "$sizeFormatted • ${item.path}"

        val ext = item.ext.lowercase(Locale.ROOT)
        val (drawableRes, bgRes) = when {
            ext in listOf("mp4", "mkv", "avi", "mov", "webm") -> R.drawable.ic_file_video to R.drawable.bg_gem_purple
            ext in listOf("mp3", "wav", "flac", "m4a", "aac", "ogg") -> R.drawable.ic_file_audio to R.drawable.bg_gem_amber
            ext in listOf("jpg", "jpeg", "png", "webp", "gif", "bmp") -> R.drawable.ic_file_image to R.drawable.bg_gem_green
            ext in listOf("zip", "rar", "7z", "tar", "gz") -> R.drawable.ic_file_archive to R.drawable.bg_gem_purple
            ext in listOf("pdf", "doc", "docx", "txt", "md") -> R.drawable.ic_file_doc to R.drawable.bg_gem_blue
            ext in listOf("exe", "msi", "apk", "bat", "ps1") -> R.drawable.ic_file_code to R.drawable.bg_gem_green
            else -> R.drawable.ic_file_generic to R.drawable.bg_gem_slate
        }
        ivIcon.setImageResource(drawableRes)
        ivIcon.setBackgroundResource(bgRes)

        btnDownload.setOnClickListener {
            dialog.dismiss()
            startDownload(item, autoOpen = false)
        }

        btnOpenPhone.setOnClickListener {
            dialog.dismiss()
            startDownload(item, autoOpen = true)
        }

        btnOpenPc.setOnClickListener {
            dialog.dismiss()
            openOnPc(item)
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun openOnPc(item: PcFileItem) {
        val payload = gson.toJson(mapOf(
            "type" to "pc_file_action",
            "fileAction" to "open",
            "path" to item.path
        ))
        PcRemoteClient.send(payload)
        Toast.makeText(this@PcFilesActivity, "Opening \"${item.name}\" on PC...", Toast.LENGTH_SHORT).show()
    }

    private fun startDownload(item: PcFileItem, autoOpen: Boolean) {
        if (isDownloading) {
            Toast.makeText(this, "A download is already in progress", Toast.LENGTH_SHORT).show()
            return
        }

        if (!PcRemoteClient.isConnected()) {
            Toast.makeText(this, "PC is not connected via WebRTC", Toast.LENGTH_SHORT).show()
            return
        }

        val downloadId = UUID.randomUUID().toString()
        currentDownloadId = downloadId
        downloadAutoOpen = autoOpen
        downloadTotalSize = item.size
        isDownloading = true
        lastProgressUpdateTime = 0L

        try {
            val destDir = if (autoOpen) {
                File(cacheDir, "ano_link_downloads").apply { mkdirs() }
            } else {
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Ano-Link").apply { mkdirs() }
            }
            val destFile = File(destDir, item.name)
            downloadDestFile = destFile
            downloadOutputStream = FileOutputStream(destFile)
        } catch (e: Exception) {
            isDownloading = false
            Toast.makeText(this, "Failed to create local destination file: ${e.message}", Toast.LENGTH_SHORT).show()
            return
        }

        llDownloadProgressOverlay.visibility = View.VISIBLE
        tvDownloadFileName.text = "Downloading: ${item.name}"
        pbDownloadProgress.progress = 0
        tvDownloadPercent.text = "0% (0 KB / ${formatFileSize(item.size)})"

        // Dispatch chunked download over pure WebRTC P2P DataChannel (direct UDP, zero Wi-Fi LAN restriction)
        PcRemoteClient.send(gson.toJson(mapOf(
            "type" to "download_pc_file",
            "downloadId" to downloadId,
            "path" to item.path
        )))
    }

    private fun cancelCurrentDownload() {
        val dId = currentDownloadId
        if (dId != null) {
            PcRemoteClient.send(gson.toJson(mapOf(
                "type" to "cancel_file_download",
                "downloadId" to dId
            )))
        }
        cleanupDownload(deleteFile = true)
        Toast.makeText(this, "Download cancelled", Toast.LENGTH_SHORT).show()
    }

    private fun handleFileDownloadChunk(json: JsonObject) {
        val downloadId = json.get("downloadId")?.asString ?: return
        if (downloadId != currentDownloadId) return

        val chunkBase64 = json.get("chunk")?.asString ?: return
        val offset = json.get("offset")?.asLong ?: 0L
        val totalSize = json.get("totalSize")?.asLong ?: downloadTotalSize
        downloadTotalSize = totalSize

        try {
            val chunkBytes = android.util.Base64.decode(chunkBase64, android.util.Base64.DEFAULT)
            downloadOutputStream?.write(chunkBytes)

            val now = System.currentTimeMillis()
            if (now - lastProgressUpdateTime > 150) {
                lastProgressUpdateTime = now
                val percent = if (totalSize > 0) ((offset * 100) / totalSize).toInt().coerceIn(0, 100) else 0
                runOnUiThread {
                    pbDownloadProgress.progress = percent
                    tvDownloadPercent.text = "$percent% (${formatFileSize(offset)} / ${formatFileSize(totalSize)})"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing chunk: ${e.message}")
        }
    }

    private fun handleFileDownloadComplete(json: JsonObject) {
        val downloadId = json.get("downloadId")?.asString ?: return
        if (downloadId != currentDownloadId) return

        try {
            downloadOutputStream?.flush()
            downloadOutputStream?.close()
        } catch (_: Exception) {}
        downloadOutputStream = null

        val file = downloadDestFile
        val autoOpen = downloadAutoOpen
        cleanupDownload(deleteFile = false)

        if (file != null && file.exists()) {
            if (autoOpen) {
                openDownloadedFile(file)
            } else {
                Toast.makeText(this, "Saved to Downloads/Ano-Link/${file.name}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun handleFileDownloadError(json: JsonObject) {
        val downloadId = json.get("downloadId")?.asString ?: return
        if (downloadId != currentDownloadId) return

        val error = json.get("error")?.asString ?: "Unknown download error"
        cleanupDownload(deleteFile = true)
        Toast.makeText(this, "Download error: $error", Toast.LENGTH_LONG).show()
    }

    private fun cleanupDownload(deleteFile: Boolean) {
        try {
            downloadOutputStream?.close()
        } catch (_: Exception) {}
        downloadOutputStream = null

        if (deleteFile) {
            try {
                downloadDestFile?.delete()
            } catch (_: Exception) {}
        }
        downloadDestFile = null
        currentDownloadId = null
        isDownloading = false
        runOnUiThread {
            llDownloadProgressOverlay.visibility = View.GONE
        }
    }

    private fun openDownloadedFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val ext = file.extension.lowercase(Locale.ROOT)
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(Intent.createChooser(intent, "Open with..."))
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleBackNavigation() {
        if (!isRoot && parentPath.isNotEmpty() && parentPath != currentPath) {
            loadDirectory(parentPath)
        } else if (!isRoot) {
            loadDirectory("")
        } else {
            finish()
        }
    }

    override fun onBackPressed() {
        if (!isRoot) {
            handleBackNavigation()
        } else {
            super.onBackPressed()
        }
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        val num = bytes / Math.pow(1024.0, digitGroups.toDouble())
        return String.format(Locale.US, "%.1f %s", num, units[digitGroups])
    }

    private inner class PcFilesAdapter(context: Context, items: List<PcFileItem>) :
        ArrayAdapter<PcFileItem>(context, 0, items) {

        private val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_pc_file, parent, false)
            val item = getItem(position) ?: return view

            val ivIcon = view.findViewById<ImageView>(R.id.ivItemIcon)
            val tvName = view.findViewById<TextView>(R.id.tvItemName)
            val tvDetails = view.findViewById<TextView>(R.id.tvItemDetails)
            val ivChevron = view.findViewById<ImageView>(R.id.ivItemChevron)

            tvName.text = item.name

            if (item.name == ".. [Parent Directory]") {
                ivIcon.setImageResource(R.drawable.ic_folder_up)
                ivIcon.setBackgroundResource(R.drawable.bg_gem_purple)
                tvDetails.text = "Up to parent folder"
                ivChevron.setImageResource(R.drawable.ic_chevron_right)
                ivChevron.setColorFilter(Color.parseColor("#C084FC"))
                ivChevron.setOnClickListener { loadDirectory(parentPath) }
            } else if (item.isDrive) {
                ivIcon.setImageResource(R.drawable.ic_drive)
                ivIcon.setBackgroundResource(R.drawable.bg_gem_purple)
                tvDetails.text = "Local Disk Drive"
                ivChevron.setImageResource(R.drawable.ic_chevron_right)
                ivChevron.setColorFilter(Color.parseColor("#C084FC"))
                ivChevron.setOnClickListener { loadDirectory(item.path) }
            } else if (item.isDirectory) {
                ivIcon.setImageResource(R.drawable.ic_folder)
                ivIcon.setBackgroundResource(R.drawable.bg_gem_purple)
                tvDetails.text = "Folder"
                ivChevron.setImageResource(R.drawable.ic_chevron_right)
                ivChevron.setColorFilter(Color.parseColor("#94A3B8"))
                ivChevron.setOnClickListener { loadDirectory(item.path) }
            } else {
                ivChevron.setImageResource(R.drawable.ic_more_vert)
                ivChevron.setColorFilter(Color.parseColor("#94A3B8"))
                ivChevron.setOnClickListener {
                    showFileActionsDialog(item)
                }
                val ext = item.ext.lowercase(Locale.ROOT)
                val (drawableRes, bgRes) = when {
                    ext in listOf("mp4", "mkv", "avi", "mov", "webm") -> R.drawable.ic_file_video to R.drawable.bg_gem_purple
                    ext in listOf("mp3", "wav", "flac", "m4a", "aac", "ogg") -> R.drawable.ic_file_audio to R.drawable.bg_gem_amber
                    ext in listOf("jpg", "jpeg", "png", "webp", "gif", "bmp") -> R.drawable.ic_file_image to R.drawable.bg_gem_green
                    ext in listOf("zip", "rar", "7z", "tar", "gz") -> R.drawable.ic_file_archive to R.drawable.bg_gem_purple
                    ext in listOf("pdf", "doc", "docx", "txt", "md") -> R.drawable.ic_file_doc to R.drawable.bg_gem_blue
                    ext in listOf("exe", "msi", "apk", "bat", "ps1") -> R.drawable.ic_file_code to R.drawable.bg_gem_green
                    else -> R.drawable.ic_file_generic to R.drawable.bg_gem_slate
                }
                ivIcon.setImageResource(drawableRes)
                ivIcon.setBackgroundResource(bgRes)

                val sizeStr = if (item.size > 0) formatSize(item.size) else "0 B"
                val dateStr = if (item.mtime > 0) dateFormat.format(Date(item.mtime)) else ""
                tvDetails.text = "$sizeStr • $dateStr"
            }

            return view
        }

        private fun formatSize(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
            val num = bytes / Math.pow(1024.0, digitGroups.toDouble())
            return String.format(Locale.US, "%.1f %s", num, units[digitGroups])
        }
    }

    private inner class PcDrivesGridAdapter(context: Context, items: List<PcFileItem>) :
        ArrayAdapter<PcFileItem>(context, 0, items) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_pc_drive_card, parent, false)
            val item = getItem(position) ?: return view

            val tvDriveName = view.findViewById<TextView>(R.id.tvDriveName)
            val tvDriveSubtitle = view.findViewById<TextView>(R.id.tvDriveSubtitle)
            val tvDriveBadge = view.findViewById<TextView>(R.id.tvDriveBadge)

            val cleanName = item.name.replace("Local Disk", "").replace("(", "").replace(")", "").trim()
            val displayTitle = if (cleanName.isNotEmpty()) "Drive $cleanName" else item.name
            tvDriveName.text = displayTitle
            tvDriveSubtitle.text = if (item.path.isNotEmpty()) item.path else "PC System Volume"
            tvDriveBadge.text = "ONLINE"

            return view
        }
    }
}
