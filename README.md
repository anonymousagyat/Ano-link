# 🚀 Ano-Link — Cross-Device Workstation Bridge

<p align="center">
  <strong>Transform your Android smartphone and Windows PC into a unified, high-performance workstation.</strong><br>
  Zero ADB required &bull; Low-latency streaming &bull; Direct Win32 Input Injection &bull; Native Windows 11 Fluent UI
</p>

---

## ✨ Features

- **🖥️ Remote PC Screen Control**:
  - Centered floating landscape viewport with precision trackpad and pinpoint crosshair (`+`) virtual cursor.
  - Multi-touch gesture engine: single tap to click, double tap to double-click, long-press or two-finger tap to right-click, and smooth two-finger scrolling.
  - On-the-fly resolution switcher (`540p`, `480p`, `720p`) and live real-time latency ping monitor.
  - Standby frosted overlay with one-tap stream start/stop and 5-minute inactivity watchdog.

- **⌨️ Integrated Physical Windows Keyboard**:
  - Complete Windows physical keyboard drawer with sticky modifiers (`Shift`, `Ctrl`, `Alt`, `Win`).
  - Top text entry box: type sentences directly to the active PC window with instant **SEND** transmission.
  - Instant Windows shortcut tiles: **DESK** (`Win+D`) and **APPS** (`Alt+Tab`).

- **🚀 Windows 11 Taskbar Dock & Start Menu**:
  - Vertical right flank dock featuring active app tiles (48dp Fluent cards with window count badges).
  - Multi-window tab switcher: click any grouped app card to bring individual windows to the foreground.
  - Windows Start Menu tile opening your PC's native Start Menu.
  - Integrated system tray dock with live digital clock, Wi-Fi indicator, and battery pill.

- **📁 ZArchiver-Style PC File Explorer**:
  - Full drive navigation (`C:\`, `D:\`) with breadcrumbs and drive storage utilization bars.
  - Remote file execution: tap any file on PC to launch it in its default desktop application.
  - Direct file download: transfer files from PC drives straight to your Android phone storage.

- **⚡ Hardware Task Manager & Telemetry**:
  - Real-time CPU utilization meter with core counts and CPU model detection.
  - RAM usage breakdown (Used vs Total GB) and storage drive gauges.
  - Live running process counter.

- **📱 Reverse Phone Control from PC (Browser Hub)**:
  - Open `http://localhost:8080` in Chrome or Edge on your PC.
  - View phone screen, read SMS messages, explore phone files, or use the two-way voice walkie-talkie.

---

## 🏗️ Architecture

```
┌─────────────────────────────────┐           WebSocket & HTTP           ┌─────────────────────────────────┐
│       Android Smartphone        │ ◄──────────────────────────────────► │          Windows PC             │
│          (Ano-Link App)         │       Port 8080 (PC Control)         │     (pc-controller Daemon)      │
│                                 │       Port 8081 (Phone Daemon)       │                                 │
│  • Ktor Embedded CIO Server     │                                      │  • Node.js Express & WS Gateway │
│  • Jetpack & Coroutines Engine  │                                      │  • Native AnoLinkWinBridge.exe  │
│  • Precision Trackpad Overlay   │                                      │  • WinSta0\Default GDI Capture  │
└─────────────────────────────────┘                                      └─────────────────────────────────┘
```

---

## 📋 Prerequisites

1. **Windows PC**: Windows 10 or Windows 11 (64-bit).
2. **Android Phone**: Android 8.0 (Oreo) or newer.
3. **Node.js**: [Download Node.js (LTS)](https://nodejs.org/) installed on your PC.
4. **Network**:
   - **Local Use**: Both devices connected to the same Wi-Fi network.
   - **Remote Access (Anywhere)**: Both devices logged into [Tailscale](https://tailscale.com/) (zero port-forwarding required).

---

## 🚀 Quick Start Guide

### Step 1: Start the PC Server

1. Download or clone this repository:
   ```bash
   git clone https://github.com/anonymousagyat/Ano-link
   ```
2. Double-click **`run.bat`** in the root folder.
   *(Or run manually via terminal)*:
   ```bash
   cd pc-controller
   npm install
   npm start
   ```
3. Allow the Windows Defender Firewall prompt if prompted.
4. The terminal will display your detected IP address:
   ```text
   =======================================================
   🚀 Ano-Link PC Controller running!
   👉 Web Dashboard: http://localhost:8080
   📡 PC Addresses to enter on your Phone:
      • Local Wi-Fi: 192.168.1.50:8080
   =======================================================
   ```
5. Enter your phone's IP address when prompted (or configure it later in the browser dashboard at `http://localhost:8080`).

---

### Step 2: Install the Android App

1. Download **`AnoLink.apk`** from [Releases](https://github.com/anonymousagyat/Ano-link/releases).
2. Install the APK on your Android device.
3. Open **AnoLink**.
4. A popup will automatically appear: **"Connect Your PC Device"**.
5. Enter your PC's IP address shown in Step 1 (e.g., `192.168.1.50` or `100.x.y.z`) and tap **Save & Connect**.
6. The status capsule will turn green: `● ONLINE`.

---

### Step 3: Compiling from Source (Optional)

If you wish to build the Android app APK yourself:
```bash
cd android-app
./gradlew assembleDebug
```
The compiled APK will be located at:
`android-app/app/build/outputs/apk/debug/app-debug.apk`

---

## 🔒 Security & Privacy

- **Zero Cloud Relay**: All screen streams, file transfers, and input commands travel directly peer-to-peer over your local Wi-Fi or encrypted Tailscale WireGuard mesh.
- **Shared Secret Authentication**: Connections between the PC controller and the Android background daemon are authenticated with a token.
- **Zero ADB Requirement**: Does not require Android Debug Bridge or USB debugging enabled during regular operation.

---

## 📄 License

This project is licensed under the MIT License — see the [LICENSE](LICENSE) file for details.
