using System.Runtime.InteropServices.WindowsRuntime;
using PodsCompanion.Core.Advertising;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Advertisement;

namespace PodsCompanion.Core.Bluetooth;

/// <summary>
/// Слушает BLE-рекламу Apple и складывает пакеты Proximity Pairing в <see cref="NearbyTracker"/>.
/// Работает обычными средствами Windows, без драйверов и без подключения к наушникам.
/// </summary>
public sealed class AdvertisementScanner : IDisposable
{
    private readonly BluetoothLEAdvertisementWatcher watcher = new()
    {
        // Активный режим: некоторые адаптеры в пассивном отдают рекламу реже.
        ScanningMode = BluetoothLEScanningMode.Active,
    };

    public NearbyTracker Tracker { get; } = new();

    /// <summary>Пришёл новый пакет (вызывается из потока Bluetooth).</summary>
    public event Action? PacketReceived;

    /// <summary>Сканирование остановилось: Bluetooth выключен или адаптер занят.</summary>
    public event Action<BluetoothError>? Stopped;

    public AdvertisementScanner()
    {
        watcher.Received += OnReceived;
        watcher.Stopped += (_, e) => Stopped?.Invoke(e.Error);
    }

    public void Start()
    {
        if (watcher.Status != BluetoothLEAdvertisementWatcherStatus.Started) watcher.Start();
    }

    private void OnReceived(BluetoothLEAdvertisementWatcher sender, BluetoothLEAdvertisementReceivedEventArgs args)
    {
        foreach (var data in args.Advertisement.ManufacturerData)
        {
            if (data.CompanyId != ProximityPairingParser.AppleCompanyId) continue;
            var message = ProximityPairingParser.Parse(data.Data.ToArray());
            if (message == null) continue;
            Tracker.OnPacket(args.BluetoothAddress, message, args.RawSignalStrengthInDBm, DateTime.UtcNow);
            PacketReceived?.Invoke();
        }
    }

    public void Dispose()
    {
        watcher.Received -= OnReceived;
        if (watcher.Status == BluetoothLEAdvertisementWatcherStatus.Started) watcher.Stop();
    }
}
