using System;
using System.Linq;
using System.Runtime.InteropServices;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using PodsCompanion.Core;
using PodsCompanion.Core.Advertising;
using PodsCompanion.Core.Bluetooth;
using PodsCompanion.Core.Switching;

namespace PodsCompanion.App;

/// <summary>Окно над треем, как у значков громкости и сети в Windows 11: прячется, когда щёлкают мимо.</summary>
public partial class FlyoutWindow : Window
{
    private readonly PodsController controller;
    private bool rendering;
    private Updater.Update? update;

    /// <summary>false — Ctrl+Alt+P занято другой программой, подсказку не показываем.</summary>
    public bool HotkeyWorks { get; set; } = true;

    /// <summary>Когда окно спряталось: нужно, чтобы щелчок по значку трея не открыл его снова сразу же.</summary>
    public DateTime HiddenAt { get; private set; } = DateTime.MinValue;

    public FlyoutWindow(PodsController controller)
    {
        this.controller = controller;
        InitializeComponent();
        AutoTakeOverBox.Content = Strings.AutoTakeOver;
        AutoTakeOverHint.Text = Strings.AutoTakeOverHint;
        AutoPauseBox.Content = Strings.AutoPause;
        PauseOnDisconnectBox.Content = Strings.PauseOnDisconnect;
        StartWithWindowsBox.Content = Strings.StartWithWindows;
        CopyLogButton.Content = Strings.CopyLog;
        ExitButton.Content = Strings.Exit;
        SizeChanged += (_, _) => PlaceNearTray();
    }

    public void ShowNearTray()
    {
        Show();
        PlaceNearTray();
        Activate();
    }

    private void PlaceNearTray()
    {
        var area = SystemParameters.WorkArea;
        Left = area.Right - ActualWidth - 12;
        Top = area.Bottom - ActualHeight - 12;
    }

    protected override void OnSourceInitialized(EventArgs e)
    {
        base.OnSourceInitialized(e);
        // Скруглённые углы, как у системных окон Windows 11 (на Windows 10 вызов просто ничего не делает).
        var hwnd = new WindowInteropHelper(this).Handle;
        int round = DwmwcpRound;
        _ = DwmSetWindowAttribute(hwnd, DwmwaWindowCornerPreference, ref round, sizeof(int));
    }

    private void OnDeactivated(object? sender, EventArgs e)
    {
        HiddenAt = DateTime.UtcNow;
        Hide();
    }

    public void Render(PodsSnapshot s)
    {
        rendering = true;
        try
        {
            RenderPods(s);
            RenderSettings(s);
        }
        finally
        {
            rendering = false;
        }
    }

    private void RenderPods(PodsSnapshot s)
    {
        BatteryGrid.Children.Clear();
        if (s.BluetoothOff || s.Pods == null)
        {
            TitleText.Text = s.BluetoothOff ? Strings.BluetoothOff : Strings.NoPods;
            StatusText.Text = s.BluetoothOff ? "" : Strings.NoPodsHint;
            EarText.Text = "";
            PhoneText.Text = "";
            ConnectButton.Visibility = Visibility.Collapsed;
            return;
        }
        var pods = s.Pods;
        TitleText.Text = pods.Name;
        StatusText.Text = pods.Connected ? Strings.ConnectedToPc
            : s.PodsOnPhone ? Strings.ConnectedToPhone(s.Phone!.Name)
            : s.OtherPc != null ? Strings.ConnectedToOtherPc(s.OtherPc.Name)
            : s.Nearby != null ? Strings.NearbyElsewhere : Strings.NotNearby;
        PhoneText.Text = s.Phone != null ? Strings.PhoneLinked(s.Phone.Name) : Strings.PhoneNotLinked;

        if (s.PhoneBattery != null && PhoneTiles(s.PhoneBattery, s.Nearby?.Message.Model?.Kind ?? PodsKind.Buds) is { } tiles)
        {
            // Наушники у телефона: он знает заряд точно, по AAP, а реклама даёт его с шагом 10 %.
            foreach (var tile in tiles) BatteryGrid.Children.Add(tile);
            EarText.Text = "";
        }
        else if (s.Nearby is { } nearby)
        {
            var m = nearby.Message;
            if (m.Model?.Kind == PodsKind.Single)
            {
                BatteryGrid.Children.Add(BatteryTile(Strings.Headphones, m.Primary.Battery, m.Primary.Charging));
                EarText.Text = m.Model!.EarDetection ? (m.Primary.InEar ? Strings.OnHead : Strings.NotWorn) : "";
            }
            else
            {
                BatteryGrid.Children.Add(BatteryTile(Strings.Left, m.Left.Battery, m.Left.Charging));
                BatteryGrid.Children.Add(BatteryTile(Strings.Right, m.Right.Battery, m.Right.Charging));
                BatteryGrid.Children.Add(BatteryTile(Strings.Case, m.CaseBattery, m.CaseCharging));
                int inEar = (m.Left.InEar ? 1 : 0) + (m.Right.InEar ? 1 : 0);
                EarText.Text = m.Model is { EarDetection: false } ? "" :
                    inEar == 2 ? Strings.BothInEars : inEar == 1 ? Strings.OneInEar : Strings.NotWorn;
            }
        }
        else
        {
            EarText.Text = "";
        }

        RenderModes(s);
        HotkeyHint.Text = HotkeyWorks ? Strings.HotkeyHint : "";
        ConnectButton.Visibility = Visibility.Visible;
        ConnectButton.IsEnabled = !s.Busy;
        ConnectButton.Content = s.Busy ? Strings.Connecting : pods.Connected ? Strings.Disconnect : Strings.ConnectToPc;
    }

    /// <summary>Заряд от телефона: «96» у Max, «80/85/60» у вкладышей (левый/правый/кейс).</summary>
    private static UIElement[]? PhoneTiles(string battery, PodsKind kind)
    {
        static int? Percent(string part) => int.TryParse(part, out var p) ? p : null;
        var parts = battery.Split('/');
        if (parts.Length == 1) return [BatteryTile(Strings.Headphones, Percent(parts[0]), false)];
        if (parts.Length != 3 || kind == PodsKind.Single) return null;
        return
        [
            BatteryTile(Strings.Left, Percent(parts[0]), false),
            BatteryTile(Strings.Right, Percent(parts[1]), false),
            BatteryTile(Strings.Case, Percent(parts[2]), false),
        ];
    }

    private void RenderModes(PodsSnapshot s)
    {
        // Режимы включает телефон: без него на связи или когда наушники не у ПК и не у телефона — прячем.
        bool available = s.Phone != null && (s.Pods!.Connected || s.PodsOnPhone);
        ModeSection.Visibility = available ? Visibility.Visible : Visibility.Collapsed;
        if (!available) return;
        // «Адаптивный» есть у вкладышей с шумоподавлением, у Max его нет.
        var modes = s.Nearby?.Message.Model?.Kind == PodsKind.Single ? LinkPods.Modes.Take(3) : LinkPods.Modes;
        var wanted = modes.ToList();
        if (ModeGrid.Children.Count != wanted.Count)
        {
            ModeGrid.Children.Clear();
            foreach (var mode in wanted)
            {
                var button = new System.Windows.Controls.Primitives.ToggleButton { Content = Strings.Mode(mode), Tag = mode, Margin = new Thickness(0, 0, 6, 0), Padding = new Thickness(6, 6, 6, 6) };
                button.Click += OnModeClick;
                ModeGrid.Children.Add(button);
            }
        }
        foreach (var button in ModeGrid.Children.OfType<System.Windows.Controls.Primitives.ToggleButton>())
        {
            button.IsChecked = (string)button.Tag == s.Mode;
            button.IsEnabled = !s.ModeBusy;
        }
        ModeHint.Text = s.ModeBusy ? Strings.ModeBusy : Strings.ModeViaPhone(s.Phone!.Name);
    }

    private async void OnModeClick(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement { Tag: string mode }) await controller.SetModeAsync(mode);
    }

    private static UIElement BatteryTile(string label, int? percent, bool charging)
    {
        var panel = new StackPanel { Margin = new Thickness(0, 0, 10, 0) };
        panel.Children.Add(new TextBlock { Text = label, Opacity = 0.7, FontSize = 12 });
        panel.Children.Add(new TextBlock
        {
            Text = Strings.Battery(percent) + (charging ? " ⚡" : ""),
            FontSize = 22,
            FontWeight = FontWeights.SemiBold,
            ToolTip = charging ? Strings.Charging : null,
        });
        panel.Children.Add(new ProgressBar { Value = percent ?? 0, Maximum = 100, Height = 4, Margin = new Thickness(0, 4, 0, 0) });
        return panel;
    }

    private void RenderSettings(PodsSnapshot s)
    {
        var settings = controller.Settings;
        AutoTakeOverBox.IsChecked = settings.AutoTakeOver;
        AutoPauseBox.IsChecked = settings.AutoPause;
        PauseOnDisconnectBox.IsChecked = settings.PauseOnDisconnect;
        StartWithWindowsBox.IsChecked = Startup.Enabled;

        // Выбор наушников нужен, только если сопряжено несколько пар.
        PodsPicker.Visibility = s.AllPaired.Count > 1 ? Visibility.Visible : Visibility.Collapsed;
        var names = s.AllPaired.Select(p => p.Name).ToList();
        if (!PodsPicker.Items.Cast<PairedPods>().Select(p => p.Name).SequenceEqual(names))
        {
            PodsPicker.Items.Clear();
            foreach (var p in s.AllPaired) PodsPicker.Items.Add(p);
            PodsPicker.DisplayMemberPath = nameof(PairedPods.Name);
        }
        PodsPicker.SelectedItem = PodsPicker.Items.Cast<PairedPods>().FirstOrDefault(p => p.Address == s.Pods?.Address);
    }

    private async void OnConnectClick(object sender, RoutedEventArgs e)
    {
        var pods = controller.Snapshot().Pods;
        if (pods == null) return;
        if (pods.Connected) await controller.ReleaseAsync();
        else await controller.TakeOverAsync("кнопка");
    }

    /// <summary>Показать кнопку обновления: на GitHub есть версия новее.</summary>
    internal void ShowUpdate(Updater.Update found)
    {
        update = found;
        UpdateButton.Content = Strings.UpdateTo(found.Version.ToString());
        UpdateButton.IsEnabled = true;
        UpdateButton.Visibility = Visibility.Visible;
    }

    private async void OnUpdateClick(object sender, RoutedEventArgs e)
    {
        if (update == null) return;
        UpdateButton.IsEnabled = false;
        UpdateButton.Content = Strings.Updating;
        try
        {
            await Updater.InstallAsync(update);
            // Установщик заменит файлы и сам запустит новую версию.
            ((App)Application.Current).Quit();
        }
        catch (Exception ex)
        {
            controller.Log($"обновление: {ex.Message}");
            UpdateButton.Content = Strings.UpdateFailed;
            UpdateButton.IsEnabled = true;
        }
    }

    private void OnPodsPicked(object sender, SelectionChangedEventArgs e)
    {
        if (rendering || PodsPicker.SelectedItem is not PairedPods picked) return;
        controller.Settings.SelectedAddress = picked.Address;
        controller.Settings.Save();
    }

    private void OnSettingClick(object sender, RoutedEventArgs e)
    {
        var settings = controller.Settings;
        settings.AutoTakeOver = AutoTakeOverBox.IsChecked == true;
        settings.AutoPause = AutoPauseBox.IsChecked == true;
        settings.PauseOnDisconnect = PauseOnDisconnectBox.IsChecked == true;
        settings.Save();
    }

    private void OnStartWithWindowsClick(object sender, RoutedEventArgs e)
    {
        try
        {
            Startup.Enabled = StartWithWindowsBox.IsChecked == true;
        }
        catch (Exception ex)
        {
            controller.Log($"автозапуск: {ex.Message}");
            StartWithWindowsBox.IsChecked = Startup.Enabled;
        }
    }

    private async void OnCopyLogClick(object sender, RoutedEventArgs e)
    {
        Clipboard.SetText(controller.LogText());
        CopyLogButton.Content = Strings.LogCopied;
        await Task.Delay(1500);
        CopyLogButton.Content = Strings.CopyLog;
    }

    private void OnExitClick(object sender, RoutedEventArgs e)
    {
        // Выход тем же путём, что из меню трея: значок убирается, Bluetooth освобождается.
        ((App)Application.Current).Quit();
    }

    private const int DwmwaWindowCornerPreference = 33;
    private const int DwmwcpRound = 2;

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int size);
}
