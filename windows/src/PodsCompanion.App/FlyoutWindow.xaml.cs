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

namespace PodsCompanion.App;

/// <summary>Окно над треем, как у значков громкости и сети в Windows 11: прячется, когда щёлкают мимо.</summary>
public partial class FlyoutWindow : Window
{
    private readonly PodsController controller;
    private bool rendering;

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
            ConnectButton.Visibility = Visibility.Collapsed;
            return;
        }
        var pods = s.Pods;
        TitleText.Text = pods.Name;
        StatusText.Text = pods.Connected ? Strings.ConnectedToPc : s.Nearby != null ? Strings.NearbyElsewhere : Strings.NotNearby;

        if (s.Nearby is { } nearby)
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

        ConnectButton.Visibility = Visibility.Visible;
        ConnectButton.IsEnabled = !s.Busy;
        ConnectButton.Content = s.Busy ? Strings.Connecting : pods.Connected ? Strings.Disconnect : Strings.ConnectToPc;
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
