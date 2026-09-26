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

        fun isPcConnected(): Boolean = activeWsSession != null

        fun sendToPc(payload: Map<String, Any>) {
            val session = activeWsSession ?: return
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    session.send(Frame.Text(gson.toJson(payload)))
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send to PC: ${e.message}")
                }
            }
        }

        fun isSkeletonModeActive(): Boolean = isSessionActive && !isStreamPaused

        fun broadcastSkeletonTree(tree: NodeDto, width: Int, height: Int) {
            val session = activeWsSession ?: return
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val map = mapOf(
                        "type" to "skeleton_tree",
                        "tree" to tree,
                        "screenWidth" to width,
                        "screenHeight" to height
                    )
                    session.send(Frame.Text(gson.toJson(map)))
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send skeleton tree: ${e.message}")
                }
            }
        }

        fun broadcastVideoFrame(bytes: ByteArray) {
            if (isStreamPaused) return
            val session = activeWsSession ?: return

            // Drop frame if previous frame is still in flight over the network
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

        fun pushSmsAlert(sender: String, body: String) {
            val session = activeWsSession ?: return
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val map = mapOf(
                        "type" to "incoming_sms",
                        "sender" to sender,
                        "body" to body,
                        "timestamp" to System.currentTimeMillis()
                    )
                    session.send(Frame.Text(gson.toJson(map)))
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to push SMS alert: ${e.message}")
                }
            }
        }

        fun onScreenCaptureGranted(resultCode: Int, data: Intent) {
            val session = activeWsSession ?: return
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val map = mapOf(
                        "type" to "screen_capture_status",
                        "status" to "granted"
                    )
                    session.send(Frame.Text(gson.toJson(map)))
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to notify capture granted: ${e.message}")
                }
            }
        }

        fun onScreenCaptureDenied() {
            val session = activeWsSession ?: return
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val map = mapOf(
                        "type" to "screen_capture_status",
                        "status" to "denied"
                    )
                    session.send(Frame.Text(gson.toJson(map)))
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to notify capture denied: ${e.message}")
                }
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var serverEngine: ApplicationEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                createNotification("Standby: Listening on Tailscale port $PORT"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification("Standby: Listening on Tailscale port $PORT"))
        }
        startKtorServer()
        startTelemetryTicker()
        Log.i(TAG, "TailConnectService initialized.")
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
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        serverEngine?.stop(1000, 2000)
        wakeLock?.let { if (it.isHeld) it.release() }
        Log.i(TAG, "TailConnectService destroyed.")
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AnoLink::DaemonWakeLock").apply {
            setReferenceCounted(false)
            acquire(24 * 60 * 60 * 1000L) // 24 hours
        }
    }

    private fun createNotification(statusText: String): Notification {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Ano-Link Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Maintains connection to paired PC over Tailscale"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ano-Link Hub Active")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    // -------------------------------------------------------------
    // Telemetry 5-Minute Background Ticker
    // -------------------------------------------------------------
    private fun startTelemetryTicker() {
        serviceScope.launch {
            while (isActive) {
                if (isSessionActive && activeWsSession != null) {
                    pushTelemetry()
                }
                // Delay 5 minutes
                delay(5 * 60 * 1000L)
            }
        }
    }

    private suspend fun pushTelemetry() {
        val session = activeWsSession ?: return
        val telemetry = TelemetryManager.collect(this@TailConnectService)
        val payload = mapOf(
            "type" to "telemetry",
            "data" to telemetry,
            "timestamp" to System.currentTimeMillis()
        )
        try {
            session.send(Frame.Text(gson.toJson(payload)))
        } catch (e: Exception) {
            Log.w(TAG, "Error pushing telemetry: ${e.message}")
        }
    }

    // -------------------------------------------------------------
    // Ktor CIO Server Engine (Tailscale Port 8081)
    // -------------------------------------------------------------
    private fun startKtorServer() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                serverEngine = embeddedServer(CIO, port = PORT, host = "0.0.0.0") {
                    install(WebSockets) {
                        pingPeriod = Duration.ofSeconds(15)
                        timeout = Duration.ofSeconds(30)
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
                            activeWsSession = this
                            isSessionActive = true

                            // Send immediate initial telemetry, initial skeleton tree, and previous SMS history
                            pushTelemetry()
                            sendInitialSkeletonTree()
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
                                Log.i(TAG, "PC Controller session ended. Returning to Standby.")
                            }
                        }
                    }
                }.start(wait = true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Ktor Server: ${e.message}")
            }
        }
    }

    private suspend fun sendInitialSkeletonTree() {
        val session = activeWsSession ?: return
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
                session.send(Frame.Text(gson.toJson(map)))
            } else {
                val map = mapOf(
                    "type" to "skeleton_error",
                    "message" to "Device screen is locked or window content is not readable."
                )
                session.send(Frame.Text(gson.toJson(map)))
            }
        } else {
            val map = mapOf(
                "type" to "skeleton_error",
                "message" to "Accessibility Service is not enabled. Please open Android Settings -> Accessibility and enable 'AnoLink Accessibility'."
            )
            session.send(Frame.Text(gson.toJson(map)))
        }
    }

    private suspend fun handleIncomingMessage(rawText: String, session: DefaultWebSocketServerSession) {
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
        if (action == "window_focused" || action == "power_status" || action == "pong") {
            pcRemoteListener?.onPcMessage(action, json)
            return
        }

        when (action) {
            "get_telemetry", "refresh_telemetry" -> {
                pushTelemetry()
            }

            "get_skeleton_tree", "refresh_skeleton" -> {
                sendInitialSkeletonTree()
            }

            "set_mode" -> {
                val newMode = json.get("mode")?.asString ?: "skeleton"
                currentMode = newMode
                isStreamPaused = false
                Log.i(TAG, "Mode updated to: $currentMode")
                if (currentMode == "skeleton") {
                    ScreenCaptureService.stop(this@TailConnectService)
                    CameraStreamManager.stopCamera()
                    updateForegroundServiceType(withCamera = false, withMic = AudioIntercomManager.isMicStreaming)
                    sendInitialSkeletonTree()
                } else if (currentMode == "video") {
                    CameraStreamManager.stopCamera()
                    updateForegroundServiceType(withCamera = false, withMic = AudioIntercomManager.isMicStreaming)
                    sendInitialSkeletonTree()
                    if (!ScreenCaptureService.isStreaming && !isStreamPaused) {
                        ScreenCaptureActivity.start(this@TailConnectService)
                    }
                } else if (currentMode == "camera") {
                    ScreenCaptureService.stop(this@TailConnectService)
                    updateForegroundServiceType(withCamera = true, withMic = AudioIntercomManager.isMicStreaming)
                    if (!CameraStreamManager.isStreaming && !isStreamPaused) {
                        val front = json.get("front")?.asBoolean ?: false
                        val started = CameraStreamManager.startCamera(this@TailConnectService, front = front) { jpegBytes ->
                            broadcastVideoFrame(jpegBytes)
                        }
                        if (!started) {
                            session.send(Frame.Text(gson.toJson(mapOf(
                                "type" to "stream_error",
                                "message" to "Camera permission not granted or sensor unavailable"
                            ))))
                        }
                    }
                }
            }

            "pause_stream", "stop_stream" -> {
                isStreamPaused = true
                ScreenCaptureService.stop(this@TailConnectService)
                CameraStreamManager.stopCamera()
                updateForegroundServiceType(withCamera = false, withMic = AudioIntercomManager.isMicStreaming)
                Log.i(TAG, "Streaming paused by PC controller. Conserving resources.")
            }

            "resume_stream", "start_stream" -> {
                isStreamPaused = false
                Log.i(TAG, "Streaming resumed by PC controller.")
                if (currentMode == "video") {
                    if (!ScreenCaptureService.isStreaming) {
                        ScreenCaptureActivity.start(this@TailConnectService)
                    }
                } else if (currentMode == "camera") {
                    if (!CameraStreamManager.isStreaming) {
                        val started = CameraStreamManager.startCamera(this@TailConnectService, front = CameraStreamManager.isFacingFront()) { jpegBytes ->
                            broadcastVideoFrame(jpegBytes)
                        }
                        if (!started) {
                            session.send(Frame.Text(gson.toJson(mapOf(
                                "type" to "stream_error",
                                "message" to "Camera permission not granted or sensor unavailable"
                            ))))
                        }
                    }
                } else if (currentMode == "skeleton") {
                    sendInitialSkeletonTree()
                }
            }

            "touch_tap" -> {
                val x = json.get("x")?.asFloat ?: return
                val y = json.get("y")?.asFloat ?: return
                RemoteAccessibilityService.instance?.injectTap(x, y)
            }

            "touch_swipe" -> {
                val startX = json.get("startX")?.asFloat ?: return
                val startY = json.get("startY")?.asFloat ?: return
                val endX = json.get("endX")?.asFloat ?: return
                val endY = json.get("endY")?.asFloat ?: return
                val durationMs = json.get("durationMs")?.asLong ?: 250L
                RemoteAccessibilityService.instance?.injectSwipe(startX, startY, endX, endY, durationMs)
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
                session.send(Frame.Text(gson.toJson(response)))
            }

            "get_sms" -> {
                val history = querySmsHistory(100)
                val response = mapOf(
                    "type" to "sms_list",
                    "messages" to history
                )
                session.send(Frame.Text(gson.toJson(response)))
            }

            "switch_camera" -> {
                CameraStreamManager.switchCamera(this@TailConnectService)
                val isFront = CameraStreamManager.isFacingFront()
                session.send(Frame.Text(gson.toJson(mapOf(
                    "type" to "camera_info",
                    "facing" to if (isFront) "front" else "back"
                ))))
            }

            "toggle_torch" -> {
                val torchOn = CameraStreamManager.toggleTorch(this@TailConnectService)
                session.send(Frame.Text(gson.toJson(mapOf(
                    "type" to "torch_status",
                    "enabled" to torchOn
                ))))
            }

            "start_mic_stream" -> {
                updateForegroundServiceType(withCamera = (currentMode == "camera"), withMic = true)
                isSendingMicAudio.set(false)
                val started = AudioIntercomManager.startMicStream(this@TailConnectService) { pcmBytes ->
                    // Drop audio chunk if previous chunk is still in flight over the network
                    if (!isSendingMicAudio.compareAndSet(false, true)) {
                        return@startMicStream
                    }
                    val base64 = android.util.Base64.encodeToString(pcmBytes, android.util.Base64.NO_WRAP)
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            session.send(Frame.Text(gson.toJson(mapOf(
                                "type" to "mic_audio",
                                "data" to base64
                            ))))
                        } catch (_: Exception) {
                        } finally {
                            isSendingMicAudio.set(false)
                        }
                    }
                }
                if (!started) {
                    updateForegroundServiceType(withCamera = (currentMode == "camera"), withMic = false)
                    session.send(Frame.Text(gson.toJson(mapOf(
                        "type" to "mic_error",
                        "message" to "Microphone permission not granted or microphone hardware unavailable"
                    ))))
                }
                session.send(Frame.Text(gson.toJson(mapOf(
                    "type" to "mic_status",
                    "streaming" to started
                ))))
            }

            "stop_mic_stream" -> {
                AudioIntercomManager.stopMicStream()
                updateForegroundServiceType(withCamera = (currentMode == "camera"), withMic = false)
                session.send(Frame.Text(gson.toJson(mapOf(
                    "type" to "mic_status",
                    "streaming" to false
                ))))
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
}
