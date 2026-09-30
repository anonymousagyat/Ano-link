const express = require('express');
const http = require('http');
const https = require('https');
const WebSocket = require('ws');
const path = require('path');
const cors = require('cors');
const fs = require('fs');
const child_process = require('child_process');
const readline = require('readline');
const os = require('os');
const crypto = require('crypto');
const dns = require('dns');
try { dns.setDefaultResultOrder('ipv4first'); } catch (_) {}

// Native WebRTC P2P Engine
let nodeDataChannel = null;
try {
    nodeDataChannel = require('node-datachannel');
    nodeDataChannel.initLogger('Debug', (level, msg) => {
        if (level === 'Error' || level === 'Warning') {
            console.log(`[WebRTC C++ ${level}] ${msg}`);
        }
    });
    console.log('[WebRTC] Native node-datachannel P2P engine initialized successfully!');
} catch (err) {
    console.warn('[WebRTC] node-datachannel not available:', err.message);
}

const app = express();
app.use(cors());
app.use(express.json());
app.use(express.static(path.join(__dirname, 'public')));

const PORT = 9090;
const server = http.createServer(app);

// Native Win32 Input & Taskbar Bridge
const bridgeExePath = path.join(__dirname, 'tools', 'AnoLinkWinBridge.exe');
let winBridge = null;
let screenStreamTimer = null;
let taskbarSyncTimer = null;

// WebSocket server for the local browser frontend
const wssBrowser = new WebSocket.Server({ noServer: true });

// WebSocket server for Phone Remote via Cloudflare Quick Tunnel
const wssPhone = new WebSocket.Server({ noServer: true });
let activePhoneWs = null;

// Cloudflare Quick Tunnel Management
const cloudflaredExePath = path.join(__dirname, 'tools', 'cloudflared.exe');
let cloudflaredProc = null;
let activeTunnelUrl = null;

const configFilePath = path.join(__dirname, 'config.json');

// State with Persistent Pairing Token & 30 FPS
let phoneConfig = {
    ip: '',
    port: 8081,
    token: 'ag-secure-token-777',
    deviceToken: '',
    pairingPin: '',
    fps: 30
};

function loadSavedConfig() {
    try {
        if (fs.existsSync(configFilePath)) {
            const raw = fs.readFileSync(configFilePath, 'utf8');
            const parsed = JSON.parse(raw);
            if (parsed.phoneIp) phoneConfig.ip = parsed.phoneIp;
            if (parsed.phonePort) phoneConfig.port = parsed.phonePort;
            if (parsed.token) phoneConfig.token = parsed.token;
            if (parsed.deviceToken) phoneConfig.deviceToken = parsed.deviceToken;
            if (parsed.pairingPin) phoneConfig.pairingPin = parsed.pairingPin;
            if (parsed.fps) phoneConfig.fps = parsed.fps;
        }
    } catch (_) {}

    // Ensure persistent cryptographic deviceToken exists for auto-reconnect
    if (!phoneConfig.deviceToken) {
        phoneConfig.deviceToken = 'ano_tok_' + crypto.randomBytes(16).toString('hex');
    }
    // Ensure clean 6-digit one-time pairing PIN exists
    if (!phoneConfig.pairingPin) {
        phoneConfig.pairingPin = Math.floor(100000 + Math.random() * 900000).toString();
    }
    phoneConfig.fps = 30; // 30 FPS target
    saveConfig();
}

function saveConfig() {
    try {
        fs.writeFileSync(configFilePath, JSON.stringify({
            phoneIp: phoneConfig.ip,
            phonePort: phoneConfig.port,
            token: phoneConfig.token,
            deviceToken: phoneConfig.deviceToken,
            pairingPin: phoneConfig.pairingPin,
            fps: phoneConfig.fps
        }, null, 2), 'utf8');
    } catch (_) {}
}

loadSavedConfig();

let connectionState = 'DISCONNECTED'; // DISCONNECTED, CONNECTING, CONNECTED
let phoneSocket = null;
let telemetryCache = null;
let lastTelemetryTime = null;
let currentMode = 'skeleton'; // 'skeleton' or 'video'
let streamQuality = { fps: 30, bitrateKbps: 2000, idleThrottling: true };
let currentSkeletonTree = null;
let smsHistoryCache = [];
let isExclusiveControlActive = false;
let lastRemoteStreamActivity = Date.now();

// 5-Minute Inactivity Safety Watchdog
setInterval(() => {
    if (isExclusiveControlActive && Date.now() - lastRemoteStreamActivity > 300000) { // 5 minutes (300,000 ms)
        console.log('[PC Watchdog] 5 minutes of phone remote inactivity elapsed. Auto-stopping screen stream and unlocking PC dashboard.');
        isExclusiveControlActive = false;
        stopPcScreenStreaming(true);
        sendToPhone({ action: 'resume_stream' });
        broadcastToBrowsers({
            type: 'exclusive_mode',
            active: false,
            message: null
        });
    }
}, 15000);

// WebRTC P2P Peer & DataChannel Management
// Pure WebRTC P2P Peer & DataChannel Management
let activeWebRtcPeer = null;
let activeControlDc = null;
let activeVideoDc = null;

const SIGNAL_HOST = 'super-river-3234.parthmisar777.workers.dev';
let signalPollTimer = null;

function postSignalMessage(targetRole, data) {
    try {
        const payload = JSON.stringify(data);
        const req = https.request({
            hostname: SIGNAL_HOST,
            port: 443,
            path: `/sig/${phoneConfig.pairingPin}/${targetRole}`,
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Content-Length': Buffer.byteLength(payload)
            }
        }, (res) => {
            console.log(`[WebRTC] Dispatched '${data.type}' to ${targetRole} via Cloudflare Worker (HTTP ${res.statusCode})`);
            res.resume();
        });
        req.on('error', (err) => {
            console.error(`[WebRTC] Failed to dispatch '${data.type}':`, err.message);
        });
        req.write(payload);
        req.end();
    } catch (err) {
        console.error('[WebRTC] postSignalMessage exception:', err.message);
    }
}

const POLL_STANDBY_MS = 2500;  // Eco standby (idle waiting for phone)
const POLL_BURST_MS   = 400;   // High-speed handshake burst
let currentPollInterval = POLL_STANDBY_MS;
let burstModeTimeout = null;
let isPollingPaused = false;
let isPollInProgress = false;

function setSignalingMode(mode) {
    if (mode === 'PAUSED') {
        if (!isPollingPaused) {
            console.log('[WebRTC] 💤 Connected — Lowering signaling polling to 3500ms liveness check.');
        }
        isPollingPaused = false;
        currentPollInterval = 3500;
        if (burstModeTimeout) {
            clearTimeout(burstModeTimeout);
            burstModeTimeout = null;
        }
        scheduleNextPoll(3500);
        return;
    }

    if (mode === 'BURST') {
        isPollingPaused = false;
        currentPollInterval = POLL_BURST_MS;
        console.log('[WebRTC] ⚡ Signaling BURST mode active (400ms interval for fast SDP exchange).');
        if (burstModeTimeout) clearTimeout(burstModeTimeout);
        burstModeTimeout = setTimeout(() => {
            if (!isPollingPaused && currentPollInterval === POLL_BURST_MS) {
                console.log('[WebRTC] Handshake window concluded. Reverting signaling to STANDBY.');
                setSignalingMode('STANDBY');
            }
        }, 15000);
        scheduleNextPoll(0);
        return;
    }

    if (mode === 'STANDBY') {
        if (isPollingPaused || currentPollInterval !== POLL_STANDBY_MS) {
            console.log(`[WebRTC] 🍃 Signaling STANDBY mode active (${POLL_STANDBY_MS}ms interval). Ready for Phone.`);
        }
        isPollingPaused = false;
        currentPollInterval = POLL_STANDBY_MS;
        if (burstModeTimeout) {
            clearTimeout(burstModeTimeout);
            burstModeTimeout = null;
        }
        scheduleNextPoll(POLL_STANDBY_MS);
        return;
    }
}

function scheduleNextPoll(delayMs) {
    if (isPollingPaused) return;
    if (signalPollTimer) {
        clearTimeout(signalPollTimer);
        signalPollTimer = null;
    }
    const ms = delayMs !== undefined ? delayMs : currentPollInterval;
    signalPollTimer = setTimeout(pollSignalWorker, ms);
}

function pollSignalWorker() {
    if (isPollingPaused || isPollInProgress) return;
    isPollInProgress = true;

    const pollPath = `/sig/${phoneConfig.pairingPin}/pc`;
    const req = https.request({
        hostname: SIGNAL_HOST,
        port: 443,
        path: pollPath,
        method: 'GET',
        timeout: 4000
    }, (res) => {
        let buffer = '';
        res.on('data', chunk => buffer += chunk);
        res.on('end', () => {
            isPollInProgress = false;
            const trimmed = buffer.trim();
            if (trimmed && trimmed !== 'null') {
                try {
                    const msg = JSON.parse(trimmed);
                    if (msg && msg.type) {
                        handleSignalMessage(msg, 'phone');
                    }
                } catch (parseErr) {}
            }
            if (!isPollingPaused) {
                scheduleNextPoll();
            }
        });
    });

    req.on('timeout', () => {
        try { req.destroy(); } catch (_) {}
        isPollInProgress = false;
        if (!isPollingPaused) scheduleNextPoll();
    });

    req.on('error', () => {
        isPollInProgress = false;
        if (!isPollingPaused) scheduleNextPoll();
    });

    req.end();
}

function initSignaling() {
    if (signalPollTimer) {
        clearTimeout(signalPollTimer);
        signalPollTimer = null;
    }
    isPollingPaused = false;

    console.log(`[WebRTC] Matchmaker listening on PIN: ${phoneConfig.pairingPin} via Cloudflare Worker...`);

    // Verify Cloudflare Worker connection on startup
    const healthReq = https.request({
        hostname: SIGNAL_HOST,
        port: 443,
        path: '/',
        method: 'GET',
        timeout: 5000
    }, (res) => {
        let buf = '';
        res.on('data', c => buf += c);
        res.on('end', () => {
            console.log(`[WebRTC] ✅ Cloudflare Worker Broker connected! (HTTP ${res.statusCode}: ${buf.trim()})`);
        });
    });
    healthReq.on('error', (err) => {
        console.error(`[WebRTC] ❌ Cloudflare Worker connection warning: ${err.message}`);
    });
    healthReq.end();

    setSignalingMode('STANDBY');
}

let lastOfferTime = 0;
let lastOfferSentTime = 0;
let lastOfferSdp = null;
let localCandidates = [];
let hasRemoteAnswer = false;
let pendingRemoteCandidates = [];

function handleSignalMessage(msg, phoneTopic) {
    if (!msg || !msg.type) return;

    if (msg.type === 'init') {
        setSignalingMode('BURST');

        // If Cloudflare Quick Tunnel is active, dispatch URL to phone immediately!
        if (activeTunnelUrl) {
            console.log(`[Cloudflare Tunnel] ⚡ Phone requested connection. Dispatching active WAN Tunnel URL: ${activeTunnelUrl}`);
            postSignalMessage('phone', {
                type: 'tunnel_ready',
                url: activeTunnelUrl,
                pairingPin: phoneConfig.pairingPin,
                deviceToken: phoneConfig.deviceToken
            });
        }

        const now = Date.now();
        // Debounce spam (< 800ms)
        if (now - lastOfferSentTime < 800) {
            return;
        }
        lastOfferSentTime = now;

        console.log(`[AnoLink] ⚡ Connection request from Phone (${msg.device || 'Android'}). Starting fresh P2P handshake...`);
        createWebRtcOffer(phoneTopic);
        return;
    }

    if (msg.type === 'session_close' || msg.type === 'app_closing') {
        console.log('[AnoLink] 📱 Phone session closed. Resetting connection state immediately.');
        connectionState = 'DISCONNECTED';
        activeControlDc = null;
        activeVideoDc = null;
        stopPcScreenStreaming(true);
        if (activeWebRtcPeer) {
            try { activeWebRtcPeer.close(); } catch (_) {}
            activeWebRtcPeer = null;
        }
        setSignalingMode('STANDBY');
        return;
    }

    if (msg.type === 'answer') {
        if (!activeWebRtcPeer || !msg.sdp) return;

        // If answer was already applied and signaling state is stable, absorb candidates
        if (hasRemoteAnswer) {
            if (Array.isArray(msg.candidates)) {
                for (const c of msg.candidates) {
                    try {
                        const candStr = (c.candidate || '').replace(/^a=/, '');
                        if (candStr) {
                            activeWebRtcPeer.addRemoteCandidate(candStr, c.mid || '0');
                        }
                    } catch (_) {}
                }
            }
            return;
        }

        console.log('[AnoLink] 🤝 Received SDP Answer from Phone! Establishing direct P2P connection...');
        try {
            hasRemoteAnswer = true;
            activeWebRtcPeer.setRemoteDescription(msg.sdp, 'answer');
            // Add any bundled candidates received with the answer
            if (Array.isArray(msg.candidates)) {
                for (const c of msg.candidates) {
                    try {
                        const candStr = (c.candidate || '').replace(/^a=/, '');
                        if (candStr) {
                            activeWebRtcPeer.addRemoteCandidate(candStr, c.mid || '0');
                        }
                    } catch (_) {}
                }
            }
            // Flush all buffered candidates
            for (const c of pendingRemoteCandidates) {
                try {
                    const candStr = (c.candidate || '').replace(/^a=/, '');
                    if (candStr) {
                        activeWebRtcPeer.addRemoteCandidate(candStr, c.mid || '0');
                    }
                } catch (_) {}
            }
            pendingRemoteCandidates = [];
        } catch (e) {
            console.error('[AnoLink] Error setting remote Answer:', e.message);
        }
        return;
    }

    if (msg.type === 'candidate') {
        if (activeWebRtcPeer && msg.candidate) {
            const candStr = (msg.candidate || '').replace(/^a=/, '');
            if (hasRemoteAnswer) {
                try {
                    activeWebRtcPeer.addRemoteCandidate(candStr, msg.mid || '0');
                } catch (_) {}
            } else {
                pendingRemoteCandidates.push({ ...msg, candidate: candStr });
            }
        }
        return;
    }
}

function createWebRtcOffer(phoneTopic) {
    if (!nodeDataChannel) {
        console.error('[AnoLink] node-datachannel engine not available!');
        return;
    }

    if (activeWebRtcPeer) {
        const oldPeer = activeWebRtcPeer;
        activeWebRtcPeer = null;
        try { oldPeer.close(); } catch (_) {}
        activeControlDc = null;
        activeVideoDc = null;
    }

    lastOfferTime = Date.now();
    lastOfferSdp = null;
    localCandidates = [];
    hasRemoteAnswer = false;
    pendingRemoteCandidates = [];

    const peer = new nodeDataChannel.PeerConnection('AnoLink-PC', {
        iceServers: [
            'stun:stun.l.google.com:19302',
            'stun:stun1.l.google.com:19302',
            'stun:stun2.l.google.com:19302',
            'stun:stun.cloudflare.com:3478'
        ]
    });

    let dispatchTimer = null;
    let hasDispatched = false;

    function dispatchBundledOffer() {
        if (hasDispatched) return;
        hasDispatched = true;
        if (dispatchTimer) clearTimeout(dispatchTimer);
        if (peer !== activeWebRtcPeer || hasRemoteAnswer || !lastOfferSdp) return;

        // Embed all valid candidates directly into the Offer SDP
        let fullSdp = lastOfferSdp;
        // Filter out non-routable virtual adapter IPs (WSL2 / Hyper-V 172.x)
        const validCandidates = localCandidates.filter(c => {
            const cand = c.candidate || '';
            return !cand.includes(' 172.28.') && !cand.includes(' 172.24.') && !cand.includes(' 172.17.') && !cand.includes(' 169.254.');
        });

        for (const c of validCandidates) {
            const candLine = c.candidate.startsWith('a=') ? c.candidate : `a=${c.candidate}`;
            if (!fullSdp.includes(candLine.trim())) {
                fullSdp += candLine.trim() + '\r\n';
            }
        }

        const cleanCandidates = validCandidates.map(c => ({
            candidate: c.candidate.replace(/^a=/, ''),
            mid: c.mid || '0'
        }));

        console.log(`[AnoLink] 📡 Dispatched WebRTC Offer (${fullSdp.length} bytes, ${validCandidates.length} WAN/LAN candidates)`);
        postSignalMessage(phoneTopic, {
            type: 'offer',
            sdp: fullSdp,
            candidates: cleanCandidates
        });
    }

    peer.onLocalDescription((sdp, type) => {
        lastOfferSdp = sdp;
        // Wait 1400ms for public STUN and TURN candidates to fully resolve and gather
        dispatchTimer = setTimeout(dispatchBundledOffer, 1400);
    });

    peer.onLocalCandidate((candidate, mid) => {
        localCandidates.push({ candidate, mid });
        // If TURN relay candidate arrives early, dispatch after brief buffer
        if (candidate.includes('relay') && lastOfferSdp && !hasDispatched) {
            if (dispatchTimer) clearTimeout(dispatchTimer);
            dispatchTimer = setTimeout(dispatchBundledOffer, 250);
        }
    });

    if (typeof peer.onError === 'function') {
        peer.onError((err) => {
            console.warn('[WebRTC] Peer Connection notice:', err);
        });
    }

    peer.onStateChange((state) => {
        if (peer !== activeWebRtcPeer) return;

        if (state === 'connected') {
            connectionState = 'CONNECTED';
            setSignalingMode('PAUSED');
            console.log('[AnoLink] 🟢 Direct WebRTC P2P DataChannel Connected (UDP)!');
        } else if (state === 'disconnected' || state === 'failed') {
            connectionState = 'DISCONNECTED';
            activeControlDc = null;
            activeVideoDc = null;
            stopPcScreenStreaming(true);
            setSignalingMode('STANDBY');
        }
        broadcastToBrowsers({
            type: 'status_change',
            state: connectionState,
            phoneConfig
        });
    });

    // Create control DataChannel (reliable, ordered)
    const controlDc = peer.createDataChannel('ano-control');
    bindControlDataChannel(controlDc);

    // Create video DataChannel (unreliable, unordered for 30 FPS video frames)
    const videoDc = peer.createDataChannel('ano-video', {
        unordered: true,
        maxRetransmits: 0
    });
    bindVideoDataChannel(videoDc);

    activeWebRtcPeer = peer;
}

function bindControlDataChannel(dc) {
    activeControlDc = dc;

    if (typeof dc.onError === 'function') {
        dc.onError((err) => {
            console.warn('[WebRTC] Control DataChannel notice:', err);
        });
    }

    dc.onOpen(() => {
        console.log('[WebRTC] P2P Control DataChannel OPEN! Full duplex encrypted control active.');
        connectionState = 'CONNECTED';
        setSignalingMode('PAUSED');
        broadcastToBrowsers({
            type: 'status_change',
            state: connectionState,
            phoneConfig
        });

        try {
            dc.sendMessage(JSON.stringify({
                type: 'pc_battery_status',
                percent: batteryInfoCache.percent,
                isCharging: batteryInfoCache.isCharging,
                hasBattery: batteryInfoCache.hasBattery
            }));
            if (winBridge && winBridge.stdin && winBridge.stdin.writable) {
                winBridge.stdin.write('GET_WINDOWS\n');
                winBridge.stdin.write('GET_POWER\n');
            }
        } catch (_) {}
    });

    dc.onMessage((data) => {
        try {
            const str = data.toString().trim();
            const msg = str.startsWith('{') ? JSON.parse(str) : { action: str };
            if (msg.action && typeof msg.action === 'string' && msg.action.trim().startsWith('{')) {
                try {
                    const nested = JSON.parse(msg.action);
                    Object.assign(msg, nested);
                } catch (_) {}
            }

            if (msg.type === 'pair_request') {
                const pin = (msg.pin || '').toString().replace(/\D/g, '');
                const expectedPin = phoneConfig.pairingPin.replace(/\D/g, '');
                if (pin === expectedPin) {
                    console.log(`[WebRTC] Successfully verified 1-Time Pairing PIN (${pin}) from phone!`);
                    dc.sendMessage(JSON.stringify({
                        type: 'pair_response',
                        success: true,
                        deviceToken: phoneConfig.deviceToken,
                        pcName: os.hostname(),
                        fps: 30
                    }));
                } else {
                    dc.sendMessage(JSON.stringify({
                        type: 'pair_response',
                        success: false,
                        message: 'Invalid 6-digit PIN'
                    }));
                }
                return;
            }

            handlePhoneMessage(msg);
        } catch (err) {
            console.error('[WebRTC] Error parsing Control message:', err);
        }
    });

    dc.onClosed(() => {
        console.log('[WebRTC] Control DataChannel closed.');
        if (activeControlDc === dc) activeControlDc = null;
        setSignalingMode('STANDBY');
    });
}

function bindVideoDataChannel(dc) {
    activeVideoDc = dc;

    if (typeof dc.onError === 'function') {
        dc.onError((err) => {
            console.warn('[WebRTC] Video DataChannel notice:', err);
        });
    }

    dc.onOpen(() => {
        console.log('[WebRTC] P2P Video DataChannel OPEN! 30 FPS hardware stream ready.');
    });
    dc.onMessage((data) => {
        broadcastBinaryToBrowsers(data);
    });
    dc.onClosed(() => {
        if (activeVideoDc === dc) activeVideoDc = null;
    });
}

// Upgrade handling for WebSockets: Browser Dashboard (/ws-browser) & Phone Remote Tunnel (/ws-phone)
server.on('upgrade', (request, socket, head) => {
    const pathname = new URL(request.url, `http://${request.headers.host}`).pathname;
    if (pathname === '/ws-browser') {
        wssBrowser.handleUpgrade(request, socket, head, (ws) => {
            wssBrowser.emit('connection', ws, request);
        });
    } else if (pathname === '/ws-phone') {
        wssPhone.handleUpgrade(request, socket, head, (ws) => {
            wssPhone.emit('connection', ws, request);
        });
    } else {
        socket.destroy();
    }
});

// Phone Remote Connection Handling via Cloudflare Quick Tunnel
wssPhone.on('connection', (ws) => {
    console.log('[Cloudflare Tunnel] 📱 Android Phone connected via WAN WebSocket Tunnel!');
    if (activePhoneWs && activePhoneWs !== ws) {
        try { activePhoneWs.close(); } catch (_) {}
    }
    activePhoneWs = ws;
    connectionState = 'CONNECTED';
    setSignalingMode('PAUSED');

    broadcastToBrowsers({
        type: 'status_change',
        state: connectionState,
        phoneConfig
    });

    try {
        ws.send(JSON.stringify({
            type: 'pc_battery_status',
            percent: batteryInfoCache.percent,
            isCharging: batteryInfoCache.isCharging,
            hasBattery: batteryInfoCache.hasBattery
        }));
        if (winBridge && winBridge.stdin && winBridge.stdin.writable) {
            winBridge.stdin.write('GET_WINDOWS\n');
            winBridge.stdin.write('GET_POWER\n');
        }
    } catch (_) {}

    ws.on('message', (data, isBinary) => {
        if (isBinary) return;
        try {
            const str = data.toString().trim();
            const msg = str.startsWith('{') ? JSON.parse(str) : { action: str };
            if (msg.action && typeof msg.action === 'string' && msg.action.trim().startsWith('{')) {
                try {
                    const nested = JSON.parse(msg.action);
                    Object.assign(msg, nested);
                } catch (_) {}
            }

            if (msg.type === 'pair_request') {
                const pin = (msg.pin || '').toString().replace(/\D/g, '');
                const expectedPin = phoneConfig.pairingPin.replace(/\D/g, '');
                if (pin === expectedPin) {
                    console.log(`[Cloudflare Tunnel] ✅ Successfully verified PIN (${pin}) from Phone!`);
                    ws.send(JSON.stringify({
                        type: 'pair_response',
                        success: true,
                        deviceToken: phoneConfig.deviceToken,
                        pcName: os.hostname(),
                        fps: 30
                    }));
                } else {
                    ws.send(JSON.stringify({
                        type: 'pair_response',
                        success: false,
                        message: 'Invalid 6-digit PIN'
                    }));
                }
                return;
            }

            handlePhoneMessage(msg);
        } catch (err) {
            console.error('[Tunnel] Error processing message from phone:', err);
        }
    });

    ws.on('close', () => {
        console.log('[Cloudflare Tunnel] 📱 Phone WAN Tunnel disconnected.');
        if (activePhoneWs === ws) {
            activePhoneWs = null;
            connectionState = 'DISCONNECTED';
            stopPcScreenStreaming(true);
            setSignalingMode('STANDBY');
            broadcastToBrowsers({
                type: 'status_change',
                state: connectionState,
                phoneConfig
            });
        }
    });

    ws.on('error', (err) => {
        console.warn('[Cloudflare Tunnel] Phone socket notice:', err.message);
    });
});

function broadcastToBrowsers(message) {
    const payload = typeof message === 'string' ? message : JSON.stringify(message);
    wssBrowser.clients.forEach((client) => {
        if (client.readyState === WebSocket.OPEN) {
            if (message && message.type === 'mic_audio' && client.bufferedAmount > 8192) {
                return;
            }
            client.send(payload);
        }
    });
}

function broadcastToPhoneRemote(message) {
    const payload = typeof message === 'string' ? message : JSON.stringify(message);
    if (activePhoneWs && activePhoneWs.readyState === WebSocket.OPEN) {
        try {
            activePhoneWs.send(payload);
        } catch (_) {}
    }
    if (activeControlDc && activeControlDc.isOpen && activeControlDc.isOpen()) {
        try {
            activeControlDc.sendMessage(payload);
        } catch (_) {}
    }
}

function broadcastBinaryToBrowsers(buffer) {
    wssBrowser.clients.forEach((client) => {
        if (client.readyState === WebSocket.OPEN) {
            if (client.bufferedAmount === 0) {
                client.send(buffer, { binary: true });
            }
        }
    });
}

// Native Win32 Controller Bridge Management
function initWinBridge() {
    try {
        if (!fs.existsSync(bridgeExePath)) {
            console.warn('[PC] AnoLinkWinBridge.exe not found at:', bridgeExePath);
            return;
        }
        winBridge = child_process.spawn(bridgeExePath, [], { stdio: ['pipe', 'pipe', 'inherit'] });
        console.log('[PC] Spawned native AnoLinkWinBridge controller');

        const rl = readline.createInterface({ input: winBridge.stdout });
        rl.on('line', (line) => {
            try {
                const msg = JSON.parse(line.trim());
                handleBridgeMessage(msg);
            } catch (e) {
                // not json
            }
        });

        winBridge.on('exit', (code) => {
            console.log('[PC] WinBridge exited with code:', code);
            winBridge = null;
            setTimeout(initWinBridge, 2500);
        });
    } catch (err) {
        console.error('[PC] Failed to start WinBridge:', err);
    }
}

function handleBridgeMessage(msg) {
    if (msg.type === 'pc_screen_frame') {
        const frameBuf = Buffer.from(msg.data, 'base64');
        if (activePhoneWs && activePhoneWs.readyState === WebSocket.OPEN) {
            try {
                if (activePhoneWs.bufferedAmount < 512 * 1024) {
                    activePhoneWs.send(frameBuf, { binary: true });
                }
            } catch (_) {}
        }
        if (activeVideoDc && activeVideoDc.isOpen && activeVideoDc.isOpen()) {
            try {
                const bufAmt = activeVideoDc.bufferedAmount ? activeVideoDc.bufferedAmount() : 0;
                if (bufAmt < 512 * 1024) {
                    activeVideoDc.sendMessageBinary(frameBuf);
                }
            } catch (_) {}
        } else if (activeControlDc && activeControlDc.isOpen && activeControlDc.isOpen()) {
            try {
                const bufAmt = activeControlDc.bufferedAmount ? activeControlDc.bufferedAmount() : 0;
                if (bufAmt < 256 * 1024) {
                    activeControlDc.sendMessageBinary(frameBuf);
                }
            } catch (_) {}
        }
        broadcastBinaryToBrowsers(frameBuf);
        return;
    }

    if (msg.type === 'taskbar_apps' || msg.type === 'window_focused' || msg.type === 'power_status' || msg.type === 'pong' || msg.type === 'start_apps' || msg.type === 'launch_status' || msg.type === 'pc_battery_status') {
        if (msg.type === 'pc_battery_status') {
            batteryInfoCache = {
                percent: typeof msg.percent === 'number' ? msg.percent : 100,
                isCharging: !!msg.isCharging,
                hasBattery: msg.hasBattery !== false
            };
        }
        sendToPhone(msg);
        broadcastToPhoneRemote(msg);
        broadcastToBrowsers(msg);
    }
}

let isPcStreamingActive = false;

function startPcScreenStreaming(fps, w, h) {
    const f = fps || 30; // 30 FPS target
    const width = w || 960;
    const height = h || 540;
    console.log(`[PC] Starting PC desktop screen streaming (${width}x${height} @ ${f}fps)...`);
    isPcStreamingActive = true;
    if (winBridge && winBridge.stdin && winBridge.stdin.writable) {
        winBridge.stdin.write(`STREAM_START ${f} ${width} ${height}\n`);
    } else {
        initWinBridge();
        setTimeout(() => {
            if (winBridge && winBridge.stdin && winBridge.stdin.writable) {
                winBridge.stdin.write(`STREAM_START ${f} ${width} ${height}\n`);
            }
        }, 400);
    }
}

function stopPcScreenStreaming(force = false) {
    const hasActiveDataChannel = activeControlDc && activeControlDc.isOpen && activeControlDc.isOpen();
    if (!force && hasActiveDataChannel) {
        // Do not stop stream if WebRTC DataChannel is currently connected unless force=true
        return;
    }
    console.log('[PC] Stopping PC desktop screen streaming.');
    isPcStreamingActive = false;
    if (winBridge && winBridge.stdin && winBridge.stdin.writable) {
        winBridge.stdin.write('STREAM_STOP\n');
    }
}

// Resilient Phone Daemon Auto-Reconnect Engine
let autoReconnectTimer = null;
let isExplicitlyDisconnected = false;

// Manage connection to Phone Daemon
function connectToPhone() {
    if (autoReconnectTimer) {
        clearTimeout(autoReconnectTimer);
        autoReconnectTimer = null;
    }

    if (!phoneConfig.ip) {
        return;
    }

    if (connectionState === 'CONNECTED' || connectionState === 'CONNECTING') {
        return;
    }

    isExplicitlyDisconnected = false;
    connectionState = 'CONNECTING';
    broadcastToBrowsers({ type: 'status_change', state: connectionState });
    console.log(`[PC] Initiating connection to Phone at ws://${phoneConfig.ip}:${phoneConfig.port}/ws...`);

    try {
        const phoneWsUrl = `ws://${phoneConfig.ip}:${phoneConfig.port}/ws?token=${phoneConfig.token}`;
        phoneSocket = new WebSocket(phoneWsUrl, {
            handshakeTimeout: 5000
        });

        phoneSocket.on('open', () => {
            console.log('[PC] Successfully connected to Phone daemon!');
            connectionState = 'CONNECTED';
            broadcastToBrowsers({ type: 'status_change', state: connectionState, phoneConfig });

            // Request initial telemetry and SMS history right away
            requestTelemetry();
            sendToPhone({ action: 'get_sms' });

            // Set initial mode
            sendToPhone({
                type: 'set_mode',
                mode: currentMode,
                quality: streamQuality
            });

            // Initial Ano-Link taskbar sync
            if (winBridge && winBridge.stdin && winBridge.stdin.writable) {
                winBridge.stdin.write('GET_WINDOWS\n');
            }
            if (!taskbarSyncTimer) {
                taskbarSyncTimer = setInterval(() => {
                    if (phoneSocket && phoneSocket.readyState === WebSocket.OPEN && winBridge && winBridge.stdin && winBridge.stdin.writable) {
                        winBridge.stdin.write('GET_WINDOWS\n');
                    }
                }, 4000);
            }
        });

        phoneSocket.on('message', (data, isBinary) => {
            if (isBinary) {
                broadcastBinaryToBrowsers(data);
                return;
            }
            try {
                const msg = JSON.parse(data.toString());
                handlePhoneMessage(msg);
            } catch (err) {
                console.error('[PC] Error parsing message from phone:', err);
            }
        });

        phoneSocket.on('close', (code, reason) => {
            console.log(`[PC] Connection to Phone closed (${code} - ${reason})`);
            cleanupPhoneConnection();
        });

        phoneSocket.on('error', (err) => {
            console.error(`[PC] Phone socket error:`, err.message);
            cleanupPhoneConnection();
        });

    } catch (error) {
        console.error('[PC] Failed to dial Phone daemon:', error.message);
        cleanupPhoneConnection();
    }
}

function disconnectFromPhone() {
    isExplicitlyDisconnected = true;
    if (autoReconnectTimer) {
        clearTimeout(autoReconnectTimer);
        autoReconnectTimer = null;
    }
    if (phoneSocket) {
        try {
            phoneSocket.send(JSON.stringify({ type: 'session_close' }));
            phoneSocket.close();
        } catch (_) {}
    }
    cleanupPhoneConnection();
}

function cleanupPhoneConnection() {
    if (taskbarSyncTimer) {
        clearInterval(taskbarSyncTimer);
        taskbarSyncTimer = null;
    }
    stopPcScreenStreaming();

    phoneSocket = null;
    connectionState = 'DISCONNECTED';
    broadcastToBrowsers({ type: 'status_change', state: connectionState });

    // Automatically retry connecting only if not explicitly disconnected and phoneConfig.ip is set
    if (!isExplicitlyDisconnected && !autoReconnectTimer && phoneConfig.ip) {
        autoReconnectTimer = setTimeout(() => {
            autoReconnectTimer = null;
            if (connectionState === 'DISCONNECTED' && !isExplicitlyDisconnected && phoneConfig.ip) {
                connectToPhone();
            }
        }, 3000);
    }
}

function sendToPhone(message) {
    if (phoneSocket && phoneSocket.readyState === WebSocket.OPEN) {
        phoneSocket.send(JSON.stringify(message));
        return true;
    }
    broadcastToPhoneRemote(message);
    return true;
}

function requestTelemetry() {
    return sendToPhone({ type: 'get_telemetry' });
}

// Handle incoming messages from Android Phone
function handlePhoneMessage(msg) {
    const action = msg.action || msg.type;

    if (action && (action.startsWith('pc_') || action === 'ping' || action === 'start_pc_screen_stream')) {
        lastRemoteStreamActivity = Date.now();
    }

    // -------------------------------------------------------------
    // Ano-Link: Phone Controlling PC Commands
    // -------------------------------------------------------------
    if (action === 'pc_mouse_move') {
        if (winBridge && winBridge.stdin && winBridge.stdin.writable) {
            winBridge.stdin.write(`M ${msg.dx || 0} ${msg.dy || 0}\n`);
        }
        return;
    }
    if (action === 'pc_mouse_abs') {
        if (winBridge && winBridge.stdin && winBridge.stdin.writable) {
            winBridge.stdin.write(`MA ${msg.x || 0} ${msg.y || 0}\n`);
        }
        return;
    }
    if (action === 'pc_mouse_move_norm') {
        if (winBridge && winBridge.stdin && winBridge.stdin.writable) {
            winBridge.stdin.write(`MN ${msg.nx || 0} ${msg.ny || 0}\n`);
        }
        return;
    }
    if (action === 'pc_mouse_tap') {
        if (winBridge && winBridge.stdin && winBridge.stdin.writable) {
            winBridge.stdin.write(`TAP ${msg.nx || 0} ${msg.ny || 0} ${msg.button || 'left'} ${msg.clickType || 'click'}\n`);
        }
        return;
    }
    if (action === 'pc_mouse_click') {
        if (winBridge && winBridge.stdin && winBridge.stdin.writable) {
            winBridge.stdin.write(`CLICK ${msg.button || 'left'} ${msg.clickType || 'click'}\n`);
        }
        return;
    }
    if (action === 'pc_mouse_scroll') {
        if (winBridge && winBridge.stdin && winBridge.stdin.writable) {
            winBridge.stdin.write(`SCROLL ${msg.dy || 0}\n`);
        }
        return;
    }
    if (action === 'pc_key') {
        if (winBridge && winBridge.stdin && winBridge.stdin.writable) {
            winBridge.stdin.write(`KEY ${msg.key}\n`);
        }
        return;
    }
    if (action === 'pc_type') {
        if (winBridge && winBridge.stdin && winBridge.stdin.writable) {
            winBridge.stdin.write(`TYPE ${msg.text || ''}\n`);
        }
        return;
    }
    if (action === 'pc_hotkey') {
        if (winBridge && winBridge.stdin && winBridge.stdin.writable && Array.isArray(msg.keys)) {
            winBridge.stdin.write(`HOTKEY ${msg.keys.join(' ')}\n`);
        }
        return;
    }
    if (action === 'pc_get_taskbar') {
        if (winBridge && winBridge.stdin && winBridge.stdin.writable) {
            winBridge.stdin.write('GET_WINDOWS\n');
            winBridge.stdin.write('GET_POWER\n');
        }
        broadcastToPhoneRemote({
            type: 'pc_battery_status',
            percent: batteryInfoCache.percent,
            isCharging: batteryInfoCache.isCharging,
            hasBattery: batteryInfoCache.hasBattery
        });
        return;
    }
    if (action === 'pc_focus_window') {
        if (winBridge && winBridge.stdin && winBridge.stdin.writable) {
            winBridge.stdin.write(`FOCUS_WINDOW ${msg.hwnd}\n`);
        }
        return;
    }
    if (action === 'pc_power') {
        if (winBridge && winBridge.stdin && winBridge.stdin.writable) {
            winBridge.stdin.write(`POWER ${msg.powerOption || msg.option}\n`);
        }
        return;
    }
    if (action === 'pc_launch_app') {
        const target = msg.path || msg.target || '';
        console.log(`[PC] Launching application on PC: ${target}`);
        if (winBridge && winBridge.stdin && winBridge.stdin.writable) {
            winBridge.stdin.write(`LAUNCH_APP ${target}\n`);
        }
        return;
    }
    if (action === 'pc_get_start_apps') {
        console.log('[PC] Requesting Start Menu apps from bridge');
        if (winBridge && winBridge.stdin && winBridge.stdin.writable) {
            winBridge.stdin.write('GET_START_APPS\n');
        }
        return;
    }
    if (action === 'pc_get_hardware') {
        const stats = getHardwareTelemetrySnapshot();
        broadcastToPhoneRemote({
            type: 'pc_hardware_stats',
            data: stats
        });
        return;
    }
    if (action === 'pc_get_processes') {
        getRunningProcessesSnapshot((procs) => {
            broadcastToPhoneRemote({
                type: 'pc_processes_list',
                processes: procs
            });
        });
        return;
    }
    if (action === 'pc_kill_process') {
        const pid = parseInt(msg.pid, 10);
        if (pid) {
            killProcessSafely(pid, msg.exe, (result) => {
                broadcastToPhoneRemote({
                    type: 'pc_kill_result',
                    ...result
                });
            });
        }
        return;
    }
    if (action === 'get_pc_files') {
        const reqPath = msg.path || '';
        let result;
        if (!reqPath || reqPath === 'root' || reqPath === '/' || reqPath === '') {
            result = {
                type: 'pc_files_response',
                currentPath: '',
                parentPath: null,
                isRoot: true,
                items: getWindowsDrives()
            };
        } else {
            try {
                const resolved = path.resolve(reqPath);
                if (fs.existsSync(resolved) && fs.statSync(resolved).isDirectory()) {
                    const entries = fs.readdirSync(resolved, { withFileTypes: true });
                    const items = [];
                    for (const entry of entries) {
                        if (items.length >= 300) break; // Keep payload safely under 64KB WebRTC DataChannel limit
                        try {
                            const itemPath = path.join(resolved, entry.name);
                            const isDir = entry.isDirectory();
                            let size = 0;
                            let mtime = Date.now();
                            try {
                                const s = fs.statSync(itemPath);
                                size = s.size;
                                mtime = s.mtimeMs;
                            } catch (_) {}
                            items.push({
                                name: entry.name,
                                path: itemPath,
                                isDirectory: isDir,
                                size: size,
                                mtime: mtime,
                                ext: isDir ? '' : path.extname(entry.name).toLowerCase().replace('.', '')
                            });
                        } catch (_) {}
                    }
                    items.sort((a, b) => {
                        if (a.isDirectory && !b.isDirectory) return -1;
                        if (!a.isDirectory && b.isDirectory) return 1;
                        return a.name.localeCompare(b.name, undefined, { sensitivity: 'base' });
                    });
                    let parentPath = path.dirname(resolved);
                    if (parentPath === resolved) parentPath = '';
                    result = {
                        type: 'pc_files_response',
                        currentPath: resolved,
                        parentPath: parentPath,
                        isRoot: false,
                        items: items
                    };
                } else {
                    result = { type: 'pc_files_response', error: 'Directory not found', currentPath: reqPath, items: [] };
                }
            } catch (err) {
                result = { type: 'pc_files_response', error: err.message, currentPath: reqPath, items: [] };
            }
        }
        broadcastToPhoneRemote(result);
        return;
    }
    if (action === 'pc_file_action') {
        const fileAction = msg.fileAction; // 'open', 'delete', 'run'
        const filePath = msg.path;
        if (filePath && fs.existsSync(filePath)) {
            if (fileAction === 'delete') {
                try {
                    fs.unlinkSync(filePath);
                    broadcastToPhoneRemote({ type: 'pc_file_action_result', success: true, action: 'delete', path: filePath });
                } catch (e) {
                    broadcastToPhoneRemote({ type: 'pc_file_action_result', success: false, error: e.message });
                }
            } else if (fileAction === 'open' || fileAction === 'run') {
                child_process.exec(`start "" "${filePath}"`, { windowsHide: false });
                broadcastToPhoneRemote({ type: 'pc_file_action_result', success: true, action: fileAction, path: filePath });
            }
        }
        return;
    }
    if (action === 'download_pc_file') {
        handleP2PFileDownload(msg);
        return;
    }
    if (action === 'cancel_file_download') {
        const downloadId = msg.downloadId;
        if (downloadId && activeFileDownloads.has(downloadId)) {
            activeFileDownloads.set(downloadId, false);
        }
        return;
    }
    if (action === 'app_closing' || action === 'session_close') {
        console.log('[AnoLink] 📱 Phone app closed. Resetting connection state immediately.');
        connectionState = 'DISCONNECTED';
        activeControlDc = null;
        activeVideoDc = null;
        stopPcScreenStreaming(true);
        if (activeWebRtcPeer) {
            try { activeWebRtcPeer.close(); } catch (_) {}
            activeWebRtcPeer = null;
        }
        setSignalingMode('STANDBY');
        return;
    }
    if (action === 'ping') {
        broadcastToPhoneRemote({ type: 'pong', timestamp: Date.now() });
        return;
    }
    if (action === 'start_pc_screen_stream') {
        lastRemoteStreamActivity = Date.now();
        startPcScreenStreaming(msg.fps, msg.width || msg.w, msg.height || msg.h);
        return;
    }
    if (action === 'stop_pc_screen_stream') {
        stopPcScreenStreaming(true);
        return;
    }
    if (action === 'exclusive_mode') {
        isExclusiveControlActive = !!msg.active;
        console.log(`[PC] Exclusive Control Mode toggled by phone: ${isExclusiveControlActive}`);
        if (isExclusiveControlActive) {
            sendToPhone({ action: 'pause_stream' });
            sendToPhone({ action: 'stop_mic_stream' });
        }
        broadcastToBrowsers({
            type: 'exclusive_mode',
            active: isExclusiveControlActive,
            message: isExclusiveControlActive ? 'Phone is controlling PC — PC dashboard controls locked' : null
        });
        return;
    }

    switch (msg.type) {
        case 'telemetry':
            telemetryCache = msg.data;
            lastTelemetryTime = Date.now();
            console.log('[PC] Received telemetry update from phone');
            broadcastToBrowsers({
                type: 'telemetry_update',
                telemetry: telemetryCache,
                timestamp: lastTelemetryTime
            });
            break;

        case 'skeleton_tree':
            currentSkeletonTree = msg.tree;
            broadcastToBrowsers({
                type: 'skeleton_update',
                tree: msg.tree,
                screenWidth: msg.screenWidth,
                screenHeight: msg.screenHeight
            });
            break;

        case 'skeleton_error':
            console.log('[PC] Skeleton Error from Phone:', msg.message);
            broadcastToBrowsers({
                type: 'skeleton_error',
                message: msg.message
            });
            break;

        case 'incoming_sms':
            console.log(`[PC] Incoming SMS from ${msg.sender}: ${msg.body}`);
            smsHistoryCache.unshift(msg);
            broadcastToBrowsers({
                type: 'sms_alert',
                sms: msg
            });
            break;

        case 'contacts_list':
            broadcastToBrowsers({
                type: 'contacts_update',
                contacts: msg.contacts
            });
            break;

        case 'sms_list':
            smsHistoryCache = msg.messages || [];
            console.log(`[PC] Received SMS history (${smsHistoryCache.length} messages) from phone`);
            broadcastToBrowsers({
                type: 'sms_history',
                messages: smsHistoryCache
            });
            break;

        case 'file_list':
            broadcastToBrowsers({
                type: 'file_list',
                path: msg.path,
                files: msg.files,
                allFilesAccess: msg.allFilesAccess
            });
            break;

        case 'notification':
            broadcastToBrowsers({
                type: 'phone_notification',
                notification: msg
            });
            break;

        default:
            broadcastToBrowsers(msg);
            break;
    }
}

// Browser connection handling
wssBrowser.on('connection', (ws) => {
    console.log('[PC] Browser connected to local Web Controller');
    
    // Auto-detect if direct WebRTC P2P DataChannel is active
    const isWebRtcConnected = !!(activeControlDc && activeControlDc.isOpen && activeControlDc.isOpen());
    const effectiveState = isWebRtcConnected ? 'CONNECTED' : connectionState;
    const effectivePhoneConfig = isWebRtcConnected
        ? { ...phoneConfig, ip: 'WebRTC P2P Direct', isWebRtc: true }
        : phoneConfig;

    // Send initial state snapshot
    ws.send(JSON.stringify({
        type: 'initial_state',
        state: effectiveState,
        phoneConfig: effectivePhoneConfig,
        telemetry: telemetryCache,
        lastTelemetryTime,
        mode: currentMode,
        quality: streamQuality,
        skeletonTree: currentSkeletonTree,
        smsHistory: smsHistoryCache
    }));

    if (isWebRtcConnected) {
        requestTelemetry();
        sendToPhone({ action: 'get_sms' });
        sendToPhone({ action: 'get_skeleton_tree' });
    }

    ws.on('message', (raw) => {
        try {
            const msg = JSON.parse(raw.toString());
            handleBrowserMessage(msg);
        } catch (err) {
            console.error('[PC] Error handling browser message:', err);
        }
    });
});

function handleBrowserMessage(msg) {
    switch (msg.action) {
        case 'connect':
        case 'save_config':
            if (msg.phoneConfig) {
                phoneConfig = { ...phoneConfig, ...msg.phoneConfig };
                saveConfig();
            }
            if (msg.action !== 'save_config') {
                connectToPhone();
            } else {
                broadcastToBrowsers({ type: 'status_change', state: connectionState, phoneConfig });
            }
            break;

        case 'disconnect':
            disconnectFromPhone();
            break;

        case 'refresh_telemetry':
            requestTelemetry();
            break;

        case 'set_mode':
            currentMode = msg.mode;
            sendToPhone({ action: 'resume_stream' });
            sendToPhone({
                type: 'set_mode',
                mode: currentMode,
                quality: streamQuality
            });
            broadcastToBrowsers({ type: 'mode_changed', mode: currentMode });
            break;

        case 'set_quality':
            streamQuality = { ...streamQuality, ...msg.quality };
            sendToPhone({
                type: 'set_quality',
                quality: streamQuality
            });
            broadcastToBrowsers({ type: 'quality_changed', quality: streamQuality });
            break;

        // Forward interaction actions directly to the phone
        case 'refresh_skeleton':
        case 'get_skeleton_tree':
        case 'touch_tap':
        case 'touch_swipe':
        case 'key_nav':
        case 'input_text':
        case 'open_url':
        case 'make_call':
        case 'get_sms':
        case 'send_sms':
        case 'get_contacts':
        case 'list_dir':
        case 'switch_camera':
        case 'toggle_torch':
        case 'start_mic_stream':
        case 'stop_mic_stream':
        case 'speaker_audio':
        case 'set_speaker_volume':
        case 'play_chime':
            if (isExclusiveControlActive) {
                console.log('[PC] Phone action blocked: Exclusive Mode active on Phone.');
                broadcastToBrowsers({
                    type: 'action_blocked',
                    reason: 'Phone is currently controlling PC. Controls are temporarily locked to avoid interference.'
                });
                return;
            }
            sendToPhone(msg);
            break;

        case 'pause_stream':
        case 'resume_stream':
            sendToPhone(msg);
            break;

        default:
            console.log('[PC] Unknown action from browser:', msg.action);
            break;
    }
}

// REST APIs
app.get('/api/status', (req, res) => {
    res.json({
        state: connectionState,
        phoneConfig,
        telemetry: telemetryCache,
        lastTelemetryTime,
        mode: currentMode,
        quality: streamQuality
    });
});

app.post('/api/config', (req, res) => {
    if (req.body.ip) phoneConfig.ip = req.body.ip;
    if (req.body.port) phoneConfig.port = req.body.port;
    if (req.body.token) phoneConfig.token = req.body.token;
    saveConfig();
    res.json({ success: true, phoneConfig });
    if (phoneConfig.ip && connectionState !== 'CONNECTED' && connectionState !== 'CONNECTING') {
        connectToPhone();
    }
});

// WebRTC & Persistent Token Info
app.get('/api/webrtc-info', (req, res) => {
    res.json({
        pairingPin: phoneConfig.pairingPin,
        deviceToken: phoneConfig.deviceToken,
        fps: 30,
        pcName: os.hostname(),
        isWebRtcReady: !!nodeDataChannel,
        isWebRtcConnected: !!(activeWebRtcPeer && activeControlDc && activeControlDc.isOpen && activeControlDc.isOpen())
    });
});

app.post('/api/webrtc-reset-token', (req, res) => {
    phoneConfig.deviceToken = 'ano_tok_' + crypto.randomBytes(16).toString('hex');
    phoneConfig.pairingPin = Math.floor(100000 + Math.random() * 900000).toString();
    saveConfig();
    console.log(`[PC] Security tokens reset. New 1-Time PIN: ${phoneConfig.pairingPin}`);
    broadcastToBrowsers({
        type: 'status_change',
        state: connectionState,
        phoneConfig
    });
    res.json({
        success: true,
        pairingPin: phoneConfig.pairingPin,
        deviceToken: phoneConfig.deviceToken
    });
});

// Proxy file download from phone to browser with range support
app.get('/api/download', (req, res) => {
    const filePath = req.query.path;
    const fileName = req.query.name || path.basename(filePath) || 'download';
    if (!filePath) {
        return res.status(400).send('Missing path parameter');
    }

    const phoneUrl = `http://${phoneConfig.ip}:${phoneConfig.port}/api/files/download?path=${encodeURIComponent(filePath)}`;
    const options = { headers: {} };
    if (req.headers.range) {
        options.headers['Range'] = req.headers.range;
    }

    const proxyReq = http.get(phoneUrl, options, (proxyRes) => {
        res.writeHead(proxyRes.statusCode, {
            ...proxyRes.headers,
            'Content-Disposition': `attachment; filename="${fileName}"`
        });
        proxyRes.pipe(res);
    });

    proxyReq.on('error', (err) => {
        console.error('[PC] Download proxy error:', err.message);
        if (!res.headersSent) {
            res.status(502).send('Error connecting to phone file server');
        }
    });
});

// Helper: Enumerate Windows Drives (C:\, D:\, etc.)
function getWindowsDrives() {
    const drives = [];
    for (let i = 65; i <= 90; i++) {
        const letter = String.fromCharCode(i);
        const drivePath = `${letter}:\\`;
        try {
            if (fs.existsSync(drivePath)) {
                drives.push({
                    name: `Local Disk (${letter}:)`,
                    path: drivePath,
                    isDirectory: true,
                    isDrive: true,
                    size: 0,
                    mtime: Date.now(),
                    ext: ''
                });
            }
        } catch (_) {}
    }
    return drives;
}

// ZArchiver-Style PC File Explorer Endpoint
app.get('/api/pc/files', (req, res) => {
    let reqPath = req.query.path;
    if (!reqPath || reqPath === 'root' || reqPath === '/' || reqPath === '') {
        return res.json({
            currentPath: '',
            parentPath: null,
            isRoot: true,
            items: getWindowsDrives()
        });
    }

    try {
        reqPath = path.resolve(reqPath);
        if (!fs.existsSync(reqPath)) {
            return res.status(404).json({ error: 'Path not found' });
        }

        const stat = fs.statSync(reqPath);
        if (!stat.isDirectory()) {
            return res.status(400).json({ error: 'Path is not a directory' });
        }

        const entries = fs.readdirSync(reqPath, { withFileTypes: true });
        const items = [];

        for (const entry of entries) {
            try {
                const itemPath = path.join(reqPath, entry.name);
                const isDir = entry.isDirectory();
                let size = 0;
                let mtime = Date.now();
                try {
                    const s = fs.statSync(itemPath);
                    size = s.size;
                    mtime = s.mtimeMs;
                } catch (_) {}

                items.push({
                    name: entry.name,
                    path: itemPath,
                    isDirectory: isDir,
                    size: size,
                    mtime: mtime,
                    ext: isDir ? '' : path.extname(entry.name).toLowerCase().replace('.', '')
                });
            } catch (_) {}
        }

        // Sort: directories first alphabetically, then files alphabetically
        items.sort((a, b) => {
            if (a.isDirectory && !b.isDirectory) return -1;
            if (!a.isDirectory && b.isDirectory) return 1;
            return a.name.localeCompare(b.name, undefined, { sensitivity: 'base' });
        });

        // Determine parent path
        let parentPath = path.dirname(reqPath);
        if (parentPath === reqPath) {
            parentPath = '';
        }

        res.json({
            currentPath: reqPath,
            parentPath: parentPath,
            isRoot: false,
            items
        });
    } catch (err) {
        res.status(500).json({ error: err.message });
    }
});

// PC File Download Endpoint with HTTP Range (206) support
app.get(['/api/pc/download', '/api/pc/files/download'], (req, res) => {
    const filePath = req.query.path;
    if (!filePath || !fs.existsSync(filePath)) {
        return res.status(404).send('File not found');
    }

    try {
        const stat = fs.statSync(filePath);
        if (stat.isDirectory()) {
            return res.status(400).send('Cannot download a directory');
        }

        const fileName = path.basename(filePath);
        const fileSize = stat.size;
        const range = req.headers.range;

        if (range) {
            const parts = range.replace(/bytes=/, '').split('-');
            const start = parseInt(parts[0], 10);
            const end = parts[1] ? parseInt(parts[1], 10) : fileSize - 1;
            const chunksize = (end - start) + 1;
            const file = fs.createReadStream(filePath, { start, end });
            const head = {
                'Content-Range': `bytes ${start}-${end}/${fileSize}`,
                'Accept-Ranges': 'bytes',
                'Content-Length': chunksize,
                'Content-Type': 'application/octet-stream',
                'Content-Disposition': `attachment; filename="${encodeURIComponent(fileName)}"`
            };
            res.writeHead(206, head);
            file.pipe(res);
        } else {
            const head = {
                'Content-Length': fileSize,
                'Content-Type': 'application/octet-stream',
                'Content-Disposition': `attachment; filename="${encodeURIComponent(fileName)}"`,
                'Accept-Ranges': 'bytes'
            };
            res.writeHead(200, head);
            fs.createReadStream(filePath).pipe(res);
        }
    } catch (err) {
        if (!res.headersSent) {
            res.status(500).send(err.message);
        }
    }
});

// Pure WebRTC P2P Chunked File Download Transfer
const activeFileDownloads = new Map();

async function handleP2PFileDownload(msg) {
    const downloadId = msg.downloadId;
    const reqPath = msg.path;
    if (!downloadId || !reqPath) return;

    activeFileDownloads.set(downloadId, true);

    try {
        const resolved = path.resolve(reqPath);
        if (!fs.existsSync(resolved) || !fs.statSync(resolved).isFile()) {
            broadcastToPhoneRemote({
                type: 'file_download_error',
                downloadId,
                error: 'File does not exist or is not a regular file'
            });
            activeFileDownloads.delete(downloadId);
            return;
        }

        const stat = fs.statSync(resolved);
        const totalSize = stat.size;
        const fileName = path.basename(resolved);

        console.log(`[AnoLink] 🚀 Starting P2P file download: ${fileName} (${totalSize} bytes, ID: ${downloadId})`);

        broadcastToPhoneRemote({
            type: 'file_download_start',
            downloadId,
            fileName,
            totalSize
        });

        const CHUNK_SIZE = 24576; // 24KB raw -> ~32.7KB base64 (well under 64KB DataChannel limit)
        const fd = fs.openSync(resolved, 'r');
        let offset = 0;
        const buf = Buffer.alloc(CHUNK_SIZE);

        while (offset < totalSize) {
            if (activeFileDownloads.get(downloadId) === false) {
                console.log(`[AnoLink] ⏹ Download cancelled by phone: ${fileName}`);
                break;
            }

            // Flow control: If DataChannel buffer is filling up, throttle
            if (activeControlDc && activeControlDc.bufferedAmount) {
                while (activeControlDc.bufferedAmount() > 256 * 1024) {
                    await new Promise(r => setTimeout(r, 15));
                    if (activeFileDownloads.get(downloadId) === false) break;
                }
            }

            const bytesToRead = Math.min(CHUNK_SIZE, totalSize - offset);
            const bytesRead = fs.readSync(fd, buf, 0, bytesToRead, offset);
            if (bytesRead <= 0) break;

            offset += bytesRead;
            const chunkBase64 = buf.subarray(0, bytesRead).toString('base64');

            broadcastToPhoneRemote({
                type: 'file_download_chunk',
                downloadId,
                offset,
                totalSize,
                chunk: chunkBase64
            });

            // Tiny yield every 4 chunks to let node event loop process keep-alives and I/O
            if ((offset / CHUNK_SIZE) % 4 === 0) {
                await new Promise(r => setTimeout(r, 2));
            }
        }

        fs.closeSync(fd);

        if (activeFileDownloads.get(downloadId) !== false) {
            console.log(`[AnoLink] ✅ P2P file download completed: ${fileName} (${totalSize} bytes)`);
            broadcastToPhoneRemote({
                type: 'file_download_complete',
                downloadId,
                fileName,
                totalSize
            });
        }
    } catch (err) {
        console.error(`[AnoLink] P2P file download error:`, err);
        broadcastToPhoneRemote({
            type: 'file_download_error',
            downloadId,
            error: err.message
        });
    } finally {
        activeFileDownloads.delete(downloadId);
    }
}

// Launch / Open file directly on Windows PC Desktop
app.post('/api/pc/open', (req, res) => {
    const filePath = req.query.path || (req.body && req.body.path);
    if (!filePath || !fs.existsSync(filePath)) {
        return res.status(404).json({ error: 'File not found' });
    }
    try {
        child_process.exec(`start "" "${filePath}"`);
        res.json({ success: true, path: filePath });
    } catch (err) {
        res.status(500).json({ error: err.message });
    }
});

// Get installed Start Menu PC apps with high-res icons
app.get('/api/pc/apps', (req, res) => {
    const cachedPath = path.join(__dirname, '..', 'app_icons', 'installed_apps.json');
    if (fs.existsSync(cachedPath)) {
        try {
            const data = fs.readFileSync(cachedPath, 'utf8').replace(/^\uFEFF/, '');
            return res.type('application/json').send(data);
        } catch (_) {}
    }

    if (winBridge && winBridge.stdin && winBridge.stdin.writable) {
        winBridge.stdin.write('GET_START_APPS\n');
    }
    // Wait briefly if cache is currently being written
    setTimeout(() => {
        if (fs.existsSync(cachedPath)) {
            try {
                const data = fs.readFileSync(cachedPath, 'utf8').replace(/^\uFEFF/, '');
                return res.type('application/json').send(data);
            } catch (_) {}
        }
        res.json({ type: 'start_apps', apps: [] });
    }, 600);
});

// =====================================================
// PC Hardware Telemetry Engine (Windows 11 Task Manager)
// =====================================================
let prevCpuTimes = null;
let currentCpuPercent = 0;
let processCountCache = 180;
let batteryInfoCache = { percent: 100, isCharging: true, hasBattery: true };

function updateBatteryTelemetry() {
    if (winBridge && winBridge.stdin && winBridge.stdin.writable) {
        winBridge.stdin.write('GET_POWER\n');
    }
}
setInterval(updateBatteryTelemetry, 4000);

function getCpuTimes() {
    const cpus = os.cpus();
    let idle = 0;
    let total = 0;
    for (const cpu of cpus) {
        for (const type in cpu.times) {
            total += cpu.times[type];
        }
        idle += cpu.times.idle;
    }
    return { idle, total };
}

function updateCpuUsage() {
    const current = getCpuTimes();
    if (prevCpuTimes) {
        const idleDiff = current.idle - prevCpuTimes.idle;
        const totalDiff = current.total - prevCpuTimes.total;
        if (totalDiff > 0) {
            const usage = Math.round(100 * (1 - idleDiff / totalDiff));
            currentCpuPercent = Math.max(0, Math.min(100, usage));
        }
    }
    prevCpuTimes = current;
}

prevCpuTimes = getCpuTimes();
setInterval(updateCpuUsage, 1000);

function updateProcessCount() {
    child_process.exec('tasklist /NH', (err, stdout) => {
        if (!err && stdout) {
            const lines = stdout.trim().split('\n').filter(l => l.trim().length > 0);
            processCountCache = lines.length;
        }
    });
}
updateProcessCount();
setInterval(updateProcessCount, 10000);

function getHardwareTelemetrySnapshot() {
    const cpus = os.cpus();
    const primaryCpu = cpus[0] || {};
    const totalMem = os.totalmem();
    const freeMem = os.freemem();
    const usedMem = totalMem - freeMem;
    const memPercent = Math.round((usedMem / totalMem) * 100);

    let diskInfo = {
        name: 'Local Disk (C:)',
        totalGB: '0',
        freeGB: '0',
        usedGB: '0',
        percent: 0,
        fsType: 'NTFS'
    };
    try {
        if (fs.statfsSync) {
            const s = fs.statfsSync('C:\\');
            const totalBytes = s.blocks * s.bsize;
            const freeBytes = s.bfree * s.bsize;
            const usedBytes = totalBytes - freeBytes;
            diskInfo = {
                name: 'Local Disk (C:)',
                totalGB: (totalBytes / (1024 ** 3)).toFixed(1),
                freeGB: (freeBytes / (1024 ** 3)).toFixed(1),
                usedGB: (usedBytes / (1024 ** 3)).toFixed(1),
                percent: Math.round((usedBytes / totalBytes) * 100),
                fsType: 'NTFS'
            };
        }
    } catch (_) {}

    const nets = os.networkInterfaces();
    let activeNet = { name: 'Wi-Fi', ip: '127.0.0.1', type: 'Wi-Fi (802.11ac)', status: 'Connected' };
    for (const name of Object.keys(nets)) {
        for (const net of nets[name]) {
            if (!net.internal && net.family === 'IPv4') {
                activeNet = {
                    name,
                    ip: net.address,
                    type: name.toLowerCase().includes('wi-fi') || name.toLowerCase().includes('wireless') ? 'Wi-Fi (802.11ac)' : 'Ethernet',
                    status: 'Operational'
                };
                break;
            }
        }
    }

    const uptimeSec = Math.floor(os.uptime());
    const hours = Math.floor(uptimeSec / 3600);
    const minutes = Math.floor((uptimeSec % 3600) / 60);
    const seconds = uptimeSec % 60;
    const uptimeFormatted = `${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}`;

    return {
        timestamp: Date.now(),
        cpu: {
            percent: currentCpuPercent,
            model: primaryCpu.model ? primaryCpu.model.trim() : 'Intel Processor',
            speedGHz: ((primaryCpu.speed || 2400) / 1000).toFixed(2),
            cores: cpus.length,
            logicalProcessors: cpus.length,
            processes: processCountCache,
            threads: processCountCache * 12,
            uptime: uptimeFormatted,
            uptimeSeconds: uptimeSec
        },
        memory: {
            percent: memPercent,
            totalGB: (totalMem / (1024 ** 3)).toFixed(1),
            usedGB: (usedMem / (1024 ** 3)).toFixed(1),
            freeGB: (freeMem / (1024 ** 3)).toFixed(1),
            speedMHz: '3200 MHz',
            slots: '2 of 2'
        },
        disk: diskInfo,
        wifi: activeNet,
        battery: batteryInfoCache
    };
}

// REST Hardware API
app.get('/api/pc/hardware', (req, res) => {
    res.json(getHardwareTelemetrySnapshot());
});

// =====================================================
// Process Manager Engine (Tasks, CPU, RAM & High-Res Icons)
// =====================================================
let appIconsCache = null;
function loadAppIconsCache() {
    if (appIconsCache) return appIconsCache;
    appIconsCache = {};
    const iconsDir = path.join(__dirname, '..', 'app_icons');
    const cachedPath = path.join(iconsDir, 'installed_apps.json');
    if (fs.existsSync(cachedPath)) {
        try {
            const raw = fs.readFileSync(cachedPath, 'utf8').replace(/^\uFEFF/, '');
            const parsed = JSON.parse(raw);
            if (Array.isArray(parsed.apps)) {
                for (const a of parsed.apps) {
                    if (a.path && a.icon) {
                        const base = path.basename(a.path).toLowerCase().replace('.exe', '');
                        appIconsCache[base] = a.icon;
                    }
                    if (a.name && a.icon) {
                        appIconsCache[a.name.toLowerCase()] = a.icon;
                    }
                }
            }
        } catch (_) {}
    }
    // Also load any standalone .png icon files in app_icons directory
    try {
        if (fs.existsSync(iconsDir)) {
            const files = fs.readdirSync(iconsDir);
            for (const f of files) {
                if (f.endsWith('.png')) {
                    const key = f.replace(/\.png$/i, '').toLowerCase();
                    if (!appIconsCache[key]) {
                        const b64 = fs.readFileSync(path.join(iconsDir, f)).toString('base64');
                        appIconsCache[key] = b64;
                    }
                }
            }
        }
    } catch (_) {}
    // Ensure aliases
    if (appIconsCache['antigravity'] && !appIconsCache['agy']) {
        appIconsCache['agy'] = appIconsCache['antigravity'];
    }
    if (appIconsCache['vscode'] && !appIconsCache['code']) {
        appIconsCache['code'] = appIconsCache['vscode'];
    }
    return appIconsCache;
}

function formatProcessTitle(processName, windowTitle) {
    const base = processName.toLowerCase().replace(/\.exe$/i, '');
    const friendlyMap = {
        'code': 'Visual Studio Code',
        'antigravity': 'Google Antigravity',
        'agy': 'Antigravity CLI',
        'brave': 'Brave Browser',
        'chrome': 'Google Chrome',
        'firefox': 'Mozilla Firefox',
        'msedge': 'Microsoft Edge',
        'explorer': 'File Explorer',
        'spotify': 'Spotify Music',
        'steam': 'Steam Client',
        'steamwebhelper': 'Steam',
        'discord': 'Discord',
        'node': 'Node.js Controller',
        'msmpeng': 'Windows Security',
        'wt': 'Windows Terminal',
        'cmd': 'Command Prompt',
        'powershell': 'Windows PowerShell',
        'pwsh': 'PowerShell 7',
        'taskmgr': 'Task Manager',
        'studio64': 'Android Studio',
        'notepad++': 'Notepad++',
        'notepad': 'Notepad',
        'vlc': 'VLC Media Player'
    };

    if (friendlyMap[base]) {
        return friendlyMap[base];
    }

    if (windowTitle && windowTitle.trim().length > 0 && !windowTitle.includes('---')) {
        const parts = windowTitle.split(' - ');
        if (parts.length > 1) {
            return parts[parts.length - 1].trim();
        }
        return windowTitle.trim().substring(0, 26);
    }

    return processName;
}

const USER_APP_SET = new Set([
    'brave', 'chrome', 'msedge', 'firefox', 'opera',
    'code', 'antigravity', 'agy', 'studio64', 'devenv', 'notepad++', 'sublime_text',
    'explorer', 'cmd', 'wt', 'powershell', 'pwsh', 'windowsterminal',
    'spotify', 'steam', 'steamwebhelper', 'discord', 'telegram', 'slack',
    'notepad', 'taskmgr', 'vlc', 'neatdm', 'winrar', '7zfm'
]);

let prevProcCpuCache = new Map();

function calcInstantCpu(pid, cpuSec) {
    if (typeof cpuSec !== 'number') return 0.0;
    const now = Date.now();
    let percent = 0.0;
    if (prevProcCpuCache.has(pid)) {
        const prev = prevProcCpuCache.get(pid);
        const deltaCpu = cpuSec - prev.cpuSec;
        const deltaSec = (now - prev.time) / 1000;
        if (deltaSec > 0.2 && deltaCpu >= 0) {
            const numCores = os.cpus().length || 1;
            percent = Math.min(99.9, Math.max(0, Math.round(((deltaCpu / deltaSec) / numCores) * 100 * 10) / 10));
        }
    }
    prevProcCpuCache.set(pid, { cpuSec, time: now });
    return percent;
}

function aggregateProcessList(rawProcesses, icons) {
    const groups = new Map();

    for (const p of rawProcesses) {
        const procName = p.ProcessName || p.name || '';
        const base = procName.toLowerCase().replace(/\.exe$/i, '');
        const hasWindow = p.MainWindowTitle && p.MainWindowTitle.trim().length > 0;
        const memMb = Math.round(((p.WorkingSet64 || p.memBytes || (p.memMb * 1048576) || 0) / 1048576) * 10) / 10;
        const rawCpuSec = typeof p.CPU === 'number' ? p.CPU : null;
        const cpuPercent = calcInstantCpu(p.Id || p.pid, rawCpuSec);

        if (!groups.has(base)) {
            const isUserApp = hasWindow || USER_APP_SET.has(base);
            const icon = icons[base] || (base === 'code' ? icons['vscode'] : '') || (base === 'agy' ? icons['antigravity'] : '') || '';
            groups.set(base, {
                base,
                exe: procName + '.exe',
                primaryPid: p.Id || p.pid,
                title: formatProcessTitle(procName, p.MainWindowTitle),
                hasWindow,
                count: 1,
                totalMem: memMb,
                totalCpu: cpuPercent,
                isUserApp,
                icon,
                highestMem: memMb
            });
        } else {
            const grp = groups.get(base);
            grp.count += 1;
            grp.totalMem = Math.round((grp.totalMem + memMb) * 10) / 10;
            grp.totalCpu = Math.round(Math.min(99.9, grp.totalCpu + cpuPercent) * 10) / 10;
            if (hasWindow) {
                grp.hasWindow = true;
                grp.isUserApp = true;
                grp.title = formatProcessTitle(procName, p.MainWindowTitle);
                grp.primaryPid = p.Id || p.pid;
            } else if (memMb > grp.highestMem && !grp.hasWindow) {
                grp.primaryPid = p.Id || p.pid;
                grp.highestMem = memMb;
            }
        }
    }

    const aggregated = [];
    for (const [base, grp] of groups.entries()) {
        const displayName = grp.count > 1 ? `${grp.title} (${grp.count})` : grp.title;
        aggregated.push({
            pid: grp.primaryPid,
            name: displayName,
            exe: grp.exe,
            cpu: grp.totalCpu,
            mem: grp.totalMem,
            type: grp.isUserApp ? 'app' : 'system',
            icon: grp.icon,
            count: grp.count
        });
    }

    // Sort: All 'app' on top, sorted by RAM descending; then 'system' sorted by RAM descending
    aggregated.sort((a, b) => {
        if (a.type === 'app' && b.type !== 'app') return -1;
        if (a.type !== 'app' && b.type === 'app') return 1;
        return b.mem - a.mem;
    });

    return aggregated.slice(0, 100);
}

function getRunningProcessesSnapshot(callback) {
    const icons = loadAppIconsCache();
    const psCmd = `Get-Process | Where-Object { $_.Id -gt 4 } | Select-Object Id, ProcessName, MainWindowTitle, WorkingSet64, CPU | ConvertTo-Json -Compress`;
    
    child_process.exec(`powershell -NoProfile -Command "${psCmd}"`, { maxBuffer: 8 * 1024 * 1024 }, (err, stdout) => {
        if (!err && stdout) {
            try {
                const raw = JSON.parse(stdout);
                const arr = Array.isArray(raw) ? raw : [raw];
                const list = aggregateProcessList(arr, icons);
                return callback(list);
            } catch (_) {}
        }

        // Fallback: tasklist with aggregation
        child_process.exec('tasklist /FO CSV /NH', (tErr, tStdout) => {
            if (tErr || !tStdout) return callback([]);
            const lines = tStdout.trim().split('\n');
            const rawList = [];
            for (const line of lines) {
                const parts = line.split('","').map(s => s.replace(/"/g, '').trim());
                if (parts.length >= 5) {
                    const exe = parts[0];
                    const pid = parseInt(parts[1], 10);
                    const memStr = parts[4].replace(/[^0-9]/g, '');
                    const memBytes = (parseInt(memStr, 10) || 0) * 1024;
                    const procName = exe.replace(/\.exe$/i, '');
                    if (pid > 4) {
                        rawList.push({
                            Id: pid,
                            ProcessName: procName,
                            MainWindowTitle: '',
                            WorkingSet64: memBytes,
                            CPU: null
                        });
                    }
                }
            }
            const list = aggregateProcessList(rawList, icons);
            callback(list);
        });
    });
}

const PROTECTED_PROCESSES = ['system', 'csrss.exe', 'winlogon.exe', 'services.exe', 'lsass.exe', 'node.exe', 'anolinkwinbridge.exe'];

function killProcessSafely(pid, exe, callback) {
    if (exe && PROTECTED_PROCESSES.includes(exe.toLowerCase())) {
        return callback({ success: false, pid, error: 'Cannot terminate protected system process.' });
    }
    const killCmd = (exe && !PROTECTED_PROCESSES.includes(exe.toLowerCase()))
        ? `taskkill /F /T /PID ${pid}`
        : `taskkill /F /PID ${pid}`;
    child_process.exec(killCmd, (err, stdout, stderr) => {
        if (err && exe) {
            child_process.exec(`taskkill /F /IM "${exe}"`, (imErr, imStdout, imStderr) => {
                if (imErr) {
                    return callback({ success: false, pid, error: imStderr || imErr.message });
                }
                callback({ success: true, pid, message: `Terminated ${exe}` });
            });
            return;
        }
        if (err) {
            return callback({ success: false, pid, error: stderr || err.message });
        }
        callback({ success: true, pid, message: `Terminated PID ${pid}` });
    });
}

// REST Process APIs
app.get('/api/pc/processes', (req, res) => {
    getRunningProcessesSnapshot((procs) => {
        res.json({ type: 'pc_processes_list', processes: procs });
    });
});

app.post('/api/pc/kill', (req, res) => {
    const pid = parseInt(req.body.pid, 10);
    killProcessSafely(pid, req.body.exe, (result) => {
        res.json(result);
    });
});

// Periodic 1-second pulse to connected phone remotes
setInterval(() => {
    if (activeControlDc && activeControlDc.isOpen && activeControlDc.isOpen()) {
        const stats = getHardwareTelemetrySnapshot();
        broadcastToPhoneRemote({
            type: 'pc_hardware_stats',
            data: stats
        });
    }
}, 1000);

process.on('uncaughtException', (err) => {
    console.error('[PC] Uncaught Exception:', err);
});

process.on('unhandledRejection', (reason, promise) => {
    console.error('[PC] Unhandled Rejection:', reason);
});

function getNetworkIps() {
    const interfaces = os.networkInterfaces();
    const result = [];
    for (const name of Object.keys(interfaces)) {
        const lower = name.toLowerCase();
        if (lower.includes('vethernet') || lower.includes('tailscale') || lower.includes('wsl')) continue;
        for (const iface of interfaces[name]) {
            if (iface.family === 'IPv4' && !iface.internal && !iface.address.startsWith('100.')) {
                result.push({ name, ip: iface.address });
            }
        }
    }
    return result;
}

server.listen(PORT, '0.0.0.0', () => {
    console.log(`=======================================================`);
    console.log(`🚀 Ano-Link Dual-Engine Controller Active!`);
    console.log(`🔑 1-Time Pairing PIN:  ${phoneConfig.pairingPin}`);
    console.log(`🔒 Device Token:        ${phoneConfig.deviceToken.substring(0, 16)}... (Auto-Reconnect Enabled)`);
    console.log(`📺 Desktop Framerate:   30 FPS (Hardware Optimized)`);
    console.log(`🌐 WAN Connectivity:    Cloudflare Quick Tunnel (No CGNAT Drops)`);
    console.log(`👉 Web Dashboard:       http://localhost:${PORT}`);
    console.log(`📱 Connect on Phone:    Enter PIN "${phoneConfig.pairingPin}" in the app (No IP needed!)`);
    console.log(`=======================================================`);

    // Initialize native Win32 controller bridge
    initWinBridge();

    // Initialize WebRTC STUN Signaling Standby
    initSignaling();

    // Initialize Cloudflare Quick Tunnel (WAN Relay)
    initCloudflareTunnel();
});

function initCloudflareTunnel() {
    if (!fs.existsSync(cloudflaredExePath)) {
        console.warn('[Cloudflare Tunnel] ⚠️ cloudflared.exe not found at:', cloudflaredExePath);
        return;
    }

    console.log(`[Cloudflare Tunnel] 🚀 Launching Cloudflare Quick Tunnel for port ${PORT}...`);
    try {
        cloudflaredProc = child_process.spawn(cloudflaredExePath, ['tunnel', '--url', `http://localhost:${PORT}`], {
            stdio: ['ignore', 'pipe', 'pipe']
        });

        const parseTunnelOutput = (data) => {
            const str = data.toString();
            const match = str.match(/https:\/\/[a-zA-Z0-9-]+\.trycloudflare\.com/i);
            if (match && !activeTunnelUrl) {
                activeTunnelUrl = match[0];
                console.log(`=======================================================`);
                console.log(`🌐 CLOUDFLARE WAN TUNNEL READY!`);
                console.log(`🔗 Public URL: ${activeTunnelUrl}`);
                console.log(`📱 Phone can connect from any network using PIN: ${phoneConfig.pairingPin}`);
                console.log(`=======================================================`);

                // Broadcast tunnel URL to Cloudflare Worker broker under pairing PIN
                postSignalMessage('phone', {
                    type: 'tunnel_ready',
                    url: activeTunnelUrl,
                    pairingPin: phoneConfig.pairingPin,
                    deviceToken: phoneConfig.deviceToken
                });
            }
        };

        cloudflaredProc.stdout.on('data', parseTunnelOutput);
        cloudflaredProc.stderr.on('data', parseTunnelOutput);

        cloudflaredProc.on('exit', (code) => {
            console.log(`[Cloudflare Tunnel] Process exited (code ${code}).`);
            cloudflaredProc = null;
            activeTunnelUrl = null;
        });
    } catch (err) {
        console.error('[Cloudflare Tunnel] Failed to start cloudflared:', err.message);
    }
}

function gracefulShutdown() {
    console.log('[PC] Gracefully shutting down Ano-Link Server...');
    try {
        broadcastToPhoneRemote({ type: 'session_close', reason: 'server_shutdown' });
    } catch (_) {}
    if (activePhoneWs) {
        try { activePhoneWs.close(); } catch (_) {}
        activePhoneWs = null;
    }
    if (activeControlDc) {
        try { activeControlDc.close(); } catch (_) {}
        activeControlDc = null;
    }
    if (activeVideoDc) {
        try { activeVideoDc.close(); } catch (_) {}
        activeVideoDc = null;
    }
    if (activeWebRtcPeer) {
        try { activeWebRtcPeer.close(); } catch (_) {}
        activeWebRtcPeer = null;
    }
    if (cloudflaredProc) {
        try { cloudflaredProc.kill(); } catch (_) {}
        cloudflaredProc = null;
    }
    if (winBridge) {
        try { winBridge.kill(); } catch (_) {}
        winBridge = null;
    }
    process.exit(0);
}

process.on('SIGINT', gracefulShutdown);
process.on('SIGTERM', gracefulShutdown);

process.on('exit', () => {
    if (cloudflaredProc) {
        try { cloudflaredProc.kill(); } catch (_) {}
    }
    if (winBridge) {
        try { winBridge.kill(); } catch (_) {}
    }
});
