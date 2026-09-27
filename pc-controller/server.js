const express = require('express');
const http = require('http');
const WebSocket = require('ws');
const path = require('path');
const cors = require('cors');
const fs = require('fs');
const child_process = require('child_process');
const readline = require('readline');
const os = require('os');

const app = express();
app.use(cors());
app.use(express.json());
app.use(express.static(path.join(__dirname, 'public')));

const PORT = 8080;
const server = http.createServer(app);

// Native Win32 Input & Taskbar Bridge
const bridgeExePath = path.join(__dirname, 'tools', 'AnoLinkWinBridge.exe');
let winBridge = null;
let screenStreamTimer = null;
let taskbarSyncTimer = null;

// WebSocket server for the browser frontend & direct phone remote
const wssBrowser = new WebSocket.Server({ noServer: true });
const wssPhoneRemote = new WebSocket.Server({ noServer: true });

const configFilePath = path.join(__dirname, 'config.json');

// State
let phoneConfig = {
    ip: '',
    port: 8081,
    token: 'ag-secure-token-777'
};

function loadSavedConfig() {
    try {
        if (fs.existsSync(configFilePath)) {
            const raw = fs.readFileSync(configFilePath, 'utf8');
            const parsed = JSON.parse(raw);
            if (parsed.phoneIp) phoneConfig.ip = parsed.phoneIp;
            if (parsed.phonePort) phoneConfig.port = parsed.phonePort;
            if (parsed.token) phoneConfig.token = parsed.token;
        }
    } catch (_) {}
}

function saveConfig() {
    try {
        fs.writeFileSync(configFilePath, JSON.stringify({
            phoneIp: phoneConfig.ip,
            phonePort: phoneConfig.port,
            token: phoneConfig.token
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

// Upgrade handling for WebSockets
server.on('upgrade', (request, socket, head) => {
    const pathname = new URL(request.url, `http://${request.headers.host}`).pathname;
    if (pathname === '/ws-browser') {
        wssBrowser.handleUpgrade(request, socket, head, (ws) => {
            wssBrowser.emit('connection', ws, request);
        });
    } else if (pathname === '/ws-remote' || pathname === '/ws-phone' || pathname === '/ws') {
        wssPhoneRemote.handleUpgrade(request, socket, head, (ws) => {
            wssPhoneRemote.emit('connection', ws, request);
        });
    } else {
        socket.destroy();
    }
});

// Broadcast to all connected PC browsers
function broadcastToBrowsers(message) {
    const payload = typeof message === 'string' ? message : JSON.stringify(message);
    wssBrowser.clients.forEach((client) => {
        if (client.readyState === WebSocket.OPEN) {
            // Drop stale mic audio if the client's socket queue is congested (> 8KB)
            if (message && message.type === 'mic_audio' && client.bufferedAmount > 8192) {
                return;
            }
            client.send(payload);
        }
    });
}

function broadcastToPhoneRemote(message) {
    const payload = typeof message === 'string' ? message : JSON.stringify(message);
    wssPhoneRemote.clients.forEach((client) => {
        if (client.readyState === WebSocket.OPEN) {
            client.send(payload);
        }
    });
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

wssPhoneRemote.on('connection', (ws) => {
    console.log('[PC] Phone Remote connected directly to PC WebSocket (/ws-remote)!');
    if (winBridge && winBridge.stdin && winBridge.stdin.writable) {
        winBridge.stdin.write('GET_WINDOWS\n');
        winBridge.stdin.write('GET_POWER\n');
    }
    try {
        ws.send(JSON.stringify({
            type: 'pc_battery_status',
            percent: batteryInfoCache.percent,
            isCharging: batteryInfoCache.isCharging,
            hasBattery: batteryInfoCache.hasBattery
        }));
    } catch (_) {}

    ws.on('message', (data, isBinary) => {
        if (isBinary) return;
        try {
            let msg;
            const str = data.toString().trim();
            if (str.startsWith('{')) {
                msg = JSON.parse(str);
            } else {
                msg = { action: str };
            }
            handlePhoneMessage(msg);
        } catch (err) {
            console.error('[PC] Error parsing message from phone remote:', err);
        }
    });

    ws.on('close', () => {
        console.log('[PC] Phone Remote disconnected from /ws-remote');
        if (wssPhoneRemote.clients.size === 0 && (!phoneSocket || phoneSocket.readyState !== WebSocket.OPEN)) {
            stopPcScreenStreaming(true);
        }
    });
});

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
        wssPhoneRemote.clients.forEach((client) => {
            if (client.readyState === WebSocket.OPEN && client.bufferedAmount === 0) {
                client.send(frameBuf, { binary: true });
            }
        });
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
    const f = fps || 15;
    const width = w || 960;
    const height = h || 540;
    console.log(`[PC] Starting PC desktop screen streaming (${width}x${height} @ ${f}fps)...`);
    isPcStreamingActive = true;
    if (winBridge && winBridge.stdin && winBridge.stdin.writable) {
        winBridge.stdin.write(`STREAM_START ${f} ${width} ${height}\n`);
    }
}

function stopPcScreenStreaming(force = false) {
    if (!force && wssPhoneRemote && wssPhoneRemote.clients && wssPhoneRemote.clients.size > 0) {
        // Do not stop stream if phone remote is currently connected unless force=true
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
    if (!wssPhoneRemote || !wssPhoneRemote.clients || wssPhoneRemote.clients.size === 0) {
        stopPcScreenStreaming();
    }

    phoneSocket = null;
    connectionState = 'DISCONNECTED';
    broadcastToBrowsers({ type: 'status_change', state: connectionState });

    // Automatically retry connecting if not explicitly disconnected by user
    if (!isExplicitlyDisconnected && !autoReconnectTimer) {
        autoReconnectTimer = setTimeout(() => {
            autoReconnectTimer = null;
            if (connectionState === 'DISCONNECTED' && !isExplicitlyDisconnected) {
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
    if (!isExplicitlyDisconnected && connectionState === 'DISCONNECTED') {
        connectToPhone();
    }
    return false;
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
    
    // Send initial state snapshot
    ws.send(JSON.stringify({
        type: 'initial_state',
        state: connectionState,
        phoneConfig,
        telemetry: telemetryCache,
        lastTelemetryTime,
        mode: currentMode,
        quality: streamQuality,
        skeletonTree: currentSkeletonTree,
        smsHistory: smsHistoryCache
    }));

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
            if (msg.phoneConfig) {
                phoneConfig = { ...phoneConfig, ...msg.phoneConfig };
            }
            connectToPhone();
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

// Periodic 1-second pulse to connected phone remotes
setInterval(() => {
    if (wssPhoneRemote.clients && wssPhoneRemote.clients.size > 0) {
        const stats = getHardwareTelemetrySnapshot();
        const payload = JSON.stringify({
            type: 'pc_hardware_stats',
            data: stats
        });
        wssPhoneRemote.clients.forEach((client) => {
            if (client.readyState === WebSocket.OPEN) {
                client.send(payload);
            }
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
        for (const iface of interfaces[name]) {
            if (iface.family === 'IPv4' && !iface.internal) {
                result.push({ name, ip: iface.address });
            }
        }
    }
    return result;
}

server.listen(PORT, '0.0.0.0', () => {
    console.log(`=======================================================`);
    console.log(`🚀 Ano-Link PC Controller running!`);
    console.log(`👉 Web Dashboard: http://localhost:${PORT}`);
    console.log(`📡 PC Addresses to enter on your Phone:`);
    const netIps = getNetworkIps();
    if (netIps.length > 0) {
        netIps.forEach(net => {
            const isTailscale = net.ip.startsWith('100.');
            const label = isTailscale ? 'Tailscale Mesh' : `Local Wi-Fi (${net.name})`;
            console.log(`   • ${label}: ${net.ip}:${PORT}`);
        });
    } else {
        console.log(`   • Localhost: http://localhost:${PORT}`);
    }
    console.log(`=======================================================`);

    // Initialize native Win32 controller bridge
    initWinBridge();

    if (phoneConfig.ip) {
        console.log(`📱 Configured Phone Target: ${phoneConfig.ip}:${phoneConfig.port}`);
        connectToPhone();
    } else {
        console.log(`📱 Phone IP is not configured.`);
        console.log(`   Configure via Web Dashboard at http://localhost:${PORT}, or enter below:\n`);
        const rl = readline.createInterface({
            input: process.stdin,
            output: process.stdout
        });
        rl.question('👉 Enter Phone IP (e.g. 192.168.1.25 or 100.x.y.z) [or Enter to skip]: ', (answer) => {
            const enteredIp = answer.trim();
            if (enteredIp) {
                phoneConfig.ip = enteredIp;
                saveConfig();
                console.log(`[PC] Saved Phone target: ${phoneConfig.ip}:${phoneConfig.port}`);
                connectToPhone();
            } else {
                console.log(`[PC] Waiting for phone configuration via Web Dashboard at http://localhost:${PORT}...`);
            }
            rl.close();
        });
    }
});

process.on('exit', () => {
    if (winBridge) {
        try { winBridge.kill(); } catch (_) {}
    }
});
