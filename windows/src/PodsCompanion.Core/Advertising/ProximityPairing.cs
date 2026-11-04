namespace PodsCompanion.Core.Advertising;

/// <summary>Состояние одного наушника. Battery: 0..100 с шагом 10, null — наушник не на связи.</summary>
public sealed record PodState(int? Battery, bool Charging, bool InEar, bool InCase = false);

/// <summary>
/// Разобранный пакет Proximity Pairing, который AirPods постоянно рассылают по BLE.
/// <code>
/// 0      тип = 0x07
/// 1      длина = 0x19
/// 3..4   model ID (big-endian)
/// 5      статус: «в ухе» / «в кейсе», какой стороной является отправитель
/// 6      заряд: младший nibble = наушник-отправитель, старший = второй
/// 7      старший nibble = флаги зарядки, младший = заряд кейса
/// 8      счётчик открытий крышки
/// 9      цвет
/// 11..26 зашифрованная часть (без ключа не читаем)
/// </code>
/// </summary>
public sealed record ProximityPairingMessage(
    int ModelId,
    PodsModel? Model,
    bool PrimaryIsLeft,
    PodState Left,
    PodState Right,
    int? CaseBattery,
    bool CaseCharging,
    int LidCounter,
    int ColorCode)
{
    /// <summary>Наушник, который отправил пакет. У Max есть только он.</summary>
    public PodState Primary => PrimaryIsLeft ? Left : Right;

    /// <summary>Хотя бы один наушник надет.</summary>
    public bool Worn => Model?.Kind == PodsKind.Single ? Primary.InEar : Left.InEar || Right.InEar;
}

/// <summary>Разбор того же формата, что в Android-приложении (ProximityPairing.kt), и с теми же проверками на реальных пакетах.</summary>
public static class ProximityPairingParser
{
    /// <summary>Bluetooth SIG company ID Apple.</summary>
    public const ushort AppleCompanyId = 0x004C;

    private const int TypeProximityPairing = 0x07;
    private const int PayloadLength = 0x19;

    private const int StatusPrimaryInEar = 0x02;
    private const int StatusBothInCase = 0x04;
    private const int StatusSecondaryInEar = 0x08;
    private const int StatusOneInCase = 0x10;
    private const int StatusPrimaryIsLeft = 0x20;
    private const int StatusPrimaryInCase = 0x40;

    private const int ChargingPrimary = 0x1;
    private const int ChargingSecondary = 0x2;
    private const int ChargingCase = 0x4;

    /// <param name="data">Данные производителя для company ID 0x004C, без самих байт 4C 00 (так их отдаёт Windows).</param>
    /// <returns>null, если это не Proximity Pairing (Apple шлёт и другие типы пакетов).</returns>
    public static ProximityPairingMessage? Parse(ReadOnlySpan<byte> data)
    {
        if (data.Length < 2 + PayloadLength) return null;
        if (data[0] != TypeProximityPairing || data[1] != PayloadLength) return null;

        int modelId = (data[3] << 8) | data[4];
        int status = data[5];
        int batteries = data[6];
        int chargeAndCase = data[7];

        bool primaryIsLeft = (status & StatusPrimaryIsLeft) != 0;
        int chargingFlags = chargeAndCase >> 4;

        bool bothInCase = (status & StatusBothInCase) != 0;
        bool oneInCase = (status & StatusOneInCase) != 0;
        bool primaryInCase = bothInCase || (status & StatusPrimaryInCase) != 0;
        bool secondaryInCase = bothInCase || (oneInCase && !primaryInCase);

        // Флаг «в ухе» у наушника, только что положенного в кейс, бывает ещё не сброшен: «в кейсе» важнее.
        var primary = new PodState(
            NibbleToBattery(batteries & 0x0F),
            (chargingFlags & ChargingPrimary) != 0,
            !primaryInCase && (status & StatusPrimaryInEar) != 0,
            primaryInCase);
        var secondary = new PodState(
            NibbleToBattery(batteries >> 4),
            (chargingFlags & ChargingSecondary) != 0,
            !secondaryInCase && (status & StatusSecondaryInEar) != 0,
            secondaryInCase);

        return new ProximityPairingMessage(
            modelId,
            PodsModel.FromId(modelId),
            primaryIsLeft,
            primaryIsLeft ? primary : secondary,
            primaryIsLeft ? secondary : primary,
            NibbleToBattery(chargeAndCase & 0x0F),
            (chargingFlags & ChargingCase) != 0,
            data[8],
            data[9]);
    }

    /// <summary>0..10 означает 0..100 %, 15 — «нет данных».</summary>
    private static int? NibbleToBattery(int nibble) => nibble is >= 0 and <= 10 ? nibble * 10 : null;
}
