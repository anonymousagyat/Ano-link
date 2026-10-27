/**
 * AnoLink — Standalone Windows 11 File Explorer Controller
 * Runs in its own dedicated browser tab with 100% monitor real estate.
 */

let ws = null;
let currentPath = '/storage/emulated/0';
let currentFilesList = [];
let fileFilterQuery = '';
let fileSortColumn = 'name';
let fileSortDirection = 'asc';
let selectedFilePath = null;
let activeTransfersCount = 0;

// History Stack (Back / Forward)
const historyStack = [];
let historyIndex = -1;

// -------------------------------------------------------------
// Initialization & WebSocket Gateway Connection
// -------------------------------------------------------------
document.addEventListener('DOMContentLoaded', () => {
    initWebSocket();
    setupEventListeners();
    pushHistory(currentPath);
});

function initWebSocket() {
    const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${wsProtocol}//${window.location.host}/ws-browser`;

    ws = new WebSocket(wsUrl);

    ws.onopen = () => {
        updateConnStatus(true, 'Connected');
        // Request initial directory
        requestDirectory(currentPath);
    };

    ws.onmessage = (event) => {
        if (event.data instanceof ArrayBuffer) return; // Binary ignored
        try {
            const msg = JSON.parse(event.data);
            handleServerMessage(msg);
        } catch (e) {
            console.error('Error parsing gateway message:', e);
        }
    };

    ws.onclose = () => {
        updateConnStatus(false, 'Disconnected · Reconnecting...');
        setTimeout(initWebSocket, 2000);
    };

    ws.onerror = (err) => {
        console.error('Gateway WebSocket error:', err);
    };
}

function sendToGateway(payload) {
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify(payload));
    }
}

function updateConnStatus(online, text) {
    const pill = document.getElementById('connStatusPill');
    const label = document.getElementById('connStatusText');
    if (label) label.textContent = text;
    if (pill) {
        if (online) {
            pill.style.color = '#34D399';
            pill.style.background = 'rgba(16, 185, 129, 0.1)';
            pill.style.borderColor = 'rgba(16, 185, 129, 0.25)';
        } else {
            pill.style.color = '#F87171';
            pill.style.background = 'rgba(239, 68, 68, 0.1)';
            pill.style.borderColor = 'rgba(239, 68, 68, 0.25)';
        }
    }
}

// -------------------------------------------------------------
// Message Dispatcher
// -------------------------------------------------------------
function handleServerMessage(msg) {
    switch (msg.type) {
        case 'initial_state':
        case 'status_change':
            if (msg.state === 'CONNECTED') {
                updateConnStatus(true, 'Phone Connected');
            } else if (msg.state === 'DISCONNECTED') {
                updateConnStatus(false, 'Phone Disconnected');
            }
            if (msg.phoneConfig && msg.phoneConfig.deviceName) {
                const devEl = document.getElementById('lblDeviceName');
                if (devEl) devEl.textContent = msg.phoneConfig.deviceName;
            }
            if (msg.telemetry) updateStorageTelemetry(msg.telemetry);
            break;

        case 'telemetry_update':
            if (msg.telemetry) updateStorageTelemetry(msg.telemetry);
            break;

        case 'file_list':
            handleFileListResponse(msg.path, msg.files, msg.allFilesAccess);
            break;
    }
}

function updateStorageTelemetry(telemetry) {
    if (!telemetry) return;
    let freeGb, totalGb;
    if (telemetry.storage && telemetry.storage.total > 0) {
        freeGb = (telemetry.storage.free / (1024 * 1024 * 1024)).toFixed(1);
        totalGb = (telemetry.storage.total / (1024 * 1024 * 1024)).toFixed(1);
    } else if (telemetry.totalStorageGb !== undefined) {
        freeGb = Number(telemetry.freeStorageGb).toFixed(1);
        totalGb = Number(telemetry.totalStorageGb).toFixed(1);
    } else {
        return;
    }
    const totalNum = parseFloat(totalGb);
    const freeNum = parseFloat(freeGb);
    if (totalNum > 0) {
        const usedPercent = Math.max(0, Math.min(100, Math.round(((totalNum - freeNum) / totalNum) * 100)));
        const lblPercent = document.getElementById('lblStoragePercent');
        const barFill = document.getElementById('storageBarFill');
        const caption = document.getElementById('lblStorageCaption');

        if (lblPercent) lblPercent.textContent = `${usedPercent}%`;
        if (barFill) barFill.style.width = `${usedPercent}%`;
        if (caption) caption.textContent = `${freeGb} GB free of ${totalGb} GB`;
    }
}

// -------------------------------------------------------------
// Directory & Path Navigation
// -------------------------------------------------------------
function requestDirectory(path) {
    currentPath = path || '/storage/emulated/0';
    const addressInput = document.getElementById('addressPathInput');
    if (addressInput) addressInput.value = currentPath;

    sendToGateway({ action: 'list_dir', path: currentPath });
}

function pushHistory(path) {
    if (historyStack[historyIndex] === path) return;
    historyIndex++;
    historyStack.splice(historyIndex, historyStack.length - historyIndex, path);
    updateHistoryButtonsUI();
}

function updateHistoryButtonsUI() {
    const btnBack = document.getElementById('btnHistoryBack');
    const btnForward = document.getElementById('btnHistoryForward');
    if (btnBack) btnBack.disabled = historyIndex <= 0;
    if (btnForward) btnForward.disabled = historyIndex >= historyStack.length - 1;
}

function handleFileListResponse(path, files, allFilesAccess) {
    currentPath = path;
    currentFilesList = files || [];
    selectedFilePath = null;

    const addressInput = document.getElementById('addressPathInput');
    if (addressInput) addressInput.value = currentPath;

    highlightSidebarItem(currentPath);
    renderFilesTable();
}

function highlightSidebarItem(path) {
    document.querySelectorAll('.sidebar-item').forEach(item => {
        const itemPath = item.getAttribute('data-path');
        if (itemPath === path) {
            item.classList.add('active');
        } else {
            item.classList.remove('active');
        }
    });
}

// -------------------------------------------------------------
// Windows 11 File Icon & Type Mappers
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
        'mp3': 'MP3 Audio',
        'm4a': 'M4A Audio',
        'wav': 'WAV Audio',
        'flac': 'FLAC Audio',
        'pdf': 'PDF Document',
        'doc': 'Word Document',
        'docx': 'Word Document',
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
        'iso': 'Disc Image File',
        'exe': 'Executable Application',
        'msi': 'Windows Installer Package',
        'bat': 'Windows Batch File',
        'cmd': 'Windows Command Script',
        'ps1': 'PowerShell Script',
        'txt': 'Text Document',
        'log': 'Log File',
        'ini': 'Configuration File',
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
    } else if (['mp4', 'mkv', 'avi', 'mov', 'webm'].includes(ext)) {
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

function formatBytes(bytes) {
    if (!bytes || bytes <= 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
}

// -------------------------------------------------------------
// Table Rendering & Sorting
// -------------------------------------------------------------
function sortFiles(files, column, direction) {
    const factor = direction === 'asc' ? 1 : -1;
    return [...files].sort((a, b) => {
        // Windows Explorer Rule: Folders always stay at top
        if (a.is_dir !== b.is_dir) {
            return a.is_dir ? -1 : 1;
        }

        switch (column) {
            case 'name':
                return a.name.localeCompare(b.name, undefined, { numeric: true, sensitivity: 'base' }) * factor;
            case 'modified':
                return ((a.modified || 0) - (b.modified || 0)) * factor;
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

function renderFilesTable() {
    const tbody = document.getElementById('explorerTbody');
    if (!tbody) return;

    if (!currentFilesList || currentFilesList.length === 0) {
        tbody.innerHTML = `
            <div class="empty-canvas-state">
                <img src="/icons/win11/folder.svg" alt="">
                <span>This folder is empty</span>
            </div>
        `;
        updateStatusBar(0);
        return;
    }

    let files = currentFilesList;
    if (fileFilterQuery) {
        files = files.filter(f => f.name.toLowerCase().includes(fileFilterQuery));
        if (files.length === 0) {
            tbody.innerHTML = `
                <div class="empty-canvas-state">
                    <img src="/icons/win11/folder.svg" alt="">
                    <span>No items match "${fileFilterQuery}"</span>
                </div>
            `;
            updateStatusBar(0);
            return;
        }
    }

    const sorted = sortFiles(files, fileSortColumn, fileSortDirection);
    tbody.innerHTML = '';

    sorted.forEach(file => {
        const row = document.createElement('div');
        row.className = `explorer-row ${file.is_dir ? 'is-folder' : 'is-file'}`;
        if (selectedFilePath === file.path) row.classList.add('selected');

        const iconHtml = getFileIcon(file);
        const typeStr = getFileType(file);
        const dateStr = formatModifiedDate(file.modified);
        const sizeStr = file.is_dir ? '' : formatBytes(file.size);

        row.innerHTML = `
            <div class="col-name" title="${file.name}">
                <div class="file-icon-wrapper">${iconHtml}</div>
                <span class="file-name-text">${file.name}</span>
            </div>
            <div class="col-date">${dateStr}</div>
            <div class="col-type">${typeStr}</div>
            <div class="col-size">${sizeStr}</div>
            <div class="col-actions">
                ${!file.is_dir ? `
                    <button class="btn-action-dl dl-btn" title="Download ${file.name}">
                        <i data-lucide="download" class="ui-icon-xs"></i> Download
                    </button>
                ` : ''}
            </div>
        `;

        // Row Selection
        row.addEventListener('click', (e) => {
            if (e.target.tagName !== 'BUTTON') {
                document.querySelectorAll('.explorer-row').forEach(r => r.classList.remove('selected'));
                row.classList.add('selected');
                selectedFilePath = file.path;
                updateSelectedStatus(file);
            }
        });

        // Double click to enter directory
        if (file.is_dir) {
            row.addEventListener('dblclick', () => {
                pushHistory(file.path);
                requestDirectory(file.path);
            });
        } else {
            const dlBtn = row.querySelector('.dl-btn');
            if (dlBtn) {
                dlBtn.addEventListener('click', (e) => {
                    e.stopPropagation();
                    downloadFile(file.path, file.name, file.size);
                });
            }
        }

        tbody.appendChild(row);
    });

    updateStatusBar(sorted.length);
    if (window.lucide && typeof window.lucide.createIcons === 'function') {
        window.lucide.createIcons();
    }
}

function updateStatusBar(count) {
    const statusCount = document.getElementById('statusItemCount');
    if (statusCount) statusCount.textContent = `${count} ${count === 1 ? 'item' : 'items'}`;
}

function updateSelectedStatus(file) {
    const statusSelected = document.getElementById('statusSelectedCount');
    if (!statusSelected) return;
    if (file) {
        statusSelected.textContent = file.is_dir ? '1 folder selected' : `1 item selected (${formatBytes(file.size)})`;
    } else {
        statusSelected.textContent = '';
    }
}

// -------------------------------------------------------------
// Downloads & Transfers
// -------------------------------------------------------------
function downloadFile(filePath, fileName, fileSize) {
    activeTransfersCount++;
    const countEl = document.getElementById('countTransfers');
    if (countEl) countEl.textContent = activeTransfersCount;

    const drawer = document.getElementById('downloadDrawer');
    if (drawer) drawer.style.display = 'flex';

    const list = document.getElementById('drawerDownloadsList');
    if (list && list.querySelector('small')) list.innerHTML = '';

    const item = document.createElement('div');
    item.style.cssText = 'background: rgba(0,0,0,0.3); border: 1px solid var(--win-border); border-radius: 8px; padding: 10px; margin-bottom: 8px;';
    item.innerHTML = `
        <div style="display: flex; justify-content: space-between; font-size: 12px; margin-bottom: 6px;">
            <strong style="color: #FFFFFF; max-width: 200px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;">${fileName}</strong>
            <span class="dl-status" style="color: var(--amber-primary); font-weight: 700;">Downloading...</span>
        </div>
        <div style="background: #1e293b; border-radius: 999px; height: 5px; overflow: hidden; margin-bottom: 6px;">
            <div class="dl-bar" style="width: 100%; height: 100%; background: linear-gradient(90deg, #F59E0B, #D97706); animation: pulse 1s infinite alternate;"></div>
        </div>
        <div style="display: flex; justify-content: space-between; font-size: 10.5px; color: var(--slate-text);">
            <span>${formatBytes(fileSize)}</span>
            <span>Local Chunk Stream</span>
        </div>
    `;
    if (list) list.prepend(item);

    // Trigger direct HTTP streaming download
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
            statusEl.style.color = '#34D399';
        }
        const bar = item.querySelector('.dl-bar');
        if (bar) {
            bar.style.animation = 'none';
            bar.style.background = '#10B981';
        }
    }, 1200);
}

// -------------------------------------------------------------
// Event Listeners (Toolbar, Address Bar, Search, Sidebar)
// -------------------------------------------------------------
function setupEventListeners() {
    // History Back
    document.getElementById('btnHistoryBack')?.addEventListener('click', () => {
        if (historyIndex > 0) {
            historyIndex--;
            requestDirectory(historyStack[historyIndex]);
            updateHistoryButtonsUI();
        }
    });

    // History Forward
    document.getElementById('btnHistoryForward')?.addEventListener('click', () => {
        if (historyIndex < historyStack.length - 1) {
            historyIndex++;
            requestDirectory(historyStack[historyIndex]);
            updateHistoryButtonsUI();
        }
    });

    // Nav Up
    document.getElementById('btnNavUp')?.addEventListener('click', () => {
        navigateUp();
    });

    // Nav Refresh
    document.getElementById('btnNavRefresh')?.addEventListener('click', () => {
        requestDirectory(currentPath);
    });

    // Address Bar Enter / Go
    const addressInput = document.getElementById('addressPathInput');
    if (addressInput) {
        addressInput.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') {
                const target = addressInput.value.trim();
                if (target) {
                    pushHistory(target);
                    requestDirectory(target);
                }
            }
        });
    }

    document.getElementById('btnGoPath')?.addEventListener('click', () => {
        const target = addressInput ? addressInput.value.trim() : '';
        if (target) {
            pushHistory(target);
            requestDirectory(target);
        }
    });

    // Copy Path Button
    document.getElementById('btnCopyPath')?.addEventListener('click', () => {
        if (addressInput) {
            navigator.clipboard.writeText(addressInput.value).then(() => {
                const btn = document.getElementById('btnCopyPath');
                if (btn) btn.style.color = '#34D399';
                setTimeout(() => { if (btn) btn.style.color = ''; }, 1200);
            });
        }
    });

    // Real-Time Search Filter
    const searchInput = document.getElementById('searchFilterInput');
    if (searchInput) {
        searchInput.addEventListener('input', (e) => {
            fileFilterQuery = e.target.value.trim().toLowerCase();
            renderFilesTable();
        });
    }

    // Sidebar Quick Access Navigation
    document.querySelectorAll('.sidebar-item').forEach(item => {
        item.addEventListener('click', () => {
            const path = item.getAttribute('data-path');
            if (path) {
                pushHistory(path);
                requestDirectory(path);
            }
        });
    });

    // Table Header Sorting
    document.querySelectorAll('.explorer-thead .th-col[data-sort]').forEach(colEl => {
        colEl.addEventListener('click', () => {
            const col = colEl.getAttribute('data-sort');
            if (fileSortColumn === col) {
                fileSortDirection = fileSortDirection === 'asc' ? 'desc' : 'asc';
            } else {
                fileSortColumn = col;
                fileSortDirection = col === 'modified' ? 'desc' : 'asc';
            }
            updateSortCaretUI();
            renderFilesTable();
        });
    });

    // Transfers Drawer Toggle
    document.getElementById('statusToggleDownloads')?.addEventListener('click', () => {
        const drawer = document.getElementById('downloadDrawer');
        if (drawer) {
            drawer.style.display = drawer.style.display === 'flex' ? 'none' : 'flex';
        }
    });

    document.getElementById('btnCloseDrawer')?.addEventListener('click', () => {
        const drawer = document.getElementById('downloadDrawer');
        if (drawer) drawer.style.display = 'none';
    });

    // Keyboard Shortcuts
    window.addEventListener('keydown', (e) => {
        if (e.target.tagName === 'INPUT') return;

        if (e.key === 'Backspace' || (e.altKey && e.key === 'ArrowUp')) {
            e.preventDefault();
            navigateUp();
        } else if (e.altKey && e.key === 'ArrowLeft') {
            e.preventDefault();
            document.getElementById('btnHistoryBack')?.click();
        } else if (e.altKey && e.key === 'ArrowRight') {
            e.preventDefault();
            document.getElementById('btnHistoryForward')?.click();
        } else if (e.key === 'F5') {
            e.preventDefault();
            requestDirectory(currentPath);
        }
    });
}

function navigateUp() {
    const parts = currentPath.split('/').filter(Boolean);
    if (parts.length > 1) {
        parts.pop();
        const parentPath = '/' + parts.join('/');
        pushHistory(parentPath);
        requestDirectory(parentPath);
    }
}

function updateSortCaretUI() {
    ['name', 'modified', 'type', 'size'].forEach(col => {
        const th = document.querySelector(`.explorer-thead .th-col[data-sort="${col}"]`);
        const caret = document.getElementById(`sortCaret${col.charAt(0).toUpperCase() + col.slice(1)}`);
        if (th && caret) {
            if (fileSortColumn === col) {
                th.classList.add('active');
                caret.textContent = fileSortDirection === 'asc' ? '▲' : '▼';
            } else {
                th.classList.remove('active');
                caret.textContent = '';
            }
        }
    });
}
