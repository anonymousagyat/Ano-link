package com.tailconnect.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-performance outbound WebSocket client connecting Android directly
 * to the PC Controller daemon over Tailscale or Local WiFi.
 */
object PcRemoteClient {

    private const val TAG = "PcRemoteClient"
    private const val PREFS_NAME = "ano_link_prefs"
    private const val KEY_PC_HOST = "pc_target_host"
    private const val KEY_PC_PORT = "pc_target_port"
    private const val DEFAULT_PC_HOST = ""
    private const val DEFAULT_PC_PORT = 8080

    private val gson = Gson()
    private val clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var client: HttpClient? = null
    private var activeSession: DefaultClientWebSocketSession? = null
    private val isConnecting = AtomicBoolean(false)
    private var isExplicitlyStopped = false

    private var activeListener: PcRemoteEventListener? = null
    private var connectionStateListener: ((Boolean, Long) -> Unit)? = null

    private var lastPingSent = 0L
    private var currentPingMs = -1L

    fun hasConfiguredPc(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val host = prefs.getString(KEY_PC_HOST, "") ?: ""
        return host.isNotBlank()
    }

    fun getPcHost(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_PC_HOST, DEFAULT_PC_HOST) ?: DEFAULT_PC_HOST
    }

    fun setPcHost(context: Context, host: String, port: Int = DEFAULT_PC_PORT) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_PC_HOST, host.trim())
            .putInt(KEY_PC_PORT, port)
            .apply()
        // Reconnect to new host
        disconnect()
        connect(context)
    }

    fun setListener(listener: PcRemoteEventListener?) {
        activeListener = listener
    }

    fun setConnectionStateListener(listener: ((Boolean, Long) -> Unit)?) {
        connectionStateListener = listener
        listener?.invoke(isConnected(), currentPingMs)
    }

    fun isConnected(): Boolean = activeSession != null

    fun connect(context: Context) {
        isExplicitlyStopped = false
        if (isConnected() || isConnecting.get()) return

        val host = getPcHost(context)
        if (host.isBlank()) return
        val port = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_PC_PORT, DEFAULT_PC_PORT)

        if (client == null) {
            client = HttpClient(CIO) {
                install(WebSockets) {
                    pingInterval = 15000
                    maxFrameSize = Long.MAX_VALUE
                }
            }
        }

        clientScope.launch {
            if (!isConnecting.compareAndSet(false, true)) return@launch

            try {
                Log.i(TAG, "Connecting to PC Controller at ws://$host:$port/ws-remote ...")
                client?.webSocket(host = host, port = port, path = "/ws-remote") {
                    activeSession = this
                    isConnecting.set(false)
                    Log.i(TAG, "Successfully connected to PC Controller WebSocket!")

                    withContext(Dispatchers.Main) {
                        connectionStateListener?.invoke(true, currentPingMs)
                    }

                    // Request initial taskbar apps (screen stream is started on-demand by user)
                    send("pc_get_taskbar")

                    // Start background ping loop
                    val pingJob = launch {
                        while (isActive) {
                            sendPing()
                            delay(3000)
                        }
                    }

                    try {
                        for (frame in incoming) {
                            when (frame) {
                                is Frame.Binary -> {
                                    val bytes = frame.readBytes()
                                    activeListener?.onPcScreenBinaryFrame(bytes)
                                }
                                is Frame.Text -> {
                                    val text = frame.readText()
                                    handleTextMessage(text)
                                }
                                else -> {}
                            }
                        }
                    } finally {
                        pingJob.cancel()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "PC connection error: ${e.message}")
            } finally {
                activeSession = null
                isConnecting.set(false)
                withContext(Dispatchers.Main) {
                    connectionStateListener?.invoke(false, -1L)
                }

                // Auto-retry if not explicitly stopped
                if (!isExplicitlyStopped) {
                    delay(2500)
                    connect(context)
                }
            }
        }
    }

    fun disconnect() {
        isExplicitlyStopped = true
        clientScope.launch {
            try {
                activeSession?.close(CloseReason(CloseReason.Codes.NORMAL, "User disconnected"))
            } catch (_: Exception) {}
            activeSession = null
        }
    }

    private fun handleTextMessage(text: String) {
        try {
            val json = gson.fromJson(text, JsonObject::class.java)
            val type = json.get("type")?.asString ?: ""

            when (type) {
                "pong" -> {
                    if (lastPingSent > 0) {
                        currentPingMs = System.currentTimeMillis() - lastPingSent
                        CoroutineScope(Dispatchers.Main).launch {
                            connectionStateListener?.invoke(true, currentPingMs)
                        }
                    }
                    activeListener?.onPcMessage("pong", json)
                }
                "taskbar_apps" -> {
                    activeListener?.onTaskbarApps(text)
                }
                else -> {
                    activeListener?.onPcMessage(type, json)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error handling PC message: ${e.message}")
        }
    }

    fun send(action: String, extra: Map<String, Any> = emptyMap()) {
        val payload = mutableMapOf<String, Any>("action" to action)
        payload.putAll(extra)
        val text = gson.toJson(payload)

        val session = activeSession
        if (session != null) {
            clientScope.launch {
                try {
                    session.send(Frame.Text(text))
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send $action: ${e.message}")
                }
            }
        } else {
            // Fallback to TailConnectService inbound socket
            TailConnectService.sendToPc(payload)
        }
    }

    fun sendPing() {
        lastPingSent = System.currentTimeMillis()
        send("ping")
    }

    fun sendTap(nx: Float, ny: Float, button: String = "left", clickType: String = "click") {
        send("pc_mouse_tap", mapOf(
            "nx" to nx,
            "ny" to ny,
            "button" to button,
            "clickType" to clickType
        ))
    }

    fun sendMouseMoveNorm(nx: Float, ny: Float) {
        send("pc_mouse_move_norm", mapOf("nx" to nx, "ny" to ny))
    }

    fun sendMove(dx: Int, dy: Int) {
        send("pc_mouse_move", mapOf("dx" to dx, "dy" to dy))
    }

    fun sendClick(button: String = "left", clickType: String = "click") {
        send("pc_mouse_click", mapOf("button" to button, "clickType" to clickType))
    }

    fun sendScroll(dy: Int) {
        send("pc_mouse_scroll", mapOf("dy" to dy))
    }

    fun sendKey(key: String) {
        send("pc_key", mapOf("key" to key))
    }

    fun sendType(text: String) {
        send("pc_type", mapOf("text" to text))
    }

    fun sendHotkey(keys: List<String>) {
        send("pc_hotkey", mapOf("keys" to keys))
    }

    fun sendPower(option: String) {
        send("pc_power", mapOf("powerOption" to option))
    }

    fun focusWindow(hwnd: Long) {
        send("pc_focus_window", mapOf("hwnd" to hwnd))
    }

    fun launchApp(path: String) {
        send("pc_launch_app", mapOf("path" to path))
    }

    fun getStartApps() {
        send("pc_get_start_apps")
    }

    fun startStream() {
        send("start_pc_screen_stream")
    }

    fun stopStream() {
        send("stop_pc_screen_stream")
    }
}
