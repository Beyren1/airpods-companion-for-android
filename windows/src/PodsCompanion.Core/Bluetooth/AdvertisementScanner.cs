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
    private BluetoothLEAdvertisementWatcher watcher;
    private readonly object gate = new();

    public NearbyTracker Tracker { get; } = new();

    /// <summary>Пришёл новый пакет (вызывается из потока Bluetooth).</summary>
    public event Action? PacketReceived;

    /// <summary>Сканирование остановилось: Bluetooth выключен или адаптер занят.</summary>
    public event Action<BluetoothError>? Stopped;

    public AdvertisementScanner()
    {
        watcher = Create(BluetoothLEScanningMode.Active);
    }

    /// <summary>
    /// Активный режим — пока наушники не у ПК: некоторые адаптеры в пассивном отдают рекламу реже.
    /// Пока звук идёт в наушники, сканируем пассивно: активный скан занимает тот же радиомодуль,
    /// и на части адаптеров звук от этого пропадает на долю секунды. Реклама Apple приходит и так.
    /// </summary>
    public void Start(bool active = true)
    {
        var mode = active ? BluetoothLEScanningMode.Active : BluetoothLEScanningMode.Passive;
        lock (gate)
        {
            if (watcher.ScanningMode != mode)
            {
                // Режим меняется только у остановленного сканера: проще завести новый.
                var old = watcher;
                watcher = Create(mode);
                Release(old);
            }
            if (watcher.Status != BluetoothLEAdvertisementWatcherStatus.Started) watcher.Start();
        }
    }

    private BluetoothLEAdvertisementWatcher Create(BluetoothLEScanningMode mode)
    {
        var created = new BluetoothLEAdvertisementWatcher { ScanningMode = mode };
        created.Received += OnReceived;
        created.Stopped += OnStopped;
        return created;
    }

    private void Release(BluetoothLEAdvertisementWatcher old)
    {
        old.Received -= OnReceived;
        old.Stopped -= OnStopped;
        if (old.Status == BluetoothLEAdvertisementWatcherStatus.Started) old.Stop();
    }

    private void OnStopped(BluetoothLEAdvertisementWatcher sender, BluetoothLEAdvertisementWatcherStoppedEventArgs e) =>
        Stopped?.Invoke(e.Error);

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
        lock (gate) Release(watcher);
    }
}
