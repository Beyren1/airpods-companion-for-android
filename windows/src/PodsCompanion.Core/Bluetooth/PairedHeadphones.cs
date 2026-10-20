using PodsCompanion.Core.Advertising;
using Windows.Devices.Bluetooth;
using Windows.Devices.Enumeration;

namespace PodsCompanion.Core.Bluetooth;

/// <summary>Сопряжённые с ПК наушники Apple/Beats.</summary>
public sealed record PairedPods(string Name, ulong Address, bool Connected, IReadOnlySet<PodsModel> Models)
{
    /// <summary>Адрес как в путях устройств Windows: 12 шестнадцатеричных цифр без разделителей.</summary>
    public string AddressHex => Address.ToString("X12");
}

/// <summary>
/// Список сопряжённых наушников и подключены ли они к ПК сейчас. Наушники Apple узнаём по имени,
/// как в Android-приложении: модель из имени потом связывает их с рекламой рядом.
/// </summary>
public sealed class PairedHeadphones : IDisposable
{
    private readonly Dictionary<ulong, BluetoothDevice> devices = [];
    private readonly object gate = new();

    /// <summary>Наушники подключились к ПК или отключились от него (вызывается из потока Bluetooth).</summary>
    public event Action<PairedPods>? ConnectionChanged;

    public IReadOnlyList<PairedPods> Current
    {
        get
        {
            lock (gate)
            {
                return devices.Values.Select(ToPods).ToList();
            }
        }
    }

    /// <summary>Перечитать список сопряжённых устройств: пользователь мог сопрячь новые наушники.</summary>
    public async Task RefreshAsync()
    {
        var infos = await DeviceInformation.FindAllAsync(BluetoothDevice.GetDeviceSelectorFromPairingState(true));
        var seen = new HashSet<ulong>();
        foreach (var info in infos)
        {
            if (!NameMatcher.LooksLikePods(info.Name)) continue;
            BluetoothDevice? device;
            try
            {
                device = await BluetoothDevice.FromIdAsync(info.Id);
            }
            catch (Exception)
            {
                continue;
            }
            if (device == null) continue;
            seen.Add(device.BluetoothAddress);
            lock (gate)
            {
                if (devices.ContainsKey(device.BluetoothAddress))
                {
                    device.Dispose();
                    continue;
                }
                devices[device.BluetoothAddress] = device;
            }
            device.ConnectionStatusChanged += OnConnectionStatusChanged;
        }
        lock (gate)
        {
            foreach (var gone in devices.Keys.Where(a => !seen.Contains(a)).ToList())
            {
                devices[gone].ConnectionStatusChanged -= OnConnectionStatusChanged;
                devices[gone].Dispose();
                devices.Remove(gone);
            }
        }
    }

    private void OnConnectionStatusChanged(BluetoothDevice sender, object args) => ConnectionChanged?.Invoke(ToPods(sender));

    private static PairedPods ToPods(BluetoothDevice d) =>
        new(d.Name, d.BluetoothAddress, d.ConnectionStatus == BluetoothConnectionStatus.Connected, NameMatcher.ModelsForName(d.Name));

    public void Dispose()
    {
        lock (gate)
        {
            foreach (var d in devices.Values)
            {
                d.ConnectionStatusChanged -= OnConnectionStatusChanged;
                d.Dispose();
            }
            devices.Clear();
        }
    }
}
