namespace PodsCompanion.Core.Advertising;

/// <summary>Последний пакет с одного адреса рекламы.</summary>
public sealed record NearbyPods(ulong Address, ProximityPairingMessage Message, int Rssi, DateTime SeenAt);

/// <summary>
/// Наушники рядом по пакетам рекламы. Адрес рекламы у AirPods случайный и меняется, поэтому
/// свои наушники выбираем по модели (из имени сопряжённого устройства) и самому сильному сигналу.
/// Чистый класс без Bluetooth: время передаётся снаружи, поэтому легко тестируется.
/// </summary>
public sealed class NearbyTracker
{
    /// <summary>Пакеты старше этого забываем: подключённые AirPods шлют рекламу примерно раз в 5 с.</summary>
    public static readonly TimeSpan StaleAfter = TimeSpan.FromSeconds(20);

    /// <summary>Чужие наушники за стеной слышно слабо: такие не считаем «рядом».</summary>
    public const int MinRssi = -80;

    private readonly Dictionary<ulong, NearbyPods> byAddress = [];
    private readonly object gate = new();

    public void OnPacket(ulong address, ProximityPairingMessage message, int rssi, DateTime now)
    {
        lock (gate) byAddress[address] = new NearbyPods(address, message, rssi, now);
    }

    /// <summary>Ближайшие из подходящих моделей; null — таких рядом нет.</summary>
    public NearbyPods? Best(IReadOnlySet<PodsModel> models, DateTime now)
    {
        lock (gate)
        {
            Prune(now);
            return byAddress.Values
                .Where(p => p.Rssi >= MinRssi && p.Message.Model is { } m && models.Contains(m))
                .MaxBy(p => p.Rssi);
        }
    }

    private void Prune(DateTime now)
    {
        foreach (var stale in byAddress.Where(kv => now - kv.Value.SeenAt > StaleAfter).Select(kv => kv.Key).ToList())
            byAddress.Remove(stale);
    }
}
