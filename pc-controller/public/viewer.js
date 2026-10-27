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

// Parse mode directly from URL query param (?mode=silent, ?mode=video, ?mode=camera, or ?mode=skeleton)
const urlParams = new URLSearchParams(window.location.search);
const rawMode = urlParams.get('mode');
let activeMode = rawMode === 'silent' ? 'silent' : (rawMode === 'video' ? 'video' : (rawMode === 'camera' ? 'camera' : 'skeleton'));

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
    if (activeMode === 'silent') {
        document.title = 'Ano-Link - Silent Screen Share';
        if (modeBadge) {
            modeBadge.innerHTML = '<i data-lucide="zap" class="ui-icon-xs"></i> <span>SILENT SCREEN</span>';
            modeBadge.className = 'mode-badge silent';
        }
        if (modeSubText) {
            modeSubText.textContent = 'Zero-Prompt Fluent Mirror (~20 FPS)';
        }
        if (viewport) {
            viewport.classList.add('mode-silent');
            viewport.classList.remove('mode-video');
            viewport.classList.remove('mode-camera');
        }
        if (videoControlsSection) videoControlsSection.style.display = 'none';
        if (skeletonControlsSection) skeletonControlsSection.style.display = 'none';
        if (cameraControlsSection) cameraControlsSection.style.display = 'none';
        if (videoCanvas) videoCanvas.style.display = 'block';
        if (canvas) canvas.style.display = 'none';
        if (streamStatusText) streamStatusText.textContent = 'Stop Silent Stream';
        if (pausedOverlayTitle) pausedOverlayTitle.textContent = 'Silent Stream Paused';
        if (pausedOverlayDesc) pausedOverlayDesc.textContent = 'Screen capture suspended to conserve phone battery.';
    } else if (activeMode === 'camera') {
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
            viewport.classList.remove('mode-silent');
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
            viewport.classList.remove('mode-silent');
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
            viewport.classList.remove('mode-silent');
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

    // Update active state on dock mode buttons
    const modeBtns = {
        silent: document.getElementById('btnModeSilent'),
        video: document.getElementById('btnModeVideo'),
        camera: document.getElementById('btnModeCamera'),
        skeleton: document.getElementById('btnModeSkeleton')
    };
    Object.entries(modeBtns).forEach(([m, btn]) => {
        if (!btn) return;
        if (m === activeMode) {
            btn.classList.add('active');
            btn.style.background = 'rgba(245, 158, 11, 0.25)';
            btn.style.borderColor = 'rgba(245, 158, 11, 0.6)';
            btn.style.color = '#fff';
        } else {
            btn.classList.remove('active');
            btn.style.background = '';
            btn.style.borderColor = '';
            btn.style.color = '';
        }
    });

    if (window.lucide && typeof window.lucide.createIcons === 'function') {
        window.lucide.createIcons();
    }
}

function switchMode(newMode) {
    if (activeMode === newMode) return;
    if (typeof isRecording !== 'undefined' && isRecording) {
        stopRecording();
    }
    activeMode = newMode;
    const url = new URL(window.location.href);
    url.searchParams.set('mode', newMode);
    window.history.replaceState({}, '', url.toString());
    setupModeUI();
    sendToGateway({ action: 'set_mode', mode: activeMode });
    if (activeMode === 'skeleton') {
        sendToGateway({ action: 'get_skeleton_tree' });
    }
}

// Bind dock mode buttons
const btnModeSilent = document.getElementById('btnModeSilent');
const btnModeVideo = document.getElementById('btnModeVideo');
const btnModeCamera = document.getElementById('btnModeCamera');
const btnModeSkeleton = document.getElementById('btnModeSkeleton');

if (btnModeSilent) btnModeSilent.addEventListener('click', () => switchMode('silent'));
if (btnModeVideo) btnModeVideo.addEventListener('click', () => switchMode('video'));
if (btnModeCamera) btnModeCamera.addEventListener('click', () => switchMode('camera'));
if (btnModeSkeleton) btnModeSkeleton.addEventListener('click', () => switchMode('skeleton'));

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
            if (activeMode === 'video' || activeMode === 'camera' || activeMode === 'silent') {
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
                if (msg.screenWidth) phoneWidth = msg.screenWidth;
                if (msg.screenHeight) phoneHeight = msg.screenHeight;
                document.documentElement.style.setProperty('--phone-ratio', `${phoneWidth} / ${phoneHeight}`);
                if (videoCanvas) {
                    videoCanvas.width = phoneWidth;
                    videoCanvas.height = phoneHeight;
                }
                if (canvas) {
                    canvas.width = phoneWidth;
                    canvas.height = phoneHeight;
                }
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
            } else if (msg.type === 'screen_metrics') {
                if (msg.screenWidth) phoneWidth = msg.screenWidth;
                if (msg.screenHeight) phoneHeight = msg.screenHeight;
                document.documentElement.style.setProperty('--phone-ratio', `${phoneWidth} / ${phoneHeight}`);
                if (videoCanvas) {
                    videoCanvas.width = phoneWidth;
                    videoCanvas.height = phoneHeight;
                }
                if (canvas) {
                    canvas.width = phoneWidth;
                    canvas.height = phoneHeight;
                }
            } else if (msg.type === 'skeleton_tree') {
                if (msg.screenWidth) phoneWidth = msg.screenWidth;
                if (msg.screenHeight) phoneHeight = msg.screenHeight;
                document.documentElement.style.setProperty('--phone-ratio', `${phoneWidth} / ${phoneHeight}`);
                if (videoCanvas) {
                    videoCanvas.width = phoneWidth;
                    videoCanvas.height = phoneHeight;
                }
                if (canvas) {
                    canvas.width = phoneWidth;
                    canvas.height = phoneHeight;
                }
                if (msg.tree) {
                    currentSkeletonTree = msg.tree;
                    if (activeMode === 'skeleton') {
                        renderSkeleton(msg.tree);
                    }
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
                if (videoCanvas) {
                    videoCanvas.width = phoneWidth;
                    videoCanvas.height = phoneHeight;
                }
                if (canvas) {
                    canvas.width = phoneWidth;
                    canvas.height = phoneHeight;
                }
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
                const title = activeMode === 'camera' ? 'Camera Error' : (activeMode === 'silent' ? 'Silent Stream Notice' : 'Stream Notice');
                if (pausedOverlayTitle) pausedOverlayTitle.textContent = title;
                if (pausedOverlayDesc) pausedOverlayDesc.textContent = msg.message || 'Stream unavailable';
                if (popoutPausedOverlay) popoutPausedOverlay.style.display = 'flex';
            } else if (msg.type === 'recording_status') {
                isRecording = !!msg.isRecording;
                updateRecordingUI(isRecording);
                if (msg.filename) lastRecordedFilename = msg.filename;
                if (msg.filePath) lastRecordedFilePath = msg.filePath;
            } else if (msg.type === 'recording_progress') {
                isRecording = true;
                updateRecordingUI(true);
                const recTimerText = document.getElementById('recTimerText');
                if (recTimerText) recTimerText.textContent = formatRecordTime(msg.durationSec || 0);
                const viewportRecTimer = document.getElementById('viewportRecTimer');
                if (viewportRecTimer) viewportRecTimer.textContent = `REC ${formatRecordTime(msg.durationSec || 0)}`;
                const recSizeText = document.getElementById('recSizeText');
                if (recSizeText && msg.sizeMb) recSizeText.textContent = msg.sizeMb;
            } else if (msg.type === 'recording_finished') {
                isRecording = false;
                updateRecordingUI(false);
                lastRecordedFilename = msg.filename || '';
                lastRecordedFilePath = msg.filePath || '';
                showRecordingToast(msg.filename || 'AnoLink_Recording.mkv', msg.sizeMb || '0.0 MB', msg.filePath || '');
            } else if (msg.type === 'recording_error') {
                isRecording = false;
                updateRecordingUI(false);
                alert('Recording Error: ' + (msg.message || 'Unknown server error'));
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
    const modeName = activeMode === 'silent' ? 'Silent Screen' : (activeMode === 'camera' ? 'Camera' : (activeMode === 'video' ? 'Video' : 'Skeleton'));

    if (isStreamRunning) {
        if (streamStatusIcon) streamStatusIcon.innerHTML = '<i data-lucide="pause" class="ui-icon-sm"></i>';
        if (streamStatusText) streamStatusText.textContent = `Stop ${modeName}`;
        if (btnToggleStream) btnToggleStream.classList.remove('paused');
        if (popoutPausedOverlay) popoutPausedOverlay.style.display = 'none';

        if (activeMode === 'video' || activeMode === 'camera' || activeMode === 'silent') {
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

        if (activeMode === 'video' || activeMode === 'camera' || activeMode === 'silent') {
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

// Live Video, Silent & Camera Frame Streaming Render Engine (Zero Lag, vSync Buffered)
let isDecodingFrame = false;
let pendingBitmap = null;
let isRafScheduled = false;
let nextFrameBuffer = null;

function scheduleVsyncDraw() {
    if (isRafScheduled) return;
    isRafScheduled = true;
    requestAnimationFrame(() => {
        isRafScheduled = false;
        if (!pendingBitmap || !videoCtx || !videoCanvas) return;
        const bitmap = pendingBitmap;
        pendingBitmap = null;

        if (activeMode === 'camera' || activeMode === 'silent') {
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
        videoCtx.clearRect(0, 0, videoCanvas.width, videoCanvas.height);
        videoCtx.drawImage(bitmap, 0, 0, videoCanvas.width, videoCanvas.height);
        bitmap.close();
    });
}

function renderVideoFrame(buffer) {
    if (!isStreamRunning || (activeMode !== 'video' && activeMode !== 'camera' && activeMode !== 'silent') || !videoCtx || !videoCanvas) return;
    if (isDecodingFrame) {
        nextFrameBuffer = buffer; // Buffer latest frame so it is never dropped
        return;
    }
    isDecodingFrame = true;

    const blob = new Blob([buffer], { type: 'image/jpeg' });
    if (window.createImageBitmap) {
        createImageBitmap(blob).then((bitmap) => {
            isDecodingFrame = false;
            if (pendingBitmap) {
                try { pendingBitmap.close(); } catch (_) {}
            }
            pendingBitmap = bitmap;
            scheduleVsyncDraw();

            if (nextFrameBuffer) {
                const nextBuf = nextFrameBuffer;
                nextFrameBuffer = null;
                renderVideoFrame(nextBuf);
            }
        }).catch(() => {
            isDecodingFrame = false;
            fallbackImageRender(blob);

            if (nextFrameBuffer) {
                const nextBuf = nextFrameBuffer;
                nextFrameBuffer = null;
                renderVideoFrame(nextBuf);
            }
        });
    } else {
        isDecodingFrame = false;
        fallbackImageRender(blob);
        if (nextFrameBuffer) {
            const nextBuf = nextFrameBuffer;
            nextFrameBuffer = null;
            renderVideoFrame(nextBuf);
        }
    }
}

function fallbackImageRender(blob) {
    const url = URL.createObjectURL(blob);
    const img = new Image();
    img.onload = () => {
        if (activeMode === 'camera' || activeMode === 'silent') {
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
        videoCtx.clearRect(0, 0, videoCanvas.width, videoCanvas.height);
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
function getActiveCanvas() {
    if (activeMode === 'skeleton') {
        return canvas;
    }
    return videoCanvas || canvas;
}

function getPhoneCoordinates(event) {
    const target = getActiveCanvas() || viewport;
    const rect = target.getBoundingClientRect();

    if (rect.width <= 0 || rect.height <= 0) {
        return { x: 0, y: 0, nx: 0, ny: 0 };
    }

    // Normalized coordinates [0.0, 1.0] relative to active canvas bounding box (eliminates 5px border offset)
    const rawNx = (event.clientX - rect.left) / rect.width;
    const rawNy = (event.clientY - rect.top) / rect.height;
    const nx = Math.min(1, Math.max(0, rawNx));
    const ny = Math.min(1, Math.max(0, rawNy));

    const x = Math.round(nx * phoneWidth);
    const y = Math.round(ny * phoneHeight);

    return { x, y, nx, ny };
}

let mouseDownClientX = 0;
let mouseDownClientY = 0;
let dragStartCoords = null;

viewport.addEventListener('mousedown', (e) => {
    isMouseDown = true;
    mouseDownClientX = e.clientX;
    mouseDownClientY = e.clientY;
    dragStartCoords = getPhoneCoordinates(e);
    dragStartX = dragStartCoords.x;
    dragStartY = dragStartCoords.y;
    dragStartTime = Date.now();
});

window.addEventListener('mouseup', (e) => {
    if (!isMouseDown) return;
    isMouseDown = false;

    const coords = getPhoneCoordinates(e);
    const clientDeltaX = e.clientX - mouseDownClientX;
    const clientDeltaY = e.clientY - mouseDownClientY;
    // Calculate distance in CSS monitor pixels, not phone coordinates!
    const clientDist = Math.hypot(clientDeltaX, clientDeltaY);
    const duration = Math.max(50, Date.now() - dragStartTime);

    // Standard touch slop: if mouse moved less than 10 CSS pixels, it is a deliberate TAP!
    if (clientDist < 10) {
        sendToGateway({
            action: 'touch_tap',
            x: dragStartX,
            y: dragStartY,
            nx: dragStartCoords ? dragStartCoords.nx : (dragStartX / phoneWidth),
            ny: dragStartCoords ? dragStartCoords.ny : (dragStartY / phoneHeight)
        });
    } else {
        sendToGateway({
            action: 'touch_swipe',
            startX: dragStartX,
            startY: dragStartY,
            endX: coords.x,
            endY: coords.y,
            startNx: dragStartCoords ? dragStartCoords.nx : (dragStartX / phoneWidth),
            startNy: dragStartCoords ? dragStartCoords.ny : (dragStartY / phoneHeight),
            endNx: coords.nx,
            endNy: coords.ny,
            durationMs: Math.min(1000, Math.max(100, duration))
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

// Screen Recording Control (PC-Side Direct MKV Engine)
let isRecording = false;
let lastRecordedFilename = '';
let lastRecordedFilePath = '';

function formatRecordTime(seconds) {
    const m = Math.floor(seconds / 60).toString().padStart(2, '0');
    const s = Math.floor(seconds % 60).toString().padStart(2, '0');
    return `${m}:${s}`;
}

function updateRecordingUI(recording) {
    const btnToggleRecord = document.getElementById('btnToggleRecord');
    const recordBtnText = document.getElementById('recordBtnText');
    const timerBadge = document.getElementById('recordingTimerBadge');
    const viewportBadge = document.getElementById('viewportRecBadge');

    if (btnToggleRecord) {
        btnToggleRecord.classList.toggle('recording', recording);
    }
    if (recordBtnText) {
        recordBtnText.textContent = recording ? 'Stop Recording' : 'Record Screen';
    }
    if (timerBadge) {
        timerBadge.style.display = recording ? 'flex' : 'none';
    }
    if (viewportBadge) {
        viewportBadge.style.display = recording ? 'flex' : 'none';
    }
    if (window.lucide && typeof window.lucide.createIcons === 'function') {
        window.lucide.createIcons();
    }
}

function startRecording() {
    sendToGateway({ action: 'start_recording' });
}

function stopRecording() {
    sendToGateway({ action: 'stop_recording' });
}

function showRecordingToast(filename, sizeMb, filePath) {
    const toast = document.getElementById('recToast');
    const toastTitle = document.getElementById('recToastTitle');
    const toastDesc = document.getElementById('recToastDesc');
    const btnOpenFolder = document.getElementById('btnRecToastOpenFolder');
    const btnPreview = document.getElementById('btnRecToastPreview');
    const btnClose = document.getElementById('btnRecToastClose');

    if (toastTitle) toastTitle.textContent = 'Recording Saved (MKV)';
    if (toastDesc) toastDesc.textContent = `${filename} (${sizeMb}) saved to PC recordings.`;

    if (btnOpenFolder) {
        btnOpenFolder.onclick = () => {
            fetch('/api/recordings/open', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ filePath })
            }).catch(() => {});
        };
    }

    if (btnPreview) {
        btnPreview.textContent = 'Download';
        btnPreview.onclick = () => {
            const a = document.createElement('a');
            a.href = `/api/recordings/download/${encodeURIComponent(filename)}`;
            a.download = filename;
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
        };
    }

    if (btnClose) {
        btnClose.onclick = () => {
            if (toast) toast.style.display = 'none';
        };
    }

    if (toast) {
        toast.style.display = 'flex';
        if (window.lucide && typeof window.lucide.createIcons === 'function') {
            window.lucide.createIcons();
        }
        setTimeout(() => {
            if (toast) toast.style.display = 'none';
        }, 12000);
    }
}

function openPreviewModal(blobUrl, filename, sizeMb) {
    const modal = document.getElementById('recPreviewModal');
    const video = document.getElementById('recPreviewVideo');
    const title = document.getElementById('recPreviewTitle');
    const info = document.getElementById('recPreviewInfo');
    const btnDownloadAgain = document.getElementById('btnRecPreviewDownloadAgain');
    const btnClose = document.getElementById('btnRecPreviewClose');

    if (title) title.textContent = filename;
    if (info) info.textContent = `Size: ${sizeMb}`;
    if (video) {
        video.src = blobUrl;
        video.play().catch(() => {});
    }

    if (btnDownloadAgain) {
        btnDownloadAgain.onclick = () => {
            const a = document.createElement('a');
            a.href = blobUrl;
            a.download = filename;
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
        };
    }

    const closeModal = () => {
        if (video) {
            video.pause();
            video.removeAttribute('src');
        }
        if (modal) modal.style.display = 'none';
    };

    if (btnClose) btnClose.onclick = closeModal;
    if (modal) {
        modal.onclick = (e) => {
            if (e.target === modal) closeModal();
        };
        modal.style.display = 'flex';
    }
}

// Bind recording buttons
const btnToggleRecord = document.getElementById('btnToggleRecord');
if (btnToggleRecord) {
    btnToggleRecord.addEventListener('click', () => {
        if (isRecording) {
            stopRecording();
        } else {
            startRecording();
        }
    });
}

const viewportRecBadge = document.getElementById('viewportRecBadge');
if (viewportRecBadge) {
    viewportRecBadge.addEventListener('click', () => {
        if (isRecording) {
            stopRecording();
        }
    });
}

window.addEventListener('beforeunload', () => {
    if (isRecording) {
        stopRecording();
    }
});

if (window.lucide && typeof window.lucide.createIcons === 'function') {
    window.lucide.createIcons();
}

initWebSocket();
