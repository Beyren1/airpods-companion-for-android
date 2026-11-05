import Foundation

/// Последний пакет с одного адреса рекламы.
public struct NearbyPods: Sendable {
    public let id: String
    public let message: ProximityPairingMessage
    public let rssi: Int
    public let seenAt: Date
}

/// Наушники рядом по пакетам рекламы. Адрес рекламы у AirPods случайный и меняется, поэтому свои
/// наушники выбираем по модели (из имени сопряжённого устройства) и самому сильному сигналу.
/// Время передаётся снаружи, поэтому класс легко проверить тестами.
public final class NearbyTracker: @unchecked Sendable {
    /// Пакеты старше этого забываем: подключённые AirPods шлют рекламу примерно раз в 5 с.
    public static let staleAfter: TimeInterval = 20

    /// Чужие наушники за стеной слышно слабо: такие не считаем «рядом».
    public static let minRssi = -80

    private var byId: [String: NearbyPods] = [:]
    private let lock = NSLock()

    public init() {}

    public func onPacket(id: String, message: ProximityPairingMessage, rssi: Int, now: Date) {
        lock.lock(); defer { lock.unlock() }
        byId[id] = NearbyPods(id: id, message: message, rssi: rssi, seenAt: now)
    }

    /// Ближайшие из подходящих моделей; nil — таких рядом нет.
    public func best(models: Set<PodsModel>, now: Date) -> NearbyPods? {
        lock.lock(); defer { lock.unlock() }
        byId = byId.filter { now.timeIntervalSince($0.value.seenAt) <= Self.staleAfter }
        return byId.values
            .filter { pods in pods.rssi >= Self.minRssi && pods.message.model.map(models.contains) == true }
            .max { $0.rssi < $1.rssi }
    }
}
