import Foundation

/// Состояние одного наушника. battery: 0...100 с шагом 10, nil — наушник не на связи.
public struct PodState: Equatable, Sendable {
    public let battery: Int?
    public let charging: Bool
    public let inEar: Bool
    public let inCase: Bool

    public init(battery: Int?, charging: Bool, inEar: Bool, inCase: Bool = false) {
        self.battery = battery
        self.charging = charging
        self.inEar = inEar
        self.inCase = inCase
    }
}

/// Разобранный пакет Proximity Pairing, который AirPods постоянно рассылают по BLE (формат — в ProximityPairing.cs).
public struct ProximityPairingMessage: Equatable, Sendable {
    public let modelId: Int
    public let model: PodsModel?
    public let primaryIsLeft: Bool
    public let left: PodState
    public let right: PodState
    public let caseBattery: Int?
    public let caseCharging: Bool

    /// Наушник, который отправил пакет. У Max есть только он.
    public var primary: PodState { primaryIsLeft ? left : right }

    /// Хотя бы один наушник надет (Max — на голове).
    public var worn: Bool { model?.kind == .single ? primary.inEar : left.inEar || right.inEar }

    /// Заряд для строки меню: у Max одно значение, у вкладышей — того, что разряжен сильнее.
    public var lowestBattery: Int? {
        if model?.kind == .single { return primary.battery }
        return [left.battery, right.battery].compactMap { $0 }.min()
    }
}

/// Разбор того же формата, что ProximityPairing.kt на телефоне и ProximityPairing.cs на Windows.
public enum ProximityPairingParser {
    /// Bluetooth SIG company ID Apple.
    public static let appleCompanyId = 0x004C

    /// - Parameter data: данные производителя без двух байт company ID (4C 00).
    /// - Returns: nil, если это не Proximity Pairing (Apple шлёт и другие типы пакетов).
    public static func parse(_ bytes: [UInt8]) -> ProximityPairingMessage? {
        guard bytes.count >= 2 + 0x19, bytes[0] == 0x07, bytes[1] == 0x19 else { return nil }
        let modelId = Int(bytes[3]) << 8 | Int(bytes[4])
        let status = Int(bytes[5])
        let batteries = Int(bytes[6])
        let chargeAndCase = Int(bytes[7])

        let primaryIsLeft = status & 0x20 != 0
        let chargingFlags = chargeAndCase >> 4
        let bothInCase = status & 0x04 != 0
        let oneInCase = status & 0x10 != 0
        let primaryInCase = bothInCase || status & 0x40 != 0
        let secondaryInCase = bothInCase || (oneInCase && !primaryInCase)

        // Флаг «в ухе» у наушника, только что положенного в кейс, бывает ещё не сброшен: «в кейсе» важнее.
        let primary = PodState(
            battery: nibble(batteries & 0x0F), charging: chargingFlags & 0x1 != 0,
            inEar: !primaryInCase && status & 0x02 != 0, inCase: primaryInCase)
        let secondary = PodState(
            battery: nibble(batteries >> 4), charging: chargingFlags & 0x2 != 0,
            inEar: !secondaryInCase && status & 0x08 != 0, inCase: secondaryInCase)
        return ProximityPairingMessage(
            modelId: modelId, model: PodsModel.from(id: modelId), primaryIsLeft: primaryIsLeft,
            left: primaryIsLeft ? primary : secondary, right: primaryIsLeft ? secondary : primary,
            caseBattery: nibble(chargeAndCase & 0x0F), caseCharging: chargingFlags & 0x4 != 0)
    }

    /// Данные производителя целиком, как их отдаёт CoreBluetooth: первые два байта — company ID (little-endian).
    public static func parseManufacturerData(_ data: Data) -> ProximityPairingMessage? {
        let bytes = [UInt8](data)
        guard bytes.count > 2, Int(bytes[0]) | Int(bytes[1]) << 8 == appleCompanyId else { return nil }
        return parse(Array(bytes[2...]))
    }

    /// 0...10 означает 0...100 %, 15 — «нет данных».
    private static func nibble(_ value: Int) -> Int? { (0...10).contains(value) ? value * 10 : nil }
}
