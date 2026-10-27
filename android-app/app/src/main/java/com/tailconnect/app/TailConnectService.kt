package com.tailconnect.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.net.wifi.WifiManager
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.JsonObject
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import android.Manifest
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.core.content.ContextCompat
import java.io.File
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

interface PcRemoteEventListener {
    fun onTaskbarApps(appsJson: String)
    fun onPcScreenFrame(base64Jpeg: String)
    fun onPcScreenBinaryFrame(jpegBytes: ByteArray)
    fun onPcMessage(type: String, json: JsonObject)
}

class TailConnectService : Service() {

    companion object {
        private const val TAG = "TailConnectService"
        private const val CHANNEL_ID = "anolink_service_channel"
        private const val NOTIFICATION_ID = 7771
        private const val PORT = 8081
        private const val AUTH_TOKEN = "ag-secure-token-777"

        var instance: TailConnectService? = null
            private set

        private var activeWsSession: DefaultWebSocketServerSession? = null
        private var pcRemoteListener: PcRemoteEventListener? = null
        private var currentMode: String = "skeleton" // "skeleton" or "video"
        private var isSessionActive: Boolean = false
        private var isStreamPaused: Boolean = false
        private val isSendingVideoFrame = AtomicBoolean(false)
        private val isSendingMicAudio = AtomicBoolean(false)
        private val gson = Gson()

        fun setPcRemoteListener(listener: PcRemoteEventListener?) {
            pcRemoteListener = listener
        }

        fun isPcConnected(): Boolean = activeWsSession != null || PcRemoteClient.isConnected() || AnoWebRtcManager.isConnected()

        fun sendToPc(payload: Map<String, Any>) {
            val jsonStr = gson.toJson(payload)
            if (AnoWebRtcManager.isConnected()) {
                AnoWebRtcManager.sendControl(jsonStr)
            }
            if (PcRemoteClient.isConnected()) {
                PcRemoteClient.sendRaw(jsonStr)
            }
            val session = activeWsSession ?: return
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    session.send(Frame.Text(jsonStr))
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send to PC: ${e.message}")
                }
            }
        }

        fun isSkeletonModeActive(): Boolean = (isSessionActive || PcRemoteClient.isConnected() || AnoWebRtcManager.isConnected()) && !isStreamPaused && currentMode == "skeleton"

        fun broadcastSkeletonTree(tree: NodeDto, width: Int, height: Int) {
            val map = mapOf(
                "type" to "skeleton_tree",
                "tree" to tree,
                "screenWidth" to width,
                "screenHeight" to height
            )
            val jsonStr = gson.toJson(map)
            if (AnoWebRtcManager.isConnected()) {
                AnoWebRtcManager.sendControl(jsonStr)
            }
            if (PcRemoteClient.isConnected()) {
                PcRemoteClient.sendRaw(jsonStr)
            }

            val session = activeWsSession ?: return
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    session.send(Frame.Text(jsonStr))
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send skeleton tree: ${e.message}")
                }
            }
        }

        fun sendScreenMetrics() {
            val service = RemoteAccessibilityService.instance
            val metrics = service?.getRealScreenMetrics() ?: run {
                val dm = android.content.res.Resources.getSystem().displayMetrics
                Pair(dm.widthPixels, dm.heightPixels)
            }
            sendToPc(mapOf(
                "type" to "screen_metrics",
                "screenWidth" to metrics.first,
                "screenHeight" to metrics.second
            ))
        }

        fun broadcastVideoFrame(bytes: ByteArray) {
            if (isStreamPaused) return

            // Stream binary frame over WebRTC / WebSocket Tunnel
            if (AnoWebRtcManager.isConnected()) {
                AnoWebRtcManager.sendBinary(bytes)
            }

            val session = activeWsSession ?: return
            // Drop frame if previous frame is still in flight over the local network
            if (!isSendingVideoFrame.compareAndSet(false, true)) {
                return
            }

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    session.send(Frame.Binary(true, bytes))
                } catch (e: Exception) {
                    // Skip frame if network error
                } finally {
                    isSendingVideoFrame.set(false)
                }
            }
        }

        fun handleRemoteControlMessage(rawText: String) {
            instance?.let { s ->
                s.serviceScope.launch(Dispatchers.IO) {
                    s.handleIncomingMessage(rawText, activeWsSession)
                }
            }
        }

        fun pushSmsAlert(sender: String, body: String) {
            val map = mapOf(
                "type" to "incoming_sms",
                "sender" to sender,
                "body" to body,
                "timestamp" to System.currentTimeMillis()
            )
            val jsonStr = gson.toJson(map)
            if (AnoWebRtcManager.isConnected()) {
                AnoWebRtcManager.sendControl(jsonStr)
            }
            if (PcRemoteClient.isConnected()) {
                PcRemoteClient.sendRaw(jsonStr)
            }
            val session = activeWsSession ?: return
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    session.send(Frame.Text(jsonStr))
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to push SMS alert: ${e.message}")
                }
            }
        }

        fun onScreenCaptureGranted(resultCode: Int, data: Intent) {
            sendToPc(mapOf(
                "type" to "screen_capture_status",
                "status" to "granted"
            ))
        }

        fun onScreenCaptureDenied() {
            sendToPc(mapOf(
                "type" to "screen_capture_status",
                "status" to "denied"
            ))
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var serverEngine: ApplicationEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var sentinelWakeLock: PowerManager.WakeLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun acquireSentinelLocks() {
        try {
            if (sentinelWakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
                sentinelWakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AnoLink::SentinelWakeLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (sentinelWakeLock?.isHeld != true) {
                sentinelWakeLock?.acquire()
                Log.d(TAG, "🛡️ Acquired Sentinel WakeLock for background responsiveness")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Notice acquiring sentinel wake lock: ${e.message}")
        }

        try {
            if (multicastLock == null) {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                multicastLock = wifiManager?.createMulticastLock("AnoLink::LanMulticastLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (multicastLock?.isHeld != true) {
                multicastLock?.acquire()
                Log.d(TAG, "📡 Acquired Wi-Fi MulticastLock for LAN UDP reception")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Notice acquiring multicast lock: ${e.message}")
        }
    }

    fun releaseSentinelLocks() {
        try {
            if (sentinelWakeLock?.isHeld == true) {
                sentinelWakeLock?.release()
                Log.d(TAG, "Released Sentinel WakeLock")
            }
        } catch (_: Exception) {}
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
                Log.d(TAG, "Released Wi-Fi MulticastLock")
            }
        } catch (_: Exception) {}
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        acquireSentinelLocks()
        try {
            val isPaired = PcRemoteClient.getPin(this).length == 6
            val initialStatus = if (isPaired) "🌙 Standby (Waiting for PC)" else "Ano-Link Service Active"
            val notification = createNotification(initialStatus)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Notice on startForeground: ${e.message}")
        }
        startKtorServer()
        startTelemetryTicker()

        // Launch ultra-low-power passive background sentinel if paired
        PcRemoteClient.startBackgroundSentinel(this)

        Log.i(TAG, "TailConnectService initialized with Passive Sentinel.")
    }

    private fun updateForegroundServiceType(withCamera: Boolean = false, withMic: Boolean = false) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                if (withCamera && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                    }
                }
                if (withMic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    }
                }
                val status = when {
                    withCamera && withMic -> "Live Camera & Audio Active"
                    withCamera -> "Live Camera Active"
                    withMic -> "Microphone Active"
                    else -> "Listening on Tailscale port $PORT"
                }
                startForeground(NOTIFICATION_ID, createNotification(status), type)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update foreground service type: ${e.message}")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        acquireSentinelLocks()
        if (!AnoWebRtcManager.isConnected() && PcRemoteClient.getPin(this).length == 6) {
            PcRemoteClient.startBackgroundSentinel(this)
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "Task swiped from Recents. Maintaining TailConnectService sentinel active.")
        acquireSentinelLocks()
        if (!AnoWebRtcManager.isConnected() && PcRemoteClient.getPin(this).length == 6) {
            PcRemoteClient.startBackgroundSentinel(this)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        PcRemoteClient.notifyAppClosing()
        serviceScope.cancel()
        serverEngine?.stop(1000, 2000)
        releaseActiveWakeLock()
        releaseSentinelLocks()
        Log.i(TAG, "TailConnectService destroyed.")
    }

    fun acquireActiveWakeLock(timeoutMs: Long = 10 * 60 * 1000L) {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AnoLink::ActiveStreamWakeLock").apply {
                    setReferenceCounted(false)
                }
            }
            wakeLock?.let {
                if (!it.isHeld) {
                    it.acquire(timeoutMs)
                    Log.d(TAG, "Acquired active CPU WakeLock for ${timeoutMs}ms")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Notice acquiring wake lock: ${e.message}")
        }
    }

    fun releaseActiveWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "Released active CPU WakeLock (standby)")
                }
            }
        } catch (_: Exception) {}
    }

    fun updateForegroundNotification(customStatus: String? = null) {
        try {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            val status = customStatus ?: when {
                AnoWebRtcManager.isConnected() -> "🟢 Connected to ${PcRemoteClient.getPcName(this).ifBlank { "Windows PC" }}"
                PcRemoteClient.getPin(this).length == 6 -> "🌙 Standby (Waiting for PC)"
                else -> "Ano-Link Service Active"
            }
            manager.notify(NOTIFICATION_ID, createNotification(status))
        } catch (e: Exception) {
            Log.w(TAG, "Notice updating notification: ${e.message}")
        }
    }

    private fun createNotification(statusText: String): Notification {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Ano-Link Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Maintains connection to paired PC over WebRTC & Cloudflare Tunnel"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ano-Link Hub Active")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_ano_logo)
            .setOngoing(true)
            .build()
    }

    // -------------------------------------------------------------
    // Response Sender & Telemetry 5-Minute Background Ticker
    // -------------------------------------------------------------
    private fun sendResponse(payload: Map<String, Any>, session: DefaultWebSocketServerSession? = activeWsSession) {
        val jsonStr = gson.toJson(payload)
        if (AnoWebRtcManager.isConnected()) {
            AnoWebRtcManager.sendControl(jsonStr)
        }
        if (PcRemoteClient.isConnected()) {
            PcRemoteClient.sendRaw(jsonStr)
        }
        val ws = session ?: activeWsSession
        if (ws != null) {
            serviceScope.launch(Dispatchers.IO) {
                try {
                    ws.send(Frame.Text(jsonStr))
                } catch (_: Exception) {}
            }
        }
    }

    private fun startTelemetryTicker() {
        serviceScope.launch {
            while (isActive) {
                if (isPcConnected()) {
                    pushTelemetry()
                }
                // Delay 5 minutes
                delay(5 * 60 * 1000L)
            }
        }
    }

    fun pushTelemetry(session: DefaultWebSocketServerSession? = activeWsSession) {
        val telemetry = TelemetryManager.collect(this@TailConnectService)
        val payload = mapOf(
            "type" to "telemetry",
            "data" to telemetry,
            "timestamp" to System.currentTimeMillis()
        )
        sendResponse(payload, session)
    }

    // -------------------------------------------------------------
    // Ktor CIO Server Engine (Tailscale Port 8081)
    // -------------------------------------------------------------
    private fun startKtorServer() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                serverEngine = embeddedServer(CIO, port = PORT, host = "0.0.0.0") {
                    install(WebSockets) {
                        pingPeriod = null
                        timeout = Duration.ofDays(365)
                        maxFrameSize = Long.MAX_VALUE
                        masking = false
                    }
                    install(CORS) {
                        anyHost()
                        allowHeader("*")
                        allowMethod(io.ktor.http.HttpMethod.Options)
                        allowMethod(io.ktor.http.HttpMethod.Get)
                        allowMethod(io.ktor.http.HttpMethod.Post)
                    }
                    routing {
                        get("/api/health") {
                            call.respondText("OK: Ano-Link Daemon Listening")
                        }

                        get("/api/telemetry") {
                            val data = TelemetryManager.collect(this@TailConnectService)
                            call.respondText(gson.toJson(data), io.ktor.http.ContentType.Application.Json)
                        }

                        // Chunked file downloading with automatic HTTP Range (206) support
                        get("/api/files/download") {
                            val filePath = call.request.queryParameters["path"]
                            if (filePath.isNullOrBlank()) {
                                call.respond(io.ktor.http.HttpStatusCode.BadRequest, "Missing path")
                                return@get
                            }
                            val file = File(filePath)
                            if (!file.exists() || !file.isFile) {
                                call.respond(io.ktor.http.HttpStatusCode.NotFound, "File not found")
                                return@get
                            }
                            call.respondFile(file)
                        }

                        // Main WebSocket endpoint for PC Controller
                        webSocket("/ws") {
                            val token = call.request.queryParameters["token"]
                            if (token != AUTH_TOKEN) {
                                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid Token"))
                                return@webSocket
                            }

                            Log.i(TAG, "PC Controller connected via WebSocket!")
                            acquireActiveWakeLock(15000L)
                            activeWsSession = this
                            isSessionActive = true

                            // Send immediate initial telemetry, initial skeleton tree, and previous SMS history
                            pushTelemetry()
                            sendInitialSkeletonTree()
                            sendScreenMetrics()
                            val initialSms = querySmsHistory(50)
                            if (initialSms.isNotEmpty()) {
                                send(Frame.Text(gson.toJson(mapOf(
                                    "type" to "sms_list",
                                    "messages" to initialSms
                                ))))
                            }

                            if (pcRemoteListener != null) {
                                send(Frame.Text(gson.toJson(mapOf("action" to "start_pc_screen_stream"))))
                                send(Frame.Text(gson.toJson(mapOf("action" to "pc_get_taskbar"))))
                            }

                            try {
                                for (frame in incoming) {
                                    if (frame is Frame.Text) {
                                        val text = frame.readText()
                                        handleIncomingMessage(text, this)
                                    } else if (frame is Frame.Binary) {
                                        val bytes = frame.readBytes()
                                        pcRemoteListener?.onPcScreenBinaryFrame(bytes)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "WebSocket error: ${e.message}")
                            } finally {
                                isSessionActive = false
                                isStreamPaused = false
                                activeWsSession = null
                                ScreenCaptureService.stop(this@TailConnectService)
                                CameraStreamManager.stopCamera()
                                AudioIntercomManager.releaseAll()
                                Log.i(TAG, "PC Controller session ended.")
                            }
                        }
                    }
                }.start(wait = true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Ktor Server: ${e.message}")
            }
        }
    }

    private fun sendInitialSkeletonTree(session: DefaultWebSocketServerSession? = activeWsSession) {
        val service = RemoteAccessibilityService.instance
        if (service != null) {
            val (tree, metrics) = service.captureCurrentTree()
            if (tree != null) {
                val map = mapOf(
                    "type" to "skeleton_tree",
                    "tree" to tree,
                    "screenWidth" to metrics.first,
                    "screenHeight" to metrics.second
                )
                sendResponse(map, session)
            } else {
                val map = mapOf(
                    "type" to "skeleton_error",
                    "message" to "Device screen is locked or window content is not readable."
                )
                sendResponse(map, session)
            }
        } else {
            val map = mapOf(
                "type" to "skeleton_error",
                "message" to "Accessibility Service is not enabled. Please open Android Settings -> Accessibility and enable 'AnoLink Accessibility'."
            )
            sendResponse(map, session)
        }
    }

    private suspend fun handleIncomingMessage(rawText: String, session: DefaultWebSocketServerSession? = activeWsSession) {
        val json = try {
            gson.fromJson(rawText, JsonObject::class.java)
        } catch (e: Exception) {
            return
        }

        val action = json.get("action")?.asString ?: json.get("type")?.asString ?: return

        // Forward PC responses directly to PcRemoteActivity
        if (action == "taskbar_apps") {
            pcRemoteListener?.onTaskbarApps(rawText)
            return
        }
        if (action == "pc_screen_frame") {
            val data = json.get("data")?.asString ?: return
            pcRemoteListener?.onPcScreenFrame(data)
            return
        }
        if (action == "ping") {
            val ts = json.get("timestamp")?.asLong ?: System.currentTimeMillis()
            sendResponse(mapOf("type" to "pong", "timestamp" to ts), session)
            return
        }
        if (action == "window_focused" || action == "power_status" || action == "pong") {
            pcRemoteListener?.onPcMessage(action, json)
            return
        }

        when (action) {
            "get_telemetry", "refresh_telemetry" -> {
                pushTelemetry(session)
            }

            "get_skeleton_tree", "refresh_skeleton" -> {
                sendInitialSkeletonTree(session)
            }

            "set_mode" -> {
                val newMode = json.get("mode")?.asString ?: "skeleton"
                currentMode = newMode
                isStreamPaused = false
                Log.i(TAG, "Mode updated to: $currentMode")
                if (currentMode == "skeleton") {
                    RemoteAccessibilityService.instance?.stopSilentScreenStreaming()
                    ScreenCaptureService.stop(this@TailConnectService)
                    CameraStreamManager.stopCamera()
                    updateForegroundServiceType(withCamera = false, withMic = AudioIntercomManager.isMicStreaming)
                    sendInitialSkeletonTree(session)
                } else if (currentMode == "silent" || currentMode == "silent_screen") {
                    ScreenCaptureService.stop(this@TailConnectService)
                    CameraStreamManager.stopCamera()
                    updateForegroundServiceType(withCamera = false, withMic = AudioIntercomManager.isMicStreaming)
                    val access = RemoteAccessibilityService.instance
                    if (access != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val started = access.startSilentScreenStreaming(10)
                        if (!started) {
                            sendResponse(mapOf(
                                "type" to "stream_error",
                                "message" to "Silent screen streaming could not be started"
                            ), session)
                        }
                    } else {
                        val msg = if (access == null) {
                            "Accessibility Service not running. Please enable Ano-Link Accessibility in Android Settings."
                        } else {
                            "Silent screen capture requires Android 11+ (API 30+)."
                        }
                        sendResponse(mapOf(
                            "type" to "stream_error",
                            "message" to msg
                        ), session)
                    }
                } else if (currentMode == "video") {
                    RemoteAccessibilityService.instance?.stopSilentScreenStreaming()
                    CameraStreamManager.stopCamera()
                    updateForegroundServiceType(withCamera = false, withMic = AudioIntercomManager.isMicStreaming)
                    sendInitialSkeletonTree(session)
                    if (!ScreenCaptureService.isStreaming && !isStreamPaused) {
                        ScreenCaptureActivity.start(this@TailConnectService)
                    }
                } else if (currentMode == "camera") {
                    RemoteAccessibilityService.instance?.stopSilentScreenStreaming()
                    ScreenCaptureService.stop(this@TailConnectService)
                    updateForegroundServiceType(withCamera = true, withMic = AudioIntercomManager.isMicStreaming)
                    if (!CameraStreamManager.isStreaming && !isStreamPaused) {
                        val front = json.get("front")?.asBoolean ?: false
                        val started = CameraStreamManager.startCamera(this@TailConnectService, front = front) { jpegBytes ->
                            broadcastVideoFrame(jpegBytes)
                        }
                        if (!started) {
                            sendResponse(mapOf(
                                "type" to "stream_error",
                                "message" to "Camera permission not granted or sensor unavailable"
                            ), session)
                        }
                    }
                }
            }

            "pause_stream", "stop_stream" -> {
                isStreamPaused = true
                RemoteAccessibilityService.instance?.stopSilentScreenStreaming()
                ScreenCaptureService.stop(this@TailConnectService)
                CameraStreamManager.stopCamera()
                updateForegroundServiceType(withCamera = false, withMic = AudioIntercomManager.isMicStreaming)
                Log.i(TAG, "Streaming paused by PC controller. Conserving resources.")
            }

            "resume_stream", "start_stream" -> {
                isStreamPaused = false
                Log.i(TAG, "Streaming resumed by PC controller.")
                if (currentMode == "silent" || currentMode == "silent_screen") {
                    RemoteAccessibilityService.instance?.startSilentScreenStreaming(10)
                } else if (currentMode == "video") {
                    if (!ScreenCaptureService.isStreaming) {
                        ScreenCaptureActivity.start(this@TailConnectService)
                    }
                } else if (currentMode == "camera") {
                    if (!CameraStreamManager.isStreaming) {
                        val started = CameraStreamManager.startCamera(this@TailConnectService, front = CameraStreamManager.isFacingFront()) { jpegBytes ->
                            broadcastVideoFrame(jpegBytes)
                        }
                        if (!started) {
                            sendResponse(mapOf(
                                "type" to "stream_error",
                                "message" to "Camera permission not granted or sensor unavailable"
                            ), session)
                        }
                    }
                } else if (currentMode == "skeleton") {
                    sendInitialSkeletonTree(session)
                }
            }

            "touch_tap" -> {
                val nx = json.get("nx")?.asFloat
                val ny = json.get("ny")?.asFloat
                if (nx != null && ny != null) {
                    RemoteAccessibilityService.instance?.injectNormalizedTap(nx, ny)
                } else {
                    val x = json.get("x")?.asFloat ?: return
                    val y = json.get("y")?.asFloat ?: return
                    RemoteAccessibilityService.instance?.injectTap(x, y)
                }
            }

            "touch_swipe" -> {
                val startNx = json.get("startNx")?.asFloat
                val startNy = json.get("startNy")?.asFloat
                val endNx = json.get("endNx")?.asFloat
                val endNy = json.get("endNy")?.asFloat
                val durationMs = json.get("durationMs")?.asLong ?: 250L

                if (startNx != null && startNy != null && endNx != null && endNy != null) {
                    RemoteAccessibilityService.instance?.injectNormalizedSwipe(startNx, startNy, endNx, endNy, durationMs)
                } else {
                    val startX = json.get("startX")?.asFloat ?: return
                    val startY = json.get("startY")?.asFloat ?: return
                    val endX = json.get("endX")?.asFloat ?: return
                    val endY = json.get("endY")?.asFloat ?: return
                    RemoteAccessibilityService.instance?.injectSwipe(startX, startY, endX, endY, durationMs)
                }
            }

            "get_screen_metrics" -> {
                sendScreenMetrics()
            }

            "key_nav" -> {
                val key = json.get("key")?.asString ?: return
                RemoteAccessibilityService.instance?.injectGlobalAction(key)
            }

            "input_text" -> {
                val text = json.get("text")?.asString ?: return
                RemoteAccessibilityService.instance?.injectText(text)
            }

            "open_url" -> {
                val url = json.get("url")?.asString ?: return
                openUrl(url)
            }

            "make_call" -> {
                val number = json.get("number")?.asString ?: return
                makeCall(number)
            }

            "list_dir" -> {
                val path = json.get("path")?.asString
                val result = FileServerManager.listDirectory(path)
                val response = mapOf(
                    "type" to "file_list",
                    "path" to result.path,
                    "files" to result.files,
                    "allFilesAccess" to result.allFilesAccess
                )
                sendResponse(response, session)
            }

            "get_sms" -> {
                val history = querySmsHistory(100)
                val response = mapOf(
                    "type" to "sms_list",
                    "messages" to history
                )
                sendResponse(response, session)
            }

            "send_sms" -> {
                val recipient = json.get("recipient")?.asString ?: json.get("to")?.asString
                val messageText = json.get("message")?.asString ?: json.get("text")?.asString
                if (!recipient.isNullOrBlank() && !messageText.isNullOrBlank()) {
                    sendSmsMessage(recipient, messageText)
                    sendResponse(mapOf(
                        "type" to "sms_send_status",
                        "success" to true,
                        "recipient" to recipient
                    ), session)
                } else {
                    sendResponse(mapOf(
                        "type" to "sms_send_status",
                        "success" to false,
                        "error" to "Missing recipient or message text"
                    ), session)
                }
            }

            "get_contacts" -> {
                val contacts = queryContacts(100)
                sendResponse(mapOf(
                    "type" to "contacts_list",
                    "contacts" to contacts
                ), session)
            }

            "switch_camera" -> {
                CameraStreamManager.switchCamera(this@TailConnectService)
                val isFront = CameraStreamManager.isFacingFront()
                sendResponse(mapOf(
                    "type" to "camera_info",
                    "facing" to if (isFront) "front" else "back"
                ), session)
            }

            "toggle_torch" -> {
                val torchOn = CameraStreamManager.toggleTorch(this@TailConnectService)
                sendResponse(mapOf(
                    "type" to "torch_status",
                    "enabled" to torchOn
                ), session)
            }

            "start_mic_stream" -> {
                updateForegroundServiceType(withCamera = (currentMode == "camera"), withMic = true)
                isSendingMicAudio.set(false)
                val started = AudioIntercomManager.startMicStream(this@TailConnectService) { pcmBytes ->
                    if (!isSendingMicAudio.compareAndSet(false, true)) {
                        return@startMicStream
                    }
                    val base64 = android.util.Base64.encodeToString(pcmBytes, android.util.Base64.NO_WRAP)
                    sendResponse(mapOf(
                        "type" to "mic_audio",
                        "data" to base64
                    ), session)
                    isSendingMicAudio.set(false)
                }
                if (!started) {
                    updateForegroundServiceType(withCamera = (currentMode == "camera"), withMic = false)
                    sendResponse(mapOf(
                        "type" to "mic_error",
                        "message" to "Microphone permission not granted or microphone hardware unavailable"
                    ), session)
                }
                sendResponse(mapOf(
                    "type" to "mic_status",
                    "streaming" to started
                ), session)
            }

            "stop_mic_stream" -> {
                AudioIntercomManager.stopMicStream()
                updateForegroundServiceType(withCamera = (currentMode == "camera"), withMic = false)
                sendResponse(mapOf(
                    "type" to "mic_status",
                    "streaming" to false
                ), session)
            }

            "speaker_audio" -> {
                val data = json.get("data")?.asString
                if (!data.isNullOrBlank()) {
                    try {
                        val pcmBytes = android.util.Base64.decode(data, android.util.Base64.DEFAULT)
                        AudioIntercomManager.playAudioChunk(this@TailConnectService, pcmBytes)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to decode speaker audio chunk: ${e.message}")
                    }
                }
            }

            "set_speaker_volume" -> {
                val vol = json.get("volume")?.asInt ?: 80
                AudioIntercomManager.setSpeakerVolume(this@TailConnectService, vol)
            }

            "play_chime" -> {
                AudioIntercomManager.playAttentionChime()
            }

            "get_location" -> {
                DeviceLocatorManager.fetchSinglePing(this@TailConnectService) { result ->
                    val resp = mapOf(
                        "type" to "location_update",
                        "success" to result.success,
                        "latitude" to result.latitude,
                        "longitude" to result.longitude,
                        "accuracy" to result.accuracy,
                        "altitude" to result.altitude,
                        "speed" to result.speed,
                        "bearing" to result.bearing,
                        "provider" to result.provider,
                        "timestamp" to result.timestamp,
                        "error" to (result.error ?: "")
                    )
                    sendResponse(resp, session)
                }
            }

            "ring_phone" -> {
                DeviceRingerManager.startRinging(this@TailConnectService) {
                    sendResponse(mapOf(
                        "type" to "ring_status",
                        "isRinging" to true
                    ), session)
                }
            }

            "stop_ring_phone" -> {
                DeviceRingerManager.stopRinging(this@TailConnectService) {
                    sendResponse(mapOf(
                        "type" to "ring_status",
                        "isRinging" to false
                    ), session)
                }
            }

            "session_close" -> {
                isSessionActive = false
            }
        }
    }

    private fun querySmsHistory(limit: Int = 100): List<Map<String, Any>> {
        val list = mutableListOf<Map<String, Any>>()
        try {
            val uri = Telephony.Sms.CONTENT_URI
            val projection = arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE
            )
            val cursor = contentResolver.query(
                uri,
                projection,
                null,
                null,
                "${Telephony.Sms.DATE} DESC"
            )
            cursor?.use {
                val addressIdx = it.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyIdx = it.getColumnIndex(Telephony.Sms.BODY)
                val dateIdx = it.getColumnIndex(Telephony.Sms.DATE)
                val typeIdx = it.getColumnIndex(Telephony.Sms.TYPE)

                var count = 0
                while (it.moveToNext() && count < limit) {
                    val sender = if (addressIdx != -1) it.getString(addressIdx) ?: "Unknown" else "Unknown"
                    val body = if (bodyIdx != -1) it.getString(bodyIdx) ?: "" else ""
                    val date = if (dateIdx != -1) it.getLong(dateIdx) else System.currentTimeMillis()
                    val type = if (typeIdx != -1) it.getInt(typeIdx) else 1

                    list.add(
                        mapOf(
                            "sender" to sender,
                            "body" to body,
                            "date" to date,
                            "type" to if (type == Telephony.Sms.MESSAGE_TYPE_SENT) "sent" else "inbox"
                        )
                    )
                    count++
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query SMS history: ${e.message}")
        }
        return list
    }

    private fun openUrl(urlStr: String) {
        try {
            val safeUrl = if (!urlStr.startsWith("http://") && !urlStr.startsWith("https://")) {
                "https://$urlStr"
            } else urlStr

            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open URL: ${e.message}")
        }
    }

    private fun makeCall(phoneNumber: String) {
        try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumber")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to place call: ${e.message}")
        }
    }

    private fun sendSmsMessage(recipient: String, message: String) {
        try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(android.telephony.SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                android.telephony.SmsManager.getDefault()
            }
            val parts = smsManager.divideMessage(message)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(recipient, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(recipient, null, message, null, null)
            }
            Log.i(TAG, "Sent SMS to $recipient: $message")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SMS to $recipient: ${e.message}")
        }
    }

    private fun queryContacts(limit: Int = 100): List<Map<String, String>> {
        val list = mutableListOf<Map<String, String>>()
        try {
            val uri = android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val cursor = contentResolver.query(
                uri,
                projection,
                null,
                null,
                "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )
            cursor?.use {
                val nameIdx = it.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = it.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
                var count = 0
                while (it.moveToNext() && count < limit) {
                    val name = if (nameIdx != -1) it.getString(nameIdx) ?: "Unknown" else "Unknown"
                    val number = if (numIdx != -1) it.getString(numIdx) ?: "" else ""
                    if (number.isNotBlank()) {
                        list.add(mapOf("name" to name, "number" to number))
                        count++
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query contacts: ${e.message}")
        }
        return list
    }
}
