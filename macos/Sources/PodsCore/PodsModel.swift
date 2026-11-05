import Foundation

/// Вид наушников: от него зависит, сколько значений заряда показывать.
public enum PodsKind: Sendable {
    /// Два наушника и кейс.
    case buds
    /// Одно значение заряда без кейса (Max, накладные Beats).
    case single
}

/// Модель по двум байтам model ID из пакета Proximity Pairing. Список тот же, что в PodsModel.kt и PodsModel.cs.
public struct PodsModel: Hashable, Sendable {
    public let modelId: Int
    public let displayName: String
    public let kind: PodsKind
    public let earDetection: Bool

    public static let all: [PodsModel] = [
        PodsModel(modelId: 0x0220, displayName: "AirPods (1st gen)", kind: .buds, earDetection: true),
        PodsModel(modelId: 0x0F20, displayName: "AirPods (2nd gen)", kind: .buds, earDetection: true),
        PodsModel(modelId: 0x1320, displayName: "AirPods (3rd gen)", kind: .buds, earDetection: true),
        PodsModel(modelId: 0x1920, displayName: "AirPods 4", kind: .buds, earDetection: true),
        PodsModel(modelId: 0x1B20, displayName: "AirPods 4 (ANC)", kind: .buds, earDetection: true),
        PodsModel(modelId: 0x0E20, displayName: "AirPods Pro", kind: .buds, earDetection: true),
        PodsModel(modelId: 0x1420, displayName: "AirPods Pro 2", kind: .buds, earDetection: true),
        PodsModel(modelId: 0x2420, displayName: "AirPods Pro 2 (USB-C)", kind: .buds, earDetection: true),
        PodsModel(modelId: 0x2720, displayName: "AirPods Pro 3", kind: .buds, earDetection: true),
        PodsModel(modelId: 0x0A20, displayName: "AirPods Max", kind: .single, earDetection: true),
        PodsModel(modelId: 0x1F20, displayName: "AirPods Max (USB-C)", kind: .single, earDetection: true),
        PodsModel(modelId: 0x0B20, displayName: "Powerbeats Pro", kind: .buds, earDetection: true),
        PodsModel(modelId: 0x1D20, displayName: "Powerbeats Pro 2", kind: .buds, earDetection: true),
        PodsModel(modelId: 0x1120, displayName: "Beats Studio Buds", kind: .buds, earDetection: false),
        PodsModel(modelId: 0x1620, displayName: "Beats Studio Buds +", kind: .buds, earDetection: false),
        PodsModel(modelId: 0x1220, displayName: "Beats Fit Pro", kind: .buds, earDetection: true),
        PodsModel(modelId: 0x2620, displayName: "Beats Solo Buds", kind: .buds, earDetection: false),
        PodsModel(modelId: 0x0620, displayName: "Beats Solo3", kind: .single, earDetection: false),
        PodsModel(modelId: 0x0C20, displayName: "Beats Solo Pro", kind: .single, earDetection: false),
        PodsModel(modelId: 0x2520, displayName: "Beats Solo 4", kind: .single, earDetection: false),
        PodsModel(modelId: 0x0920, displayName: "Beats Studio3", kind: .single, earDetection: false),
        PodsModel(modelId: 0x1720, displayName: "Beats Studio Pro", kind: .single, earDetection: false),
        PodsModel(modelId: 0x0320, displayName: "Powerbeats3", kind: .single, earDetection: false),
        PodsModel(modelId: 0x0D20, displayName: "Powerbeats", kind: .single, earDetection: false),
        PodsModel(modelId: 0x0520, displayName: "BeatsX", kind: .single, earDetection: false),
        PodsModel(modelId: 0x1020, displayName: "Beats Flex", kind: .single, earDetection: false),
    ]

    public static func from(id: Int) -> PodsModel? { all.first { $0.modelId == id } }
}
