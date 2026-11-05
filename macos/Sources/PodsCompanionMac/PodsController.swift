import CoreBluetooth
import Foundation
import PodsCore
import UserNotifications

/// Что показывать в меню.
struct PodsSnapshot {
    let pods: PairedPods?
    let nearby: NearbyPods?
    let allPaired: [PairedPods]
    let busy: Bool
    let bluetoothOff: Bool
    let bluetoothDenied: Bool
    /// Телефон с приложением, который слышно в сети; nil — не на связи.
    let phone: LinkPeer?
    /// Эти наушники сейчас у телефона (по его же сообщению).
    let onPhone: LinkPods?
    /// Другой компьютер с программой, у которого сейчас наушники.
    let otherComputer: LinkPeer?
    let mode: String?
    let modeBusy: Bool
    let micInUse: Bool
    let linkFailure: String?
}

/// Вся логика без интерфейса: сопряжённые наушники, реклама рядом, звук на Mac и переключение
/// «как между iPhone и Mac»: включил звук на Mac — надетые наушники переходят на Mac, включил на телефоне —
/// телефон забирает их (это делает приложение на телефоне, Mac только отпускает).
/// Тот же порядок действий, что PodsController.cs у программы для Windows.
@MainActor
final class PodsController {
    /// Сколько ждать, пока наушники подключатся к Mac после команды.
    private static let connectWait: TimeInterval = 10
    /// Наше же подключение ещё несколько секунд выглядит как «звук начался»: его не считаем.
    private static let ownQuiet: TimeInterval = 5
    /// Не получилось забрать: не пробуем снова на каждое начало звука.
    private static let failedQuiet: TimeInterval = 120
    /// После ручного «Отдать телефону» не забираем наушники обратно сразу же.
    private static let manualReleaseQuiet: TimeInterval = 10
    /// Наушники ушли от Mac: звук ещё несколько секунд идёт в динамики, пока плеер не встал на паузу.
    /// Не принимаем это за «включили звук на Mac», иначе заберём наушники обратно.
    private static let leftQuiet: TimeInterval = 20
    /// Звук должен идти столько секунд подряд: короткие звуки уведомлений не считаются.
    private static let playbackSustain: TimeInterval = 2

    let settings = AppSettings()
    private let paired = PairedHeadphones()
    private let scanner = AdvertisementScanner()
    private lazy var link = DeviceLink(ownPods: { [weak self] in self?.ownPods() ?? [] })
    private var playback = PlaybackStartDetector(sustain: playbackSustain)
    private var timers: [Timer] = []
    private var busy = false
    private var modeBusy = false
    private var modes: [String: String] = [:]
    private var micInUse = false
    private var outputRunning = false
    private var releasedAt = Date.distantPast
    private var autoQuietUntil = Date.distantPast

    /// Состояние поменялось: меню и значок обновляются.
    var onChange: (() -> Void)?

    func start() {
        log("запуск, Mac: \(DeviceLink.computerName)")
        paired.onConnectionChanged = { [weak self] pods in self?.connectionChanged(pods) }
        scanner.onPacket = { [weak self] in self?.onChange?() }
        link.onRelease = { [weak self] hash in self?.releaseRequested(hash) }
        link.onGive = { [weak self] hash in self?.giveRequested(hash) }
        link.onPeersChanged = { [weak self] in self?.onChange?() }
        paired.refresh()
        log("сопряжённые наушники: \(paired.current.map { "\($0.name)\($0.connected ? " (подключены)" : "")" }.joined(separator: ", "))")
        scanner.start()
        link.start()
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert]) { _, _ in }
        timers.append(Timer.scheduledTimer(withTimeInterval: 1.5, repeats: true) { [weak self] _ in
            Task { @MainActor in
                self?.paired.refresh()
                self?.onChange?()
            }
        })
        timers.append(Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.checkAudio() }
        })
        onChange?()
    }

    func stop() {
        timers.forEach { $0.invalidate() }
        link.stop()
    }

    func snapshot() -> PodsSnapshot {
        let all = paired.current
        let pods = choose(all)
        let nearby = pods.flatMap { scanner.tracker.best(models: $0.models, now: Date()) }
        let hash = pods?.hash
        let peers = link.activePeers
        let phones = peers.filter { $0.kind == .phone }
        // Телефон, у которого сейчас эти наушники, иначе последний, от кого было слышно.
        let phone = phones.first { hash != nil && $0.holding(hash!) != nil } ?? phones.max { $0.lastSeen < $1.lastSeen }
        let onPhone = hash.flatMap { phone?.holding($0) }
        let other = hash.flatMap { h in peers.first { $0.kind == .pc && $0.holding(h) != nil } }
        let mode = onPhone?.mode ?? hash.flatMap { modes[$0] }
        return PodsSnapshot(
            pods: pods, nearby: nearby, allPaired: all, busy: busy,
            bluetoothOff: scanner.state == .poweredOff, bluetoothDenied: scanner.denied,
            phone: phone, onPhone: onPhone, otherComputer: other, mode: mode, modeBusy: modeBusy,
            micInUse: micInUse, linkFailure: link.failure)
    }

    /// Какие наушники главные: выбранные в меню, иначе подключённые к Mac, иначе надетые или ближайшие.
    private func choose(_ all: [PairedPods]) -> PairedPods? {
        if let selected = settings.selectedAddress, let chosen = all.first(where: { $0.address == selected }) { return chosen }
        if let connected = all.first(where: \.connected) { return connected }
        let now = Date()
        return all.map { ($0, scanner.tracker.best(models: $0.models, now: now)) }
            .max { a, b in
                let wornA = a.1?.message.worn == true, wornB = b.1?.message.worn == true
                if wornA != wornB { return !wornA }
                return (a.1?.rssi ?? Int.min) < (b.1?.rssi ?? Int.min)
            }?.0
    }

    func select(_ address: UInt64?) {
        settings.selectedAddress = address
        onChange?()
    }

    // MARK: Подключение

    /// Подключить наушники к Mac. Телефон (или другой компьютер) заранее просим отпустить их.
    func takeOver(reason: String) {
        guard let pods = snapshot().pods, !pods.connected, !busy else { return }
        busy = true
        onChange?()
        log("забрать на Mac (\(reason)): \(pods.name)")
        link.requestRelease(pods.address)
        attemptConnect(pods, attempt: 1)
    }

    private func attemptConnect(_ pods: PairedPods, attempt: Int) {
        let started = paired.connect(pods)
        waitConnected(pods.address, until: Date().addingTimeInterval(started ? Self.connectWait : 0)) { [weak self] ok in
            guard let self else { return }
            if !ok && attempt == 1 {
                // Иногда первая команда пропадает (например, наушники ещё отключались от телефона).
                log("не подключились — пробую ещё раз")
                self.attemptConnect(pods, attempt: 2)
                return
            }
            log(ok ? "наушники подключились к Mac" : "наушники не подключились")
            self.autoQuietUntil = Date().addingTimeInterval(ok ? Self.ownQuiet : Self.failedQuiet)
            self.busy = false
            self.onChange?()
        }
    }

    private func waitConnected(_ address: UInt64, until: Date, done: @escaping (Bool) -> Void) {
        paired.refresh()
        if paired.current.contains(where: { $0.address == address && $0.connected }) {
            done(true)
        } else if Date() >= until {
            done(false)
        } else {
            after(0.25) { [weak self] in self?.waitConnected(address, until: until, done: done) }
        }
    }

    /// Отдать наушники телефону: отключить от Mac и сказать телефону, чтобы забрал их.
    /// Подключить их к телефону может только сам телефон.
    func giveToPhone() {
        guard let pods = snapshot().pods, pods.connected else { return }
        releasedAt = Date()
        log("отдать телефону: \(pods.name)")
        paired.disconnect(pods)
        link.sendTake(pods.address)
        // Телефон обычно забирает наушники за 4–5 с. Если за 15 с не забрал — говорим об этом.
        waitPhone(pods.address, until: Date().addingTimeInterval(15))
    }

    private func waitPhone(_ address: UInt64, until: Date) {
        if link.phoneHolds(address) {
            log("телефон забрал наушники")
        } else if Date() >= until {
            log("телефон не забрал наушники")
            notify(Strings.phoneDidNotTake)
        } else {
            after(0.25) { [weak self] in self?.waitPhone(address, until: until) }
        }
    }

    /// Включить режим шумоподавления через телефон (LinkPods.modes).
    func setMode(_ mode: String) {
        guard let pods = snapshot().pods, !modeBusy else { return }
        modeBusy = true
        onChange?()
        link.setMode(pods.address, mode: mode) { [weak self] done in
            guard let self else { return }
            if let done { self.modes[pods.hash] = done }
            self.modeBusy = false
            self.onChange?()
        }
    }

    /// Телефон забирает наушники и просит отпустить их: отключаем сразу, без сигнала «забери».
    private func releaseRequested(_ hash: String) {
        guard let pods = paired.current.first(where: { $0.connected && $0.hash == hash }) else { return }
        releasedAt = Date()
        log("телефон забирает наушники: \(pods.name)")
        paired.disconnect(pods)
    }

    /// Телефон отдаёт наушники (кнопка в виджете): забираем, как по кнопке «Подключить к Mac».
    private func giveRequested(_ hash: String) {
        guard let pods = snapshot().pods, !pods.connected, pods.hash == hash else { return }
        takeOver(reason: "телефон отдал")
    }

    private func ownPods() -> [LinkPods] {
        paired.current.map { LinkPods(hash: $0.hash, here: $0.connected, inCall: $0.connected && micInUse) }
    }

    // MARK: События

    private func checkAudio() {
        let running = AudioActivity.outputRunning
        if running != outputRunning {
            outputRunning = running
            let name = AudioActivity.defaultDevice(input: false).map(AudioActivity.name) ?? "?"
            log(running ? "звук на Mac начался (\(name))" : "звук на Mac остановился")
        }
        if playback.update(running: running, now: Date()) { playbackStarted() }

        let mic = AudioActivity.inputRunning
        if mic != micInUse {
            micInUse = mic
            log(mic ? "микрофон на Mac занят (звонок): телефон не будет забирать наушники сам" : "микрофон на Mac свободен")
            link.notifyChanged()
            onChange?()
        }
    }

    private func playbackStarted() {
        guard settings.autoTakeOver, !busy else { return }
        let now = Date()
        guard now >= autoQuietUntil, now.timeIntervalSince(releasedAt) >= Self.manualReleaseQuiet else { return }
        let snapshot = snapshot()
        guard let pods = snapshot.pods, !pods.connected else { return }
        // Забираем только надетые наушники: лежащие в кейсе или на столе пусть остаются, где были.
        // Если рекламы не слышно совсем (нет разрешения Bluetooth), верим телефону: наушники у него.
        let worn = snapshot.nearby?.message.worn == true
        let noScan = !scanner.scanning
        guard worn || (noScan && snapshot.onPhone != nil) else {
            log("звук на Mac, но наушники не надеты (или их не слышно) — не забираю")
            return
        }
        log("звук на Mac, наушники \(worn ? "надеты" : "у телефона")")
        takeOver(reason: "звук на Mac")
    }

    private func connectionChanged(_ pods: PairedPods) {
        log(pods.connected ? "\(pods.name) подключены к Mac" : "\(pods.name) отключены от Mac")
        link.notifyChanged()
        if !pods.connected {
            let quiet = Date().addingTimeInterval(Self.leftQuiet)
            if quiet > autoQuietUntil { autoQuietUntil = quiet }
            // Наушники ушли (к телефону или выключились): не даём музыке заиграть из динамиков.
            if settings.pauseOnDisconnect {
                after(0.8) {
                    guard AudioActivity.outputRunning else { return }
                    MediaControl.pauseAll()
                    log("пауза: наушники ушли от Mac")
                }
            }
        }
        onChange?()
    }

    private func notify(_ text: String) {
        let content = UNMutableNotificationContent()
        content.title = "Pods Companion"
        content.body = text
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil))
    }
}
