import Foundation
import PodsCore
import SystemConfiguration

/// Телефон или другой компьютер, который слышно в сети: pods — какие наушники у него и в каком состоянии.
struct LinkPeer {
    let id: String
    let kind: LinkMessage.Kind
    let name: String
    let address: IPv4
    let pods: [LinkPods]
    let lastSeen: Date

    func holding(_ hash: String) -> LinkPods? { pods.first { $0.hash == hash && $0.here } }
}

/// Постоянная связь с приложением на телефоне и с программой на других компьютерах (UDP, порт 47820).
/// Тот же протокол, что DeviceLink.cs у программы для Windows: телефон видит Mac как ещё один «ПК».
/// Раз в 30 с и при каждом изменении сообщаем, какие наушники подключены к Mac: широковещательно
/// и напрямую каждому известному устройству. Кто забирает наушники, сначала просит держателя отпустить.
@MainActor
final class DeviceLink {
    static let helloPeriod: TimeInterval = 30
    /// Сколько устройство считается на связи без новых сообщений.
    static let peerTimeout: TimeInterval = 90
    /// Телефону нужно открыть канал к наушникам и дождаться подтверждения режима.
    static let modeWait: TimeInterval = 15

    private let id = String(format: "%08x", UInt32.random(in: 0...UInt32.max))
    private var peers: [String: LinkPeer] = [:]
    private var modeWaits: [String: (String?) -> Void] = [:]
    private let socket = UdpSocket()
    private var timer: Timer?
    private let ownPods: () -> [LinkPods]
    private(set) var failure: String?

    /// Телефон или другой компьютер просит отпустить наушники с этим хэшем.
    var onRelease: ((String) -> Void)?
    /// Телефон отдаёт наушники компьютеру (кнопка «⇄» в виджете).
    var onGive: ((String) -> Void)?
    var onPeersChanged: (() -> Void)?

    init(ownPods: @escaping () -> [LinkPods]) {
        self.ownPods = ownPods
    }

    static var computerName: String {
        (SCDynamicStoreCopyComputerName(nil, nil) as String?) ?? Host.current().localizedName ?? "Mac"
    }

    var activePeers: [LinkPeer] {
        let now = Date()
        return peers.values.filter { now.timeIntervalSince($0.lastSeen) < Self.peerTimeout }
    }

    func start() {
        if let error = socket.open(port: LinkMessage.port) {
            failure = error
            log("связь с телефоном недоступна: \(error)")
            return
        }
        socket.onReceive = { [weak self] bytes, from in self?.receive(bytes, from: from) }
        log("связь с телефоном: слушаю UDP \(LinkMessage.port)")
        sayHello(.hi)
        timer = Timer.scheduledTimer(withTimeInterval: Self.helloPeriod, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.sayHello(.hi) }
        }
    }

    func stop() {
        timer?.invalidate()
        socket.close()
    }

    /// Наше состояние поменялось: сразу сообщаем остальным.
    func notifyChanged() { sayHello(.hello) }

    /// Перед тем как забрать наушники: если они у телефона или другого компьютера, просим отпустить
    /// и сразу подключаем сами, не дожидаясь ответа. Просьба нужна, чтобы телефон поставил музыку на паузу
    /// и не забирал наушники обратно.
    func requestRelease(_ address: UInt64) {
        let hash = LinkMessage.hash(of: address)
        let message = message(.release, [LinkPods(hash: hash, here: false)])
        for holder in activePeers where holder.holding(hash) != nil {
            log("прошу \(holder.name) отпустить наушники")
            sendRepeated(message, to: holder.address)
        }
    }

    /// Включить наушникам режим через телефон (пока наушники у телефона). Ответ — режим, который
    /// наушники подтвердили, или nil.
    func setMode(_ address: UInt64, mode: String, done: @escaping (String?) -> Void) {
        let hash = LinkMessage.hash(of: address)
        guard let phone = activePeers.filter({ $0.kind == .phone }).max(by: { $0.lastSeen < $1.lastSeen }) else {
            done(nil)
            return
        }
        log("режим \(mode) через телефон \(phone.name)")
        modeWaits[hash] = done
        send(message(.mode, [LinkPods(hash: hash, here: false, mode: mode)]), to: phone.address)
        after(Self.modeWait) { [weak self] in
            guard let wait = self?.modeWaits.removeValue(forKey: hash) else { return }
            log("режим не включился")
            wait(nil)
        }
    }

    /// Телефон держит эти наушники (по его последнему hello).
    func phoneHolds(_ address: UInt64) -> Bool {
        let hash = LinkMessage.hash(of: address)
        return activePeers.contains { $0.kind == .phone && $0.holding(hash) != nil }
    }

    /// Сигнал «забери наушники» (PCSW) раз в секунду 8 секунд — на каждую сеть и напрямую знакомым телефонам.
    func sendTake(_ address: UInt64) {
        let payload = PhoneSignal.payload(address: address, nonce: UInt16.random(in: 1...UInt16.max))
        let targets = targets()
        log("сигнал телефону по Wi-Fi: \(targets.map(\.description).joined(separator: ", "))")
        for second in 0..<PhoneSignal.duration {
            after(Double(second)) { [weak self] in
                guard let self else { return }
                for target in targets { self.socket.send(payload, to: target, port: PhoneSignal.udpPort) }
            }
        }
    }

    private func message(_ type: LinkMessage.MessageType, _ pods: [LinkPods]) -> LinkMessage {
        LinkMessage(type: type, id: id, kind: .pc, pods: pods, name: Self.computerName)
    }

    private func targets() -> [IPv4] {
        var list = IPv4.broadcastAddresses()
        list.append(.broadcast)
        list.append(contentsOf: activePeers.map(\.address))
        var seen = Set<IPv4>()
        return list.filter { seen.insert($0).inserted }
    }

    private func sayHello(_ type: LinkMessage.MessageType) {
        let message = message(type, ownPods())
        for target in targets() { send(message, to: target) }
        onPeersChanged?() // кто-то мог пропасть по таймауту
    }

    /// Просьбы шлём трижды (0, 150, 400 мс): в Wi-Fi один пакет иногда теряется. Повтор безопасен.
    private func sendRepeated(_ message: LinkMessage, to address: IPv4) {
        for delay in [0, 150, 400] {
            after(Double(delay) / 1000) { [weak self] in
                self?.send(message, to: address)
            }
        }
    }

    private var sendFailureLogged = false

    private func send(_ message: LinkMessage, to address: IPv4) {
        let error = socket.send(Array(message.encode().utf8), to: address, port: LinkMessage.port)
        if error != 0, !sendFailureLogged {
            sendFailureLogged = true
            log("не отправилось на \(address): \(String(cString: strerror(error)))")
        } else if error == 0 {
            sendFailureLogged = false
        }
    }

    private func receive(_ bytes: [UInt8], from: IPv4) {
        // Сигнал «забери наушники» (PCSW) идёт на тот же порт, но он для телефона.
        guard let text = String(bytes: bytes, encoding: .utf8), let message = LinkMessage.parse(text), message.id != id else { return }
        if message.type == .modedone {
            for pods in message.pods {
                if let wait = modeWaits.removeValue(forKey: pods.hash) {
                    log("режим включён: \(pods.mode ?? "?")")
                    wait(pods.mode)
                }
            }
            return
        }
        let old = peers[message.id]
        let isNew = old.map { Date().timeIntervalSince($0.lastSeen) >= Self.peerTimeout } ?? true
        // В просьбах (release, mode) только нужные наушники: прежнее состояние устройства сохраняем.
        let pods = message.type == .hi || message.type == .hello ? message.pods : old?.pods ?? []
        peers[message.id] = LinkPeer(id: message.id, kind: message.kind, name: message.name, address: from, pods: pods, lastSeen: Date())
        if isNew { log("\(message.kind == .phone ? "телефон" : "компьютер") на связи: \(message.name) (\(from))") }
        switch message.type {
        case .hi: send(self.message(.hello, ownPods()), to: from)
        case .release: message.pods.forEach { onRelease?($0.hash) }
        case .give: message.pods.forEach { onGive?($0.hash) }
        default: break
        }
        onPeersChanged?()
    }
}
