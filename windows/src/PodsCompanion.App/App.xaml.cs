using System;
using System.Linq;
using System.Threading;
using System.Windows;
using System.Windows.Threading;
using PodsCompanion.Core;
using Forms = System.Windows.Forms;

namespace PodsCompanion.App;

/// <summary>
/// Приложение живёт в трее: значок с зарядом в подсказке, по щелчку — окно с наушниками.
/// Запущенная копия всего одна; повторный запуск просто открывает окно уже работающей.
/// </summary>
public partial class App : Application
{
    private const string InstanceName = "PodsCompanion.SingleInstance";
    private Mutex? instanceMutex;
    private EventWaitHandle? showSignal;
    private PodsController? controller;
    private Forms.NotifyIcon? tray;
    private FlyoutWindow? flyout;
    private readonly DispatcherTimer uiTimer = new() { Interval = TimeSpan.FromMilliseconds(300) };
    private volatile bool dirty = true;

    protected override async void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        instanceMutex = new Mutex(true, InstanceName, out bool first);
        if (!first)
        {
            // Уже запущено: просим ту копию показать окно и выходим.
            if (EventWaitHandle.TryOpenExisting(InstanceName + ".Show", out var other)) other.Set();
            Shutdown();
            return;
        }
        showSignal = new EventWaitHandle(false, EventResetMode.AutoReset, InstanceName + ".Show");
        ThreadPool.RegisterWaitForSingleObject(showSignal, (_, _) => Dispatcher.BeginInvoke(ShowFlyout), null, -1, false);

        // Оформление Windows 11 (Fluent), светлое или тёмное как в системе.
        ThemeMode = ThemeMode.System;

        controller = new PodsController();
        controller.Changed += () => dirty = true;
        flyout = new FlyoutWindow(controller);
        CreateTray();
        uiTimer.Tick += (_, _) => Refresh();
        uiTimer.Start();

        await controller.StartAsync();
        if (!e.Args.Contains("--background")) ShowFlyout();
    }

    private void CreateTray()
    {
        var menu = new Forms.ContextMenuStrip();
        menu.Items.Add(Strings.Open, null, (_, _) => ShowFlyout());
        menu.Items.Add(Strings.ConnectToPc, null, (_, _) => _ = controller!.TakeOverAsync("меню трея"));
        menu.Items.Add(Strings.Disconnect, null, (_, _) => _ = controller!.ReleaseAsync());
        menu.Items.Add(new Forms.ToolStripSeparator());
        menu.Items.Add(Strings.Exit, null, (_, _) => Quit());

        tray = new Forms.NotifyIcon
        {
            Icon = new System.Drawing.Icon(GetResourceStream(new Uri("pack://application:,,,/Assets/app.ico")).Stream),
            Text = Strings.AppName,
            ContextMenuStrip = menu,
            Visible = true,
        };
        tray.MouseClick += (_, args) =>
        {
            if (args.Button == Forms.MouseButtons.Left) ToggleFlyout();
        };
    }

    private void ToggleFlyout()
    {
        if (flyout == null) return;
        // Щелчок по значку сначала уводит фокус из окна (и оно прячется), поэтому смотрим, когда оно скрылось.
        if (flyout.IsVisible || DateTime.UtcNow - flyout.HiddenAt < TimeSpan.FromMilliseconds(300)) flyout.Hide();
        else ShowFlyout();
    }

    private void ShowFlyout()
    {
        if (flyout == null) return;
        Refresh(force: true);
        flyout.ShowNearTray();
    }

    /// <summary>Обновляем окно и подсказку трея не чаще раза в 300 мс: пакеты рекламы приходят много раз в секунду.</summary>
    private void Refresh(bool force = false)
    {
        if (controller == null || (!dirty && !force)) return;
        dirty = false;
        var snapshot = controller.Snapshot();
        flyout?.Render(snapshot);
        if (tray != null) tray.Text = TrayText(snapshot);
    }

    private static string TrayText(PodsSnapshot s)
    {
        if (s.Pods == null) return Strings.AppName;
        var text = s.Pods.Name;
        if (s.Nearby is { } n)
        {
            var m = n.Message;
            text += m.Model?.Kind == Core.Advertising.PodsKind.Single
                ? $"\n{Strings.Battery(m.Primary.Battery)}"
                : $"\n{Strings.Left} {Strings.Battery(m.Left.Battery)} · {Strings.Right} {Strings.Battery(m.Right.Battery)}" +
                  (m.CaseBattery is { } c ? $" · {Strings.Case} {c}%" : "");
        }
        // У подсказки значка в трее предел 127 символов.
        return text.Length > 127 ? text[..127] : text;
    }

    public void Quit()
    {
        uiTimer.Stop();
        if (tray != null) tray.Visible = false;
        tray?.Dispose();
        controller?.Dispose();
        Shutdown();
    }
}
