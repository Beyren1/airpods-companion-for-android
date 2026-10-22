namespace PodsCompanion.Core.Advertising;

/// <summary>Вид наушников: от него зависит, сколько значений заряда показывать.</summary>
public enum PodsKind
{
    /// <summary>Два наушника и кейс.</summary>
    Buds,
    /// <summary>Одно значение заряда без кейса (Max, накладные Beats).</summary>
    Single,
}

/// <summary>
/// Модель по двум байтам model ID из пакета Proximity Pairing (big-endian, как в эфире).
/// Список тот же, что в Android-приложении (PodsModel.kt).
/// </summary>
public sealed record PodsModel(int ModelId, string DisplayName, PodsKind Kind, bool EarDetection)
{
    public static readonly IReadOnlyList<PodsModel> All =
    [
        new(0x0220, "AirPods (1st gen)", PodsKind.Buds, true),
        new(0x0F20, "AirPods (2nd gen)", PodsKind.Buds, true),
        new(0x1320, "AirPods (3rd gen)", PodsKind.Buds, true),
        new(0x1920, "AirPods 4", PodsKind.Buds, true),
        new(0x1B20, "AirPods 4 (ANC)", PodsKind.Buds, true),
        new(0x0E20, "AirPods Pro", PodsKind.Buds, true),
        new(0x1420, "AirPods Pro 2", PodsKind.Buds, true),
        new(0x2420, "AirPods Pro 2 (USB-C)", PodsKind.Buds, true),
        new(0x2720, "AirPods Pro 3", PodsKind.Buds, true),
        new(0x0A20, "AirPods Max", PodsKind.Single, true),
        new(0x1F20, "AirPods Max (USB-C)", PodsKind.Single, true),
        new(0x0B20, "Powerbeats Pro", PodsKind.Buds, true),
        new(0x1D20, "Powerbeats Pro 2", PodsKind.Buds, true),
        new(0x1120, "Beats Studio Buds", PodsKind.Buds, false),
        new(0x1620, "Beats Studio Buds +", PodsKind.Buds, false),
        new(0x1220, "Beats Fit Pro", PodsKind.Buds, true),
        new(0x2620, "Beats Solo Buds", PodsKind.Buds, false),
        new(0x0620, "Beats Solo3", PodsKind.Single, false),
        new(0x0C20, "Beats Solo Pro", PodsKind.Single, false),
        new(0x2520, "Beats Solo 4", PodsKind.Single, false),
        new(0x0920, "Beats Studio3", PodsKind.Single, false),
        new(0x1720, "Beats Studio Pro", PodsKind.Single, false),
        new(0x0320, "Powerbeats3", PodsKind.Single, false),
        new(0x0D20, "Powerbeats", PodsKind.Single, false),
        new(0x0520, "BeatsX", PodsKind.Single, false),
        new(0x1020, "Beats Flex", PodsKind.Single, false),
    ];

    public static PodsModel? FromId(int modelId) => All.FirstOrDefault(m => m.ModelId == modelId);
}
