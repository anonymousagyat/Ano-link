package com.tailconnect.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

data class StartAppItem(
    val name: String,
    val path: String,
    val category: String,
    val iconBitmap: Bitmap? = null,
    val localDrawableRes: Int? = null
)

/**
 * Singleton repository managing PC installed applications.
 *
 * Preloads and pre-decodes app icons off the UI thread at startup,
 * eliminating all scrolling lag and deduplicating apps across Windows/Android paths.
 */
object PcAppsRepository {

    private const val TAG = "PcAppsRepository"
    private val gson = Gson()
    private val repoScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isPreloading = AtomicBoolean(false)

    // Pinned essentials priority order
    val pinnedOrder = listOf(
        "Brave Browser",
        "Visual Studio Code",
        "Android Studio",
        "File Explorer",
        "Command Prompt",
        "Steam",
        "Task Manager",
        "VLC Media Player",
        "Notepad++",
        "Neat Download Manager"
    )

    // Observable flow of deduplicated apps with pre-decoded Bitmaps
    private val _appsFlow = MutableStateFlow<List<StartAppItem>>(emptyList())
    val appsFlow: StateFlow<List<StartAppItem>> = _appsFlow

    val appsList: List<StartAppItem>
        get() = _appsFlow.value

    init {
        // Initialize with default seeds so UI is immediately populated
        _appsFlow.value = sortApps(getSeedApps())
    }

    fun getSeedApps(): List<StartAppItem> {
        return listOf(
            StartAppItem("Brave Browser", "C:\\Program Files\\BraveSoftware\\Brave-Browser\\Application\\brave.exe", "Web Browser", localDrawableRes = R.drawable.ic_app_brave),
            StartAppItem("Visual Studio Code", "C:\\Users\\parth\\AppData\\Local\\Programs\\Microsoft VS Code\\Code.exe", "Development IDE", localDrawableRes = R.drawable.ic_app_vscode),
            StartAppItem("Android Studio", "C:\\Program Files\\Android\\Android Studio\\bin\\studio64.exe", "Development IDE", localDrawableRes = R.drawable.ic_app_studio),
            StartAppItem("File Explorer", "C:\\WINDOWS\\explorer.exe", "System Tool", localDrawableRes = R.drawable.ic_app_explorer),
            StartAppItem("Command Prompt", "C:\\WINDOWS\\system32\\cmd.exe", "Console & Shell", localDrawableRes = R.drawable.ic_app_cmd),
            StartAppItem("Steam", "C:\\Program Files (x86)\\Steam\\Steam.exe", "Gaming Platform", localDrawableRes = R.drawable.ic_app_steam),
            StartAppItem("Task Manager", "C:\\WINDOWS\\system32\\Taskmgr.exe", "System Monitor", localDrawableRes = R.drawable.ic_app_taskmgr),
            StartAppItem("VLC Media Player", "C:\\Program Files\\VideoLAN\\VLC\\vlc.exe", "Media Player", localDrawableRes = R.drawable.ic_app_vlc),
            StartAppItem("Notepad++", "C:\\Program Files\\Notepad++\\notepad++.exe", "Text & Code Editor", localDrawableRes = R.drawable.ic_app_notepadpp),
            StartAppItem("Neat Download Manager", "D:\\1.UTILITIES\\Neat Download Manager\\NeatDM.exe", "Download Manager", localDrawableRes = R.drawable.ic_app_neatdm),
            StartAppItem("WinRAR", "C:\\Program Files\\WinRAR\\WinRAR.exe", "Archive Utility", localDrawableRes = R.drawable.ic_win_logo),
            StartAppItem("Calculator", "calc.exe", "Utility", localDrawableRes = R.drawable.ic_win_logo),
            StartAppItem("Windows Settings", "ms-settings:", "System Settings", localDrawableRes = R.drawable.ic_win_logo)
        )
    }

    /**
     * Canonical key generator that works identically on both Android (Linux separator)
     * and Windows paths (backslashes), preventing duplicate cmd, explorer, taskmgr, and browser entries.
     */
    fun getCanonicalKey(name: String, path: String): String {
        val exe = path.substringAfterLast('\\').substringAfterLast('/').lowercase().trim()
        if (exe.isNotEmpty() && exe.endsWith(".exe")) {
            return exe
        }
        return name.lowercase()
            .replace(" browser", "")
            .replace(" media player", "")
            .replace(" 64-bit", "")
            .replace(" (64-bit)", "")
            .replace(" (x64)", "")
            .replace(" (x86)", "")
            .trim()
    }

    fun findLocalDrawable(name: String, path: String): Int? {
        val exe = path.substringAfterLast('\\').substringAfterLast('/').lowercase().trim()
        val lower = name.lowercase()
        return when {
            exe == "brave.exe" || lower.contains("brave") -> R.drawable.ic_app_brave
            exe == "code.exe" || lower.contains("visual studio code") || lower == "code" -> R.drawable.ic_app_vscode
            exe == "studio64.exe" || lower.contains("android studio") -> R.drawable.ic_app_studio
            exe == "explorer.exe" || lower.contains("explorer") -> R.drawable.ic_app_explorer
            exe == "cmd.exe" || lower.contains("command prompt") || lower == "cmd" -> R.drawable.ic_app_cmd
            exe == "steam.exe" || lower.contains("steam") -> R.drawable.ic_app_steam
            exe == "taskmgr.exe" || lower.contains("task manager") || lower.contains("taskmgr") -> R.drawable.ic_app_taskmgr
            exe == "vlc.exe" || lower.contains("vlc") -> R.drawable.ic_app_vlc
            exe == "notepad++.exe" || lower.contains("notepad++") -> R.drawable.ic_app_notepadpp
            exe == "neatdm.exe" || lower.contains("neat") -> R.drawable.ic_app_neatdm
            else -> null
        }
    }

    /**
     * Preloads PC apps from http://<pcHost>:8080/api/pc/apps in the background.
     * Decodes Base64 icons into Bitmaps off the UI thread (Dispatchers.Default).
     */
    fun preload(context: Context) {
        if (!isPreloading.compareAndSet(false, true)) {
            return
        }

        repoScope.launch {
            try {
                val pcHost = PcRemoteClient.getPcHost(context)
                val url = URL("http://$pcHost:8080/api/pc/apps")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 5000
                conn.requestMethod = "GET"

                if (conn.responseCode == 200) {
                    val rawBody = conn.inputStream.bufferedReader().use { it.readText() }
                    val cleanBody = rawBody.replace("\uFEFF", "").trim()
                    val json = gson.fromJson(cleanBody, JsonObject::class.java)
                    processIncomingJson(json)
                } else {
                    Log.w(TAG, "Preload /api/pc/apps returned HTTP ${conn.responseCode}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Preload fetch failed: ${e.message}")
            } finally {
                isPreloading.set(false)
            }
        }
    }

    fun handleRemoteJson(json: JsonObject) {
        repoScope.launch {
            processIncomingJson(json)
        }
    }

    private suspend fun processIncomingJson(json: JsonObject) {
        val appsArray = json.getAsJsonArray("apps") ?: return
        val parsedList = mutableListOf<StartAppItem>()

        withContext(Dispatchers.Default) {
            for (el in appsArray) {
                if (!el.isJsonObject) continue
                val obj = el.asJsonObject
                val name = obj.get("name")?.asString ?: continue
                val path = obj.get("path")?.asString ?: continue
                val category = obj.get("category")?.asString ?: "Windows App"
                val iconBase64 = obj.get("icon")?.asString

                val localRes = findLocalDrawable(name, path)
                // Only decode Base64 bitmap if we don't already have a local crisp vector/PNG drawable
                val bitmap = if (localRes != null) null else decodeBitmap(iconBase64)

                parsedList.add(
                    StartAppItem(
                        name = name,
                        path = path,
                        category = category,
                        iconBitmap = bitmap,
                        localDrawableRes = localRes
                    )
                )
            }
        }

        if (parsedList.isNotEmpty()) {
            val merged = mergeWithSeedAndDeduplicate(parsedList)
            _appsFlow.value = merged
            Log.i(TAG, "Loaded and pre-decoded ${merged.size} deduplicated PC apps")
        }
    }

    private fun decodeBitmap(base64Str: String?): Bitmap? {
        if (base64Str.isNullOrEmpty()) return null
        return try {
            val bytes = Base64.decode(base64Str, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        }
    }

    private fun mergeWithSeedAndDeduplicate(incoming: List<StartAppItem>): List<StartAppItem> {
        val map = linkedMapOf<String, StartAppItem>()

        // 1. Add incoming PC apps keyed canonically
        for (item in incoming) {
            val key = getCanonicalKey(item.name, item.path)
            map[key] = item
        }

        // 2. Merge seed apps (ensuring local drawables and preferred names like "Command Prompt")
        for (seed in getSeedApps()) {
            val key = getCanonicalKey(seed.name, seed.path)
            if (map.containsKey(key)) {
                val existing = map[key]!!
                val resolvedPath = if (existing.path.contains(":") || existing.path.contains("\\")) existing.path else seed.path
                map[key] = existing.copy(
                    name = seed.name,
                    path = resolvedPath,
                    category = seed.category,
                    localDrawableRes = seed.localDrawableRes ?: existing.localDrawableRes
                )
            } else {
                map[key] = seed
            }
        }

        return sortApps(map.values.toList())
    }

    private fun sortApps(items: List<StartAppItem>): List<StartAppItem> {
        return items.sortedWith(Comparator { a, b ->
            val idxA = pinnedOrder.indexOfFirst { it.equals(a.name, ignoreCase = true) }
            val idxB = pinnedOrder.indexOfFirst { it.equals(b.name, ignoreCase = true) }

            if (idxA >= 0 && idxB >= 0) {
                idxA.compareTo(idxB)
            } else if (idxA >= 0) {
                -1
            } else if (idxB >= 0) {
                1
            } else {
                a.name.compareTo(b.name, ignoreCase = true)
            }
        })
    }
}
