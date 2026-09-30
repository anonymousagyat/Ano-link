// Ano-Link Standalone Fullscreen Viewport
let ws = null;
let phoneWidth = 1080;
let phoneHeight = 2400;
let showBoxLabels = true;
let isMouseDown = false;
let dragStartX = 0;
let dragStartY = 0;
let dragStartTime = 0;
let currentSkeletonTree = null;
let isStreamRunning = true;
let activeFps = 30;

// Parse mode directly from URL query param (?mode=skeleton, ?mode=video, or ?mode=camera)
const urlParams = new URLSearchParams(window.location.search);
const rawMode = urlParams.get('mode');
const activeMode = rawMode === 'video' ? 'video' : (rawMode === 'camera' ? 'camera' : 'skeleton');

// DOM Elements
const canvas = document.getElementById('popoutCanvas');
const ctx = canvas.getContext('2d');
const videoCanvas = document.getElementById('popoutVideoCanvas');
const videoCtx = videoCanvas ? videoCanvas.getContext('2d') : null;
const viewport = document.getElementById('popoutViewport');
const popoutStatus = document.getElementById('popoutStatus');
const popoutDot = document.getElementById('popoutDot');
const modeBadge = document.getElementById('modeBadge');
const modeSubText = document.getElementById('modeSubText');
const btnToggleStream = document.getElementById('btnToggleStream');
const streamStatusIcon = document.getElementById('streamStatusIcon');
const streamStatusText = document.getElementById('streamStatusText');
const popoutPausedOverlay = document.getElementById('popoutPausedOverlay');
const pausedOverlayTitle = document.getElementById('pausedOverlayTitle');
const pausedOverlayDesc = document.getElementById('pausedOverlayDesc');
const btnPopoutResumeStreamOverlay = document.getElementById('btnPopoutResumeStreamOverlay');
const skeletonControlsSection = document.getElementById('skeletonControlsSection');
const videoControlsSection = document.getElementById('videoControlsSection');
const cameraControlsSection = document.getElementById('cameraControlsSection');
const btnViewerFlipCam = document.getElementById('btnViewerFlipCam');
const lblViewerLens = document.getElementById('lblViewerLens');
const btnViewerToggleTorch = document.getElementById('btnViewerToggleTorch');
const lblViewerTorch = document.getElementById('lblViewerTorch');
const btnViewerSnapshot = document.getElementById('btnViewerSnapshot');
const btnToggleBoxLabels = document.getElementById('btnToggleBoxLabels');
const textInput = document.getElementById('popoutTextInput');
const btnPopoutSendText = document.getElementById('btnPopoutSendText');

let currentFacingFront = false;
let isTorchOn = false;

// Setup UI based on Active Mode
function setupModeUI() {
    if (activeMode === 'camera') {
        document.title = 'Ano-Link - Remote Camera Feed';
        if (modeBadge) {
            modeBadge.innerHTML = '<i data-lucide="camera" class="ui-icon-xs"></i> <span>REMOTE CAMERA</span>';
            modeBadge.className = 'mode-badge camera';
        }
        if (modeSubText) {
            modeSubText.textContent = 'Dual Lens Live Feed';
        }
        if (viewport) {
            viewport.classList.add('mode-camera');
            viewport.classList.remove('mode-video');
        }
        if (videoControlsSection) videoControlsSection.style.display = 'none';
        if (skeletonControlsSection) skeletonControlsSection.style.display = 'none';
        if (cameraControlsSection) cameraControlsSection.style.display = 'block';
        if (videoCanvas) videoCanvas.style.display = 'block';
        if (canvas) canvas.style.display = 'none';
        if (streamStatusText) streamStatusText.textContent = 'Stop Camera';
        if (pausedOverlayTitle) pausedOverlayTitle.textContent = 'Camera Feed Paused';
        if (pausedOverlayDesc) pausedOverlayDesc.textContent = 'Camera sensor suspended to conserve phone battery.';
    } else if (activeMode === 'video') {
        document.title = 'Ano-Link - Live Video Stream';
        if (modeBadge) {
            modeBadge.innerHTML = '<i data-lucide="monitor" class="ui-icon-xs"></i> <span>LIVE SCREEN VIDEO</span>';
            modeBadge.className = 'mode-badge video';
        }
        if (modeSubText) {
            modeSubText.textContent = 'Hardware Mirror 30 FPS';
        }
        if (viewport) {
            viewport.classList.add('mode-video');
            viewport.classList.remove('mode-camera');
        }
        if (videoControlsSection) videoControlsSection.style.display = 'block';
        if (skeletonControlsSection) skeletonControlsSection.style.display = 'none';
        if (cameraControlsSection) cameraControlsSection.style.display = 'none';
        if (videoCanvas) videoCanvas.style.display = 'block';
        if (canvas) canvas.style.display = 'none';
        if (streamStatusText) streamStatusText.textContent = 'Stop Video';
        if (pausedOverlayTitle) pausedOverlayTitle.textContent = 'Video Stream Paused';
        if (pausedOverlayDesc) pausedOverlayDesc.textContent = 'Conserving phone battery, GPU & bandwidth.';
    } else {
        document.title = 'Ano-Link - Skeleton Remote';
        if (modeBadge) {
            modeBadge.innerHTML = '<i data-lucide="zap" class="ui-icon-xs"></i> <span>SKELETON REMOTE</span>';
            modeBadge.className = 'mode-badge skeleton';
        }
        if (modeSubText) {
            modeSubText.textContent = 'Zero-Popup Low Bandwidth';
        }
        if (viewport) {
            viewport.classList.remove('mode-video');
            viewport.classList.remove('mode-camera');
        }
        if (videoControlsSection) videoControlsSection.style.display = 'none';
        if (skeletonControlsSection) skeletonControlsSection.style.display = 'block';
        if (cameraControlsSection) cameraControlsSection.style.display = 'none';
        if (videoCanvas) videoCanvas.style.display = 'none';
        if (canvas) canvas.style.display = 'block';
        if (streamStatusText) streamStatusText.textContent = 'Stop Skeleton';
        if (pausedOverlayTitle) pausedOverlayTitle.textContent = 'Skeleton Remote Paused';
        if (pausedOverlayDesc) pausedOverlayDesc.textContent = 'Paused accessibility UI inspection.';
    }
    if (window.lucide && typeof window.lucide.createIcons === 'function') {
        window.lucide.createIcons();
    }
}
setupModeUI();

function initWebSocket() {
    const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${wsProtocol}//${window.location.host}/ws-browser`;
    ws = new WebSocket(wsUrl);
    ws.binaryType = 'arraybuffer';

    ws.onopen = () => {
        // Enforce the specific tab's dedicated mode on the gateway
        sendToGateway({ action: 'set_mode', mode: activeMode });
        if (activeMode === 'skeleton') {
            sendToGateway({ action: 'get_skeleton_tree' });
        }
    };

    ws.onmessage = (event) => {
        if (event.data instanceof ArrayBuffer) {
            if (activeMode === 'video' || activeMode === 'camera') {
                renderVideoFrame(event.data);
            }
            return;
        }
        try {
            const msg = JSON.parse(event.data);
            if (msg.type === 'initial_state' || msg.type === 'status_change') {
                const state = msg.state || 'DISCONNECTED';
                if (popoutStatus) popoutStatus.textContent = state;
                if (popoutDot) popoutDot.className = `dot ${state.toLowerCase()}`;
                if (msg.telemetry && msg.telemetry.battery !== undefined) {
                    const bEl = document.getElementById('popoutStatusBarBattery');
                    if (bEl) {
                        const iconName = msg.telemetry.charging ? 'battery-charging' : 'battery';
                        bEl.innerHTML = `<i data-lucide="${iconName}" class="status-bar-icon"></i> <span>${msg.telemetry.battery}%</span>`;
                        if (window.lucide && typeof window.lucide.createIcons === 'function') {
                            window.lucide.createIcons();
                        }
                    }
                }
                if (msg.skeletonTree && activeMode === 'skeleton') {
                    renderSkeleton(msg.skeletonTree);
                }
            } else if (msg.type === 'telemetry_update') {
                if (msg.telemetry && msg.telemetry.battery !== undefined) {
                    const bEl = document.getElementById('popoutStatusBarBattery');
                    if (bEl) {
                        const iconName = msg.telemetry.charging ? 'battery-charging' : 'battery';
                        bEl.innerHTML = `<i data-lucide="${iconName}" class="status-bar-icon"></i> <span>${msg.telemetry.battery}%</span>`;
                        if (window.lucide && typeof window.lucide.createIcons === 'function') {
                            window.lucide.createIcons();
                        }
                    }
                }
            } else if (msg.type === 'skeleton_update') {
                if (msg.screenWidth) phoneWidth = msg.screenWidth;
                if (msg.screenHeight) phoneHeight = msg.screenHeight;
                document.documentElement.style.setProperty('--phone-ratio', `${phoneWidth} / ${phoneHeight}`);
                currentSkeletonTree = msg.tree;
                if (activeMode === 'skeleton') {
                    if (popoutPausedOverlay && isStreamRunning) {
                        popoutPausedOverlay.style.display = 'none';
                    }
                    renderSkeleton(msg.tree);
                }
            } else if (msg.type === 'skeleton_error') {
                if (activeMode === 'skeleton') {
                    if (pausedOverlayTitle) pausedOverlayTitle.textContent = 'Accessibility Notice';
                    if (pausedOverlayDesc) pausedOverlayDesc.textContent = msg.message || 'Accessibility Service not running on phone.';
                    if (popoutPausedOverlay) popoutPausedOverlay.style.display = 'flex';
                }
            } else if (msg.type === 'camera_info') {
                currentFacingFront = (msg.facing === 'front');
                updateCameraUI();
            } else if (msg.type === 'torch_status') {
                isTorchOn = !!msg.enabled;
                updateTorchUI();
            } else if (msg.type === 'quality_changed') {
                if (msg.quality && msg.quality.fps) {
                    activeFps = msg.quality.fps;
                    updateFpsButtons(activeFps);
                }
            } else if (msg.type === 'stream_error') {
                if (pausedOverlayTitle) pausedOverlayTitle.textContent = 'Camera Error';
                if (pausedOverlayDesc) pausedOverlayDesc.textContent = msg.message || 'Camera unavailable';
                if (popoutPausedOverlay) popoutPausedOverlay.style.display = 'flex';
            }
        } catch (_) {}
    };

    ws.onclose = () => {
        if (popoutStatus) popoutStatus.textContent = 'RECONNECTING...';
        if (popoutDot) popoutDot.className = 'dot connecting';
        setTimeout(initWebSocket, 2000);
    };
}

function sendToGateway(payload) {
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify(payload));
    }
}

// Dedicated Stream Start / Stop Control (Conserves Phone Battery & CPU)
function setStreamRunning(running) {
    isStreamRunning = running;
    const modeName = activeMode === 'camera' ? 'Camera' : (activeMode === 'video' ? 'Video' : 'Skeleton');

    if (isStreamRunning) {
        if (streamStatusIcon) streamStatusIcon.innerHTML = '<i data-lucide="pause" class="ui-icon-sm"></i>';
        if (streamStatusText) streamStatusText.textContent = `Stop ${modeName}`;
        if (btnToggleStream) btnToggleStream.classList.remove('paused');
        if (popoutPausedOverlay) popoutPausedOverlay.style.display = 'none';

        if (activeMode === 'video' || activeMode === 'camera') {
            sendToGateway({ action: 'resume_stream' });
        } else {
            sendToGateway({ action: 'get_skeleton_tree' });
            if (currentSkeletonTree) {
                renderSkeleton(currentSkeletonTree);
            }
        }
    } else {
        if (streamStatusIcon) streamStatusIcon.innerHTML = '<i data-lucide="play" class="ui-icon-sm"></i>';
        if (streamStatusText) streamStatusText.textContent = `Start ${modeName}`;
        if (btnToggleStream) btnToggleStream.classList.add('paused');
        if (popoutPausedOverlay) popoutPausedOverlay.style.display = 'flex';

        if (activeMode === 'video' || activeMode === 'camera') {
            sendToGateway({ action: 'pause_stream' });
        } else {
            // Clear canvas when skeleton stream paused
            ctx.clearRect(0, 0, canvas.width, canvas.height);
        }
    }
    if (window.lucide && typeof window.lucide.createIcons === 'function') {
        window.lucide.createIcons();
    }
}

if (btnToggleStream) {
    btnToggleStream.addEventListener('click', () => setStreamRunning(!isStreamRunning));
}
if (btnPopoutResumeStreamOverlay) {
    btnPopoutResumeStreamOverlay.addEventListener('click', () => setStreamRunning(true));
}

// Box Text Label Toggle (Skeleton Mode Only)
if (btnToggleBoxLabels) {
    btnToggleBoxLabels.addEventListener('click', () => {
        showBoxLabels = !showBoxLabels;
        const lblBoxText = document.getElementById('lblBoxText');
        if (lblBoxText) {
            lblBoxText.textContent = showBoxLabels ? 'Box Text: ON' : 'Box Text: OFF';
        }
        btnToggleBoxLabels.classList.toggle('btn-secondary', showBoxLabels);
        btnToggleBoxLabels.classList.toggle('btn-outline', !showBoxLabels);
        if (activeMode === 'skeleton' && currentSkeletonTree) {
            renderSkeleton(currentSkeletonTree);
        }
    });
}

// Framerate selector (Video Mode Only)
const fpsButtons = document.querySelectorAll('.btn-fps');
fpsButtons.forEach(btn => {
    btn.addEventListener('click', () => {
        const fps = parseInt(btn.getAttribute('data-fps'), 10);
        activeFps = fps;
        updateFpsButtons(fps);
        sendToGateway({
            action: 'set_quality',
            quality: { fps }
        });
    });
});

function updateFpsButtons(fps) {
    fpsButtons.forEach(b => {
        if (parseInt(b.getAttribute('data-fps'), 10) === fps) {
            b.classList.add('active');
        } else {
            b.classList.remove('active');
        }
    });
}

// Live Video & Camera Frame Streaming Render Engine (Pure Video - 100% Unobstructed)
let isDecodingFrame = false;
function renderVideoFrame(buffer) {
    if (!isStreamRunning || (activeMode !== 'video' && activeMode !== 'camera') || !videoCtx || !videoCanvas) return;
    if (isDecodingFrame) return; // Drop frame if browser is still decoding/painting previous frame
    isDecodingFrame = true;

    const blob = new Blob([buffer], { type: 'image/jpeg' });
    if (window.createImageBitmap) {
        createImageBitmap(blob).then((bitmap) => {
            if (activeMode === 'camera') {
                if (videoCanvas.width !== bitmap.width || videoCanvas.height !== bitmap.height) {
                    videoCanvas.width = bitmap.width;
                    videoCanvas.height = bitmap.height;
                    document.documentElement.style.setProperty('--phone-ratio', `${bitmap.width} / ${bitmap.height}`);
                }
            } else {
                if (videoCanvas.width !== phoneWidth || videoCanvas.height !== phoneHeight) {
                    videoCanvas.width = phoneWidth;
                    videoCanvas.height = phoneHeight;
                }
            }
            videoCtx.drawImage(bitmap, 0, 0, videoCanvas.width, videoCanvas.height);
            bitmap.close();
            isDecodingFrame = false;
        }).catch(() => {
            isDecodingFrame = false;
            fallbackImageRender(blob);
        });
    } else {
        isDecodingFrame = false;
        fallbackImageRender(blob);
    }
}

function fallbackImageRender(blob) {
    const url = URL.createObjectURL(blob);
    const img = new Image();
    img.onload = () => {
        if (activeMode === 'camera') {
            if (videoCanvas.width !== img.width || videoCanvas.height !== img.height) {
                videoCanvas.width = img.width;
                videoCanvas.height = img.height;
                document.documentElement.style.setProperty('--phone-ratio', `${img.width} / ${img.height}`);
            }
        } else {
            if (videoCanvas.width !== phoneWidth || videoCanvas.height !== phoneHeight) {
                videoCanvas.width = phoneWidth;
                videoCanvas.height = phoneHeight;
            }
        }
        videoCtx.drawImage(img, 0, 0, videoCanvas.width, videoCanvas.height);
        URL.revokeObjectURL(url);
    };
    img.src = url;
}

// Skeleton Rendering Engine (Only drawn in Skeleton Mode)
function renderSkeleton(tree) {
    if (!tree) return;
    if (canvas.width !== phoneWidth || canvas.height !== phoneHeight) {
        canvas.width = phoneWidth;
        canvas.height = phoneHeight;
    }

    ctx.clearRect(0, 0, canvas.width, canvas.height);

    // In pure Video mode or when paused: NEVER render any wireframe or text boxes
    if (activeMode === 'video' || !isStreamRunning) {
        return;
    }

    // In Skeleton mode: draw sleek dark background
    ctx.fillStyle = '#0a0e17';
    ctx.fillRect(0, 0, canvas.width, canvas.height);

    drawNode(tree, null);
}

function drawNode(node, parentText) {
    if (!node || !node.bounds) return;

    const [left, top, right, bottom] = node.bounds;
    const width = right - left;
    const height = bottom - top;
    const isFullScreen = width >= phoneWidth - 10 && height >= phoneHeight - 10;

    let nodeText = (node.text || '').trim();
    const isDuplicateText = parentText && nodeText === parentText;

    if (!isFullScreen && width > 0 && height > 0) {
        if (node.isClickable) {
            ctx.fillStyle = 'rgba(37, 99, 235, 0.15)';
            ctx.strokeStyle = '#3b82f6';
            ctx.lineWidth = 3;
            roundRect(ctx, left, top, width, height, 12, true, true);
        } else if (node.isEditable) {
            ctx.fillStyle = 'rgba(6, 182, 212, 0.15)';
            ctx.strokeStyle = '#06b6d4';
            ctx.lineWidth = 3;
            roundRect(ctx, left, top, width, height, 8, true, true);
        } else if (nodeText.length > 0) {
            ctx.fillStyle = 'rgba(255, 255, 255, 0.04)';
            ctx.strokeStyle = 'rgba(255, 255, 255, 0.1)';
            ctx.lineWidth = 1;
            roundRect(ctx, left, top, width, height, 6, true, true);
        }

        const shouldRenderText = node.isClickable ? showBoxLabels : true;

        if (shouldRenderText && nodeText.length > 0 && !isDuplicateText) {
            ctx.fillStyle = node.isClickable ? '#93c5fd' : (node.isEditable ? '#67e8f9' : '#e2e8f0');
            const fontSize = Math.max(22, Math.min(36, Math.floor(height * 0.45)));
            ctx.font = `600 ${fontSize}px "Plus Jakarta Sans", sans-serif`;
            ctx.textBaseline = 'middle';
            ctx.fillText(nodeText, left + 14, top + height / 2, width - 20);
        }
    }

    if (node.children && Array.isArray(node.children)) {
        node.children.forEach(child => drawNode(child, nodeText || parentText));
    }
}

function roundRect(ctx, x, y, width, height, radius, fill, stroke) {
    ctx.beginPath();
    ctx.moveTo(x + radius, y);
    ctx.lineTo(x + width - radius, y);
    ctx.quadraticCurveTo(x + width, y, x + width, y + radius);
    ctx.lineTo(x + width, y + height - radius);
    ctx.quadraticCurveTo(x + width, y + height, x + width - radius, y + height);
    ctx.lineTo(x + radius, y + height);
    ctx.quadraticCurveTo(x, y + height, x, y + height - radius);
    ctx.lineTo(x, y + radius);
    ctx.quadraticCurveTo(x, y, x + radius, y);
    ctx.closePath();
    if (fill) ctx.fill();
    if (stroke) ctx.stroke();
}

// Touch & Mouse Handling for Remote Interaction
function getPhoneCoordinates(event, element) {
    const rect = element.getBoundingClientRect();
    const scaleX = phoneWidth / rect.width;
    const scaleY = phoneHeight / rect.height;
    return {
        x: Math.round((event.clientX - rect.left) * scaleX),
        y: Math.round((event.clientY - rect.top) * scaleY)
    };
}

viewport.addEventListener('mousedown', (e) => {
    isMouseDown = true;
    const coords = getPhoneCoordinates(e, viewport);
    dragStartX = coords.x;
    dragStartY = coords.y;
    dragStartTime = Date.now();
});

viewport.addEventListener('mouseup', (e) => {
    if (!isMouseDown) return;
    isMouseDown = false;
    const coords = getPhoneCoordinates(e, viewport);
    const deltaX = coords.x - dragStartX;
    const deltaY = coords.y - dragStartY;
    const dist = Math.sqrt(deltaX * deltaX + deltaY * deltaY);
    const duration = Math.max(50, Date.now() - dragStartTime);

    if (dist < 20) {
        sendToGateway({ action: 'touch_tap', x: dragStartX, y: dragStartY });
    } else {
        sendToGateway({
            action: 'touch_swipe',
            startX: dragStartX,
            startY: dragStartY,
            endX: coords.x,
            endY: coords.y,
            durationMs: Math.min(500, duration)
        });
    }
});

// Camera Mode Controls
if (btnViewerFlipCam) {
    btnViewerFlipCam.addEventListener('click', () => {
        sendToGateway({ action: 'switch_camera' });
    });
}

if (btnViewerToggleTorch) {
    btnViewerToggleTorch.addEventListener('click', () => {
        sendToGateway({ action: 'toggle_torch' });
    });
}

if (btnViewerSnapshot) {
    btnViewerSnapshot.addEventListener('click', () => {
        if (!videoCanvas) return;
        try {
            const link = document.createElement('a');
            link.download = `anolink-camera-${Date.now()}.jpg`;
            link.href = videoCanvas.toDataURL('image/jpeg', 0.95);
            link.click();
        } catch (err) {
            console.error('Snapshot failed:', err);
        }
    });
}

function updateCameraUI() {
    if (lblViewerLens) {
        lblViewerLens.textContent = currentFacingFront ? 'Switch Lens (Front)' : 'Switch Lens (Rear)';
    }
    if (btnViewerToggleTorch) {
        btnViewerToggleTorch.disabled = currentFacingFront;
        btnViewerToggleTorch.style.opacity = currentFacingFront ? '0.35' : '1';
        btnViewerToggleTorch.title = currentFacingFront ? 'Flashlight unavailable on front lens' : 'Toggle Flashlight';
    }
}

function updateTorchUI() {
    if (lblViewerTorch) {
        lblViewerTorch.textContent = isTorchOn ? 'Flashlight: ON' : 'Flashlight: OFF';
    }
    if (btnViewerToggleTorch) {
        btnViewerToggleTorch.classList.toggle('btn-secondary', isTorchOn);
        btnViewerToggleTorch.classList.toggle('btn-outline', !isTorchOn);
    }
}

// Floating Navigation Controls
document.getElementById('btnPopoutBack').addEventListener('click', () => sendToGateway({ action: 'key_nav', key: 'BACK' }));
document.getElementById('btnPopoutHome').addEventListener('click', () => sendToGateway({ action: 'key_nav', key: 'HOME' }));
document.getElementById('btnPopoutRecents').addEventListener('click', () => sendToGateway({ action: 'key_nav', key: 'RECENTS' }));
document.getElementById('btnPopoutNotif').addEventListener('click', () => sendToGateway({ action: 'key_nav', key: 'NOTIFICATIONS' }));
document.getElementById('btnPopoutLock').addEventListener('click', () => sendToGateway({ action: 'key_nav', key: 'LOCK' }));

// Text Typing
function sendText() {
    if (!textInput) return;
    const val = textInput.value.trim();
    if (!val) return;
    sendToGateway({ action: 'input_text', text: val });
    textInput.value = '';
}

if (btnPopoutSendText) btnPopoutSendText.addEventListener('click', sendText);
if (textInput) {
    textInput.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') sendText();
    });
}

// Telemetry Refresh
document.getElementById('btnPopoutRefresh').addEventListener('click', () => {
    sendToGateway({ action: 'refresh_telemetry' });
});

function updatePopoutClock() {
    const now = new Date();
    const timeStr = now.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: false });
    const clockEl = document.getElementById('popoutClock');
    if (clockEl) clockEl.textContent = timeStr;
}
setInterval(updatePopoutClock, 5000);
updatePopoutClock();

if (window.lucide && typeof window.lucide.createIcons === 'function') {
    window.lucide.createIcons();
}

initWebSocket();
