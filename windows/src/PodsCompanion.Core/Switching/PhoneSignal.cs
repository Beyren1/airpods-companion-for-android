using System.Runtime.InteropServices.WindowsRuntime;
using System.Security.Cryptography;
using System.Text;
using Windows.Devices.Bluetooth.Advertisement;

namespace PodsCompanion.Core.Switching;

/// <summary>
/// Сигнал телефону «забери наушники» по Bluetooth: ПК несколько секунд рассылает BLE-рекламу,
/// приложение на телефоне её слышит и подключает наушники к себе. Сам ПК подключить их
/// к телефону не может, а Wi-Fi и интернет для этого не нужны.
///
/// Пакет (данные производителя 0xFFFF, «для тестов», такой же разбирает PcSignal.kt на телефоне):
/// «PCSW», версия 1, команда 1 (забрать), 4 байта SHA-256 от адреса наушников, 2 байта номера нажатия.
/// Сам адрес не рассылаем: телефон сравнивает хэш со своими сопряжёнными наушниками.
/// </summary>
public static class PhoneSignal
{
    public const ushort CompanyId = 0xFFFF;
    public const byte Version = 1;
    public const byte CommandTake = 1;
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

    /// <summary>Разослать сигнал «забери наушники с адресом <paramref name="address"/>».</summary>
    public static async Task SendTakeAsync(ulong address, Action<string> log)
    {
        var publisher = new BluetoothLEAdvertisementPublisher();
        publisher.Advertisement.ManufacturerData.Add(
            new BluetoothLEManufacturerData(CompanyId, Payload(address, (ushort)Random.Shared.Next(1, 0x10000)).AsBuffer()));
        try
        {
            publisher.Start();
            await Task.Delay(TimeSpan.FromMilliseconds(500));
            // Не все адаптеры умеют рассылать рекламу: тогда Windows сразу прерывает рассылку.
            if (publisher.Status != BluetoothLEAdvertisementPublisherStatus.Started)
            {
                log($"сигнал телефону не отправлен: {publisher.Status}");
                return;
            }
            log("сигнал телефону: забрать наушники");
            await Task.Delay(Duration);
        }
        catch (Exception e)
        {
            log($"сигнал телефону не отправлен: {e.Message}");
        }
        finally
        {
            if (publisher.Status == BluetoothLEAdvertisementPublisherStatus.Started) publisher.Stop();
        }
    }
}
