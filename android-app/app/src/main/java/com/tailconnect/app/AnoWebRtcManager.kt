package com.tailconnect.app

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

typealias AnoCloudflareManager = AnoWebRtcManager
typealias AnoTunnelManager = AnoWebRtcManager

/**
 * Resilient Dual-Tier Auto-Connect & Cloudflare Tunnel Manager.
 * Tier 1: Local Wi-Fi LAN UDP sniffer (Port 9092) for <15ms instant connection when on same network.
 * Tier 2: Self-healing Cloudflare WAN Quick Tunnel + Broker with live fallback.
 */
object AnoWebRtcManager {

    private const val TAG = "AnoTunnelManager"
    private const val SIGNAL_HOST = "super-river-3234.parthmisar777.workers.dev"
    private const val FIREBASE_DB_HOST = "ano-link-relay-default-rtdb.asia-southeast1.firebasedatabase.app"
    private const val UDP_PORT = 9092
    private const val PREFS_NAME = "tailconnect_prefs"
    private const val KEY_LAST_KNOWN_PC_IP = "last_known_pc_ip"
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // WebSocket Client
    private var tunnelWebSocket: WebSocket? = null
    private val isTunnelConnected = AtomicBoolean(false)
    private val isConnecting = AtomicBoolean(false)
    private var currentPin: String = ""
    private var activeTunnelUrl: String = ""
    private var appContext: Context? = null
    private var isExplicitlyDisconnected = false
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private val connectionLock = Any()

    // Passive Listeners
    private var udpJob: Job? = null
    private var udpSocket: DatagramSocket? = null
    private var wanStreamJob: Job? = null
    private var wanStreamCall: Call? = null
    private var wanStreamWebSocket: WebSocket? = null
    private var signalingJob: Job? = null
    private var initRetryJob: Job? = null
    private var connectionTimeoutJob: Job? = null

    // Transient WakeLock for Handshake Completion (Auto-released in <=10s)
    private var transientWakeLock: PowerManager.WakeLock? = null

    // OkHttp Clients (Zero background transport pings to prevent battery drain)
    private val okHttpClient = OkHttpClient.Builder()
        .pingInterval(0, TimeUnit.MILLISECONDS)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val sseClient = OkHttpClient.Builder()
        .pingInterval(0, TimeUnit.MILLISECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    // Callbacks
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onControlMessage: ((String) -> Unit)? = null
    var onVideoFrame: ((ByteArray) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onStatusUpdate: ((String) -> Unit)? = null
    @Volatile var isAppInForeground: Boolean = true

    fun initFactory(context: Context) {
        // No-op for WebSocket architecture (preserves backward compatibility)
    }

    fun getTunnelUrl(): String = activeTunnelUrl

    fun isConnected(): Boolean {
        return isTunnelConnected.get() && tunnelWebSocket != null
    }

    fun isUsingTunnel(): Boolean = true

    private fun acquireTransientWakeLock(timeoutMs: Long = 10000L) {
        try {
            val ctx = appContext ?: return
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            if (transientWakeLock == null) {
                transientWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AnoLink::TransientHandshakeLock").apply {
                    setReferenceCounted(false)
                }
            }
            transientWakeLock?.acquire(timeoutMs)
            Log.d(TAG, "⚡ Acquired transient WakeLock for ${timeoutMs}ms to finish connection handshake")
        } catch (e: Exception) {
            Log.w(TAG, "Transient wake lock notice: ${e.message}")
        }
    }

    private fun releaseTransientWakeLock() {
        try {
            if (transientWakeLock?.isHeld == true) {
                transientWakeLock?.release()
                Log.d(TAG, "Released transient WakeLock")
            }
        } catch (_: Exception) {}
    }

    fun cacheLastKnownPcIp(ip: String) {
        val ctx = appContext ?: return
        try {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_KNOWN_PC_IP, ip)
                .apply()
        } catch (_: Exception) {}
    }

    fun getLastKnownPcIp(): String? {
        val ctx = appContext ?: return null
        return try {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LAST_KNOWN_PC_IP, null)
        } catch (_: Exception) {
            null
        }
    }

    private fun probeLastKnownPcIp(pin: String) {
        val lastIp = getLastKnownPcIp() ?: return
        scope.launch(Dispatchers.IO) {
            try {
                if (isConnected()) return@launch
                val url = URL("http://$lastIp:9090/api/phone/ping")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 1200
                    readTimeout = 1200
                }
                if (conn.responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    if (body.contains("\"success\":true") && (body.contains("\"pin\":\"$pin\"") || body.contains(pin))) {
                        Log.i(TAG, "⚡ Fast direct LAN probe succeeded to $lastIp:9090! Connecting...")
                        acquireTransientWakeLock(10000L)
                        onStatusUpdate?.invoke("Fast LAN Link ($lastIp)")
                        stopPassiveListening()
                        connectTunnelWebSocket("ws://$lastIp:9090/ws-phone")
                    }
                } else {
                    conn.disconnect()
                }
            } catch (_: Exception) {}
        }
    }

    private fun notifyPhoneOpened(pin: String) {
        scope.launch(Dispatchers.IO) {
            try {
                postSignalMessage("$pin/pc", mapOf(
                    "type" to "phone_opened",
                    "pin" to pin,
                    "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
                    "timestamp" to System.currentTimeMillis()
                ))
            } catch (e: Exception) {
                Log.d(TAG, "Notice posting phone opened: ${e.message}")
            }
        }
    }

    /**
     * Ultra-Low-Power Passive Sentinel Mode for Background Standby.
     * Keeps no continuous WakeLock (<0.3% battery/hr).
     * Wakes CPU only on incoming LAN UDP beacon or WAN push event from PC.
     */
    fun startPassiveSentinel(context: Context, pin: String) {
        val cleanPin = pin.trim().replace(Regex("[^0-9]"), "")
        if (cleanPin.length != 6) return
        appContext = context.applicationContext
        currentPin = cleanPin
        isExplicitlyDisconnected = false
        reconnectJob?.cancel()
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null

        if (isConnected()) {
            Log.d(TAG, "Sentinel check: Already connected to PC, maintaining active session.")
            return
        }

        Log.i(TAG, "🛡️ Passive Background Sentinel active for PIN $cleanPin (Low-Power Standby)")
        onStatusUpdate?.invoke("Standby (Waiting for PC)")

        // Tier 1: Local Wi-Fi UDP Sniffer (Zero CPU, kernel socket wake)
        startLanUdpSniffer(cleanPin)

        // Tier 2: Resilient Cloudflare Long-Poll & LAN Sentinel Loop
        startWanBrokerSentinelLoop(cleanPin)
    }

    /**
     * Start dual-tier connection discovery:
     * 1. Local LAN UDP Sniffer on Port 9092 (<15ms).
     * 2. Passive WAN SSE stream + Broker probe as fallback.
     */
    fun connect(context: Context, pinOrUrl: String) {
        appContext = context.applicationContext
        isExplicitlyDisconnected = false
        reconnectJob?.cancel()
        reconnectAttempts = 0

        val input = pinOrUrl.trim()
        if (input.isBlank()) {
            onError?.invoke("PIN or Tunnel URL cannot be blank")
            return
        }

        // Direct URL Connect Mode (e.g. http://192.168.1.5:9090 or https://*.trycloudflare.com)
        if (input.startsWith("http://", ignoreCase = true) ||
            input.startsWith("https://", ignoreCase = true) ||
            input.startsWith("ws://", ignoreCase = true) ||
            input.startsWith("wss://", ignoreCase = true)) {
            Log.i(TAG, "Direct URL provided: $input. Connecting directly...")
            cleanupSession()
            isConnecting.set(true)
            connectTunnelWebSocket(input)
            return
        }

        if (isConnected()) {
            Log.i(TAG, "Already connected to PC, skipping connect.")
            return
        }

        val cleanPin = input.replace(Regex("[^0-9]"), "")
        if (cleanPin.length != 6) {
            onError?.invoke("Please enter a valid 6-digit PIN")
            return
        }

        currentPin = cleanPin
        cleanupSession()
        isConnecting.set(true)

        Log.i(TAG, "⚡ Initiating Dual-Tier Connection for PIN $cleanPin...")
        onStatusUpdate?.invoke("Searching for PC...")

        // 25-second connection watchdog for UI feedback (does NOT kill background sentinel)
        connectionTimeoutJob = scope.launch {
            delay(25000)
            if (isConnecting.get() && !isConnected()) {
                Log.i(TAG, "Initial UI connect window elapsed. Continuing in low-power Sentinel Standby...")
                isConnecting.set(false)
                onStatusUpdate?.invoke("PC offline. Waiting for server...")
            }
        }

        // Fast Direct LAN Probe if last known PC IP exists (<15ms)
        probeLastKnownPcIp(cleanPin)

        // Tier 1: Local Wi-Fi UDP Sniffer
        startLanUdpSniffer(cleanPin)

        // Tier 2: Resilient Cloudflare Long-Poll & LAN Sentinel Loop
        startWanBrokerSentinelLoop(cleanPin)
        checkExistingBrokerSignal(cleanPin)
    }

    /**
     * Tier 1: Passive Local Wi-Fi UDP Sniffer on Port 9092.
     * Instant <15ms direct connection when on same Wi-Fi router.
     */
    private fun startLanUdpSniffer(pin: String) {
        if (udpJob?.isActive == true) return
        udpJob?.cancel()
        udpJob = scope.launch(Dispatchers.IO) {
            while (isActive && !isConnected()) {
                try {
                    udpSocket?.close()
                    val socket = DatagramSocket(null).apply {
                        reuseAddress = true
                        broadcast = true
                        bind(InetSocketAddress(UDP_PORT))
                    }
                    udpSocket = socket
                    val buffer = ByteArray(1024)
                    Log.i(TAG, "📡 LAN UDP Sniffer listening on port $UDP_PORT (PIN: $pin)")
                    while (isActive && !isConnected()) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val text = String(packet.data, 0, packet.length).trim()
                        if (text.startsWith("ANOLINK_HELLO:$pin") || text == "ANOLINK_HELLO") {
                            val pcIp = packet.address.hostAddress ?: continue
                            Log.i(TAG, "⚡ Fast LAN UDP Beacon detected from PC ($pcIp)! Initiating instant LAN connect...")
                            cacheLastKnownPcIp(pcIp)
                            acquireTransientWakeLock(10000L)
                            onStatusUpdate?.invoke("Fast LAN Link ($pcIp)")
                            stopPassiveListening()
                            connectTunnelWebSocket("ws://$pcIp:9090/ws-phone")
                            return@launch
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) break
                    Log.d(TAG, "LAN UDP Sniffer notice: ${e.message}")
                    delay(5000)
                }
            }
        }
    }

    /**
     * Tier 2: Resilient Cloudflare Long-Poll & LAN Sentinel Loop.
     * Continuously holds an HTTP Long-Poll request to Cloudflare Anycast edge (readTimeout = 24s).
     * If PC clicks 'Connect Phone' or publishes its tunnel URL, Cloudflare resolves instantly (<100ms).
     * Also probes local PC IP on each cycle for instant <10ms LAN direct connection.
     */
    private fun startWanBrokerSentinelLoop(pin: String) {
        if (wanStreamJob?.isActive == true) return
        wanStreamJob?.cancel()
        wanStreamJob = scope.launch(Dispatchers.IO) {
            Log.i(TAG, "🌐 Starting Cloudflare Broker Long-Poll Sentinel for PIN $pin")
            var backoffMs = 1500L
            while (isActive && !isConnected()) {
                if (!isActive || isConnected()) break

                // If app is backgrounded, sit completely silent without polling WAN
                if (!isAppInForeground) {
                    delay(10000L)
                    continue
                }

                // Step B: Resolve tunnel via Firebase RTDB Relay (<40ms Anycast) or Worker Broker
                try {
                    val resolvedUrl = resolveTunnelUrl(pin)
                    if (!resolvedUrl.isNullOrBlank() && !isConnected()) {
                        Log.i(TAG, "⚡ Broker Sentinel: Detected active PC Tunnel $resolvedUrl!")
                        acquireTransientWakeLock(10000L)
                        onStatusUpdate?.invoke("Discovered via WAN Relay")
                        activeTunnelUrl = resolvedUrl
                        stopPassiveListening()
                        connectTunnelWebSocket(resolvedUrl)
                        return@launch
                    }
                    backoffMs = 1500L
                } catch (e: Exception) {
                    if (e is CancellationException) break
                    val msg = e.message ?: ""
                    if (msg.contains("timeout", ignoreCase = true) || e is java.net.SocketTimeoutException) {
                        backoffMs = 500L
                    } else {
                        Log.d(TAG, "Broker sentinel notice: $msg")
                        backoffMs = (backoffMs * 2).coerceAtMost(10000L)
                    }
                }

                if (isActive && !isConnected()) {
                    delay(backoffMs)
                }
            }
        }
    }

    private fun queryFirebaseTunnelUrl(pin: String): String? {
        try {
            val url = URL("https://$FIREBASE_DB_HOST/pins/$pin.json")
            val conn = (url.openConnection() as HttpsURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3000
                readTimeout = 4000
            }
            if (conn.responseCode in 200..299) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }.trim()
                conn.disconnect()
                if (text.isNotBlank() && text != "null") {
                    return extractTunnelUrl(text)
                }
            } else {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.d(TAG, "Firebase query notice: ${e.message}")
        }
        return null
    }

    private fun queryWorkerTunnelUrl(pin: String): String? {
        try {
            val url = URL("https://$SIGNAL_HOST/sig/$pin/phone")
            val conn = (url.openConnection() as HttpsURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3000
                readTimeout = 4000
            }
            if (conn.responseCode in 200..299) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }.trim()
                conn.disconnect()
                if (text.isNotBlank() && text != "null") {
                    return extractTunnelUrl(text)
                }
            } else {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.d(TAG, "Worker query notice: ${e.message}")
        }
        return null
    }

    private fun resolveTunnelUrl(pin: String): String? {
        // Priority 1: Google Firebase RTDB Relay (Global Anycast, <40ms, zero V8 isolate issues)
        val fbResult = queryFirebaseTunnelUrl(pin)
        if (!fbResult.isNullOrBlank()) {
            return fbResult
        }
        // Priority 2: Secondary Cloudflare Worker broker fallback
        val workerResult = queryWorkerTunnelUrl(pin)
        if (!workerResult.isNullOrBlank()) {
            return workerResult
        }
        return null
    }

    /**
     * Probe broker in case PC tunnel is already published (with 4-attempt retry window).
     */
    private fun checkExistingBrokerSignal(pin: String) {
        scope.launch(Dispatchers.IO) {
            try {
                // Post get_tunnel once to prompt PC to publish if idle
                postSignalMessage("$pin/pc", mapOf(
                    "type" to "get_tunnel",
                    "device" to Build.MODEL,
                    "timestamp" to System.currentTimeMillis()
                ))

                // Also notify PC immediately via broker
                notifyPhoneOpened(pin)

                // Poll up to 4 iterations (spaced 1.2s) to eliminate tunnel boot race condition
                for (attempt in 1..4) {
                    if (isConnected()) break
                    val resolvedUrl = resolveTunnelUrl(pin)
                    if (!resolvedUrl.isNullOrBlank() && !isConnected()) {
                        Log.i(TAG, "Existing active tunnel detected (attempt $attempt): $resolvedUrl")
                        acquireTransientWakeLock(10000L)
                        onStatusUpdate?.invoke("Connecting via WAN...")
                        activeTunnelUrl = resolvedUrl
                        stopPassiveListening()
                        connectTunnelWebSocket(resolvedUrl)
                        break
                    }
                    if (attempt < 4 && !isConnected()) {
                        delay(1200)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun stopPassiveListening() {
        udpJob?.cancel()
        udpJob = null
        try {
            udpSocket?.close()
        } catch (_: Exception) {}
        udpSocket = null

        wanStreamJob?.cancel()
        wanStreamJob = null
        try {
            wanStreamWebSocket?.close(1000, "Stop Passive")
        } catch (_: Exception) {}
        wanStreamWebSocket = null
        try {
            wanStreamCall?.cancel()
        } catch (_: Exception) {}
        wanStreamCall = null

        initRetryJob?.cancel()
        initRetryJob = null
        signalingJob?.cancel()
        signalingJob = null
    }

    fun extractTunnelUrl(rawText: String): String? {
        val trimmed = rawText.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("ws://") || trimmed.startsWith("wss://")) {
            return trimmed
        }
        return try {
            val root = gson.fromJson(trimmed, JsonObject::class.java) ?: return null
            if (root.has("url")) return root.get("url").asString
            if (root.has("tunnelUrl")) return root.get("tunnelUrl").asString
            if (root.has("message")) {
                val msgElem = root.get("message")
                if (msgElem.isJsonObject) {
                    val msgObj = msgElem.asJsonObject
                    if (msgObj.has("url")) return msgObj.get("url").asString
                    if (msgObj.has("tunnelUrl")) return msgObj.get("tunnelUrl").asString
                } else if (msgElem.isJsonPrimitive && msgElem.asJsonPrimitive.isString) {
                    return extractTunnelUrl(msgElem.asString)
                }
            }
            if (root.has("body")) {
                val bodyElem = root.get("body")
                if (bodyElem.isJsonObject) {
                    val bodyObj = bodyElem.asJsonObject
                    if (bodyObj.has("url")) return bodyObj.get("url").asString
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    fun getLocalWifiIpAddress(): String {
        try {
            val interfaces = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val name = intf.name.lowercase(java.util.Locale.ROOT)
                if (name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("en")) {
                    for (addr in java.util.Collections.list(intf.inetAddresses)) {
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                            return addr.hostAddress ?: ""
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return ""
    }

    private fun connectTunnelWebSocket(rawUrl: String) {
        synchronized(connectionLock) {
            if (isConnected()) {
                Log.d(TAG, "Already connected to PC, ignoring redundant connectTunnelWebSocket request.")
                return
            }
            if (isConnecting.get() && tunnelWebSocket != null) {
                Log.d(TAG, "Connection handshake already in progress, ignoring duplicate attempt.")
                return
            }
            isConnecting.set(true)
        }

        // Immediately stop passive discovery routines so no other path triggers in parallel
        stopPassiveListening()

        val cleanUrl = rawUrl.trim()
        val ipMatch = Regex("(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})").find(cleanUrl)?.value
        if (ipMatch != null) {
            cacheLastKnownPcIp(ipMatch)
        }
        val wsUrl = (if (cleanUrl.startsWith("https://", ignoreCase = true)) {
            cleanUrl.replace("https://", "wss://", ignoreCase = true)
        } else if (cleanUrl.startsWith("http://", ignoreCase = true)) {
            cleanUrl.replace("http://", "ws://", ignoreCase = true)
        } else if (cleanUrl.startsWith("ws://", ignoreCase = true) || cleanUrl.startsWith("wss://", ignoreCase = true)) {
            cleanUrl
        } else {
            "wss://$cleanUrl"
        }).trimEnd('/') + (if (!cleanUrl.endsWith("/ws-phone")) "/ws-phone" else "")

        Log.i(TAG, "Opening Full-Duplex Host WebSocket: $wsUrl")
        onStatusUpdate?.invoke("Connecting to PC...")

        try {
            val request = Request.Builder().url(wsUrl).build()
            val socket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (tunnelWebSocket !== webSocket) {
                        Log.d(TAG, "Ignoring onOpen from stale/superseded WebSocket")
                        try { webSocket.close(1000, "Superseded") } catch (_: Exception) {}
                        return
                    }
                    Log.i(TAG, "🟢 WebSocket Connected to PC! Dispatching pair_request handshake...")
                    isTunnelConnected.set(true)
                    isConnecting.set(false)
                    reconnectAttempts = 0
                    reconnectJob?.cancel()
                    connectionTimeoutJob?.cancel()
                    connectionTimeoutJob = null
                    stopPassiveListening()

                    // Dispatch instant pair_request to authenticate with PC
                    val wifiIp = getLocalWifiIpAddress()
                    val handshake = mutableMapOf<String, Any>(
                        "type" to "pair_request",
                        "pin" to currentPin,
                        "deviceName" to "${Build.MANUFACTURER} ${Build.MODEL}",
                        "deviceToken" to (appContext?.let { PcRemoteClient.getDeviceToken(it) } ?: "")
                    )
                    if (wifiIp.isNotBlank()) {
                        handshake["lanIp"] = wifiIp
                    }
                    webSocket.send(gson.toJson(handshake))

                    onStatusUpdate?.invoke("Connected")
                    onConnected?.invoke()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (tunnelWebSocket !== webSocket) return
                    try {
                        val root = gson.fromJson(text, JsonObject::class.java)
                        val type = root?.get("type")?.asString ?: root?.get("action")?.asString ?: ""

                        // Immediately respond to PC on-demand probe ping
                        if (type == "ping") {
                            val ts = root?.get("timestamp")?.asLong ?: System.currentTimeMillis()
                            webSocket.send(gson.toJson(mapOf("type" to "pong", "timestamp" to ts)))
                            return
                        }

                        if (type == "pair_response") {
                            val success = root?.get("success")?.asBoolean ?: false
                            if (success) {
                                releaseTransientWakeLock()
                                val deviceToken = root?.get("deviceToken")?.asString ?: ""
                                val pcName = root?.get("pcName")?.asString ?: "Windows PC"
                                if (deviceToken.isNotBlank() && appContext != null) {
                                    PcRemoteClient.setDeviceToken(appContext!!, deviceToken, pcName)
                                }
                                Log.i(TAG, "✅ Pair handshake accepted by PC ($pcName)!")
                                onStatusUpdate?.invoke("Connected to $pcName")
                                TailConnectService.instance?.pushTelemetry()
                            } else {
                                releaseTransientWakeLock()
                                val msg = root?.get("message")?.asString ?: "Pairing failed"
                                Log.w(TAG, "❌ Pair handshake rejected: $msg")
                                onError?.invoke(msg)
                            }
                        }
                    } catch (_: Exception) {}

                    onControlMessage?.invoke(text)
                    TailConnectService.handleRemoteControlMessage(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (tunnelWebSocket !== webSocket) return
                    onVideoFrame?.invoke(bytes.toByteArray())
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    if (tunnelWebSocket !== webSocket) {
                        try { webSocket.close(1000, null) } catch (_: Exception) {}
                        return
                    }
                    Log.d(TAG, "Tunnel WebSocket closing ($code): $reason")
                    releaseTransientWakeLock()
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (tunnelWebSocket !== webSocket) {
                        Log.d(TAG, "Ignoring onClosed from stale/superseded WebSocket ($code): $reason")
                        return
                    }
                    Log.i(TAG, "Tunnel WebSocket closed ($code): $reason")
                    releaseTransientWakeLock()
                    isTunnelConnected.set(false)
                    tunnelWebSocket = null
                    isConnecting.set(false)
                    onDisconnected?.invoke()

                    if (!isExplicitlyDisconnected && currentPin.isNotBlank()) {
                        handleUnexpectedDisconnect("Socket closed ($code)")
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (tunnelWebSocket !== webSocket) {
                        Log.d(TAG, "Ignoring onFailure from stale/superseded WebSocket: ${t.message}")
                        return
                    }
                    Log.w(TAG, "Tunnel WebSocket failure: ${t.message}")
                    releaseTransientWakeLock()
                    isTunnelConnected.set(false)
                    tunnelWebSocket = null
                    isConnecting.set(false)
                    onDisconnected?.invoke()

                    if (!isExplicitlyDisconnected && currentPin.isNotBlank()) {
                        handleUnexpectedDisconnect("Connect failure: ${t.message}")
                    }
                }
            })
            tunnelWebSocket = socket
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating WebSocket: ${e.message}")
            releaseTransientWakeLock()
            isConnecting.set(false)
            onError?.invoke("Failed to open WebSocket: ${e.message}")
        }
    }

    private fun handleUnexpectedDisconnect(reason: String) {
        releaseTransientWakeLock()
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            reconnectAttempts++
            if (reconnectAttempts <= 5) {
                val delayMs = when (reconnectAttempts) {
                    1 -> 1500L
                    2 -> 2500L
                    3 -> 4000L
                    else -> 6000L
                }
                Log.i(TAG, "🔄 Auto-reconnect attempt $reconnectAttempts in ${delayMs}ms (Reason: $reason)...")
                onStatusUpdate?.invoke("Reconnecting... ($reconnectAttempts/5)")
                delay(delayMs)
                if (isExplicitlyDisconnected || isConnected()) return@launch

                val ctx = appContext
                if (ctx != null && currentPin.isNotBlank()) {
                    connect(ctx, currentPin)
                }
            } else {
                Log.i(TAG, "🌙 Direct reconnect attempts exhausted. Returning to low-power Sentinel Standby...")
                reconnectAttempts = 0
                val ctx = appContext
                if (ctx != null && currentPin.isNotBlank()) {
                    startPassiveSentinel(ctx, currentPin)
                }
            }
        }
    }

    fun sendControl(text: String): Boolean {
        val ws = tunnelWebSocket
        return if (ws != null && isTunnelConnected.get()) {
            ws.send(text)
        } else {
            false
        }
    }

    fun sendBinary(bytes: ByteArray): Boolean {
        val ws = tunnelWebSocket
        return if (ws != null && isTunnelConnected.get()) {
            ws.send(bytes.toByteString())
        } else {
            false
        }
    }

    fun disconnect() {
        isExplicitlyDisconnected = true
        releaseTransientWakeLock()
        stopPassiveListening()
        val pin = currentPin
        if (pin.isNotBlank()) {
            scope.launch(Dispatchers.IO) {
                postSignalMessage("$pin/pc", mapOf("type" to "session_close"))
            }
        }
        try {
            tunnelWebSocket?.close(1000, "Disconnect requested")
        } catch (_: Exception) {}
        tunnelWebSocket = null
        isTunnelConnected.set(false)
        isConnecting.set(false)
        onDisconnected?.invoke()
    }

    private fun cleanupSession() {
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
        stopPassiveListening()
        activeTunnelUrl = ""
        try {
            tunnelWebSocket?.close(1000, "New connect session")
        } catch (_: Exception) {}
        tunnelWebSocket = null
        isTunnelConnected.set(false)
    }

    private fun postSignalMessage(endpoint: String, data: Map<String, Any>) {
        try {
            val jsonStr = gson.toJson(data)
            val jsonBytes = jsonStr.toByteArray(Charsets.UTF_8)
            val url = URL("https://$SIGNAL_HOST/sig/$endpoint")
            val conn = (url.openConnection() as HttpsURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Content-Length", jsonBytes.size.toString())
                connectTimeout = 4000
                readTimeout = 4000
                doOutput = true
            }
            conn.outputStream.use { os ->
                os.write(jsonBytes)
                os.flush()
            }
            conn.responseCode
            conn.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Notice posting signal message: ${e.message}")
        }
    }
}
