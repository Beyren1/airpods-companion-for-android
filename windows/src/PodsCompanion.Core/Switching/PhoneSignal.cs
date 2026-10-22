using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices.WindowsRuntime;
using System.Security.Cryptography;
using System.Text;
using Windows.Devices.Bluetooth.Advertisement;

namespace PodsCompanion.Core.Switching;

/// <summary>
/// Сигнал телефону «забери наушники»: ПК несколько секунд рассылает его как BLE-рекламу и как
/// UDP-пакет в локальной сети, приложение на телефоне слышит первый дошедший и подключает наушники
/// к себе. Сам ПК подключить их к телефону не может.
///
/// Пакет (данные производителя 0xFFFF, «для тестов», или весь UDP-пакет; такой же разбирает PcSignal.kt на телефоне):
/// «PCSW», версия 1, команда 1 (забрать), 4 байта SHA-256 от адреса наушников, 2 байта номера нажатия.
/// Сам адрес не рассылаем: телефон сравнивает хэш со своими сопряжёнными наушниками.
/// </summary>
public static class PhoneSignal
{
    public const ushort CompanyId = 0xFFFF;
    public const byte Version = 1;
    public const byte CommandTake = 1;
    public const int UdpPort = 47820;
    private static readonly byte[] Magic = "PCSW"u8.ToArray();

    /// <summary>Сколько рассылать: телефон с экономным сканом слушает эфир раз в несколько секунд.</summary>
    public static readonly TimeSpan Duration = TimeSpan.FromSeconds(8);

    /// <summary>Адрес как его пишет Android: «70:F9:4A:9D:33:92».</summary>
    public static string AndroidAddress(ulong address) =>
        string.Join(':', Enumerable.Range(0, 6).Select(i => ((address >> (8 * (5 - i))) & 0xFF).ToString("X2")));

    public static byte[] AddressHash(ulong address) =>
        SHA256.HashData(Encoding.ASCII.GetBytes(AndroidAddress(address)))[..4];

    public static byte[] Payload(ulong address, ushort nonce)
    {
        var payload = new byte[12];
        Magic.CopyTo(payload, 0);
        payload[4] = Version;
        payload[5] = CommandTake;
        AddressHash(address).CopyTo(payload, 6);
        payload[10] = (byte)(nonce >> 8);
        payload[11] = (byte)nonce;
        return payload;
    }

    /// <summary>Разослать сигнал «забери наушники с адресом <paramref name="address"/>» обоими путями.</summary>
    public static Task SendTakeAsync(ulong address, Action<string> log)
    {
        // Один номер нажатия в обоих путях: телефон выполнит его один раз.
        var payload = Payload(address, (ushort)Random.Shared.Next(1, 0x10000));
        return Task.WhenAll(SendBluetoothAsync(payload, log), SendWifiAsync(payload, log));
    }

    /// <summary>UDP на широковещательный адрес каждой сети ПК, раз в секунду.</summary>
    private static async Task SendWifiAsync(byte[] payload, Action<string> log)
    {
        var targets = BroadcastAddresses().Append(IPAddress.Broadcast).Distinct().ToList();
        try
        {
            using var udp = new UdpClient { EnableBroadcast = true };
            log($"сигнал телефону по Wi-Fi: {string.Join(", ", targets)}");
            for (var sent = TimeSpan.Zero; sent < Duration; sent += TimeSpan.FromSeconds(1))
            {
                foreach (var target in targets)
                {
                    try
                    {
                        await udp.SendAsync(payload, new IPEndPoint(target, UdpPort));
                    }
                    catch (SocketException)
                    {
                        // Сеть без широковещания (например, VPN): остальные сети пробуем дальше.
                    }
                }
                await Task.Delay(TimeSpan.FromSeconds(1));
            }
        }
        catch (Exception e)
        {
            log($"сигнал телефону по Wi-Fi не отправлен: {e.Message}");
        }
    }

    /// <summary>Широковещательные адреса включённых сетей IPv4 (Ethernet и Wi-Fi), например 192.168.1.255.</summary>
    private static IEnumerable<IPAddress> BroadcastAddresses() =>
        NetworkInterface.GetAllNetworkInterfaces()
            .Where(n => n.OperationalStatus == OperationalStatus.Up && n.NetworkInterfaceType != NetworkInterfaceType.Loopback)
            .SelectMany(n => n.GetIPProperties().UnicastAddresses)
            .Where(a => a.Address.AddressFamily == AddressFamily.InterNetwork && a.IPv4Mask != null)
            .Select(a =>
            {
                var ip = a.Address.GetAddressBytes();
                var mask = a.IPv4Mask.GetAddressBytes();
                return new IPAddress(ip.Select((b, i) => (byte)(b | ~mask[i])).ToArray());
            });

    private static async Task SendBluetoothAsync(byte[] payload, Action<string> log)
    {
        var publisher = new BluetoothLEAdvertisementPublisher();
        publisher.Advertisement.ManufacturerData.Add(new BluetoothLEManufacturerData(CompanyId, payload.AsBuffer()));
        var started = new TaskCompletionSource();
        // Сразу после отключения наушников радио ещё занято: Windows ставит рассылку в очередь (Waiting)
        // и начинает её сама. Прерывает (Aborted) — если адаптер рассылать рекламу не умеет.
        publisher.StatusChanged += (_, e) =>
        {
            if (e.Status == BluetoothLEAdvertisementPublisherStatus.Started)
            {
                log("сигнал телефону по Bluetooth: забрать наушники");
                started.TrySetResult();
            }
            else if (e.Status == BluetoothLEAdvertisementPublisherStatus.Aborted)
            {
                log($"сигнал телефону по Bluetooth не отправлен: {e.Error}");
                started.TrySetResult();
            }
        };
        try
        {
            publisher.Start();
            // Ждём начала не дольше самой рассылки, потом рассылаем положенные секунды.
            await Task.WhenAny(started.Task, Task.Delay(Duration));
            if (publisher.Status != BluetoothLEAdvertisementPublisherStatus.Started)
            {
                log($"сигнал телефону по Bluetooth не начался: {publisher.Status}");
                return;
            }
            await Task.Delay(Duration);
        }
        catch (Exception e)
        {
            log($"сигнал телефону по Bluetooth не отправлен: {e.Message}");
        }
        finally
        {
            if (publisher.Status is BluetoothLEAdvertisementPublisherStatus.Started or BluetoothLEAdvertisementPublisherStatus.Waiting)
                publisher.Stop();
        }
    }
}
