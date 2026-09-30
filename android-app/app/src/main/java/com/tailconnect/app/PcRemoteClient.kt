package com.tailconnect.app

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*

/**
 * Pure WebRTC Remote Client connecting Android to PC Controller
 * over direct peer-to-peer UDP via Google STUN (Zero WebSockets).
 */
object PcRemoteClient {

    private const val TAG = "PcRemoteClient"
    private const val PREFS_NAME = "ano_link_prefs"
    private const val KEY_PAIRING_PIN = "pairing_pin"
    private const val KEY_DEVICE_TOKEN = "device_token"
    private const val KEY_PAIRED_PC_NAME = "paired_pc_name"

    private val gson = Gson()
    private val clientScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var activeListener: PcRemoteEventListener? = null
    private var connectionStateListener: ((Boolean, Long) -> Unit)? = null
    private var onPairingResultListener: ((Boolean, String) -> Unit)? = null
    private var onAuthResultListener: ((Boolean, String) -> Unit)? = null
    var onStatusListener: ((String) -> Unit)? = null
    var onPcNameListener: ((String) -> Unit)? = null

    private var appContext: Context? = null
    private var lastPingSent = 0L
    private var currentPingMs = -1L

    fun hasConfiguredPc(context: Context): Boolean {
        return getPin(context).isNotBlank()
    }

    fun getPcHost(context: Context): String {
        val pin = getPin(context)
        return if (pin.isNotBlank()) "WebRTC P2P (PIN: $pin)" else "Not Configured"
    }

    fun getPin(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PAIRING_PIN, "") ?: ""
    }

    fun setPin(context: Context, pin: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_PAIRING_PIN, pin.trim())
            .apply()
    }

    fun setPcHost(context: Context, host: String, port: Int = 8080) {
        val digits = host.replace(Regex("\\D"), "")
        if (digits.length == 6) {
            setPin(context, digits)
            connect(context)
        }
    }

    fun getDeviceToken(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DEVICE_TOKEN, "") ?: ""
    }

    fun setDeviceToken(context: Context, token: String, pcName: String = "Windows PC") {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_DEVICE_TOKEN, token.trim())
            .putString(KEY_PAIRED_PC_NAME, pcName.trim())
            .apply()
    }

    fun getPairedPcName(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PAIRED_PC_NAME, "Windows PC") ?: "Windows PC"
    }

    fun isPaired(context: Context): Boolean = getPin(context).isNotBlank()

    fun forgetDevice(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_PAIRING_PIN)
            .remove(KEY_DEVICE_TOKEN)
            .remove(KEY_PAIRED_PC_NAME)
            .apply()
        disconnect()
    }

    fun setListener(listener: PcRemoteEventListener?) {
        activeListener = listener
    }

    private var pingJob: Job? = null
    @Volatile
    private var lastPongReceived = 0L

    fun setConnectionStateListener(listener: ((Boolean, Long) -> Unit)?) {
        connectionStateListener = listener
        listener?.invoke(isConnected(), currentPingMs)
        if (isConnected()) {
            sendPing()
        }
    }

    fun isConnected(): Boolean = AnoWebRtcManager.isConnected()

    fun startPingLoop() {
        pingJob?.cancel()
        lastPongReceived = System.currentTimeMillis()
        var missedProbes = 0
        pingJob = clientScope.launch {
            while (isActive && isConnected()) {
                sendPing()
                delay(2000L)
                val elapsed = System.currentTimeMillis() - lastPongReceived
                if (lastPongReceived > 0 && elapsed > 4500L) {
                    missedProbes++
                    Log.d(TAG, "Heartbeat probe delayed: ${elapsed}ms (missed probe $missedProbes/3)")
                    if (elapsed > 15000L || missedProbes >= 4) {
                        Log.w(TAG, "Heartbeat watchdog timeout: No pong from PC for >15s. Declaring disconnected.")
                        stopPingLoop()
                        currentPingMs = -1L
                        disconnect()
                        clientScope.launch {
                            connectionStateListener?.invoke(false, -1L)
                        }
                        break
                    }
                } else {
                    missedProbes = 0
                }
            }
        }
    }

    fun stopPingLoop() {
        pingJob?.cancel()
        pingJob = null
    }

    private fun setupManagerCallbacks(context: Context) {
        AnoWebRtcManager.onConnected = {
            Log.i(TAG, "WebRTC P2P DataChannel Connected! Verifying PIN...")
            startPingLoop()
            clientScope.launch {
                connectionStateListener?.invoke(true, if (currentPingMs > 0) currentPingMs else 15L)
                sendRaw(gson.toJson(mapOf(
                    "type" to "pair_request",
                    "pin" to getPin(context),
                    "deviceName" to Build.MODEL
                )))
                send("pc_get_taskbar")
            }
        }

        AnoWebRtcManager.onDisconnected = {
            Log.i(TAG, "WebRTC P2P DataChannel Disconnected.")
            stopPingLoop()
            currentPingMs = -1L
            clientScope.launch {
                connectionStateListener?.invoke(false, -1L)
            }
        }

        AnoWebRtcManager.onControlMessage = { text ->
            handleTextMessage(text)
        }

        AnoWebRtcManager.onVideoFrame = { bytes ->
            activeListener?.onPcScreenBinaryFrame(bytes)
        }

        AnoWebRtcManager.onError = { error ->
            Log.w(TAG, "WebRTC error: $error")
            stopPingLoop()
            clientScope.launch {
                onPairingResultListener?.invoke(false, error)
                connectionStateListener?.invoke(false, -1L)
            }
        }

        AnoWebRtcManager.onStatusUpdate = { status ->
            clientScope.launch {
                onStatusListener?.invoke(status)
            }
        }
    }

    fun connect(context: Context) {
        appContext = context.applicationContext
        val pin = getPin(context)
        if (pin.isBlank()) return
        setupManagerCallbacks(context)
        if (isConnected()) {
            if (pingJob == null || pingJob?.isActive != true) {
                startPingLoop()
            }
            sendPing()
            return
        }
        AnoWebRtcManager.connect(context, pin)
    }

    fun pairWithPin(pin: String, onResult: (Boolean, String) -> Unit) {
        val ctx = appContext ?: return
        pairWithPin(ctx, pin, onResult)
    }

    fun pairWithPin(context: Context, pin: String, onResult: (Boolean, String) -> Unit) {
        appContext = context.applicationContext
        onPairingResultListener = onResult
        val cleanPin = pin.trim()
        setPin(context, cleanPin)
        setupManagerCallbacks(context)
        AnoWebRtcManager.connect(context, cleanPin)
    }

    fun disconnect() {
        stopPingLoop()
        AnoWebRtcManager.disconnect()
    }

    fun notifyAppClosing() {
        stopPingLoop()
        try {
            sendRaw(gson.toJson(mapOf("type" to "app_closing")))
        } catch (_: Exception) {}
        disconnect()
    }

    private fun handleTextMessage(text: String) {
        try {
            val json = gson.fromJson(text, JsonObject::class.java)
            val type = json.get("type")?.asString ?: json.get("action")?.asString ?: ""

            when (type) {
                "pair_response" -> {
                    val success = json.get("success")?.asBoolean ?: false
                    if (success) {
                        val deviceToken = json.get("deviceToken")?.asString ?: ""
                        val pcName = json.get("pcName")?.asString ?: "Windows PC"
                        if (deviceToken.isNotBlank()) {
                            appContext?.let { setDeviceToken(it, deviceToken, pcName) }
                            Log.i(TAG, "WebRTC Pairing successful with $pcName!")
                            clientScope.launch {
                                onPcNameListener?.invoke(pcName)
                                onPairingResultListener?.invoke(true, "WebRTC Connected to $pcName")
                            }
                        }
                    } else {
                        val msg = json.get("message")?.asString ?: "Pairing failed"
                        clientScope.launch {
                            onPairingResultListener?.invoke(false, msg)
                        }
                    }
                }
                "pong" -> {
                    lastPongReceived = System.currentTimeMillis()
                    if (lastPingSent > 0) {
                        currentPingMs = (System.currentTimeMillis() - lastPingSent).coerceAtLeast(1L)
                        clientScope.launch {
                            connectionStateListener?.invoke(true, currentPingMs)
                        }
                    }
                    activeListener?.onPcMessage("pong", json)
                }
                "session_close", "app_closing" -> {
                    Log.i(TAG, "PC Server notified session close / app closing.")
                    stopPingLoop()
                    currentPingMs = -1L
                    disconnect()
                    clientScope.launch {
                        connectionStateListener?.invoke(false, -1L)
                    }
                }
                "taskbar_apps" -> {
                    activeListener?.onTaskbarApps(text)
                }
                "pc_screen_frame" -> {
                    val data = json.get("data")?.asString
                    if (data != null) {
                        activeListener?.onPcScreenFrame(data)
                    }
                }
                "window_focused", "power_status", "pc_hardware_stats", "pc_battery_status", "pc_files_response", "pc_file_action_result", "file_download_start", "file_download_chunk", "file_download_complete", "file_download_error" -> {
                    activeListener?.onPcMessage(type, json)
                }
                else -> {
                    TailConnectService.handleRemoteControlMessage(text)
                    activeListener?.onPcMessage(type, json)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error handling PC WebRTC message: ${e.message}")
        }
    }

    fun sendRaw(text: String) {
        AnoWebRtcManager.sendControl(text)
    }

    fun send(action: String, extra: Map<String, Any> = emptyMap()) {
        if (action.startsWith("{") && action.endsWith("}")) {
            sendRaw(action)
            return
        }
        val payload = mutableMapOf<String, Any>("action" to action)
        payload.putAll(extra)
        sendRaw(gson.toJson(payload))
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
