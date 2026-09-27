using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Linq;
using System.Reflection;
using System.Windows.Forms;

namespace AnoLink
{
    class Program
    {
        // ---------------- Win32 API ----------------
        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        struct STARTUPINFO
        {
            public int cb;
            public string lpReserved;
            public string lpDesktop;
            public string lpTitle;
            public int dwX, dwY, dwXSize, dwYSize;
            public int dwXCountChars, dwYCountChars, dwFillAttribute, dwFlags;
            public short wShowWindow, cbReserved2;
            public IntPtr lpReserved2;
            public IntPtr hStdInput, hStdOutput, hStdError;
        }

        [StructLayout(LayoutKind.Sequential)]
        struct PROCESS_INFORMATION
        {
            public IntPtr hProcess, hThread;
            public int dwProcessId, dwThreadId;
        }

        [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        static extern bool CreateProcess(
            string lpApplicationName, string lpCommandLine, IntPtr lpPA, IntPtr lpTA,
            bool bInheritHandles, uint dwFlags, IntPtr lpEnv, string lpDir,
            ref STARTUPINFO lpSI, out PROCESS_INFORMATION lpPI);

        [DllImport("kernel32.dll", SetLastError = true)]
        static extern bool CloseHandle(IntPtr hObject);

        [DllImport("user32.dll")]
        static extern int GetSystemMetrics(int nIndex);

        [DllImport("user32.dll")]
        static extern bool SetCursorPos(int X, int Y);

        [DllImport("user32.dll")]
        static extern void mouse_event(uint dwFlags, int dx, int dy, int dwData, UIntPtr dwExtraInfo);

        [DllImport("user32.dll")]
        static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, UIntPtr dwExtraInfo);

        [DllImport("user32.dll")]
        static extern bool SetForegroundWindow(IntPtr hWnd);

        [DllImport("user32.dll")]
        static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);

        [DllImport("user32.dll")]
        static extern bool IsWindowVisible(IntPtr hWnd);

        [DllImport("user32.dll", SetLastError = true, CharSet = CharSet.Auto)]
        static extern int GetWindowText(IntPtr hWnd, StringBuilder lpString, int nMaxCount);

        [DllImport("user32.dll", SetLastError = true)]
        static extern int GetWindowTextLength(IntPtr hWnd);

        [DllImport("user32.dll")]
        static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint lpdwProcessId);

        [DllImport("user32.dll")]
        static extern bool EnumWindows(EnumWindowsProc lpEnumFunc, IntPtr lParam);

        [DllImport("user32.dll")]
        static extern IntPtr GetShellWindow();

        [DllImport("user32.dll", SetLastError = true)]
        static extern int GetWindowLong(IntPtr hWnd, int nIndex);

        [DllImport("user32.dll")]
        static extern bool LockWorkStation();

        [DllImport("user32.dll", SetLastError = true)]
        static extern bool DestroyIcon(IntPtr hIcon);

        [DllImport("user32.dll", CharSet = CharSet.Auto)]
        static extern uint PrivateExtractIcons(
            string lpszFile,
            int nIconIndex,
            int cxIcon,
            int cyIcon,
            IntPtr[] phicon,
            uint[] piconid,
            uint nIcons,
            uint flags);

        [DllImport("dwmapi.dll")]
        static extern int DwmGetWindowAttribute(IntPtr hwnd, int dwAttribute, out int pvAttribute, int cbAttribute);
        const int DWMWA_CLOAKED = 14;

        [DllImport("user32.dll")]
        static extern bool SetProcessDPIAware();

        [DllImport("user32.dll", SetLastError = true)]
        static extern bool SetProcessDpiAwarenessContext(IntPtr dpiFlag);

        static void EnableDpiAwareness()
        {
            try
            {
                // DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2 = -4
                SetProcessDpiAwarenessContext(new IntPtr(-4));
            }
            catch
            {
                try { SetProcessDPIAware(); } catch { }
            }
        }

        [DllImport("PowrProf.dll", SetLastError = true)]
        static extern bool SetSuspendState(bool hibernate, bool forceCritical, bool disableWakeEvent);

        delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);

        const uint MOUSEEVENTF_MOVE = 0x0001;
        const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
        const uint MOUSEEVENTF_LEFTUP = 0x0004;
        const uint MOUSEEVENTF_RIGHTDOWN = 0x0008;
        const uint MOUSEEVENTF_RIGHTUP = 0x0010;
        const uint MOUSEEVENTF_MIDDLEDOWN = 0x0020;
        const uint MOUSEEVENTF_MIDDLEUP = 0x0040;
        const uint MOUSEEVENTF_WHEEL = 0x0800;

        const uint KEYEVENTF_EXTENDEDKEY = 0x0001;
        const uint KEYEVENTF_KEYUP = 0x0002;

        const int GWL_STYLE = -16;
        const int GWL_EXSTYLE = -20;
        const long WS_VISIBLE = 0x10000000L;
        const long WS_EX_TOOLWINDOW = 0x00000080L;
        const long WS_EX_APPWINDOW = 0x00040000L;
        const int SW_RESTORE = 9;

        // Virtual Keys
        const byte VK_LBUTTON = 0x01;
        const byte VK_BACK = 0x08;
        const byte VK_TAB = 0x09;
        const byte VK_RETURN = 0x0D;
        const byte VK_SHIFT = 0x10;
        const byte VK_CONTROL = 0x11;
        const byte VK_MENU = 0x12; // Alt
        const byte VK_CAPITAL = 0x14; // Caps Lock
        const byte VK_ESCAPE = 0x1B;
        const byte VK_SPACE = 0x20;
        const byte VK_LEFT = 0x25;
        const byte VK_UP = 0x26;
        const byte VK_RIGHT = 0x27;
        const byte VK_DOWN = 0x28;
        const byte VK_DELETE = 0x2E;
        const byte VK_LWIN = 0x5B;
        const byte VK_VOLUME_MUTE = 0xAD;
        const byte VK_VOLUME_DOWN = 0xAE;
        const byte VK_VOLUME_UP = 0xAF;

        static ImageCodecInfo _jpegEncoder = null;
        static EncoderParameters _encoderParams = null;

        static volatile bool _isStreaming = false;
        static int _streamFps = 15;
        static int _streamW = 960;
        static int _streamH = 540;
        static StreamWriter _workerWriter = null;

        static void Main(string[] args)
        {
            EnableDpiAwareness();
            try { Console.OutputEncoding = Encoding.UTF8; } catch { }

            if (args.Length > 0 && args[0] == "--worker")
            {
                int port = args.Length > 1 ? int.Parse(args[1]) : 18092;
                RunWorkerMode(port);
                return;
            }

            RunParentProxyMode();
        }

        // =========================================================================
        // PARENT PROXY MODE: Bridges Node.js stdio to worker process on WinSta0\Default
        // =========================================================================
        static void RunParentProxyMode()
        {
            int listenPort = 18092;
            TcpListener listener = new TcpListener(IPAddress.Loopback, listenPort);
            try
            {
                listener.Start();
            }
            catch
            {
                listenPort = 18093;
                listener = new TcpListener(IPAddress.Loopback, listenPort);
                listener.Start();
            }

            STARTUPINFO si = new STARTUPINFO();
            si.cb = Marshal.SizeOf(si);
            si.lpDesktop = @"WinSta0\Default";

            PROCESS_INFORMATION pi = new PROCESS_INFORMATION();
            string myExe = Process.GetCurrentProcess().MainModule.FileName;
            string cmd = "\"" + myExe + "\" --worker " + listenPort;

            bool spawned = CreateProcess(null, cmd, IntPtr.Zero, IntPtr.Zero, false, 0, IntPtr.Zero, null, ref si, out pi);
            if (!spawned)
            {
                Console.WriteLine("{\"type\":\"bridge_error\",\"error\":\"Failed to spawn worker on WinSta0\\\\Default\"}");
                return;
            }

            TcpClient client = null;
            try
            {
                client = listener.AcceptTcpClient();
            }
            catch (Exception ex)
            {
                Console.WriteLine("{\"type\":\"bridge_error\",\"error\":\"Listener accept error: " + EscapeJson(ex.Message) + "\"}");
                return;
            }

            NetworkStream ns = client.GetStream();
            StreamReader clientReader = new StreamReader(ns, Encoding.UTF8);
            StreamWriter clientWriter = new StreamWriter(ns, Encoding.UTF8) { AutoFlush = true };

            // Forward worker output lines to Console.Out
            Thread pumpOut = new Thread(() =>
            {
                try
                {
                    string l;
                    while ((l = clientReader.ReadLine()) != null)
                    {
                        Console.WriteLine(l);
                    }
                }
                catch { }
            })
            { IsBackground = true };
            pumpOut.Start();

            // Forward Console.In to worker
            string inputLine;
            while ((inputLine = Console.ReadLine()) != null)
            {
                try
                {
                    clientWriter.WriteLine(inputLine);
                }
                catch
                {
                    break;
                }
            }

            try { Process.GetProcessById(pi.dwProcessId).Kill(); } catch { }
            try { client.Close(); } catch { }
            try { listener.Stop(); } catch { }
            CloseHandle(pi.hProcess);
            CloseHandle(pi.hThread);
        }

        // =========================================================================
        // WORKER MODE: Runs directly on WinSta0\Default with unrestricted GDI & input
        // =========================================================================
        static void RunWorkerMode(int port)
        {
            EnableDpiAwareness();
            InitJpegEncoder();

            TcpClient client = null;
            for (int attempt = 0; attempt < 20; attempt++)
            {
                try
                {
                    client = new TcpClient("127.0.0.1", port);
                    break;
                }
                catch
                {
                    Thread.Sleep(50);
                }
            }

            if (client == null) return;

            NetworkStream ns = client.GetStream();
            StreamReader reader = new StreamReader(ns, Encoding.UTF8);
            _workerWriter = new StreamWriter(ns, Encoding.UTF8) { AutoFlush = true };

            _workerWriter.WriteLine("{\"type\":\"bridge_ready\",\"version\":\"3.0\",\"desktop\":\"WinSta0\\\\Default\"}");

            // Background screen capture streaming thread
            Thread streamThread = new Thread(StreamLoop) { IsBackground = true };
            streamThread.Start();

            try
            {
                string line;
                while ((line = reader.ReadLine()) != null)
                {
                    line = line.Trim();
                    if (string.IsNullOrEmpty(line)) continue;

                    try
                    {
                        ProcessWorkerCommand(line);
                    }
                    catch (Exception ex)
                    {
                        _workerWriter.WriteLine("{\"type\":\"bridge_error\",\"error\":\"" + EscapeJson(ex.Message) + "\"}");
                    }
                }
            }
            catch { }

            _isStreaming = false;
            try { client.Close(); } catch { }
        }

        static void StreamLoop()
        {
            while (true)
            {
                if (_isStreaming && _workerWriter != null)
                {
                    try
                    {
                        DoCapture(_streamW, _streamH);
                    }
                    catch { }

                    int delayMs = Math.Max(25, 1000 / Math.Max(1, _streamFps));
                    Thread.Sleep(delayMs);
                }
                else
                {
                    Thread.Sleep(50);
                }
            }
        }

        static void DoCapture(int targetW, int targetH)
        {
            int sw = GetSystemMetrics(0); // SM_CXSCREEN
            int sh = GetSystemMetrics(1); // SM_CYSCREEN
            if (sw <= 0) sw = 1920;
            if (sh <= 0) sh = 1080;

            int scaledW = targetW;
            int scaledH = (int)Math.Round((double)targetW * sh / sw);
            if (scaledH % 2 != 0) scaledH++;
            if (scaledW <= 0) scaledW = 960;
            if (scaledH <= 0) scaledH = 540;

            using (Bitmap fullBmp = new Bitmap(sw, sh, PixelFormat.Format24bppRgb))
            {
                using (Graphics g = Graphics.FromImage(fullBmp))
                {
                    g.CopyFromScreen(0, 0, 0, 0, new Size(sw, sh), CopyPixelOperation.SourceCopy);
                }

                using (Bitmap scaledBmp = new Bitmap(scaledW, scaledH, PixelFormat.Format24bppRgb))
                {
                    using (Graphics gScaled = Graphics.FromImage(scaledBmp))
                    {
                        gScaled.InterpolationMode = InterpolationMode.Bilinear;
                        gScaled.DrawImage(fullBmp, 0, 0, scaledW, scaledH);
                    }

                    using (MemoryStream ms = new MemoryStream())
                    {
                        scaledBmp.Save(ms, _jpegEncoder, _encoderParams);
                        string base64 = Convert.ToBase64String(ms.ToArray());
                        _workerWriter.WriteLine("{\"type\":\"pc_screen_frame\",\"data\":\"" + base64 + "\"}");
                    }
                }
            }
        }

        static void ProcessWorkerCommand(string line)
        {
            string[] parts = line.Split(' ');
            string cmd = parts[0].ToUpperInvariant();

            switch (cmd)
            {
                case "M": // Mouse Move Relative: M dx dy
                    if (parts.Length >= 3)
                    {
                        int dx = int.Parse(parts[1]);
                        int dy = int.Parse(parts[2]);
                        mouse_event(MOUSEEVENTF_MOVE, dx, dy, 0, UIntPtr.Zero);
                    }
                    break;

                case "MA": // Mouse Move Absolute: MA x y
                    if (parts.Length >= 3)
                    {
                        int x = int.Parse(parts[1]);
                        int y = int.Parse(parts[2]);
                        SetCursorPos(x, y);
                    }
                    break;

                case "TAP": // TAP nx ny [left|right|middle] [click|double]
                    if (parts.Length >= 3)
                    {
                        float nx = float.Parse(parts[1], System.Globalization.CultureInfo.InvariantCulture);
                        float ny = float.Parse(parts[2], System.Globalization.CultureInfo.InvariantCulture);
                        string btn = parts.Length >= 4 ? parts[3].ToLowerInvariant() : "left";
                        string clickType = parts.Length >= 5 ? parts[4].ToLowerInvariant() : "click";
                        int sw = GetSystemMetrics(0);
                        int sh = GetSystemMetrics(1);
                        if (sw <= 0) sw = 1920;
                        if (sh <= 0) sh = 1080;
                        int x = (int)(nx * sw);
                        int y = (int)(ny * sh);
                        SetCursorPos(x, y);
                        HandleMouseClick(btn, clickType);
                    }
                    break;

                case "CLICK": // CLICK [left|right|middle] [down|up|click]
                    if (parts.Length >= 2)
                    {
                        string btn = parts[1].ToLowerInvariant();
                        string action = parts.Length >= 3 ? parts[2].ToLowerInvariant() : "click";
                        HandleMouseClick(btn, action);
                    }
                    break;

                case "SCROLL": // SCROLL dy
                    if (parts.Length >= 2)
                    {
                        int dy = int.Parse(parts[1]);
                        mouse_event(MOUSEEVENTF_WHEEL, 0, 0, dy, UIntPtr.Zero);
                    }
                    break;

                case "KEY": // KEY [key_name]
                    if (parts.Length >= 2)
                    {
                        PressKey(parts[1]);
                    }
                    break;

                case "TYPE": // TYPE [text]
                    if (line.Length > 5)
                    {
                        string text = line.Substring(5);
                        SendKeys.SendWait(text);
                    }
                    break;

                case "HOTKEY": // HOTKEY k1 k2 [k3]
                    if (parts.Length >= 2)
                    {
                        HandleHotkey(parts);
                    }
                    break;

                case "GET_WINDOWS": // Enumerate Taskbar Windows
                    EnumerateWindows();
                    break;

                case "FOCUS_WINDOW": // FOCUS_WINDOW [hwnd]
                    if (parts.Length >= 2)
                    {
                        long hwndVal = long.Parse(parts[1]);
                        IntPtr hWnd = new IntPtr(hwndVal);
                        ShowWindow(hWnd, SW_RESTORE);
                        SetForegroundWindow(hWnd);
                        _workerWriter.WriteLine("{\"type\":\"window_focused\",\"hwnd\":" + hwndVal + "}");
                    }
                    break;

                case "STREAM_START": // STREAM_START [fps] [w] [h]
                    _streamFps = parts.Length >= 2 ? int.Parse(parts[1]) : 15;
                    _streamW = parts.Length >= 3 ? int.Parse(parts[2]) : 960;
                    _streamH = parts.Length >= 4 ? int.Parse(parts[3]) : 540;
                    _isStreaming = true;
                    _workerWriter.WriteLine("{\"type\":\"stream_status\",\"active\":true,\"fps\":" + _streamFps + "}");
                    break;

                case "STREAM_STOP":
                    _isStreaming = false;
                    _workerWriter.WriteLine("{\"type\":\"stream_status\",\"active\":false}");
                    break;

                case "CAPTURE": // CAPTURE [target_width] [target_height]
                    int targetW = parts.Length >= 2 ? int.Parse(parts[1]) : 960;
                    int targetH = parts.Length >= 3 ? int.Parse(parts[2]) : 540;
                    DoCapture(targetW, targetH);
                    break;

                case "POWER": // POWER [lock|sleep|restart|shutdown]
                    if (parts.Length >= 2)
                    {
                        HandlePower(parts[1].ToLowerInvariant());
                    }
                    break;

                case "LAUNCH_APP": // LAUNCH_APP [target path]
                    if (line.Length > 11)
                    {
                        string target = line.Substring(11).Trim();
                        LaunchApp(target);
                    }
                    break;

                case "GET_START_APPS":
                    EnumerateStartApps();
                    break;

                case "GET_POWER":
                    SendPowerStatus();
                    break;

                case "PING":
                    _workerWriter.WriteLine("{\"type\":\"pong\",\"time\":" + DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() + "}");
                    break;

                case "SPEAKER_PLAY":
                    if (line.Length > 13)
                    {
                        string b64 = line.Substring(13).Trim();
                        PlaySpeakerChunk(b64);
                    }
                    break;

                case "SPEAKER_STOP":
                    StopSpeakerPlayback();
                    break;

                case "SPEAKER_SET_VOL":
                    if (parts.Length >= 2)
                    {
                        int vol = int.Parse(parts[1]);
                        SetSpeakerVolume(vol);
                    }
                    break;

                case "MIC_START":
                    StartMicCapture();
                    break;

                case "MIC_STOP":
                    StopMicCapture();
                    break;
            }
        }

        static void HandleMouseClick(string btn, string action)
        {
            uint down = MOUSEEVENTF_LEFTDOWN;
            uint up = MOUSEEVENTF_LEFTUP;

            if (btn == "right")
            {
                down = MOUSEEVENTF_RIGHTDOWN;
                up = MOUSEEVENTF_RIGHTUP;
            }
            else if (btn == "middle")
            {
                down = MOUSEEVENTF_MIDDLEDOWN;
                up = MOUSEEVENTF_MIDDLEUP;
            }

            if (action == "down")
            {
                mouse_event(down, 0, 0, 0, UIntPtr.Zero);
            }
            else if (action == "up")
            {
                mouse_event(up, 0, 0, 0, UIntPtr.Zero);
            }
            else if (action == "double")
            {
                mouse_event(down, 0, 0, 0, UIntPtr.Zero);
                mouse_event(up, 0, 0, 0, UIntPtr.Zero);
                Thread.Sleep(30);
                mouse_event(down, 0, 0, 0, UIntPtr.Zero);
                mouse_event(up, 0, 0, 0, UIntPtr.Zero);
            }
            else
            {
                mouse_event(down, 0, 0, 0, UIntPtr.Zero);
                mouse_event(up, 0, 0, 0, UIntPtr.Zero);
            }
        }

        static void PressKey(string keyName)
        {
            byte vk = ParseVk(keyName);
            if (vk != 0)
            {
                keybd_event(vk, 0, 0, UIntPtr.Zero);
                Thread.Sleep(15);
                keybd_event(vk, 0, KEYEVENTF_KEYUP, UIntPtr.Zero);
            }
        }

        static void HandleHotkey(string[] parts)
        {
            List<byte> keysToPress = new List<byte>();
            for (int i = 1; i < parts.Length; i++)
            {
                byte vk = ParseVk(parts[i]);
                if (vk != 0) keysToPress.Add(vk);
            }

            for (int i = 0; i < keysToPress.Count; i++)
            {
                keybd_event(keysToPress[i], 0, 0, UIntPtr.Zero);
            }
            Thread.Sleep(30);
            for (int i = keysToPress.Count - 1; i >= 0; i--)
            {
                keybd_event(keysToPress[i], 0, KEYEVENTF_KEYUP, UIntPtr.Zero);
            }
        }

        static byte ParseVk(string k)
        {
            switch (k.ToLowerInvariant())
            {
                case "win": case "windows": case "lwin": return VK_LWIN;
                case "alt": return VK_MENU;
                case "ctrl": case "control": return VK_CONTROL;
                case "shift": return VK_SHIFT;
                case "tab": return VK_TAB;
                case "enter": case "return": return VK_RETURN;
                case "escape": case "esc": return VK_ESCAPE;
                case "backspace": return VK_BACK;
                case "space": return VK_SPACE;
                case "delete": case "del": return VK_DELETE;
                case "left": return VK_LEFT;
                case "up": return VK_UP;
                case "right": return VK_RIGHT;
                case "down": return VK_DOWN;
                case "caps": case "capslock": return VK_CAPITAL;
                case "mute": return VK_VOLUME_MUTE;
                case "volumedown": return VK_VOLUME_DOWN;
                case "volumeup": return VK_VOLUME_UP;
                default:
                    if (k.Length == 1)
                    {
                        char c = char.ToUpperInvariant(k[0]);
                        if (c >= 'A' && c <= 'Z') return (byte)c;
                        if (c >= '0' && c <= '9') return (byte)c;
                    }
                    int fNum;
                    if (k.StartsWith("f") && int.TryParse(k.Substring(1), out fNum) && fNum >= 1 && fNum <= 12)
                    {
                        return (byte)(0x6F + fNum);
                    }
                    return 0;
            }
        }

        class WindowEntry
        {
            public long Hwnd;
            public string Title;
            public string ProcessName;
            public string AppName;
            public uint Pid;
        }

        static Dictionary<string, string> _iconCache = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        static List<string> _taskbarSequence = new List<string>();

        static string ExtractProcIconBase64(string procName, int pid)
        {
            if (_iconCache.ContainsKey(procName))
            {
                return _iconCache[procName];
            }

            try
            {
                using (Process p = Process.GetProcessById(pid))
                {
                    string fileName = p.MainModule.FileName;
                    if (!string.IsNullOrEmpty(fileName) && File.Exists(fileName))
                    {
                        // 1. Native High-Resolution Icon Extraction (96x96 for Retina phone displays)
                        IntPtr[] phicon = new IntPtr[1];
                        uint[] piconid = new uint[1];
                        uint extracted = PrivateExtractIcons(fileName, 0, 96, 96, phicon, piconid, 1, 0);
                        if (extracted > 0 && phicon[0] != IntPtr.Zero)
                        {
                            try
                            {
                                using (Icon ico = Icon.FromHandle(phicon[0]))
                                using (Bitmap bmp = ico.ToBitmap())
                                using (MemoryStream ms = new MemoryStream())
                                {
                                    bmp.Save(ms, ImageFormat.Png);
                                    string b64 = Convert.ToBase64String(ms.ToArray());
                                    _iconCache[procName] = b64;
                                    return b64;
                                }
                            }
                            finally
                            {
                                DestroyIcon(phicon[0]);
                            }
                        }

                        // 2. High-Quality Bicubic Fallback
                        using (Icon ico = Icon.ExtractAssociatedIcon(fileName))
                        {
                            if (ico != null)
                            {
                                using (Bitmap bmp = ico.ToBitmap())
                                using (Bitmap highRes = new Bitmap(96, 96))
                                using (Graphics g = Graphics.FromImage(highRes))
                                using (MemoryStream ms = new MemoryStream())
                                {
                                    g.InterpolationMode = System.Drawing.Drawing2D.InterpolationMode.HighQualityBicubic;
                                    g.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.HighQuality;
                                    g.PixelOffsetMode = System.Drawing.Drawing2D.PixelOffsetMode.HighQuality;
                                    g.DrawImage(bmp, 0, 0, 96, 96);
                                    highRes.Save(ms, ImageFormat.Png);
                                    string b64 = Convert.ToBase64String(ms.ToArray());
                                    _iconCache[procName] = b64;
                                    return b64;
                                }
                            }
                        }
                    }
                }
            }
            catch { }

            _iconCache[procName] = "";
            return "";
        }

        static void EnumerateWindows()
        {
            IntPtr shellWnd = GetShellWindow();
            List<WindowEntry> windowList = new List<WindowEntry>();

            EnumWindows((hWnd, lParam) =>
            {
                if (hWnd == shellWnd) return true;
                if (!IsWindowVisible(hWnd)) return true;

                // 1. Skip DWM Cloaked windows (drops background UWP apps, TextInputHost, SystemSettings)
                try
                {
                    int cloaked;
                    if (DwmGetWindowAttribute(hWnd, DWMWA_CLOAKED, out cloaked, sizeof(int)) == 0 && cloaked != 0)
                    {
                        return true;
                    }
                }
                catch { }

                int len = GetWindowTextLength(hWnd);
                if (len <= 0) return true;

                int style = GetWindowLong(hWnd, GWL_STYLE);
                int exStyle = GetWindowLong(hWnd, GWL_EXSTYLE);

                if ((exStyle & WS_EX_TOOLWINDOW) != 0 && (exStyle & WS_EX_APPWINDOW) == 0)
                {
                    return true;
                }

                StringBuilder titleSb = new StringBuilder(len + 1);
                GetWindowText(hWnd, titleSb, len + 1);
                string title = titleSb.ToString().Trim();
                if (string.IsNullOrEmpty(title)) return true;

                uint pid;
                GetWindowThreadProcessId(hWnd, out pid);

                string procName = "App";
                try
                {
                    using (Process p = Process.GetProcessById((int)pid))
                    {
                        procName = p.ProcessName;
                    }
                }
                catch { }

                string lowerProc = procName.ToLowerInvariant();
                if (lowerProc == "shellexperiencehost" || lowerProc == "searchhost" ||
                    lowerProc == "taskhostw" || lowerProc == "startmenuexperiencehost" ||
                    lowerProc == "systemsettings" || lowerProc == "textinputhost" ||
                    lowerProc == "textinput" || lowerProc == "lockapp" ||
                    lowerProc == "widgetservice" || lowerProc == "dwm" ||
                    (lowerProc == "applicationframehost" && title == "Settings"))
                {
                    // Ignore background system hosts
                    return true;
                }

                windowList.Add(new WindowEntry
                {
                    Hwnd = hWnd.ToInt64(),
                    Title = title,
                    ProcessName = procName,
                    AppName = GetFriendlyAppName(procName, title),
                    Pid = pid
                });

                return true;
            }, IntPtr.Zero);

            Dictionary<string, List<WindowEntry>> grouped = new Dictionary<string, List<WindowEntry>>(StringComparer.OrdinalIgnoreCase);
            Dictionary<string, string> appIcons = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);

            foreach (var w in windowList)
            {
                if (!grouped.ContainsKey(w.AppName))
                {
                    grouped[w.AppName] = new List<WindowEntry>();
                    appIcons[w.AppName] = ExtractProcIconBase64(w.ProcessName, (int)w.Pid);
                }
                grouped[w.AppName].Add(w);
            }

            // Maintain stable dock sequence so apps don't jump around on focus change
            _taskbarSequence.RemoveAll(name => !grouped.ContainsKey(name));
            foreach (var name in grouped.Keys)
            {
                if (!_taskbarSequence.Contains(name, StringComparer.OrdinalIgnoreCase))
                {
                    _taskbarSequence.Add(name);
                }
            }

            StringBuilder json = new StringBuilder();
            json.Append("{\"type\":\"taskbar_apps\",\"apps\":[");
            int appCount = 0;
            foreach (var appName in _taskbarSequence)
            {
                if (!grouped.ContainsKey(appName)) continue;
                var windows = grouped[appName];
                string iconBase64 = appIcons.ContainsKey(appName) ? appIcons[appName] : "";

                if (appCount > 0) json.Append(",");
                json.Append("{\"name\":\"").Append(EscapeJson(appName)).Append("\",");
                json.Append("\"count\":").Append(windows.Count).Append(",");
                json.Append("\"icon\":\"").Append(iconBase64).Append("\",");
                json.Append("\"windows\":[");
                for (int i = 0; i < windows.Count; i++)
                {
                    if (i > 0) json.Append(",");
                    json.Append("{\"hwnd\":").Append(windows[i].Hwnd).Append(",");
                    json.Append("\"title\":\"").Append(EscapeJson(windows[i].Title)).Append("\"}");
                }
                json.Append("]}");
                appCount++;
            }
            json.Append("]}");

            _workerWriter.WriteLine(json.ToString());
            SendPowerStatus();
        }

        static void SendPowerStatus()
        {
            try
            {
                var ps = SystemInformation.PowerStatus;
                int pct = (int)Math.Round(ps.BatteryLifePercent * 100);
                if (pct < 0 || pct > 100) pct = 100;
                bool online = ps.PowerLineStatus == PowerLineStatus.Online;
                bool hasBat = (ps.BatteryChargeStatus & BatteryChargeStatus.NoSystemBattery) == 0;
                _workerWriter.WriteLine("{\"type\":\"pc_battery_status\",\"percent\":" + pct + ",\"isCharging\":" + (online ? "true" : "false") + ",\"hasBattery\":" + (hasBat ? "true" : "false") + "}");
            }
            catch { }
        }

        static string GetFriendlyAppName(string procName, string title)
        {
            string lower = procName.ToLowerInvariant();
            if (lower.Contains("brave")) return "Brave";
            if (lower.Contains("chrome")) return "Chrome";
            if (lower.Contains("code")) return "VS Code";
            if (lower.Contains("explorer")) return "File Explorer";
            if (lower.Contains("spotify")) return "Spotify";
            if (lower.Contains("discord")) return "Discord";
            if (lower.Contains("powershell") || lower.Contains("windowsterminal") || lower.Contains("cmd")) return "Terminal";
            if (lower.Contains("notepad")) return "Notepad";
            if (lower.Contains("steam")) return "Steam";
            return procName;
        }

        static void InitJpegEncoder()
        {
            foreach (var codec in ImageCodecInfo.GetImageDecoders())
            {
                if (codec.FormatID == ImageFormat.Jpeg.Guid)
                {
                    _jpegEncoder = codec;
                    break;
                }
            }
            _encoderParams = new EncoderParameters(1);
            _encoderParams.Param[0] = new EncoderParameter(System.Drawing.Imaging.Encoder.Quality, 45L); // Fast 45% JPEG
        }

        static void HandlePower(string opt)
        {
            if (opt == "lock")
            {
                LockWorkStation();
                _workerWriter.WriteLine("{\"type\":\"power_status\",\"action\":\"lock\",\"success\":true}");
            }
            else if (opt == "sleep")
            {
                SetSuspendState(false, true, false);
                _workerWriter.WriteLine("{\"type\":\"power_status\",\"action\":\"sleep\",\"success\":true}");
            }
            else if (opt == "restart")
            {
                Process.Start("shutdown", "/r /t 0");
                _workerWriter.WriteLine("{\"type\":\"power_status\",\"action\":\"restart\",\"success\":true}");
            }
            else if (opt == "shutdown")
            {
                Process.Start("shutdown", "/s /t 0");
                _workerWriter.WriteLine("{\"type\":\"power_status\",\"action\":\"shutdown\",\"success\":true}");
            }
        }

        static void LaunchApp(string target)
        {
            try
            {
                ProcessStartInfo psi = new ProcessStartInfo();
                psi.FileName = target;
                psi.UseShellExecute = true;
                try
                {
                    string dir = Path.GetDirectoryName(target);
                    if (!string.IsNullOrEmpty(dir) && Directory.Exists(dir))
                    {
                        psi.WorkingDirectory = dir;
                    }
                }
                catch { }
                Process.Start(psi);
                _workerWriter.WriteLine("{\"type\":\"launch_status\",\"target\":\"" + EscapeJson(target) + "\",\"success\":true}");
            }
            catch (Exception ex)
            {
                _workerWriter.WriteLine("{\"type\":\"launch_status\",\"target\":\"" + EscapeJson(target) + "\",\"success\":false,\"error\":\"" + EscapeJson(ex.Message) + "\"}");
            }
        }

        static string _cachedStartAppsJson = null;

        static void EnumerateStartApps()
        {
            if (_cachedStartAppsJson != null)
            {
                _workerWriter.WriteLine(_cachedStartAppsJson);
                return;
            }

            try
            {
                List<string> dirs = new List<string>();
                string common = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonStartMenu), "Programs");
                string user = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.StartMenu), "Programs");
                if (Directory.Exists(common)) dirs.Add(common);
                if (Directory.Exists(user)) dirs.Add(user);

                Type shellType = Type.GetTypeFromProgID("WScript.Shell");
                object shell = shellType != null ? Activator.CreateInstance(shellType) : null;

                // Deduplicate strictly by canonical target executable path
                Dictionary<string, StartAppItem> targetMap = new Dictionary<string, StartAppItem>(StringComparer.OrdinalIgnoreCase);

                // Pre-seed core built-in Windows applications
                string sys32 = Environment.GetFolderPath(Environment.SpecialFolder.System);
                string windir = Environment.GetFolderPath(Environment.SpecialFolder.Windows);

                AddAppIfValid(targetMap, "Brave Browser", @"C:\Program Files\BraveSoftware\Brave-Browser\Application\brave.exe", "Web Browser");
                AddAppIfValid(targetMap, "Visual Studio Code", Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), @"Programs\Microsoft VS Code\Code.exe"), "Development IDE");
                AddAppIfValid(targetMap, "Android Studio", @"C:\Program Files\Android\Android Studio\bin\studio64.exe", "Development IDE");
                AddAppIfValid(targetMap, "File Explorer", Path.Combine(windir, "explorer.exe"), "System Tool");
                AddAppIfValid(targetMap, "Command Prompt", Path.Combine(sys32, "cmd.exe"), "Console & Shell");
                AddAppIfValid(targetMap, "Steam", @"C:\Program Files (x86)\Steam\Steam.exe", "Gaming Platform");
                AddAppIfValid(targetMap, "Task Manager", Path.Combine(sys32, "Taskmgr.exe"), "System Monitor");
                AddAppIfValid(targetMap, "VLC Media Player", @"C:\Program Files\VideoLAN\VLC\vlc.exe", "Media Player");
                AddAppIfValid(targetMap, "Notepad++", @"C:\Program Files\Notepad++\notepad++.exe", "Text & Code Editor");
                AddAppIfValid(targetMap, "Neat Download Manager", @"D:\1.UTILITIES\Neat Download Manager\NeatDM.exe", "Download Manager");
                AddAppIfValid(targetMap, "Calculator", Path.Combine(sys32, "calc.exe"), "Utility");
                AddAppIfValid(targetMap, "Notepad", Path.Combine(sys32, "notepad.exe"), "Text & Code Editor");
                AddAppIfValid(targetMap, "Windows Settings", "ms-settings:", "System Settings");

                foreach (var dir in dirs)
                {
                    try
                    {
                        var lnks = Directory.GetFiles(dir, "*.lnk", SearchOption.AllDirectories);
                        foreach (var lnk in lnks)
                        {
                            try
                            {
                                string rawName = Path.GetFileNameWithoutExtension(lnk);
                                string lower = rawName.ToLowerInvariant();
                                if (lower.Contains("uninstall") || lower.Contains("help") || lower.Contains("readme") ||
                                    lower.Contains("license") || lower.Contains("manual") || lower.Contains("documentation") ||
                                    lower.Contains("release notes") || lower.Contains("reset") || lower.Contains("skinned") ||
                                    lower.Contains("module docs") || lower.Contains("manuals") || lower.Contains("cmd prompt"))
                                {
                                    continue;
                                }

                                string target = "";
                                if (shell != null)
                                {
                                    try
                                    {
                                        object shortcut = shellType.InvokeMember("CreateShortcut", BindingFlags.InvokeMethod, null, shell, new object[] { lnk });
                                        if (shortcut != null)
                                        {
                                            target = (string)shortcut.GetType().InvokeMember("TargetPath", BindingFlags.GetProperty, null, shortcut, null);
                                        }
                                    }
                                    catch { }
                                }

                                if (!string.IsNullOrEmpty(target) && target.EndsWith(".exe", StringComparison.OrdinalIgnoreCase) && File.Exists(target))
                                {
                                    string cleanName = NormalizeAppName(rawName);
                                    string category = DetermineCategory(cleanName, target);
                                    AddAppIfValid(targetMap, cleanName, target, category);
                                }
                            }
                            catch { }
                        }
                    }
                    catch { }
                }

                StringBuilder sb = new StringBuilder();
                sb.Append("{\"type\":\"start_apps\",\"apps\":[");
                int count = 0;
                foreach (var kvp in targetMap)
                {
                    if (count > 0) sb.Append(",");
                    sb.Append("{\"name\":\"").Append(EscapeJson(kvp.Value.Name)).Append("\",");
                    sb.Append("\"path\":\"").Append(EscapeJson(kvp.Value.Path)).Append("\",");
                    sb.Append("\"category\":\"").Append(EscapeJson(kvp.Value.Category)).Append("\",");
                    sb.Append("\"icon\":\"").Append(kvp.Value.Icon).Append("\"}");
                    count++;
                }
                sb.Append("]}");

                _cachedStartAppsJson = sb.ToString();

                // Save to local file so server.js REST endpoint can read it directly
                try
                {
                    string outDir = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, @"..\..\app_icons");
                    if (!Directory.Exists(outDir)) Directory.CreateDirectory(outDir);
                    File.WriteAllText(Path.Combine(outDir, "installed_apps.json"), _cachedStartAppsJson, Encoding.UTF8);
                }
                catch { }

                _workerWriter.WriteLine(_cachedStartAppsJson);
            }
            catch (Exception ex)
            {
                _workerWriter.WriteLine("{\"type\":\"start_apps\",\"apps\":[],\"error\":\"" + EscapeJson(ex.Message) + "\"}");
            }
        }

        static string NormalizeAppName(string name)
        {
            string lower = name.ToLowerInvariant().Trim();
            if (lower == "brave" || lower.Contains("brave browser")) return "Brave Browser";
            if (lower.Contains("microsoft edge") || lower == "edge") return "Microsoft Edge";
            if (lower.Contains("visual studio code") || lower == "code") return "Visual Studio Code";
            if (lower.Contains("android studio")) return "Android Studio";
            if (lower == "steam") return "Steam";
            if (lower.Contains("vlc media player") || lower == "vlc") return "VLC Media Player";
            if (lower.Contains("notepad++")) return "Notepad++";
            if (lower.Contains("neat download manager") || lower == "neatdm") return "Neat Download Manager";
            if (lower == "winrar") return "WinRAR";
            if (lower.Contains("7-zip")) return "7-Zip File Manager";
            if (lower == "explorer" || lower == "file explorer") return "File Explorer";
            if (lower == "cmd" || lower == "command prompt") return "Command Prompt";
            if (lower == "taskmgr" || lower == "task manager") return "Task Manager";
            if (lower.Contains("powershell ise")) return "PowerShell ISE";
            if (lower.Contains("powershell")) return "Windows PowerShell";
            return name.Trim();
        }

        static string DetermineCategory(string name, string path)
        {
            string lower = (name + " " + path).ToLowerInvariant();
            if (lower.Contains("brave") || lower.Contains("edge") || lower.Contains("chrome") || lower.Contains("browser")) return "Web Browser";
            if (lower.Contains("code") || lower.Contains("studio") || lower.Contains("git") || lower.Contains("python") || lower.Contains("node")) return "Development IDE";
            if (lower.Contains("steam") || lower.Contains("game") || lower.Contains("prism")) return "Gaming Platform";
            if (lower.Contains("vlc") || lower.Contains("media player") || lower.Contains("music") || lower.Contains("video")) return "Media Player";
            if (lower.Contains("download manager") || lower.Contains("neatdm")) return "Download Manager";
            if (lower.Contains("notepad") || lower.Contains("editor")) return "Text & Code Editor";
            if (lower.Contains("winrar") || lower.Contains("7-zip") || lower.Contains("zip")) return "Archive Utility";
            if (lower.Contains("taskmgr") || lower.Contains("monitor") || lower.Contains("diagnostic")) return "System Monitor";
            if (lower.Contains("cmd") || lower.Contains("terminal") || lower.Contains("powershell") || lower.Contains("bash")) return "Console & Shell";
            if (lower.Contains("explorer") || lower.Contains("calc") || lower.Contains("cleanup") || lower.Contains("control")) return "System Tool";
            return "Windows App";
        }

        static void AddAppIfValid(Dictionary<string, StartAppItem> map, string name, string path, string category)
        {
            if (string.IsNullOrEmpty(path)) return;
            string key = path.ToLowerInvariant().Trim();
            try
            {
                if (File.Exists(path))
                {
                    key = Path.GetFullPath(path).ToLowerInvariant();
                }
            }
            catch { }

            // Deduplicate by canonical path and app name
            if (map.ContainsKey(key)) return;
            foreach (var existing in map.Values)
            {
                if (existing.Name.Equals(name, StringComparison.OrdinalIgnoreCase)) return;
            }

            if (!path.StartsWith("ms-") && !File.Exists(path)) return;

            string icon = ExtractFileIconBase64(path);
            map[key] = new StartAppItem { Name = name, Path = path, Category = category, Icon = icon };
        }

        static string ExtractFileIconBase64(string filePath)
        {
            if (string.IsNullOrEmpty(filePath) || !File.Exists(filePath)) return "";
            try
            {
                IntPtr[] phicon = new IntPtr[1];
                uint[] piconid = new uint[1];
                uint num = PrivateExtractIcons(filePath, 0, 64, 64, phicon, piconid, 1, 0);
                if (num > 0 && phicon[0] != IntPtr.Zero)
                {
                    try
                    {
                        using (Icon ico = Icon.FromHandle(phicon[0]))
                        using (Bitmap bmp = ico.ToBitmap())
                        using (MemoryStream ms = new MemoryStream())
                        {
                            bmp.Save(ms, ImageFormat.Png);
                            return Convert.ToBase64String(ms.ToArray());
                        }
                    }
                    finally
                    {
                        DestroyIcon(phicon[0]);
                    }
                }
                using (Icon ico = Icon.ExtractAssociatedIcon(filePath))
                {
                    if (ico != null)
                    {
                        using (Bitmap bmp = ico.ToBitmap())
                        using (Bitmap highRes = new Bitmap(64, 64))
                        using (Graphics g = Graphics.FromImage(highRes))
                        using (MemoryStream ms = new MemoryStream())
                        {
                            g.InterpolationMode = InterpolationMode.HighQualityBicubic;
                            g.SmoothingMode = SmoothingMode.HighQuality;
                            g.PixelOffsetMode = PixelOffsetMode.HighQuality;
                            g.DrawImage(bmp, 0, 0, 64, 64);
                            highRes.Save(ms, ImageFormat.Png);
                            return Convert.ToBase64String(ms.ToArray());
                        }
                    }
                }
            }
            catch { }
            return "";
        }

        public class StartAppItem
        {
            public string Name;
            public string Path;
            public string Category;
            public string Icon;
        }

        // =========================================================================
        // NATIVE WIN32 AUDIO: Speaker Playback (waveOut) & Mic Capture (waveIn)
        // =========================================================================
        [StructLayout(LayoutKind.Sequential)]
        public struct WAVEFORMATEX
        {
            public ushort wFormatTag;
            public ushort nChannels;
            public uint nSamplesPerSec;
            public uint nAvgBytesPerSec;
            public ushort nBlockAlign;
            public ushort wBitsPerSample;
            public ushort cbSize;
        }

        [StructLayout(LayoutKind.Sequential)]
        public struct WAVEHDR
        {
            public IntPtr lpData;
            public uint dwBufferLength;
            public uint dwBytesRecorded;
            public IntPtr dwUser;
            public uint dwFlags;
            public uint dwLoops;
            public IntPtr lpNext;
            public IntPtr reserved;
        }

        public delegate void WaveInProc(IntPtr hwi, uint uMsg, IntPtr dwInstance, IntPtr dwParam1, IntPtr dwParam2);
        public delegate void WaveOutProc(IntPtr hwo, uint uMsg, IntPtr dwInstance, IntPtr dwParam1, IntPtr dwParam2);

        const int WAVE_MAPPER = -1;
        const int CALLBACK_FUNCTION = 0x00030000;
        const uint WIM_DATA = 0x3C0;
        const uint WOM_DONE = 0x3BD;

        [DllImport("winmm.dll")]
        public static extern int waveOutOpen(out IntPtr hWaveOut, int uDeviceID, ref WAVEFORMATEX lpFormat, WaveOutProc dwCallback, IntPtr dwInstance, int dwFlags);

        [DllImport("winmm.dll")]
        public static extern int waveOutPrepareHeader(IntPtr hWaveOut, IntPtr lpWaveOutHdr, int uSize);

        [DllImport("winmm.dll")]
        public static extern int waveOutWrite(IntPtr hWaveOut, IntPtr lpWaveOutHdr, int uSize);

        [DllImport("winmm.dll")]
        public static extern int waveOutUnprepareHeader(IntPtr hWaveOut, IntPtr lpWaveOutHdr, int uSize);

        [DllImport("winmm.dll")]
        public static extern int waveOutReset(IntPtr hWaveOut);

        [DllImport("winmm.dll")]
        public static extern int waveOutClose(IntPtr hWaveOut);

        [DllImport("winmm.dll")]
        public static extern int waveOutSetVolume(IntPtr hWaveOut, uint dwVolume);

        [DllImport("winmm.dll")]
        public static extern int waveInOpen(out IntPtr hWaveIn, int uDeviceID, ref WAVEFORMATEX lpFormat, WaveInProc dwCallback, IntPtr dwInstance, int dwFlags);

        [DllImport("winmm.dll")]
        public static extern int waveInPrepareHeader(IntPtr hWaveIn, IntPtr lpWaveInHdr, int uSize);

        [DllImport("winmm.dll")]
        public static extern int waveInAddBuffer(IntPtr hWaveIn, IntPtr lpWaveInHdr, int uSize);

        [DllImport("winmm.dll")]
        public static extern int waveInStart(IntPtr hWaveIn);

        [DllImport("winmm.dll")]
        public static extern int waveInStop(IntPtr hWaveIn);

        [DllImport("winmm.dll")]
        public static extern int waveInReset(IntPtr hWaveIn);

        [DllImport("winmm.dll")]
        public static extern int waveInUnprepareHeader(IntPtr hWaveIn, IntPtr lpWaveInHdr, int uSize);

        [DllImport("winmm.dll")]
        public static extern int waveInClose(IntPtr hWaveIn);

        static IntPtr _hWaveOut = IntPtr.Zero;
        static WaveOutProc _waveOutCallback = null;
        static readonly object _speakerLock = new object();
        static List<IntPtr> _activeOutHdrs = new List<IntPtr>();

        static void InitWaveOutIfNeeded()
        {
            if (_hWaveOut != IntPtr.Zero) return;
            WAVEFORMATEX fmt = new WAVEFORMATEX
            {
                wFormatTag = 1, // PCM
                nChannels = 1,
                nSamplesPerSec = 16000,
                nAvgBytesPerSec = 32000,
                nBlockAlign = 2,
                wBitsPerSample = 16,
                cbSize = 0
            };
            _waveOutCallback = OnWaveOutDone;
            waveOutOpen(out _hWaveOut, WAVE_MAPPER, ref fmt, _waveOutCallback, IntPtr.Zero, CALLBACK_FUNCTION);
        }

        static void OnWaveOutDone(IntPtr hwo, uint uMsg, IntPtr dwInstance, IntPtr dwParam1, IntPtr dwParam2)
        {
            if (uMsg == WOM_DONE && dwParam1 != IntPtr.Zero)
            {
                lock (_speakerLock)
                {
                    try
                    {
                        waveOutUnprepareHeader(hwo, dwParam1, Marshal.SizeOf(typeof(WAVEHDR)));
                        WAVEHDR hdr = (WAVEHDR)Marshal.PtrToStructure(dwParam1, typeof(WAVEHDR));
                        if (hdr.lpData != IntPtr.Zero) Marshal.FreeHGlobal(hdr.lpData);
                        Marshal.FreeHGlobal(dwParam1);
                        _activeOutHdrs.Remove(dwParam1);
                    }
                    catch { }
                }
            }
        }

        static void PlaySpeakerChunk(string base64)
        {
            try
            {
                byte[] pcm = Convert.FromBase64String(base64);
                if (pcm.Length == 0) return;

                lock (_speakerLock)
                {
                    InitWaveOutIfNeeded();
                    if (_hWaveOut == IntPtr.Zero) return;

                    IntPtr pData = Marshal.AllocHGlobal(pcm.Length);
                    Marshal.Copy(pcm, 0, pData, pcm.Length);

                    WAVEHDR hdr = new WAVEHDR
                    {
                        lpData = pData,
                        dwBufferLength = (uint)pcm.Length,
                        dwBytesRecorded = 0,
                        dwUser = IntPtr.Zero,
                        dwFlags = 0,
                        dwLoops = 0,
                        lpNext = IntPtr.Zero,
                        reserved = IntPtr.Zero
                    };

                    IntPtr pHdr = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(WAVEHDR)));
                    Marshal.StructureToPtr(hdr, pHdr, false);
                    _activeOutHdrs.Add(pHdr);

                    waveOutPrepareHeader(_hWaveOut, pHdr, Marshal.SizeOf(typeof(WAVEHDR)));
                    waveOutWrite(_hWaveOut, pHdr, Marshal.SizeOf(typeof(WAVEHDR)));
                }
            }
            catch { }
        }

        static void StopSpeakerPlayback()
        {
            lock (_speakerLock)
            {
                if (_hWaveOut != IntPtr.Zero)
                {
                    waveOutReset(_hWaveOut);
                }
            }
        }

        static void SetSpeakerVolume(int vol)
        {
            lock (_speakerLock)
            {
                InitWaveOutIfNeeded();
                if (_hWaveOut != IntPtr.Zero)
                {
                    vol = Math.Max(0, Math.Min(100, vol));
                    ushort val = (ushort)(vol * 65535 / 100);
                    uint dwVol = (uint)((val) | (val << 16));
                    waveOutSetVolume(_hWaveOut, dwVol);
                }
            }
        }

        // --- Mic Capture (PC Mic -> Phone Speaker) ---
        static IntPtr _hWaveIn = IntPtr.Zero;
        static WaveInProc _waveInCallback = null;
        static volatile bool _isMicRecording = false;
        static readonly int MIC_BUFFER_SIZE = 1600; // ~50ms of 16kHz 16-bit mono PCM
        static readonly int MIC_BUFFER_COUNT = 4;
        static IntPtr[] _inHdrs = new IntPtr[4];
        static IntPtr[] _inBuffers = new IntPtr[4];

        static void StartMicCapture()
        {
            if (_isMicRecording) return;
            try
            {
                WAVEFORMATEX fmt = new WAVEFORMATEX
                {
                    wFormatTag = 1, // PCM
                    nChannels = 1,
                    nSamplesPerSec = 16000,
                    nAvgBytesPerSec = 32000,
                    nBlockAlign = 2,
                    wBitsPerSample = 16,
                    cbSize = 0
                };

                _waveInCallback = OnWaveInChunk;
                int res = waveInOpen(out _hWaveIn, WAVE_MAPPER, ref fmt, _waveInCallback, IntPtr.Zero, CALLBACK_FUNCTION);
                if (res != 0 || _hWaveIn == IntPtr.Zero)
                {
                    _workerWriter.WriteLine("{\"type\":\"mic_status\",\"streaming\":false,\"error\":\"waveInOpen failed code " + res + "\"}");
                    return;
                }

                _isMicRecording = true;

                for (int i = 0; i < MIC_BUFFER_COUNT; i++)
                {
                    _inBuffers[i] = Marshal.AllocHGlobal(MIC_BUFFER_SIZE);
                    WAVEHDR hdr = new WAVEHDR
                    {
                        lpData = _inBuffers[i],
                        dwBufferLength = (uint)MIC_BUFFER_SIZE,
                        dwBytesRecorded = 0,
                        dwUser = new IntPtr(i),
                        dwFlags = 0,
                        dwLoops = 0,
                        lpNext = IntPtr.Zero,
                        reserved = IntPtr.Zero
                    };
                    _inHdrs[i] = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(WAVEHDR)));
                    Marshal.StructureToPtr(hdr, _inHdrs[i], false);

                    waveInPrepareHeader(_hWaveIn, _inHdrs[i], Marshal.SizeOf(typeof(WAVEHDR)));
                    waveInAddBuffer(_hWaveIn, _inHdrs[i], Marshal.SizeOf(typeof(WAVEHDR)));
                }

                waveInStart(_hWaveIn);
                _workerWriter.WriteLine("{\"type\":\"mic_status\",\"streaming\":true}");
            }
            catch (Exception ex)
            {
                _workerWriter.WriteLine("{\"type\":\"mic_status\",\"streaming\":false,\"error\":\"" + EscapeJson(ex.Message) + "\"}");
            }
        }

        static void OnWaveInChunk(IntPtr hwi, uint uMsg, IntPtr dwInstance, IntPtr dwParam1, IntPtr dwParam2)
        {
            if (uMsg == WIM_DATA && _isMicRecording && dwParam1 != IntPtr.Zero)
            {
                try
                {
                    WAVEHDR hdr = (WAVEHDR)Marshal.PtrToStructure(dwParam1, typeof(WAVEHDR));
                    if (hdr.dwBytesRecorded > 0 && hdr.lpData != IntPtr.Zero)
                    {
                        byte[] bytes = new byte[hdr.dwBytesRecorded];
                        Marshal.Copy(hdr.lpData, bytes, 0, (int)hdr.dwBytesRecorded);
                        string b64 = Convert.ToBase64String(bytes);
                        _workerWriter.WriteLine("{\"type\":\"pc_mic_chunk\",\"data\":\"" + b64 + "\"}");
                    }

                    // Re-queue buffer for continuous recording
                    if (_isMicRecording && _hWaveIn != IntPtr.Zero)
                    {
                        waveInAddBuffer(_hWaveIn, dwParam1, Marshal.SizeOf(typeof(WAVEHDR)));
                    }
                }
                catch { }
            }
        }

        static void StopMicCapture()
        {
            if (!_isMicRecording) return;
            _isMicRecording = false;

            try
            {
                if (_hWaveIn != IntPtr.Zero)
                {
                    waveInStop(_hWaveIn);
                    waveInReset(_hWaveIn);

                    for (int i = 0; i < MIC_BUFFER_COUNT; i++)
                    {
                        if (_inHdrs[i] != IntPtr.Zero)
                        {
                            waveInUnprepareHeader(_hWaveIn, _inHdrs[i], Marshal.SizeOf(typeof(WAVEHDR)));
                            Marshal.FreeHGlobal(_inHdrs[i]);
                            _inHdrs[i] = IntPtr.Zero;
                        }
                        if (_inBuffers[i] != IntPtr.Zero)
                        {
                            Marshal.FreeHGlobal(_inBuffers[i]);
                            _inBuffers[i] = IntPtr.Zero;
                        }
                    }

                    waveInClose(_hWaveIn);
                    _hWaveIn = IntPtr.Zero;
                }
                _workerWriter.WriteLine("{\"type\":\"mic_status\",\"streaming\":false}");
            }
            catch { }
        }

        static string EscapeJson(string str)
        {
            if (string.IsNullOrEmpty(str)) return "";
            return str.Replace("\\", "\\\\")
                      .Replace("\"", "\\\"")
                      .Replace("\n", "\\n")
                      .Replace("\r", "\\r")
                      .Replace("\t", "\\t");
        }
    }
}
