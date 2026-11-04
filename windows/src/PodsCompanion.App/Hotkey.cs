using System;
using System.Runtime.InteropServices;
using System.Windows.Interop;

namespace PodsCompanion.App;

/// <summary>
/// Глобальная горячая клавиша Ctrl+Alt+P: работает, даже когда окно программы закрыто.
/// Сообщения WM_HOTKEY приходят в невидимое окно-приёмник.
/// </summary>
internal sealed class Hotkey : IDisposable
{
    private const int WmHotkey = 0x0312;
    private const uint ModAlt = 0x0001, ModControl = 0x0002, ModNoRepeat = 0x4000;
    private const uint KeyP = 0x50;
    private const int Id = 0x5043;

    private readonly HwndSource window;
    private readonly Action pressed;

    /// <summary>false — сочетание уже занято другой программой.</summary>
    public bool Registered { get; }

    public Hotkey(Action pressed)
    {
        this.pressed = pressed;
        // HWND_MESSAGE: окно только для сообщений, его не видно и нет на панели задач.
        window = new HwndSource(new HwndSourceParameters("PodsCompanionHotkey") { ParentWindow = new IntPtr(-3) });
        window.AddHook(OnMessage);
        Registered = RegisterHotKey(window.Handle, Id, ModControl | ModAlt | ModNoRepeat, KeyP);
    }

    private IntPtr OnMessage(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
    {
        if (msg == WmHotkey && wParam.ToInt32() == Id)
        {
            handled = true;
            pressed();
        }
        return IntPtr.Zero;
    }

    public void Dispose()
    {
        if (Registered) UnregisterHotKey(window.Handle, Id);
        window.Dispose();
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool RegisterHotKey(IntPtr hWnd, int id, uint fsModifiers, uint vk);

    [DllImport("user32.dll")]
    private static extern bool UnregisterHotKey(IntPtr hWnd, int id);
}
