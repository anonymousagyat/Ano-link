package com.tailconnect.app

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import org.webrtc.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pure WebRTC PeerConnection and Cloudflare Quick Tunnel Manager.
 * Operates across LAN (direct WebRTC) and WAN (Cloudflare Quick Tunnel).
 * Eliminates all CGNAT, firewall, and carrier packet drop issues over cellular data.
 */
object AnoWebRtcManager {

    private const val TAG = "AnoWebRtcManager"
    private const val SIGNAL_HOST = "super-river-3234.parthmisar777.workers.dev"
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var controlChannel: DataChannel? = null
    private var videoChannel: DataChannel? = null

    // Cloudflare Quick Tunnel WebSocket Client
    private var tunnelWebSocket: WebSocket? = null
    private val isTunnelConnected = AtomicBoolean(false)
    private val okHttpClient = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var sseConnection: HttpsURLConnection? = null
    private var signalingJob: Job? = null
    private var initRetryJob: Job? = null
    private var answerBroadcastJob: Job? = null
    private val isConnecting = AtomicBoolean(false)
    private val isDisposing = AtomicBoolean(false)
    private var currentPin: String = ""
    private var connectionTimeoutJob: Job? = null

    // Candidate queue in case candidates arrive before setRemoteDescription
    private val pendingCandidates = mutableListOf<IceCandidate>()
    private val localIceCandidates = mutableListOf<Map<String, String>>()
    @Volatile
    private var hasRemoteDescription = false
    @Volatile
    private var lastAnswerSdp: String? = null

    // Callbacks
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onControlMessage: ((String) -> Unit)? = null
    var onVideoFrame: ((ByteArray) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onStatusUpdate: ((String) -> Unit)? = null

    fun initFactory(context: Context) {
        if (factory != null) return
        try {
            val initOptions = PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(initOptions)

            val options = PeerConnectionFactory.Options()
            factory = PeerConnectionFactory.builder()
                .setOptions(options)
                .createPeerConnectionFactory()
            Log.i(TAG, "Native WebRTC PeerConnectionFactory initialized successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize WebRTC PeerConnectionFactory: ${e.message}", e)
        }
    }

    fun isConnected(): Boolean {
        return isTunnelConnected.get() || (controlChannel?.state() == DataChannel.State.OPEN)
    }

    fun connect(context: Context, pin: String) {
        if (isConnected()) return
        initFactory(context)
        val cleanPin = pin.trim()
        if (cleanPin.isBlank()) {
            onError?.invoke("PIN cannot be blank")
            return
        }

        currentPin = cleanPin
        hasRemoteDescription = false
        lastAnswerSdp = null
        isDisposing.set(false)
        isConnecting.set(true)

        // === CLEANUP BEFORE NEW SESSION ===
        connectionTimeoutJob?.cancel()
        initRetryJob?.cancel()
        initRetryJob = null
        answerBroadcastJob?.cancel()
        answerBroadcastJob = null
        signalingJob?.cancel()
        signalingJob = null
        try {
            tunnelWebSocket?.close(1000, "New connect session")
        } catch (_: Exception) {}
        tunnelWebSocket = null
        isTunnelConnected.set(false)
        closePeerConnection()

        // 30-second connection watchdog
        connectionTimeoutJob = scope.launch {
            delay(30000)
            if (isConnecting.get() && !isConnected()) {
                Log.w(TAG, "Connection handshake timed out after 30s")
                isConnecting.set(false)
                closeSignaling()
                closePeerConnection()
                onError?.invoke("Connection timed out. Verify that the PC Controller is running and PIN is correct.")
            }
        }

        signalingJob = scope.launch(Dispatchers.IO) {
            try {
                Log.i(TAG, "Initiating WebRTC signaling handshake for PIN: $cleanPin via Cloudflare Worker HTTPS...")
                onStatusUpdate?.invoke("Connecting to Matchmaker broker...")

                val pcEndpoint = "$cleanPin/pc"
                val phoneEndpoint = "$cleanPin/phone"

                // Start periodic init ping to PC: dispatch immediately, retry every 3.5s
                initRetryJob = scope.launch(Dispatchers.IO) {
                    var attempts = 0
                    while (isActive && isConnecting.get() && !hasRemoteDescription && attempts < 6) {
                        attempts++
                        Log.d(TAG, "Dispatching init request to PC (attempt $attempts/6)...")
                        postSignalMessage(pcEndpoint, mapOf(
                            "type" to "init",
                            "device" to Build.MODEL
                        ))
                        delay(3500)
                    }
                    if (!hasRemoteDescription) {
                        Log.w(TAG, "All $attempts init attempts exhausted. No offer received from PC.")
                    }
                }

                // Poll Cloudflare Worker every 600ms for offer/candidates from PC
                while (isActive && isConnecting.get() && !isConnected()) {
                    try {
                        val url = URL("https://$SIGNAL_HOST/sig/$phoneEndpoint")
                        val conn = (url.openConnection() as HttpsURLConnection).apply {
                            requestMethod = "GET"
                            connectTimeout = 3000
                            readTimeout = 3000
                            instanceFollowRedirects = true
                        }
                        val respCode = conn.responseCode
                        if (respCode in 200..299) {
                            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
                            if (text.isNotBlank() && text != "null") {
                                Log.d(TAG, "Received signal packet from Cloudflare Worker: ${text.take(60)}")
                                handleSignalPacket(text, pcEndpoint)
                            }
                        }
                        conn.disconnect()
                    } catch (e: Exception) {
                        Log.w(TAG, "Signaling poll notice: ${e.message}")
                    }

                    if (isActive && isConnecting.get() && !isConnected()) {
                        delay(600)
                    }
                }
            } catch (e: CancellationException) {
                // Normal cancellation, don't treat as error
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "WebRTC signaling/connection error: ${e.message}")
                if (!isConnected() && isConnecting.get()) {
                    isConnecting.set(false)
                    connectionTimeoutJob?.cancel()
                    onError?.invoke(e.message ?: "Signaling failed")
                }
            }
        }
    }

    private suspend fun handleSignalPacket(rawText: String, pcTopic: String) {
        try {
            val root = gson.fromJson(rawText, JsonObject::class.java) ?: return
            val payload = if (root.has("message")) {
                val m = root.get("message")
                if (m.isJsonObject) {
                    m.asJsonObject
                } else if (m.isJsonPrimitive && m.asJsonPrimitive.isString) {
                    try {
                        gson.fromJson(m.asString, JsonObject::class.java) ?: root
                    } catch (_: Exception) {
                        root
                    }
                } else {
                    root
                }
            } else if (root.has("body")) {
                val b = root.get("body")
                if (b.isJsonObject) {
                    b.asJsonObject
                } else if (b.isJsonPrimitive && b.asJsonPrimitive.isString) {
                    try {
                        gson.fromJson(b.asString, JsonObject::class.java) ?: root
                    } catch (_: Exception) {
                        root
                    }
                } else {
                    root
                }
            } else {
                root
            }
            val type = payload.get("type")?.asString ?: return

            when (type) {
                "tunnel_ready" -> {
                    val rawUrl = payload.get("url")?.asString ?: return
                    Log.i(TAG, "Received Cloudflare Quick Tunnel URL: $rawUrl")
                    connectTunnelWebSocket(rawUrl)
                    return
                }
                "offer" -> {
                    initRetryJob?.cancel()
                    initRetryJob = null
                    val sdp = payload.get("sdp")?.asString ?: return

                    // Parse bundled candidates if present in offer
                    if (payload.has("candidates") && payload.get("candidates").isJsonArray) {
                        val arr = payload.getAsJsonArray("candidates")
                        synchronized(pendingCandidates) {
                            for (elem in arr) {
                                if (elem.isJsonObject) {
                                    val cObj = elem.asJsonObject
                                    val rawStr = cObj.get("candidate")?.asString
                                    val cStr = rawStr?.removePrefix("a=")
                                    val mid = cObj.get("mid")?.asString ?: "0"
                                    if (!cStr.isNullOrBlank()) {
                                        pendingCandidates.add(IceCandidate(mid, 0, cStr))
                                    }
                                }
                            }
                        }
                    }

                    // If we ALREADY have a remote description and peer connection actively negotiating,
                    // do NOT destroy it for duplicate re-sent offers!
                    if (hasRemoteDescription && peerConnection != null) {
                        Log.i(TAG, "Duplicate SDP Offer received while already negotiating. Preserving active session.")
                        return
                    }

                    if (peerConnection != null) {
                        Log.i(TAG, "Closing existing PeerConnection to accept new Offer cleanly...")
                        closePeerConnection()
                    }

                    Log.i(TAG, "Received SDP Offer from PC (${sdp.length} chars). Establishing WebRTC PeerConnection...")
                    initRetryJob?.cancel()
                    initRetryJob = null
                    onStatusUpdate?.invoke("Offer received! Negotiating WebRTC P2P...")
                    handleOffer(sdp, pcTopic)
                }
                "candidate" -> {
                    val rawStr = payload.get("candidate")?.asString ?: return
                    val candidateStr = rawStr.removePrefix("a=")
                    val mid = payload.get("mid")?.asString ?: "0"
                    val candidate = IceCandidate(mid, 0, candidateStr)
                    if (hasRemoteDescription && peerConnection != null) {
                        peerConnection?.addIceCandidate(candidate)
                        Log.d(TAG, "Added remote ICE Candidate from PC: $candidateStr")
                    } else {
                        synchronized(pendingCandidates) {
                            pendingCandidates.add(candidate)
                        }
                        Log.d(TAG, "Buffered remote ICE Candidate: $candidateStr")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error handling signal packet: ${e.message}")
        }
    }

    private fun connectTunnelWebSocket(rawUrl: String) {
        if (isTunnelConnected.get() || tunnelWebSocket != null) return
        val cleanUrl = rawUrl.trim()
        val wsUrl = (if (cleanUrl.startsWith("https://")) {
            cleanUrl.replace("https://", "wss://")
        } else if (cleanUrl.startsWith("http://")) {
            cleanUrl.replace("http://", "ws://")
        } else {
            "wss://$cleanUrl"
        }).trimEnd('/') + "/ws-phone"

        Log.i(TAG, "Connecting to Cloudflare Quick Tunnel: $wsUrl")
        onStatusUpdate?.invoke("Connecting to Cloudflare Quick Tunnel...")

        try {
            val request = Request.Builder().url(wsUrl).build()
            tunnelWebSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.i(TAG, "🟢 Cloudflare Quick Tunnel Connected via WebSocket!")
                    isTunnelConnected.set(true)
                    isConnecting.set(false)
                    connectionTimeoutJob?.cancel()
                    connectionTimeoutJob = null
                    initRetryJob?.cancel()
                    initRetryJob = null
                    closeSignaling()
                    onStatusUpdate?.invoke("Connected via Cloudflare Tunnel!")
                    onConnected?.invoke()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    onControlMessage?.invoke(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    onVideoFrame?.invoke(bytes.toByteArray())
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "Tunnel WebSocket closing ($code): $reason")
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "Tunnel WebSocket closed.")
                    isTunnelConnected.set(false)
                    tunnelWebSocket = null
                    onDisconnected?.invoke()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "Tunnel WebSocket notice: ${t.message}")
                    isTunnelConnected.set(false)
                    tunnelWebSocket = null
                    if (isConnecting.get() && !isConnected()) {
                        Log.d(TAG, "Tunnel attempt notice, standing by for WebRTC offer or retry...")
                    } else {
                        onDisconnected?.invoke()
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating tunnel WebSocket: ${e.message}")
        }
    }

    private fun handleOffer(sdpOffer: String, pcTopic: String) {
        val f = factory ?: run {
            Log.e(TAG, "PeerConnectionFactory is null!")
            onError?.invoke("WebRTC Engine initialization failed.")
            return
        }
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = f.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.i(TAG, "WebRTC ICE Connection State: $state")
                if (state == PeerConnection.IceConnectionState.CONNECTED ||
                    state == PeerConnection.IceConnectionState.COMPLETED) {
                    Log.i(TAG, "WebRTC direct peer connection locked in!")
                    onStatusUpdate?.invoke("P2P Direct UDP Connected!")
                } else if (state == PeerConnection.IceConnectionState.DISCONNECTED ||
                    state == PeerConnection.IceConnectionState.FAILED) {
                    Log.w(TAG, "WebRTC ICE Connection Lost ($state).")
                    isConnecting.set(false)
                    scope.launch(Dispatchers.IO) {
                        closePeerConnection()
                        onDisconnected?.invoke()
                    }
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                Log.d(TAG, "WebRTC ICE Gathering State: $state")
            }

            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate != null) {
                    val cleanCand = candidate.sdp.removePrefix("a=")
                    val cMap = mapOf(
                        "candidate" to cleanCand,
                        "mid" to candidate.sdpMid
                    )
                    synchronized(localIceCandidates) {
                        localIceCandidates.add(cMap)
                    }
                    Log.d(TAG, "Gathered local ICE candidate: ${cleanCand.take(50)}")
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

            override fun onAddStream(stream: MediaStream?) {}

            override fun onRemoveStream(stream: MediaStream?) {}

            override fun onDataChannel(dc: DataChannel?) {
                if (dc == null) return
                Log.i(TAG, "WebRTC DataChannel received from PC: ${dc.label()}")
                bindDataChannel(dc)
            }

            override fun onRenegotiationNeeded() {}

            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        })

        // Use pristine standard RFC 8841 SDP without tampering
        val sessionDesc = SessionDescription(SessionDescription.Type.OFFER, sdpOffer)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {}
            override fun onSetSuccess() {
                Log.i(TAG, "Remote Offer set successfully. Flushing buffered ICE candidates and creating Answer...")
                hasRemoteDescription = true
                synchronized(pendingCandidates) {
                    for (cand in pendingCandidates) {
                        peerConnection?.addIceCandidate(cand)
                    }
                    pendingCandidates.clear()
                }

                peerConnection?.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(answerDesc: SessionDescription?) {
                        if (answerDesc == null) return
                        peerConnection?.setLocalDescription(object : SdpObserver {
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onSetSuccess() {
                                Log.i(TAG, "Local Answer set. Starting Answer re-transmission...")
                                lastAnswerSdp = answerDesc.description
                                startAnswerRebroadcast(pcTopic, answerDesc.description)
                            }
                            override fun onCreateFailure(err: String?) {
                                Log.e(TAG, "Failed to set local Answer description: $err")
                                onError?.invoke("Set Local Answer error: $err")
                            }
                            override fun onSetFailure(err: String?) {
                                Log.e(TAG, "Failed onSetFailure local Answer: $err")
                                onError?.invoke("Set Local Answer error: $err")
                            }
                        }, answerDesc)
                    }

                    override fun onSetSuccess() {}
                    override fun onCreateFailure(err: String?) {
                        Log.e(TAG, "Failed to create Answer: $err")
                        onError?.invoke("Create Answer error: $err")
                    }
                    override fun onSetFailure(err: String?) {
                        Log.e(TAG, "Failed onSetFailure create Answer: $err")
                        onError?.invoke("Create Answer error: $err")
                    }
                }, MediaConstraints())
            }

            override fun onCreateFailure(err: String?) {
                Log.e(TAG, "Failed to set remote Offer: $err")
                scope.launch(Dispatchers.IO) {
                    closePeerConnection()
                    onError?.invoke("Remote Offer rejected: $err")
                }
            }
            override fun onSetFailure(err: String?) {
                Log.e(TAG, "Failed onSetFailure remote Offer: $err")
                scope.launch(Dispatchers.IO) {
                    closePeerConnection()
                    onError?.invoke("Remote Offer rejected: $err")
                }
            }
        }, sessionDesc)
    }

    private fun startAnswerRebroadcast(pcTopic: String, sdpAnswer: String) {
        answerBroadcastJob?.cancel()
        answerBroadcastJob = scope.launch(Dispatchers.IO) {
            // Wait 1400ms for local candidate gathering (STUN + TURN Relay)
            delay(1400)
            Log.d(TAG, "Dispatching bundled Answer to PC on $pcTopic...")
            val candList = synchronized(localIceCandidates) {
                localIceCandidates.filter {
                    val c = it["candidate"] ?: ""
                    !c.contains(" 172.28.") && !c.contains(" 172.24.") && !c.contains(" 172.17.") && !c.contains(" 169.254.")
                }
            }
            postSignalMessage(pcTopic, mapOf(
                "type" to "answer",
                "sdp" to sdpAnswer,
                "candidates" to candList
            ))

            // Single retry after 3.5s if not yet connected
            delay(3500)
            if (isActive && isConnecting.get() && !isConnected()) {
                val updatedCandList = synchronized(localIceCandidates) {
                    localIceCandidates.filter {
                        val c = it["candidate"] ?: ""
                        !c.contains(" 172.28.") && !c.contains(" 172.24.") && !c.contains(" 172.17.") && !c.contains(" 169.254.")
                    }
                }
                postSignalMessage(pcTopic, mapOf(
                    "type" to "answer",
                    "sdp" to sdpAnswer,
                    "candidates" to updatedCandList
                ))
            }
        }
    }

    private fun bindDataChannel(dc: DataChannel) {
        val label = dc.label()
        if (label == "ano-control") {
            controlChannel = dc
        } else if (label == "ano-video") {
            videoChannel = dc
        }

        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}

            override fun onStateChange() {
                val state = dc.state()
                Log.i(TAG, "DataChannel '$label' state changed to: $state")
                if (state == DataChannel.State.OPEN) {
                    isConnecting.set(false)
                    connectionTimeoutJob?.cancel()
                    connectionTimeoutJob = null
                    // Close transient signaling connection immediately!
                    closeSignaling()
                    if (label == "ano-control") {
                        onStatusUpdate?.invoke("Connected!")
                        onConnected?.invoke()
                    }
                } else if (state == DataChannel.State.CLOSED) {
                    if (label == "ano-control") {
                        isConnecting.set(false)
                        scope.launch(Dispatchers.IO) {
                            closePeerConnection()
                            onDisconnected?.invoke()
                        }
                    }
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer?) {
                if (buffer == null) return
                if (buffer.binary) {
                    val bytes = ByteArray(buffer.data.remaining())
                    buffer.data.get(bytes)
                    onVideoFrame?.invoke(bytes)
                } else {
                    val bytes = ByteArray(buffer.data.remaining())
                    buffer.data.get(bytes)
                    val text = String(bytes, Charsets.UTF_8)
                    onControlMessage?.invoke(text)
                }
            }
        })
    }

    fun sendControl(text: String): Boolean {
        val ws = tunnelWebSocket
        if (ws != null && isTunnelConnected.get()) {
            return ws.send(text)
        }
        val dc = controlChannel
        if (dc != null && dc.state() == DataChannel.State.OPEN) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            val buffer = DataChannel.Buffer(ByteBuffer.wrap(bytes), false)
            return dc.send(buffer)
        }
        return false
    }

    fun sendBinary(bytes: ByteArray): Boolean {
        val ws = tunnelWebSocket
        if (ws != null && isTunnelConnected.get()) {
            return ws.send(bytes.toByteString())
        }
        val dc = videoChannel ?: controlChannel
        if (dc != null && dc.state() == DataChannel.State.OPEN) {
            val buffer = DataChannel.Buffer(ByteBuffer.wrap(bytes), true)
            return dc.send(buffer)
        }
        return false
    }

    fun disconnect() {
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
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
        closeSignaling()
        closePeerConnection()
        isConnecting.set(false)
        onDisconnected?.invoke()
    }

    private fun closeSignaling() {
        initRetryJob?.cancel()
        initRetryJob = null
        answerBroadcastJob?.cancel()
        answerBroadcastJob = null
        signalingJob?.cancel()
        signalingJob = null
        val conn = sseConnection
        sseConnection = null
        scope.launch(Dispatchers.IO) {
            try {
                conn?.disconnect()
            } catch (_: Exception) {}
        }
    }

    private fun closePeerConnection() {
        if (!isDisposing.compareAndSet(false, true)) return
        try {
            hasRemoteDescription = false
            lastAnswerSdp = null
            synchronized(pendingCandidates) {
                pendingCandidates.clear()
            }
            synchronized(localIceCandidates) {
                localIceCandidates.clear()
            }

            try {
                controlChannel?.unregisterObserver()
                controlChannel?.close()
                controlChannel?.dispose()
            } catch (_: Exception) {}
            controlChannel = null

            try {
                videoChannel?.unregisterObserver()
                videoChannel?.close()
                videoChannel?.dispose()
            } catch (_: Exception) {}
            videoChannel = null

            try {
                peerConnection?.close()
                peerConnection?.dispose()
            } catch (_: Exception) {}
            peerConnection = null
        } catch (_: Exception) {}
        finally {
            isDisposing.set(false)
        }
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
                connectTimeout = 6000
                readTimeout = 6000
                doOutput = true
            }
            conn.outputStream.use { os ->
                os.write(jsonBytes)
                os.flush()
            }
            val respCode = conn.responseCode // execute request
            conn.disconnect()
            Log.d(TAG, "Posted signal ${data["type"]} to $endpoint via Cloudflare Worker (HTTP $respCode)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to post signal message: ${e.message}")
        }
    }
}
