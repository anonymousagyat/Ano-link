# AnoLink — Comprehensive Project Context & Agent Memory

## 1. User Preferences & Critical Operational Rules

### The `~*` Prompt Prefix (Strict Read-Only / Advisory Mode)
- **CRITICAL RULE**: Whenever the user's prompt begins with **`~*`** (tilde asterisk):
  - **DO NOT EDIT, CREATE, MODIFY, OR DELETE ANY PROJECT FILES**. Leave all files in the project completely as they are.
  - **NO MUTATING TOOLS OR COMMANDS**: Do not call `replace_file_content`, `write_to_file`, or execute build/mutation commands.
  - **READ-ONLY & ADVISORY ONLY**: You may read files, investigate, analyze bugs, explain root causes, answer questions, and discuss proposals strictly in chat.
  - **MODIFICATION PERMISSION**: Only proceed with editing or modifying project files when the user explicitly instructs to proceed or submits an explicit instruction without `~*`.

### UI & Design Constraints (Zero Cyan Rule)
- **NO CYAN / AQUA / TEAL**: Never use cyan, light blue, or teal gradients/accents in any Android UI XML, layouts, or PC dashboards.
- **Allowed Color Palette**:
  - Primary Accent: **Warm Amber** (`#F59E0B`, `#D97706`)
  - Secondary Accent: **Lavender / Violet** (`#C4B5FD`, `#8B5CF6`)
  - Neutral / Card Surface: **Slate & Dark Zinc** (`#0F172A`, `#1E293B`, `#334155`, `#94A3B8`)
- **Process Icons**:
  - Always use **high-resolution original native application logos**.
  - **NO EMOJIS** for app icons.
  - Process icons are extracted by `AnoLinkWinBridge.exe` from Windows `.exe` binaries, cached in `ano-link-webrtc/app_icons/`, and streamed as high-res base64 bitmaps.
- **Process Aggregation (Task Manager)**:
  - Multi-process applications (e.g. Brave with 15 instances, CMD with 10 instances, Antigravity) **MUST be aggregated into a single entry** displaying the instance count, total RAM, and total CPU.
  - Active user-facing applications appear under the **APPS** section.
  - System daemons and headless services appear under **BACKGROUND PROCESSES**.

---

## 2. Project Architecture & Network Design

### The Core Problem Solved
- **Constraint**: *"The phone and PC will never be on the same Wi-Fi."*
- **Network Reality**: The Android phone is on **Cellular 4G/5G** (Jio, Airtel, Vi) behind Carrier-Grade NAT (CGNAT) / Symmetric NAT. Mobile carriers aggressively kill idle UDP NAT mappings after 20–30 seconds. The PC is on Home Broadband / Wi-Fi.
- **Solution**: A **Dual-Engine Relay & Direct Traversal Architecture**:
  1. **Primary WAN Engine (Cloudflare Quick Tunnel)**:
     - `pc-controller/tools/cloudflared.exe` is managed automatically by `server.js`.
     - Creates an outbound HTTPS / WSS tunnel (`https://*.trycloudflare.com`).
     - Bypasses carrier CGNAT, firewalls, and port blocks with **100% reliability, zero accounts, and zero credit cards**.
     - Handles controls, task manager, audio, and **30 FPS desktop video streaming** over binary WebSocket payloads (`/ws-phone`).
  2. **Signaling Broker (Cloudflare Worker)**:
     - Host: `super-river-3234.parthmisar777.workers.dev`
     - Matches Phone and PC using a **6-digit One-Time Pairing PIN** (e.g., `359202`).
     - When the phone enters the PIN, the worker immediately delivers the active `trycloudflare.com` tunnel URL (`tunnel_ready`).
  3. **Secondary LAN / STUN Engine (WebRTC)**:
     - Clean Google & Cloudflare STUN servers (`stun.l.google.com:19302`, `stun.cloudflare.com:3478`).
     - Used if devices ever share a direct network. Broken TURN servers (`openrelayproject`) were permanently removed to prevent `libjuice` C++ authentication errors.

---

## 3. Component Breakdown

### A. PC Controller (`pc-controller/`)
- **`server.js`**:
  - Express & HTTP server on `http://localhost:9090`.
  - Spawns `tools/AnoLinkWinBridge.exe` (Win32 API bridge).
  - Spawns `tools/cloudflared.exe` (`cloudflared tunnel --url http://localhost:9090`).
  - WebSockets:
    - `/ws-browser`: Local PC dashboard and telemetry.
    - `/ws-phone`: Android phone remote connection for control & 30 FPS video frames.
  - `config.json`: Stores persistent `pairingPin`, cryptographic `deviceToken` (for auto-reconnect), and 30 FPS settings.
- **`tools/AnoLinkWinBridge.exe` (Compiled from `AnoLinkWinBridge.cs`)**:
  - GDI / Desktop Duplication 30 FPS screen capture with JPEG compression.
  - Native Win32 input synthesis (mouse move, click, drag, scroll, keyboard shortcuts).
  - Process enumeration, window focus tracking, taskbar synchronization, power/battery state.
  - High-res icon extraction from Windows executable resources.
- **`tools/cloudflared.exe`**:
  - Official Cloudflare Tunnel binary (version 2026.9.3).
  - Creates the zero-config WAN tunnel on startup.

### B. Android App (`android-app/`)
- **Package**: `com.tailconnect.app` / `com.anolink.app`
- **Key Files**:
  - `AnoWebRtcManager.kt`: The unified connection manager. Connects to Cloudflare Quick Tunnel via OkHttp WebSocket (`wss://*.trycloudflare.com/ws-phone`) with a 10-second heartbeat ping. Fallback to WebRTC DataChannel if needed.
  - `PcRemoteClient.kt`: Central client facade. Coordinates connection, authentication, ping watchdog, process actions, and telemetry callbacks.
  - `MainActivity.kt`: Dashboard showing paired PC name, live ping dot, system stats, quick tiles.
  - `PcTaskManagerActivity.kt`: Aggregated process management with high-res icons and Kill button.
  - `PcRemoteScreenActivity.kt`: 30 FPS interactive desktop stream with low-latency touch controls.
  - `PcStartMenuActivity.kt`: PC Start Menu applications grid.
  - `PcHardwareActivity.kt`: Real-time gauges for CPU, RAM, Disk, and Network usage.

---

## 4. Key Commands & Recipes

### Running the PC Controller
```powershell
cd D:\Documents\PROJECTS\ano-link-webrtc\pc-controller
node server.js
```

### Compiling the Android APK
```powershell
cd D:\Documents\PROJECTS\ano-link-webrtc\android-app
.\gradlew.bat assembleDebug
```
*Output APK*: `android-app/app/build/outputs/apk/debug/app-debug.apk`

---

## 5. Originating Conversation Memory
- **Original Conversation ID**: `55aee8ec-36ed-4d9d-beb9-9c8d745a14b0`
- **Full Logs Location**: `C:\Users\parth\.gemini\antigravity-cli\brain\55aee8ec-36ed-4d9d-beb9-9c8d745a14b0\.system_generated\logs\transcript.jsonl`
