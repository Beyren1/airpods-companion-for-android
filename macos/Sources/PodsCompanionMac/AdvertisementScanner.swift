import CoreBluetooth
import Foundation
import PodsCore

/// BLE-реклама AirPods (Proximity Pairing): надеты ли наушники и примерный заряд. Подключаться к
/// наушникам не нужно: они сами рассылают её всем рядом, даже когда подключены к телефону.
@MainActor
final class AdvertisementScanner: NSObject, CBCentralManagerDelegate {
    let tracker = NearbyTracker()
    private var central: CBCentralManager?
    private(set) var state: CBManagerState = .unknown
    private(set) var lastPacketAt: Date?

    /// Пришёл пакет от каких-то AirPods.
    var onPacket: (() -> Void)?

    var denied: Bool { CBCentralManager.authorization == .denied || CBCentralManager.authorization == .restricted }
    var scanning: Bool { state == .poweredOn }

    func start() {
        // Создание менеджера и есть запрос разрешения «Bluetooth» при первом запуске.
        central = CBCentralManager(delegate: self, queue: .main)
    }

    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        let state = central.state
        Task { @MainActor in
            self.state = state
            log("Bluetooth: \(Self.describe(state))")
            if state == .poweredOn {
                self.central?.scanForPeripherals(withServices: nil, options: [CBCentralManagerScanOptionAllowDuplicatesKey: true])
            }
            self.onPacket?()
        }
    }

    nonisolated func centralManager(
        _ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any], rssi RSSI: NSNumber
    ) {
        guard let data = advertisementData[CBAdvertisementDataManufacturerDataKey] as? Data,
              let message = ProximityPairingParser.parseManufacturerData(data), message.model != nil else { return }
        let id = peripheral.identifier.uuidString
        let rssi = RSSI.intValue
        Task { @MainActor in
            let now = Date()
            self.tracker.onPacket(id: id, message: message, rssi: rssi, now: now)
            self.lastPacketAt = now
            self.onPacket?()
        }
    }

    private static func describe(_ state: CBManagerState) -> String {
        switch state {
        case .poweredOn: return "включён, слушаю рекламу AirPods"
        case .poweredOff: return "выключен"
        case .unauthorized: return "нет разрешения (Системные настройки → Конфиденциальность → Bluetooth)"
        case .unsupported: return "не поддерживается"
        case .resetting: return "перезапуск"
        default: return "неизвестно"
        }
    }
}
