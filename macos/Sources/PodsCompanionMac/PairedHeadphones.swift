import Foundation
import IOBluetooth
import PodsCore

/// Сопряжённые с Mac наушники Apple/Beats (по имени) и подключены ли они сейчас.
struct PairedPods {
    let device: IOBluetoothDevice
    let address: UInt64
    let name: String
    let connected: Bool
    let models: Set<PodsModel>

    var hash: String { LinkMessage.hash(of: address) }
    var isSingle: Bool { models.contains { $0.kind == .single } }
    var supportsAdaptive: Bool { !isSingle && models.contains { $0.displayName.contains("Pro 2") || $0.displayName.contains("Pro 3") || $0.displayName.contains("ANC") } }
}

/// Список сопряжённых наушников через IOBluetooth. Подключение и отключение — те же вызовы, что у
/// пункта «Подключить» в настройках Bluetooth macOS: openConnection / closeConnection.
@MainActor
final class PairedHeadphones: NSObject {
    private(set) var current: [PairedPods] = []

    /// Подключение каких-то наушников поменялось.
    var onConnectionChanged: ((PairedPods) -> Void)?

    func refresh() {
        let devices = (IOBluetoothDevice.pairedDevices() as? [IOBluetoothDevice]) ?? []
        let list = devices.compactMap { device -> PairedPods? in
            guard let text = device.addressString, let address = PhoneSignal.parseAddress(text) else { return nil }
            let name = device.name ?? text
            let models = NameMatcher.models(forName: name)
            guard !models.isEmpty else { return nil }
            return PairedPods(device: device, address: address, name: name, connected: device.isConnected(), models: models)
        }
        let old = Dictionary(current.map { ($0.address, $0.connected) }, uniquingKeysWith: { a, _ in a })
        current = list
        for pods in list where old[pods.address] != nil && old[pods.address] != pods.connected {
            onConnectionChanged?(pods)
        }
    }

    /// Начать подключение (не ждёт: результат видно по refresh). false — macOS сразу отказала.
    func connect(_ pods: PairedPods) -> Bool {
        let result = pods.device.openConnection(self)
        if result != kIOReturnSuccess { log("openConnection: ошибка \(String(format: "0x%08x", result))") }
        return result == kIOReturnSuccess
    }

    func disconnect(_ pods: PairedPods) {
        let result = pods.device.closeConnection()
        if result != kIOReturnSuccess { log("closeConnection: ошибка \(String(format: "0x%08x", result))") }
    }

    /// Ответ IOBluetooth на openConnection(target).
    @objc nonisolated func connectionComplete(_ device: IOBluetoothDevice, status: IOReturn) {
        let name = device.name ?? "?"
        Task { @MainActor in
            log(status == kIOReturnSuccess ? "связь Bluetooth с \(name) установлена" : "связь Bluetooth с \(name) не установлена: \(String(format: "0x%08x", status))")
            self.refresh()
        }
    }
}
