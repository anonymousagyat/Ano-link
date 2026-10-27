// Ano-Link PC Controller Client - Command & Control Hub
let ws = null;
let connectionState = 'DISCONNECTED';

// DOM Elements
const targetDeviceText = document.getElementById('targetDeviceText');
const openSettingsBtn = document.getElementById('openSettingsBtn');
const settingsModal = document.getElementById('settingsModal');
const closeSettingsBtn = document.getElementById('closeSettingsBtn');
const settingsIpInput = document.getElementById('settingsIpInput');
const settingsPortInput = document.getElementById('settingsPortInput');
const saveSettingsBtn = document.getElementById('saveSettingsBtn');
const disconnectModalBtn = document.getElementById('disconnectModalBtn');
const modalStatusText = document.getElementById('modalStatusText');
const statusDot = document.getElementById('statusDot');
const statusText = document.getElementById('statusText');
const btnConnectPhone = document.getElementById('btnConnectPhone');
const btnConnectPhoneText = document.getElementById('btnConnectPhoneText');
const refreshTelemetryBtn = document.getElementById('refreshTelemetryBtn');

// Telemetry DOM
const batteryVal = document.getElementById('batteryVal');
const chargingStatus = document.getElementById('chargingStatus');
const tempVal = document.getElementById('tempVal');
const storageVal = document.getElementById('storageVal');
const ramVal = document.getElementById('ramVal');
const deviceModel = document.getElementById('deviceModel');
const lastUpdatedText = document.getElementById('lastUpdatedText');
const logsConsole = document.getElementById('logsConsole');

// Stream Launch Buttons
const btnLaunchSkeleton = document.getElementById('btnLaunchSkeleton');
const btnLaunchSilent = document.getElementById('btnLaunchSilent');
const btnLaunchVideo = document.getElementById('btnLaunchVideo');

// -------------------------------------------------------------
// File Explorer & Windows Explorer Sorting State
// -------------------------------------------------------------
let currentFilesList = [];
let currentFolderPath = '/storage/emulated/0';
let fileSortColumn = 'name'; // 'name', 'modified', 'type', 'size'
let fileSortDirection = 'asc'; // 'asc', 'desc'
let fileFilterQuery = '';
let isAllFilesAccessGranted = true;

// Initialize WebSocket to PC Gateway server
function initWebSocket() {
    const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${wsProtocol}//${window.location.host}/ws-browser`;

    log(`Connecting to local gateway at ${wsUrl}...`, 'info');
    ws = new WebSocket(wsUrl);
    ws.binaryType = 'arraybuffer';

    ws.onopen = () => {
        log('Connected to PC gateway server', 'success');
    };

    ws.onmessage = (event) => {
        if (event.data instanceof ArrayBuffer) {
            return; // Binary video frames are consumed by dedicated viewer tabs
        }
        try {
            const msg = JSON.parse(event.data);
            handleServerMessage(msg);
        } catch (e) {
            console.error('Error handling message:', e);
        }
    };

    ws.onclose = () => {
        log('Disconnected from local gateway. Reconnecting in 2s...', 'warn');
        setTimeout(initWebSocket, 2000);
    };

    ws.onerror = (err) => {
        console.error('WebSocket error:', err);
    };
}

function sendToGateway(payload) {
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify(payload));
    }
}

// Handle messages from PC Gateway
function handleServerMessage(msg) {
    switch (msg.type) {
        case 'initial_state':
            updateConnectionUI(msg.state, msg.rtt);
            if (msg.phoneConfig) {
                updateTargetDeviceDisplay(msg.phoneConfig);
            }
            if (msg.telemetry) updateTelemetryUI(msg.telemetry, msg.lastTelemetryTime);
            if (msg.smsHistory && msg.smsHistory.length > 0) {
                renderSmsHistory(msg.smsHistory);
            }
            break;

        case 'status_change':
            updateConnectionUI(msg.state, msg.rtt);
            if (msg.phoneConfig) {
                updateTargetDeviceDisplay(msg.phoneConfig);
            }
            break;

        case 'probe_status':
            handleProbeStatus(msg);
            break;

        case 'telemetry_update':
            updateTelemetryUI(msg.telemetry, msg.timestamp);
            log('Telemetry updated (5-min schedule / on-demand)', 'info');
            break;

        case 'sms_alert':
            showToast(`SMS from ${msg.sms.sender}`, msg.sms.body);
            log(`SMS received from ${msg.sms.sender}`, 'info');
            addLiveSmsEntry(msg.sms);
            break;

        case 'sms_history':
            renderSmsHistory(msg.messages);
            log(`Loaded ${msg.messages ? msg.messages.length : 0} past SMS messages`, 'success');
            break;

        case 'file_list':
            renderFileList(msg.path, msg.files, msg.allFilesAccess);
            log(`Loaded folder: ${msg.path} (${msg.files ? msg.files.length : 0} items)`, 'info');
            break;

        case 'mic_audio':
            handleIncomingMicAudio(msg.data);
            break;

        case 'mic_status':
            updateMicStatusUI(msg.streaming);
            break;

        case 'mic_error':
            showToast('Microphone Error', msg.message || 'Microphone could not be accessed');
            log(`Microphone Error: ${msg.message}`, 'error');
            break;

        case 'stream_error':
            showToast('Camera Error', msg.message || 'Camera stream could not be started');
            log(`Camera Stream Error: ${msg.message}`, 'error');
            break;

        case 'location_update':
            handleLocationUpdate(msg);
            break;

        case 'ring_status':
            handleRingStatus(msg.isRinging);
            break;

        default:
            break;
    }
}

let currentRtt = -1;

function handleProbeStatus(msg) {
    if (msg.state === 'PROBING') {
        if (btnConnectPhone && btnConnectPhoneText) {
            btnConnectPhone.className = 'btn btn-connect-action connecting';
            btnConnectPhone.disabled = true;
            btnConnectPhoneText.textContent = `Probing (${msg.attempt}/${msg.maxAttempts})...`;
        }
        if (statusText) statusText.textContent = 'PROBING';
        if (statusDot) statusDot.className = 'dot connecting';
    } else if (msg.state === 'STOPPED') {
        if (!msg.connected) {
            updateConnectionUI('DISCONNECTED');
            log('Phone did not respond after 5 probe attempts. Probing stopped.', 'warn');
            showToast('Probe Ended', 'Phone is not active. Click Connect Phone to retry.');
        }
    }
}

// UI State Updates
function updateConnectionUI(state, rtt) {
    connectionState = state;
    if (rtt && typeof rtt === 'number' && rtt > 0) {
        currentRtt = rtt;
    }
    if (statusText) statusText.textContent = state;
    if (statusDot) statusDot.className = `dot ${state.toLowerCase()}`;
    if (modalStatusText) modalStatusText.textContent = state;

    const latencyBadge = document.getElementById('latencyBadge');
    const latencyText = document.getElementById('latencyText');

    if (disconnectModalBtn) {
        disconnectModalBtn.style.display = (state === 'CONNECTED') ? 'inline-block' : 'none';
    }

    if (btnConnectPhone && btnConnectPhoneText) {
        if (state === 'CONNECTED') {
            btnConnectPhone.className = 'btn btn-connect-action connected';
            btnConnectPhone.disabled = false;
            btnConnectPhoneText.textContent = currentRtt > 0 ? `✓ Connected (${currentRtt}ms)` : '✓ Connected';
            btnConnectPhone.title = 'Phone is connected. Click to refresh telemetry & ping.';
            if (latencyBadge && latencyText && currentRtt > 0) {
                latencyBadge.style.display = 'flex';
                latencyText.textContent = `${currentRtt} ms`;
            }
        } else if (state === 'CONNECTING') {
            btnConnectPhone.className = 'btn btn-connect-action connecting';
            btnConnectPhone.disabled = true;
            btnConnectPhoneText.textContent = 'Probing Phone...';
            btnConnectPhone.title = 'Probing phone on demand...';
            if (latencyBadge) latencyBadge.style.display = 'none';
        } else {
            btnConnectPhone.className = 'btn btn-connect-action';
            btnConnectPhone.disabled = false;
            btnConnectPhoneText.textContent = 'Connect Phone';
            btnConnectPhone.title = 'Ping & connect paired phone on demand';
            if (latencyBadge) latencyBadge.style.display = 'none';
        }
    }

    if (state === 'CONNECTED') {
        log('Phone connected over cross-device bridge!', 'success');
        // Automatically fetch initial files and SMS history
        sendToGateway({ action: 'get_sms' });
        sendToGateway({ action: 'list_dir', path: currentFolderPath });
    }
}

function updateTargetDeviceDisplay(config) {
    if (!config) return;
    const name = config.deviceName || 'Android Phone';
    if (config.isTunnel || config.ip === 'Cloudflare Tunnel (WAN)') {
        if (targetDeviceText) targetDeviceText.textContent = `${name} (Cloudflare WAN)`;
        if (settingsIpInput) settingsIpInput.value = 'Cloudflare Quick Tunnel';
        if (settingsPortInput) settingsPortInput.value = 'Port 9090';
        return;
    }
    if (config.isWebRtc || config.ip === 'Cloudflare Tunnel' || config.ip === 'WebRTC P2P Direct') {
        if (targetDeviceText) targetDeviceText.textContent = `${name} (LAN Fast-Path)`;
        if (settingsIpInput) settingsIpInput.value = 'Cloudflare Quick Tunnel / LAN';
        if (settingsPortInput) settingsPortInput.value = 'WebSocket Encrypted';
        return;
    }
    if (config.ip) {
        if (targetDeviceText) targetDeviceText.textContent = `${name} (${config.ip})`;
        if (settingsIpInput) settingsIpInput.value = config.ip;
        if (settingsPortInput) settingsPortInput.value = config.port || 8081;
    } else {
        if (targetDeviceText) targetDeviceText.textContent = config.deviceName ? config.deviceName : 'Auto-Detecting...';
    }
}

function updateTelemetryUI(data, timestamp) {
    if (!data) return;

    if (data.battery !== undefined && batteryVal) {
        batteryVal.textContent = `${data.battery}%`;
        if (chargingStatus) {
            chargingStatus.innerHTML = data.charging 
                ? '<i data-lucide="zap" class="ui-icon-xs" style="color: var(--accent-amber);"></i> Charging' 
                : 'On Battery';
            if (window.lucide && typeof window.lucide.createIcons === 'function') {
                window.lucide.createIcons();
            }
        }
    }
    if (data.temperature !== undefined && tempVal) {
        tempVal.textContent = `${(data.temperature / 10).toFixed(1)} °C`;
    }
    if (data.freeStorageGb !== undefined && storageVal) {
        storageVal.textContent = `${data.freeStorageGb.toFixed(1)} GB`;
    }
    if (data.freeRamGb !== undefined && ramVal) {
        ramVal.textContent = `${data.freeRamGb.toFixed(1)} GB`;
    }
    if (data.model && deviceModel) {
        deviceModel.textContent = data.model;
    }

    if (timestamp && lastUpdatedText) {
        const timeStr = new Date(timestamp).toLocaleTimeString();
        lastUpdatedText.textContent = `Last updated: ${timeStr}`;
    }

    // Detailed specs tab
    const specBatteryVoltage = document.getElementById('specBatteryVoltage');
    if (specBatteryVoltage && data.voltage) {
        specBatteryVoltage.textContent = `${data.voltage} mV`;
    }
    const specTotalStorage = document.getElementById('specTotalStorage');
    if (specTotalStorage && data.totalStorageGb) {
        specTotalStorage.textContent = `${data.totalStorageGb.toFixed(1)} GB`;
    }
    const specTotalRam = document.getElementById('specTotalRam');
    if (specTotalRam && data.totalRamGb) {
        specTotalRam.textContent = `${data.totalRamGb.toFixed(1)} GB`;
    }
    const specAndroidVer = document.getElementById('specAndroidVer');
    if (specAndroidVer && data.androidVersion) {
        specAndroidVer.textContent = `Android ${data.androidVersion}`;
    }
}

// Cloudflare Tunnel Pairing Info Loader
async function refreshWebRtcInfo() {
    try {
        const res = await fetch('/api/cloudflare-info');
        const data = await res.json();
        const pin = data.pairingPin || '------';
        const pinEl = document.getElementById('webrtcPairingPin');
        if (pinEl) pinEl.textContent = pin;
        const barPin = document.getElementById('barPinText');
        if (barPin) barPin.textContent = pin;
    } catch (_) {}

    try {
        const wanRes = await fetch('/api/pc/wan_info');
        const wanData = await wanRes.json();
        const wanUrlInput = document.getElementById('wanTunnelUrlInput');
        if (wanUrlInput) {
            wanUrlInput.value = wanData.tunnelUrl || 'Initializing Cloudflare Tunnel...';
        }
        const badge = document.getElementById('wanTunnelStatusBadge');
        if (badge) {
            badge.textContent = wanData.tunnelUrl ? 'Ready for WAN' : 'Starting...';
            badge.style.color = wanData.tunnelUrl ? '#10B981' : '#F59E0B';
        }
    } catch (_) {}
}

const pinBadge = document.getElementById('pinBadge');
if (pinBadge) {
    pinBadge.addEventListener('click', openSettingsModal);
}

const copyPinBtn = document.getElementById('copyPinBtn');
if (copyPinBtn) {
    copyPinBtn.addEventListener('click', () => {
        const pinEl = document.getElementById('webrtcPairingPin');
        if (pinEl && pinEl.textContent) {
            navigator.clipboard.writeText(pinEl.textContent.trim());
            copyPinBtn.textContent = 'Copied!';
            setTimeout(() => { copyPinBtn.textContent = 'Copy PIN'; }, 2000);
            showToast('PIN Copied', `Pairing PIN ${pinEl.textContent} copied to clipboard`);
        }
    });
}

const copyWanTunnelBtn = document.getElementById('copyWanTunnelBtn');
if (copyWanTunnelBtn) {
    copyWanTunnelBtn.addEventListener('click', () => {
        const input = document.getElementById('wanTunnelUrlInput');
        if (input && input.value && input.value.startsWith('http')) {
            navigator.clipboard.writeText(input.value.trim());
            copyWanTunnelBtn.textContent = 'Copied!';
            setTimeout(() => { copyWanTunnelBtn.textContent = 'Copy URL'; }, 2000);
            showToast('WAN URL Copied', 'Cloudflare Quick Tunnel URL copied to clipboard');
        }
    });
}

const resetPinBtn = document.getElementById('resetPinBtn');
if (resetPinBtn) {
    resetPinBtn.addEventListener('click', async () => {
        if (!confirm('Reset pairing PIN and security token? (Existing paired devices will need to enter the new PIN once)')) return;
        try {
            const res = await fetch('/api/tunnel-reset-token', { method: 'POST' });
            const data = await res.json();
            if (data.success) {
                refreshWebRtcInfo();
                showToast('Keys Reset', `New Pairing PIN generated: ${data.pairingPin}`);
            }
        } catch (_) {}
    });
}

// Settings Modal Handlers
function openSettingsModal() {
    refreshWebRtcInfo();
    if (settingsModal) settingsModal.style.display = 'flex';
}
function closeSettingsModal() {
    if (settingsModal) settingsModal.style.display = 'none';
}

if (openSettingsBtn) openSettingsBtn.addEventListener('click', openSettingsModal);
if (closeSettingsBtn) closeSettingsBtn.addEventListener('click', closeSettingsModal);
if (settingsModal) {
    settingsModal.addEventListener('click', (e) => {
        if (e.target === settingsModal) closeSettingsModal();
    });
}

if (saveSettingsBtn) {
    saveSettingsBtn.addEventListener('click', () => {
        const ip = settingsIpInput ? settingsIpInput.value.trim() : '';
        const port = settingsPortInput ? parseInt(settingsPortInput.value.trim(), 10) || 8081 : 8081;
        if (!ip) {
            showToast('Settings', 'Please enter a valid Phone IP address');
            if (settingsIpInput) settingsIpInput.focus();
            return;
        }
        sendToGateway({
            action: 'connect',
            phoneConfig: { ip, port }
        });
        showToast('Settings Saved', `Connecting to ${ip}:${port}...`);
        closeSettingsModal();
    });
}

if (disconnectModalBtn) {
    disconnectModalBtn.addEventListener('click', () => {
        sendToGateway({ action: 'disconnect' });
        showToast('Disconnected', 'Disconnected from Phone daemon');
        closeSettingsModal();
    });
}

// On-Demand Ping & Connect Phone Button
if (btnConnectPhone) {
    btnConnectPhone.addEventListener('click', () => {
        if (connectionState === 'CONNECTING') return;

        if (connectionState === 'CONNECTED') {
            log('Phone is already connected! Requesting telemetry refresh & probe ping...', 'info');
            showToast('Phone Live', 'Requesting latest telemetry & ping...');
            sendToGateway({ action: 'refresh_telemetry' });
            sendToGateway({ action: 'wake_phone' });
            return;
        }

        updateConnectionUI('CONNECTING');
        if (btnConnectPhoneText) btnConnectPhoneText.textContent = 'Probing (1/5)...';
        log('⚡ Dispatching on-demand probe burst to phone (max 5 attempts)...', 'info');
        showToast('Probing Phone', 'Pinging phone up to 5 times. Will stop automatically.');

        // Send through local WebSocket gateway
        sendToGateway({ action: 'wake_phone' });

        // Also hit REST endpoint
        fetch('/api/wake_phone', { method: 'POST' }).catch(() => {});
    });
}

// Refresh Telemetry Button (Instant On-Demand)
if (refreshTelemetryBtn) {
    refreshTelemetryBtn.addEventListener('click', () => {
        log('Requested instant telemetry refresh...', 'info');
        refreshTelemetryBtn.classList.add('rotating');
        sendToGateway({ action: 'refresh_telemetry' });
        setTimeout(() => refreshTelemetryBtn.classList.remove('rotating'), 600);
    });
}

// Launch Dedicated Viewer Windows
if (btnLaunchSkeleton) {
    btnLaunchSkeleton.addEventListener('click', () => {
        window.open('/viewer.html?mode=skeleton', '_blank');
        log('Opened Skeleton Remote View in dedicated tab', 'info');
    });
}

if (btnLaunchSilent) {
    btnLaunchSilent.addEventListener('click', () => {
        window.open('/viewer.html?mode=silent', '_blank');
        log('Opened Silent Screen Share in dedicated tab', 'info');
    });
}

if (btnLaunchVideo) {
    btnLaunchVideo.addEventListener('click', () => {
        window.open('/viewer.html?mode=video', '_blank');
        log('Opened Live Screen Video Stream in dedicated tab', 'info');
    });
}

const btnLaunchCamera = document.getElementById('btnLaunchCamera');
if (btnLaunchCamera) {
    btnLaunchCamera.addEventListener('click', () => {
        window.open('/viewer.html?mode=camera', '_blank');
        log('Opened Remote Camera View in dedicated tab', 'info');
    });
}

// Tab Switching
document.querySelectorAll('.tab-btn').forEach(btn => {
    btn.addEventListener('click', () => {
        const tabId = btn.getAttribute('data-tab');
        if (!tabId || tabId === 'tab-files' || btn.getAttribute('href') === '/explorer.html' || btn.classList.contains('tab-link-external')) {
            return;
        }

        document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
        document.querySelectorAll('.tab-pane').forEach(p => p.classList.remove('active'));

        btn.classList.add('active');
        const pane = document.getElementById(tabId);
        if (pane) pane.classList.add('active');
        if (tabId === 'tab-location' && window.lucide && typeof window.lucide.createIcons === 'function') {
            window.lucide.createIcons();
        }
    });
});

// Quick Shortcuts
const btnQuickSettings = document.getElementById('btnQuickSettings');
if (btnQuickSettings) {
    btnQuickSettings.addEventListener('click', () => {
        sendToGateway({ action: 'key_nav', key: 'QUICK_SETTINGS' });
        log('Sent QUICK SETTINGS action', 'info');
    });
}

const btnVolUp = document.getElementById('btnVolUp');
if (btnVolUp) {
    btnVolUp.addEventListener('click', () => {
        sendToGateway({ action: 'key_nav', key: 'VOLUME_UP' });
        log('Sent VOLUME UP action', 'info');
    });
}

const btnVolDown = document.getElementById('btnVolDown');
if (btnVolDown) {
    btnVolDown.addEventListener('click', () => {
        sendToGateway({ action: 'key_nav', key: 'VOLUME_DOWN' });
        log('Sent VOLUME DOWN action', 'info');
    });
}

const btnWakeScreen = document.getElementById('btnWakeScreen');
if (btnWakeScreen) {
    btnWakeScreen.addEventListener('click', () => {
        sendToGateway({ action: 'wake_screen' });
        log('Sent WAKE SCREEN action', 'info');
    });
}

// Quick Actions: Open URL & Call
const btnOpenUrl = document.getElementById('btnOpenUrl');
if (btnOpenUrl) {
    btnOpenUrl.addEventListener('click', () => {
        const urlInput = document.getElementById('urlInput');
        const url = urlInput ? urlInput.value.trim() : '';
        if (!url) return;
        sendToGateway({ action: 'open_url', url });
        log(`Opening URL on phone: ${url}`, 'info');
    });
}

const btnMakeCall = document.getElementById('btnMakeCall');
if (btnMakeCall) {
    btnMakeCall.addEventListener('click', () => {
        const dialerInput = document.getElementById('dialerInput');
        const number = dialerInput ? dialerInput.value.trim() : '';
        if (!number) return;
        sendToGateway({ action: 'make_call', number });
        log(`Placing call to ${number} on phone...`, 'info');
    });
}

// -------------------------------------------------------------
// Intercom & Audio Subsystem (Two-Way Voice + Remote Ear)
// -------------------------------------------------------------
let isPttActive = false;
let pttMediaStream = null;
let pttAudioCtx = null;
let pttProcessor = null;

let isPhoneMicListening = false;
let pcAudioCtx = null;
let pcGainNode = null;
let micNextStartTime = 0;

// Walkie-Talkie Elements
const btnPushToTalk = document.getElementById('btnPushToTalk');
const pttStatusLabel = document.getElementById('pttStatusLabel');
const pttSoundwaves = document.getElementById('pttSoundwaves');
const btnPlayChime = document.getElementById('btnPlayChime');
const speakerVolumeSlider = document.getElementById('speakerVolumeSlider');
const lblSpeakerVolume = document.getElementById('lblSpeakerVolume');

// Remote Ear Elements
const btnTogglePhoneMic = document.getElementById('btnTogglePhoneMic');
const micToggleIcon = document.getElementById('micToggleIcon');
const micToggleText = document.getElementById('micToggleText');
const micDot = document.getElementById('micDot');
const micStatusText = document.getElementById('micStatusText');
const micVuBar = document.getElementById('micVuBar');
const micDbLevel = document.getElementById('micDbLevel');
const pcVolumeSlider = document.getElementById('pcVolumeSlider');
const lblPcVolume = document.getElementById('lblPcVolume');

function downsampleTo16k(inputBuffer, inputSampleRate) {
    if (inputSampleRate === 16000) {
        return inputBuffer;
    }
    const ratio = inputSampleRate / 16000;
    const newLength = Math.round(inputBuffer.length / ratio);
    const result = new Float32Array(newLength);
    let offsetResult = 0;
    let offsetBuffer = 0;
    while (offsetResult < result.length) {
        const nextOffsetBuffer = Math.round((offsetResult + 1) * ratio);
        let accum = 0, count = 0;
        for (let i = offsetBuffer; i < nextOffsetBuffer && i < inputBuffer.length; i++) {
            accum += inputBuffer[i];
            count++;
        }
        result[offsetResult] = count > 0 ? accum / count : 0;
        offsetResult++;
        offsetBuffer = nextOffsetBuffer;
    }
    return result;
}

async function startPtt() {
    if (isPttActive) return;
    isPttActive = true;

    if (btnPushToTalk) btnPushToTalk.classList.add('active');
    if (pttStatusLabel) pttStatusLabel.textContent = 'TRANSMITTING TO PHONE SPEAKER...';
    if (pttSoundwaves) pttSoundwaves.classList.add('active');

    try {
        pttMediaStream = await navigator.mediaDevices.getUserMedia({
            audio: {
                echoCancellation: true,
                noiseSuppression: true,
                autoGainControl: true
            }
        });

        const AudioCtxClass = window.AudioContext || window.webkitAudioContext;
        pttAudioCtx = new AudioCtxClass();
        if (pttAudioCtx.state === 'suspended') {
            await pttAudioCtx.resume();
        }
        const source = pttAudioCtx.createMediaStreamSource(pttMediaStream);
        const inputSampleRate = pttAudioCtx.sampleRate || 48000;

        // 4096 samples buffer at native PC rate (~85ms)
        pttProcessor = pttAudioCtx.createScriptProcessor(4096, 1, 1);
        pttProcessor.onaudioprocess = (e) => {
            if (!isPttActive) return;
            const inputData = e.inputBuffer.getChannelData(0);
            const resampled = downsampleTo16k(inputData, inputSampleRate);

            // Encode to 16-bit PCM little-endian
            let binary = '';
            for (let i = 0; i < resampled.length; i++) {
                const s = Math.max(-1, Math.min(1, resampled[i]));
                const val = s < 0 ? Math.round(s * 0x8000) : Math.round(s * 0x7FFF);
                const u16 = val < 0 ? val + 0x10000 : val;
                binary += String.fromCharCode(u16 & 0xFF, (u16 >> 8) & 0xFF);
            }
            const base64 = btoa(binary);
            sendToGateway({ action: 'speaker_audio', data: base64 });
        };

        // Mute local feedback to PC speakers while keeping ScriptProcessor alive
        const muteGain = pttAudioCtx.createGain();
        muteGain.gain.value = 0;
        source.connect(pttProcessor);
        pttProcessor.connect(muteGain);
        muteGain.connect(pttAudioCtx.destination);

        log('Intercom: Transmitting voice to phone speaker', 'info');
    } catch (err) {
        console.error('PTT Mic access failed:', err);
        showToast('Microphone Notice', 'Please allow PC microphone permissions to use Push-to-Talk.');
        stopPtt();
    }
}

function stopPtt() {
    if (!isPttActive) return;
    isPttActive = false;

    if (btnPushToTalk) btnPushToTalk.classList.remove('active');
    if (pttStatusLabel) pttStatusLabel.textContent = 'Standby — Hold button or Spacebar';
    if (pttSoundwaves) pttSoundwaves.classList.remove('active');

    if (pttProcessor) {
        try { pttProcessor.disconnect(); } catch (_) {}
        pttProcessor = null;
    }
    if (pttAudioCtx) {
        try { pttAudioCtx.close(); } catch (_) {}
        pttAudioCtx = null;
    }
    if (pttMediaStream) {
        try {
            pttMediaStream.getTracks().forEach(t => t.stop());
        } catch (_) {}
        pttMediaStream = null;
    }
    log('Intercom: Voice transmission ended', 'info');
}

if (btnPushToTalk) {
    btnPushToTalk.addEventListener('mousedown', (e) => {
        e.preventDefault();
        startPtt();
    });
    btnPushToTalk.addEventListener('mouseup', (e) => {
        e.preventDefault();
        stopPtt();
    });
    btnPushToTalk.addEventListener('mouseleave', () => {
        if (isPttActive) stopPtt();
    });
    btnPushToTalk.addEventListener('touchstart', (e) => {
        e.preventDefault();
        startPtt();
    }, { passive: false });
    btnPushToTalk.addEventListener('touchend', (e) => {
        e.preventDefault();
        stopPtt();
    }, { passive: false });
}

// Global Spacebar PTT hotkey (only when intercom tab active and not typing in an input)
window.addEventListener('keydown', (e) => {
    if (e.code === 'Space' && !e.repeat) {
        const activeTab = document.querySelector('.tab-pane.active');
        if (activeTab && activeTab.id === 'tab-intercom') {
            const tag = document.activeElement ? document.activeElement.tagName : '';
            if (tag !== 'INPUT' && tag !== 'TEXTAREA') {
                e.preventDefault();
                startPtt();
            }
        }
    }
});

window.addEventListener('keyup', (e) => {
    if (e.code === 'Space') {
        if (isPttActive) {
            e.preventDefault();
            stopPtt();
        }
    }
});

if (btnPlayChime) {
    btnPlayChime.addEventListener('click', () => {
        sendToGateway({ action: 'play_chime' });
        showToast('Chime Alert', 'Played attention chime on phone speaker');
        log('Attention chime triggered on phone speaker', 'info');
    });
}

if (speakerVolumeSlider) {
    speakerVolumeSlider.addEventListener('input', (e) => {
        const vol = parseInt(e.target.value, 10);
        if (lblSpeakerVolume) lblSpeakerVolume.textContent = `${vol}%`;
        sendToGateway({ action: 'set_speaker_volume', volume: vol });
    });
}

// Remote Ear (Phone Mic -> PC Speaker)
function initPcAudioContext() {
    if (!pcAudioCtx) {
        const AudioCtxClass = window.AudioContext || window.webkitAudioContext;
        pcAudioCtx = new AudioCtxClass({ sampleRate: 16000 });
        pcGainNode = pcAudioCtx.createGain();
        const vol = pcVolumeSlider ? parseInt(pcVolumeSlider.value, 10) : 100;
        pcGainNode.gain.value = vol / 100;
        pcGainNode.connect(pcAudioCtx.destination);
    }
    if (pcAudioCtx.state === 'suspended') {
        pcAudioCtx.resume();
    }
}

function togglePhoneMic() {
    isPhoneMicListening = !isPhoneMicListening;
    if (isPhoneMicListening) {
        initPcAudioContext();
        micNextStartTime = pcAudioCtx ? pcAudioCtx.currentTime : 0;
        sendToGateway({ action: 'start_mic_stream' });
        updateMicStatusUI(true);
        log('Started listening to remote phone microphone', 'success');
    } else {
        sendToGateway({ action: 'stop_mic_stream' });
        updateMicStatusUI(false);
        log('Stopped listening to phone microphone', 'info');
    }
}

if (btnTogglePhoneMic) {
    btnTogglePhoneMic.addEventListener('click', togglePhoneMic);
}

function updateMicStatusUI(isStreaming) {
    isPhoneMicListening = isStreaming;
    if (btnTogglePhoneMic) {
        btnTogglePhoneMic.classList.toggle('streaming', isStreaming);
    }
    if (micToggleIcon) {
        micToggleIcon.innerHTML = isStreaming 
            ? '<i data-lucide="square" class="ui-icon-sm"></i>' 
            : '<i data-lucide="play" class="ui-icon-sm"></i>';
        if (window.lucide && typeof window.lucide.createIcons === 'function') {
            window.lucide.createIcons();
        }
    }
    if (micToggleText) micToggleText.textContent = isStreaming ? 'Stop Listening' : 'Start Listening to Phone';
    if (micDot) micDot.className = `dot ${isStreaming ? 'connected' : 'disconnected'}`;
    if (micStatusText) micStatusText.textContent = isStreaming ? 'STREAMING' : 'OFF';

    if (!isStreaming) {
        if (micVuBar) micVuBar.style.width = '0%';
        if (micDbLevel) micDbLevel.textContent = '-60 dB';
    }
}

if (pcVolumeSlider) {
    pcVolumeSlider.addEventListener('input', (e) => {
        const vol = parseInt(e.target.value, 10);
        if (lblPcVolume) lblPcVolume.textContent = `${vol}%`;
        if (pcGainNode) {
            pcGainNode.gain.value = vol / 100;
        }
    });
}

function handleIncomingMicAudio(base64Data) {
    if (!isPhoneMicListening || !base64Data) return;
    try {
        const bin = atob(base64Data);
        const sampleCount = Math.floor(bin.length / 2);
        if (sampleCount === 0) return;

        const float32 = new Float32Array(sampleCount);
        let sumSquares = 0;
        for (let i = 0; i < sampleCount; i++) {
            const b1 = bin.charCodeAt(i * 2);
            const b2 = bin.charCodeAt(i * 2 + 1);
            let val = (b2 << 8) | b1;
            if (val >= 0x8000) val -= 0x10000;
            const s = val / 32768.0;
            float32[i] = s;
            sumSquares += s * s;
        }

        // VU meter calculation
        const rms = Math.sqrt(sumSquares / sampleCount);
        const db = rms > 0.0001 ? Math.max(-60, Math.round(20 * Math.log10(rms))) : -60;
        const pct = Math.min(100, Math.max(0, Math.round((db + 60) * (100 / 60))));
        if (micVuBar) micVuBar.style.width = `${pct}%`;
        if (micDbLevel) micDbLevel.textContent = `${db} dB`;

        // Smooth gapless scheduling playback
        initPcAudioContext();
        if (pcAudioCtx) {
            const buffer = pcAudioCtx.createBuffer(1, sampleCount, 16000);
            buffer.copyToChannel(float32, 0);
            const source = pcAudioCtx.createBufferSource();
            source.buffer = buffer;
            source.connect(pcGainNode);

            const now = pcAudioCtx.currentTime;
            // ANTI-DRIFT CEILING: If micNextStartTime is in the past OR more than 75ms in the future,
            // immediately snap it back to (now + 15ms). This prevents ANY buffer backlog or 1-minute delay!
            if (micNextStartTime < now || (micNextStartTime - now) > 0.075) {
                micNextStartTime = now + 0.015;
            }
            source.start(micNextStartTime);
            micNextStartTime += buffer.duration;
        }
    } catch (e) {
        console.warn('Error decoding or playing mic audio:', e);
    }
}

// -------------------------------------------------------------
// Windows Explorer Style File Explorer Engine
// -------------------------------------------------------------
function getFileExtension(filename) {
    if (!filename || !filename.includes('.')) return '';
    return filename.split('.').pop().toLowerCase();
}

function getFileType(file) {
    if (file.is_dir) return 'File folder';
    const ext = getFileExtension(file.name);
    const typeMap = {
        'apk': 'Android App Package',
        'aab': 'Android App Bundle',
        'xapk': 'Android App Package',
        'jpg': 'JPEG Image',
        'jpeg': 'JPEG Image',
        'png': 'PNG Image',
        'webp': 'WEBP Image',
        'gif': 'GIF Image',
        'svg': 'SVG Vector',
        'bmp': 'BMP Image',
        'mp4': 'MP4 Video',
        'mkv': 'MKV Video',
        'avi': 'AVI Video',
        'mov': 'QuickTime Video',
        'webm': 'WebM Video',
        'flv': 'FLV Video',
        'mp3': 'MP3 Audio',
        'm4a': 'M4A Audio',
        'wav': 'WAV Audio',
        'flac': 'FLAC Audio',
        'aac': 'AAC Audio',
        'ogg': 'OGG Audio',
        'pdf': 'PDF Document',
        'doc': 'Word Document',
        'docx': 'Word Document',
        'rtf': 'Rich Text Document',
        'xls': 'Excel Spreadsheet',
        'xlsx': 'Excel Spreadsheet',
        'csv': 'CSV Spreadsheet',
        'ppt': 'PowerPoint Slide',
        'pptx': 'PowerPoint Slide',
        'zip': 'Compressed (ZIP)',
        'rar': 'RAR Archive',
        '7z': '7-Zip Archive',
        'tar': 'TAR Archive',
        'gz': 'GZip Archive',
        'bz2': 'BZip Archive',
        'xz': 'XZ Archive',
        'iso': 'Disc Image File',
        'img': 'Disc Image File',
        'vhd': 'Virtual Hard Disk',
        'exe': 'Executable Application',
        'msi': 'Windows Installer Package',
        'bat': 'Windows Batch File',
        'cmd': 'Windows Command Script',
        'ps1': 'PowerShell Script',
        'txt': 'Text Document',
        'log': 'Log File',
        'ini': 'Configuration Settings',
        'cfg': 'Configuration File',
        'conf': 'Configuration File',
        'json': 'JSON File',
        'xml': 'XML Document',
        'html': 'HTML Document',
        'css': 'Cascading Style Sheet',
        'js': 'JavaScript File',
        'ts': 'TypeScript File',
        'py': 'Python Script',
        'java': 'Java Source File',
        'kt': 'Kotlin Source File',
        'c': 'C Source File',
        'cpp': 'C++ Source File',
        'cs': 'C# Source File',
        'sh': 'Shell Script'
    };
    return typeMap[ext] || (ext ? `${ext.toUpperCase()} File` : 'File');
}

function getFileIcon(file) {
    if (file.is_dir) {
        if (file.name === '..' || file.name === '../' || file.name.includes('[Parent Directory]')) {
            return '<img src="/icons/win11/folder_up.svg" class="win11-file-icon folder" alt="" draggable="false">';
        }
        return '<img src="/icons/win11/folder.svg" class="win11-file-icon folder" alt="" draggable="false">';
    }
    const ext = getFileExtension(file.name);
    let icon = 'generic';

    if (['jpg', 'jpeg', 'png', 'webp', 'gif', 'svg', 'bmp'].includes(ext)) {
        icon = 'image';
    } else if (['mp4', 'mkv', 'avi', 'mov', 'webm', 'flv'].includes(ext)) {
        icon = 'video';
    } else if (['mp3', 'm4a', 'wav', 'flac', 'aac', 'ogg'].includes(ext)) {
        icon = 'audio';
    } else if (['apk', 'aab', 'xapk'].includes(ext)) {
        icon = 'apk';
    } else if (['zip', 'rar', '7z', 'tar', 'gz', 'bz2', 'xz'].includes(ext)) {
        icon = 'zip';
    } else if (ext === 'pdf') {
        icon = 'pdf';
    } else if (['doc', 'docx', 'rtf'].includes(ext)) {
        icon = 'doc';
    } else if (['xls', 'xlsx', 'csv'].includes(ext)) {
        icon = 'xls';
    } else if (['ppt', 'pptx'].includes(ext)) {
        icon = 'ppt';
    } else if (['exe', 'msi', 'bat', 'cmd', 'ps1'].includes(ext)) {
        icon = 'exe';
    } else if (['iso', 'img', 'vhd'].includes(ext)) {
        icon = 'iso';
    } else if (['txt', 'log', 'ini', 'cfg', 'conf'].includes(ext)) {
        icon = 'txt';
    } else if (['js', 'ts', 'py', 'java', 'kt', 'c', 'cpp', 'cs', 'html', 'css', 'json', 'xml', 'sh'].includes(ext)) {
        icon = 'code';
    }

    return `<img src="/icons/win11/${icon}.svg" class="win11-file-icon ${icon}" alt="" draggable="false">`;
}

function formatModifiedDate(timestamp) {
    if (!timestamp || timestamp <= 0) return '--';
    const d = new Date(timestamp);
    if (isNaN(d.getTime())) return '--';
    const year = d.getFullYear();
    const month = String(d.getMonth() + 1).padStart(2, '0');
    const day = String(d.getDate()).padStart(2, '0');
    const hours = String(d.getHours()).padStart(2, '0');
    const mins = String(d.getMinutes()).padStart(2, '0');
    return `${year}-${month}-${day} ${hours}:${mins}`;
}

function sortFiles(files, column, direction) {
    const factor = direction === 'asc' ? 1 : -1;
    return [...files].sort((a, b) => {
        // Windows Explorer rule: Folders stay grouped at top
        if (a.is_dir !== b.is_dir) {
            return a.is_dir ? -1 : 1;
        }

        switch (column) {
            case 'name':
                return a.name.localeCompare(b.name, undefined, { numeric: true, sensitivity: 'base' }) * factor;
            case 'modified':
                const modA = a.modified || 0;
                const modB = b.modified || 0;
                return (modA - modB) * factor;
            case 'type':
                const typeA = getFileType(a);
                const typeB = getFileType(b);
                const typeCmp = typeA.localeCompare(typeB);
                if (typeCmp !== 0) return typeCmp * factor;
                return a.name.localeCompare(b.name) * factor;
            case 'size':
                const sizeA = a.is_dir ? 0 : (a.size || 0);
                const sizeB = b.is_dir ? 0 : (b.size || 0);
                return (sizeA - sizeB) * factor;
            default:
                return 0;
        }
    });
}

function updateSortUI() {
    ['name', 'modified', 'type', 'size'].forEach(col => {
        const colHeader = document.querySelector(`.explorer-header .col[data-sort="${col}"]`);
        const iconId = `sortIcon${col.charAt(0).toUpperCase() + col.slice(1)}`;
        const icon = document.getElementById(iconId);
        if (colHeader && icon) {
            if (col === fileSortColumn) {
                colHeader.classList.add('active');
                icon.innerHTML = fileSortDirection === 'asc' 
                    ? '<i data-lucide="arrow-up" class="ui-icon-xs"></i>' 
                    : '<i data-lucide="arrow-down" class="ui-icon-xs"></i>';
            } else {
                colHeader.classList.remove('active');
                icon.innerHTML = '';
            }
        }
    });
    if (window.lucide && typeof window.lucide.createIcons === 'function') {
        window.lucide.createIcons();
    }
}

// Setup sorting click listeners
document.querySelectorAll('.explorer-header .sortable').forEach(colEl => {
    colEl.addEventListener('click', () => {
        const sortType = colEl.getAttribute('data-sort');
        if (fileSortColumn === sortType) {
            fileSortDirection = fileSortDirection === 'asc' ? 'desc' : 'asc';
        } else {
            fileSortColumn = sortType;
            // Default descending for date, ascending for others
            fileSortDirection = sortType === 'modified' ? 'desc' : 'asc';
        }
        updateSortUI();
        displayCurrentFiles();
    });
});

// Setup search filter
const fileSearchInput = document.getElementById('fileSearchInput');
if (fileSearchInput) {
    fileSearchInput.addEventListener('input', (e) => {
        fileFilterQuery = e.target.value.trim().toLowerCase();
        displayCurrentFiles();
    });
}

// Path Navigation
const btnBrowsePath = document.getElementById('btnBrowsePath');
if (btnBrowsePath) {
    btnBrowsePath.addEventListener('click', () => {
        const filePathInput = document.getElementById('filePathInput');
        const path = filePathInput ? filePathInput.value.trim() : '/storage/emulated/0';
        sendToGateway({ action: 'list_dir', path });
    });
}

const btnRefreshFiles = document.getElementById('btnRefreshFiles');
if (btnRefreshFiles) {
    btnRefreshFiles.addEventListener('click', () => {
        const filePathInput = document.getElementById('filePathInput');
        const path = filePathInput ? filePathInput.value.trim() : currentFolderPath;
        sendToGateway({ action: 'list_dir', path });
    });
}

const btnPathUp = document.getElementById('btnPathUp');
if (btnPathUp) {
    btnPathUp.addEventListener('click', () => {
        const input = document.getElementById('filePathInput');
        if (!input) return;
        const parts = input.value.split('/').filter(Boolean);
        if (parts.length > 1) {
            parts.pop();
            const parentPath = '/' + parts.join('/');
            input.value = parentPath;
            sendToGateway({ action: 'list_dir', path: parentPath });
        }
    });
}

function renderFileList(currentPath, files, allFilesAccess) {
    currentFolderPath = currentPath;
    currentFilesList = files || [];
    if (allFilesAccess !== undefined) {
        isAllFilesAccessGranted = allFilesAccess;
    }
    const filePathInput = document.getElementById('filePathInput');
    if (filePathInput) filePathInput.value = currentPath;
    displayCurrentFiles();
}

function displayCurrentFiles() {
    const container = document.getElementById('fileListContainer');
    if (!container) return;

    const bannerHtml = isAllFilesAccessGranted === false ? `
        <div style="background: rgba(239, 68, 68, 0.12); border: 1px solid rgba(239, 68, 68, 0.4); border-radius: 8px; padding: 12px 16px; margin-bottom: 14px; color: #fca5a5; font-size: 13px; line-height: 1.5;">
            <div style="font-weight: 600; font-size: 14px; margin-bottom: 4px; display: flex; align-items: center; gap: 8px; color: #f87171;">
                <i data-lucide="shield-alert" class="ui-icon-sm"></i> All Files Access Not Granted on Phone
            </div>
            <div>Android Scoped Storage is hiding non-folder files (photos, downloads, documents). Only directories are currently visible.</div>
            <div style="margin-top: 6px; color: #e2e8f0; display: flex; align-items: center; gap: 6px;">
                <i data-lucide="arrow-right" class="ui-icon-xs"></i> Open <strong>Ano-Link</strong> on your phone and tap <strong>"4. Enable All Files Access (Photos & Docs)"</strong> to reveal all files.
            </div>
        </div>
    ` : '';

    if (!currentFilesList || currentFilesList.length === 0) {
        container.innerHTML = bannerHtml + '<div class="empty-state">Empty directory</div>';
        if (window.lucide && typeof window.lucide.createIcons === 'function') {
            window.lucide.createIcons();
        }
        return;
    }

    let files = currentFilesList;
    if (fileFilterQuery) {
        files = files.filter(f => f.name.toLowerCase().includes(fileFilterQuery));
        if (files.length === 0) {
            container.innerHTML = bannerHtml + `<div class="empty-state">No matching files found for "${fileFilterQuery}"</div>`;
            if (window.lucide && typeof window.lucide.createIcons === 'function') {
                window.lucide.createIcons();
            }
            return;
        }
    }

    const sortedFiles = sortFiles(files, fileSortColumn, fileSortDirection);
    container.innerHTML = bannerHtml;

    sortedFiles.forEach(file => {
        const row = document.createElement('div');
        row.className = `explorer-row ${file.is_dir ? 'is-folder' : 'is-file'}`;

        const icon = getFileIcon(file);
        const typeStr = getFileType(file);
        const dateStr = formatModifiedDate(file.modified);
        const sizeStr = file.is_dir ? '' : formatBytes(file.size);

        row.innerHTML = `
            <div class="col col-name" title="${file.name}">
                <span class="file-icon">${icon}</span>
                <strong class="file-title">${file.name}</strong>
            </div>
            <div class="col col-date">${dateStr}</div>
            <div class="col col-type">${typeStr}</div>
            <div class="col col-size">${sizeStr}</div>
            <div class="col col-actions">
                ${!file.is_dir ? `<button class="btn btn-xs btn-primary dl-btn" title="Download ${file.name}"><i data-lucide="download" class="ui-icon-xs"></i> Download</button>` : ''}
            </div>
        `;

        if (file.is_dir) {
            row.addEventListener('click', (e) => {
                if (e.target.tagName !== 'BUTTON') {
                    sendToGateway({ action: 'list_dir', path: file.path });
                }
            });
        } else {
            const dlBtn = row.querySelector('.dl-btn');
            if (dlBtn) {
                dlBtn.addEventListener('click', (e) => {
                    e.stopPropagation();
                    startChunkDownload(file.path, file.name, file.size);
                });
            }
        }

        container.appendChild(row);
    });

    if (window.lucide && typeof window.lucide.createIcons === 'function') {
        window.lucide.createIcons();
    }
}

function formatBytes(bytes) {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
}

// Resumable Chunk Downloader
function startChunkDownload(filePath, fileName, fileSize) {
    log(`Initiating download for ${fileName} (${formatBytes(fileSize)})...`, 'info');

    const downloadsList = document.getElementById('downloadsList');
    if (downloadsList && downloadsList.querySelector('.muted')) {
        downloadsList.innerHTML = '';
    }

    const transferId = 'dl_' + Math.random().toString(36).substring(2, 9);
    const item = document.createElement('div');
    item.className = 'download-item';
    item.id = transferId;
    item.style.cssText = 'background: rgba(18, 24, 36, 0.7); border: 1px solid var(--border-color); border-radius: 8px; padding: 10px; margin-bottom: 8px;';
    item.innerHTML = `
        <div style="display: flex; justify-content: space-between; font-size: 12px; margin-bottom: 6px;">
            <strong>${fileName}</strong>
            <span class="dl-status" style="color: var(--accent-cyan); font-weight: 600;">Downloading...</span>
        </div>
        <div style="background: #0b0f17; border-radius: 4px; height: 6px; overflow: hidden; margin-bottom: 6px;">
            <div class="dl-bar" style="width: 100%; height: 100%; background: linear-gradient(90deg, #2563eb, #06b6d4); animation: pulse 1s infinite alternate;"></div>
        </div>
        <div style="display: flex; justify-content: space-between; font-size: 11px; color: var(--text-muted);">
            <span>${formatBytes(fileSize)}</span>
            <span>Chunk Stream</span>
        </div>
    `;
    if (downloadsList) downloadsList.prepend(item);

    const downloadUrl = `/api/download?path=${encodeURIComponent(filePath)}&name=${encodeURIComponent(fileName)}`;
    const a = document.createElement('a');
    a.href = downloadUrl;
    a.download = fileName;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);

    setTimeout(() => {
        const statusEl = item.querySelector('.dl-status');
        if (statusEl) {
            statusEl.textContent = 'Completed';
            statusEl.style.color = 'var(--accent-green)';
        }
        const bar = item.querySelector('.dl-bar');
        if (bar) {
            bar.style.animation = 'none';
            bar.style.background = 'var(--accent-green)';
        }
        log(`Download ready: ${fileName}`, 'success');
    }, 1200);
}
window.startChunkDownload = startChunkDownload;

// -------------------------------------------------------------
// SMS Inbox & OTP History
// -------------------------------------------------------------
const btnFetchSms = document.getElementById('btnFetchSms');
if (btnFetchSms) {
    btnFetchSms.addEventListener('click', () => {
        sendToGateway({ action: 'get_sms' });
        log('Requested SMS history from phone...', 'info');
    });
}

function formatSmsDate(timestamp) {
    if (!timestamp) return '';
    const d = new Date(timestamp);
    if (isNaN(d.getTime())) return '';
    const now = new Date();
    const isToday = d.toDateString() === now.toDateString();
    const timePart = d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    if (isToday) {
        return `Today ${timePart}`;
    }
    const yesterday = new Date(now);
    yesterday.setDate(yesterday.getDate() - 1);
    if (d.toDateString() === yesterday.toDateString()) {
        return `Yesterday ${timePart}`;
    }
    return `${d.toLocaleDateString([], { month: 'short', day: 'numeric', year: 'numeric' })} ${timePart}`;
}

function isOtpMessage(body) {
    if (!body) return false;
    const lower = body.toLowerCase();
    return lower.includes('otp') || 
           lower.includes('code') || 
           lower.includes('verification') || 
           lower.includes('password') || 
           /\b\d{4,8}\b/.test(body);
}

function createSmsCard(sms) {
    const card = document.createElement('div');
    const isOtp = isOtpMessage(sms.body);
    card.className = `sms-card ${isOtp ? 'is-otp' : ''}`;

    const dateFormatted = formatSmsDate(sms.date || sms.timestamp);
    const type = (sms.type || 'inbox').toLowerCase();

    card.innerHTML = `
        <div class="sms-card-header">
            <div class="sms-sender-badge">
                <span class="sms-type-tag ${type}">${type}</span>
                <strong style="color: var(--accent-cyan); font-size: 13px;">${sms.sender || 'Unknown'}</strong>
            </div>
            <span class="sms-card-date">${dateFormatted}</span>
        </div>
        <p class="sms-card-body">${sms.body || ''}</p>
    `;
    return card;
}

function renderSmsHistory(messages) {
    const feed = document.getElementById('smsFeed');
    if (!feed) return;
    if (!messages || messages.length === 0) {
        feed.innerHTML = '<div class="empty-state">No SMS messages found on device</div>';
        return;
    }
    feed.innerHTML = '';
    // messages is sorted DESC (newest first). Append in order so newest is at the top
    messages.forEach(msg => {
        feed.appendChild(createSmsCard(msg));
    });
}

function addLiveSmsEntry(sms) {
    const feed = document.getElementById('smsFeed');
    if (!feed) return;
    if (feed.querySelector('.empty-state')) {
        feed.innerHTML = '';
    }
    // New live message arrives: prepend to top of list
    feed.prepend(createSmsCard(sms));
}

// Toast Notifications
function showToast(title, body) {
    const container = document.getElementById('toastContainer');
    if (!container) return;
    const toast = document.createElement('div');
    toast.className = 'toast';
    toast.innerHTML = `
        <h4>${title}</h4>
        <p>${body}</p>
    `;
    container.appendChild(toast);
    setTimeout(() => toast.remove(), 7000);
}

// Logs Console
const btnClearLogs = document.getElementById('btnClearLogs');
if (btnClearLogs) {
    btnClearLogs.addEventListener('click', () => {
        if (logsConsole) logsConsole.innerHTML = '';
    });
}

function log(message, type = 'info') {
    if (!logsConsole) return;
    const entry = document.createElement('div');
    entry.className = `log-entry ${type}`;
    const time = new Date().toLocaleTimeString();
    entry.textContent = `[${time}] ${message}`;
    logsConsole.appendChild(entry);
    logsConsole.scrollTop = logsConsole.scrollHeight;
}

// ==========================================================================
// Location & Find Device Manager (Satellite Aerial Tracker & Emergency Alarm)
// ==========================================================================
let currentLocLat = 28.613939;
let currentLocLng = 77.209021;
let currentLocAddress = 'Connaught Place, New Delhi';
let lastLocPingSeconds = 0;
let isEmergencyRinging = false;

function initLocationTracker() {
    const btnPing = document.getElementById('btnSinglePingGps');
    const btnAlarm = document.getElementById('btnEmergencyAlarm');
    const btnCopy = document.getElementById('btnCopyLocCoords');

    if (btnPing) {
        btnPing.addEventListener('click', triggerSingleGpsPing);
    }

    if (btnAlarm) {
        btnAlarm.addEventListener('click', toggleEmergencyRinger);
    }

    if (btnCopy) {
        btnCopy.addEventListener('click', copyLocationCoordinates);
    }

    // Preset Location chips for testing
    document.querySelectorAll('.loc-preset-chip').forEach(chip => {
        chip.addEventListener('click', () => {
            document.querySelectorAll('.loc-preset-chip').forEach(c => c.classList.remove('active'));
            chip.classList.add('active');
            const lat = parseFloat(chip.dataset.lat);
            const lng = parseFloat(chip.dataset.lng);
            const place = chip.dataset.place;
            updateLocationDisplay(lat, lng, 4.2, 216, 'preset', place);
            showToast('Satellite Focused', `Centered map on ${place.split(',')[0]}`);
        });
    });

    // "Last Ping" live seconds counter
    setInterval(() => {
        lastLocPingSeconds++;
        const el = document.getElementById('locValLastPing');
        if (!el) return;
        if (lastLocPingSeconds < 5) {
            el.textContent = 'Just now';
            el.style.color = 'var(--accent-green)';
        } else if (lastLocPingSeconds < 60) {
            el.textContent = `${lastLocPingSeconds}s ago`;
            el.style.color = 'var(--text-secondary)';
        } else {
            const mins = Math.floor(lastLocPingSeconds / 60);
            el.textContent = `${mins}m ago`;
            el.style.color = 'var(--text-secondary)';
        }
    }, 1000);
}

function triggerSingleGpsPing() {
    const btn = document.getElementById('btnSinglePingGps');
    const label = document.getElementById('labelSinglePing');
    if (btn) btn.classList.add('loading');
    if (label) label.textContent = 'Pinging GPS Satellites...';

    sendToGateway({ action: 'get_location' });
    log('Sent single GPS location query to phone', 'info');

    // UI watchdog timeout (if phone is offline)
    setTimeout(() => {
        if (btn) btn.classList.remove('loading');
        if (label) label.textContent = 'Single Ping (Fetch Location)';
    }, 10000);
}

function handleLocationUpdate(data) {
    const btn = document.getElementById('btnSinglePingGps');
    const label = document.getElementById('labelSinglePing');
    if (btn) btn.classList.remove('loading');
    if (label) label.textContent = 'Single Ping (Fetch Location)';

    if (!data.success) {
        showToast('Location Notice', data.error || 'Could not resolve device location');
        log(`Location error from device: ${data.error}`, 'warn');
        return;
    }

    const lat = data.latitude;
    const lng = data.longitude;
    const accuracy = data.accuracy || 4.2;
    const altitude = data.altitude || 0;
    const provider = data.provider || 'gps';

    updateLocationDisplay(lat, lng, accuracy, altitude, provider);
    showToast('Location Updated', `Device pinpointed: ${lat.toFixed(5)}, ${lng.toFixed(5)} (±${Math.round(accuracy)}m)`);
    log(`Device GPS fixed: ${lat.toFixed(6)}, ${lng.toFixed(6)} (±${accuracy.toFixed(1)}m)`, 'success');
}

function updateLocationDisplay(lat, lng, accuracy, altitude, provider, placeName) {
    currentLocLat = lat;
    currentLocLng = lng;
    lastLocPingSeconds = 0;

    const frame = document.getElementById('locSatelliteFrame');
    if (frame) {
        frame.src = `https://maps.google.com/maps?q=${lat},${lng}&t=k&z=17&ie=UTF8&iwloc=&output=embed`;
    }

    const coordsEl = document.getElementById('locValCoords');
    if (coordsEl) coordsEl.textContent = `${lat.toFixed(6)}, ${lng.toFixed(6)}`;

    const accEl = document.getElementById('locValAccuracy');
    if (accEl) accEl.textContent = `±${accuracy.toFixed(1)} meters`;

    const fixEl = document.getElementById('locFixPrecision');
    if (fixEl) fixEl.textContent = `±${accuracy.toFixed(1)}m`;

    const altEl = document.getElementById('locValAltitude');
    if (altEl) altEl.textContent = `${Math.round(altitude)} m MSL`;

    const lastPingEl = document.getElementById('locValLastPing');
    if (lastPingEl) {
        lastPingEl.textContent = 'Just now';
        lastPingEl.style.color = 'var(--accent-green)';
    }

    if (placeName) {
        currentLocAddress = placeName;
        const addrEl = document.getElementById('locValAddress');
        if (addrEl) addrEl.textContent = placeName;
    }

    const gmaps = document.getElementById('linkLocGoogle');
    if (gmaps) gmaps.href = `https://maps.google.com/?q=${lat},${lng}`;

    const osm = document.getElementById('linkLocOsm');
    if (osm) osm.href = `https://www.openstreetmap.org/?mlat=${lat}&mlon=${lng}#map=17/${lat}/${lng}`;
}

function toggleEmergencyRinger() {
    isEmergencyRinging = !isEmergencyRinging;
    if (isEmergencyRinging) {
        sendToGateway({ action: 'ring_phone' });
        handleRingStatus(true);
        showToast('Emergency Alarm Sent', 'Phone will sound at 100% volume bypassing Silent & DND');
        log('Triggered 100% volume emergency alarm on phone', 'warn');
    } else {
        sendToGateway({ action: 'stop_ring_phone' });
        handleRingStatus(false);
        showToast('Alarm Stopped', 'Emergency alarm halted and volume restored');
        log('Halted emergency alarm on phone', 'info');
    }
}

function handleRingStatus(ringing) {
    isEmergencyRinging = ringing;
    const btn = document.getElementById('btnEmergencyAlarm');
    const label = document.getElementById('labelEmergencyAlarm');
    if (!btn || !label) return;

    if (ringing) {
        btn.classList.add('active-ringing');
        label.textContent = 'Stop Ringing Device';
    } else {
        btn.classList.remove('active-ringing');
        label.textContent = 'Ring Phone at 100% Volume';
    }
}

function copyLocationCoordinates() {
    const text = `${currentLocLat.toFixed(6)}, ${currentLocLng.toFixed(6)}`;
    navigator.clipboard.writeText(text).then(() => {
        showToast('Copied', `Coordinates copied: ${text}`);
    }).catch(() => {
        showToast('Coordinates', text);
    });
}

// Initialize on load
if (window.lucide && typeof window.lucide.createIcons === 'function') {
    window.lucide.createIcons();
}
updateSortUI();
initWebSocket();
refreshWebRtcInfo();
initLocationTracker();
