import AppKit
import PodsCore
import ServiceManagement

/// Значок в строке меню и его меню: где наушники, заряд, кнопки переключения и настройки.
@MainActor
final class StatusMenu: NSObject, NSMenuDelegate {
    private let controller: PodsController
    private let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
    private let menu = NSMenu()

    init(controller: PodsController) {
        self.controller = controller
        super.init()
        menu.delegate = self
        menu.autoenablesItems = false
        item.menu = menu
        controller.onChange = { [weak self] in self?.updateButton() }
        updateButton()
    }

    // MARK: Значок

    private func updateButton() {
        let snapshot = controller.snapshot()
        let symbol: String
        if snapshot.pods?.isSingle == true {
            symbol = "airpodsmax"
        } else if snapshot.pods?.models.contains(where: { $0.displayName.contains("Pro") }) == true {
            symbol = "airpodspro"
        } else {
            symbol = "airpods"
        }
        let image = NSImage(systemSymbolName: symbol, accessibilityDescription: "Pods Companion")
            ?? NSImage(systemSymbolName: "headphones", accessibilityDescription: "Pods Companion")
        image?.isTemplate = true
        item.button?.image = image
        item.button?.imagePosition = .imageLeading
        // Пока наушники не у Mac, значок полупрозрачный.
        item.button?.appearsDisabled = snapshot.pods?.connected != true
        item.button?.title = batteryDigit(snapshot).map { " \($0)%" } ?? ""
    }

    /// Заряд для значка: точный от телефона, иначе из рекламы (с шагом 10%).
    private func batteryDigit(_ snapshot: PodsSnapshot) -> Int? {
        if let text = snapshot.onPhone?.battery {
            let values = text.split(separator: "/").compactMap { Int($0) }
            if snapshot.pods?.isSingle == true { return values.first }
            return values.prefix(2).min()
        }
        return snapshot.nearby?.message.lowestBattery
    }

    // MARK: Меню

    func menuNeedsUpdate(_ menu: NSMenu) {
        menu.removeAllItems()
        let snapshot = controller.snapshot()

        if snapshot.bluetoothDenied {
            menu.addItem(info(Strings.bluetoothDenied))
        } else if snapshot.bluetoothOff {
            menu.addItem(info(Strings.bluetoothOff))
        }

        guard let pods = snapshot.pods else {
            menu.addItem(header(Strings.noPods))
            menu.addItem(info(Strings.noPodsHint))
            addCommon(snapshot)
            return
        }

        menu.addItem(header(pods.name))
        menu.addItem(info(stateText(snapshot, pods)))
        if let battery = batteryText(snapshot, pods) { menu.addItem(info(battery)) }

        if snapshot.onPhone != nil {
            menu.addItem(.separator())
            menu.addItem(info(snapshot.modeBusy ? Strings.modeBusy : Strings.modesTitle))
            let modes = pods.supportsAdaptive ? LinkPods.modes : LinkPods.modes.filter { $0 != "adaptive" }
            for mode in modes {
                let entry = action(Strings.mode(mode), #selector(chooseMode(_:)))
                entry.representedObject = mode
                entry.state = snapshot.mode == mode ? .on : .off
                entry.isEnabled = !snapshot.modeBusy
                entry.indentationLevel = 1
                menu.addItem(entry)
            }
        }

        menu.addItem(.separator())
        if snapshot.busy {
            let entry = action(Strings.connecting, nil)
            entry.isEnabled = false
            menu.addItem(entry)
        } else if pods.connected {
            menu.addItem(action(Strings.giveToPhone, #selector(giveToPhone)))
        } else {
            menu.addItem(action(Strings.connectHere, #selector(connectHere)))
        }
        addCommon(snapshot)
    }

    private func addCommon(_ snapshot: PodsSnapshot) {
        menu.addItem(.separator())
        if let failure = snapshot.linkFailure {
            menu.addItem(info(Strings.linkFailed(failure)))
        } else if let phone = snapshot.phone {
            menu.addItem(info(Strings.phoneLinked(phone.name)))
        } else {
            menu.addItem(info(Strings.phoneNotLinked))
        }
        if snapshot.micInUse { menu.addItem(info(Strings.micInUse)) }

        if snapshot.allPaired.count > 1 {
            let submenu = NSMenu()
            submenu.autoenablesItems = false
            let auto = action(Strings.chooseAutomatically, #selector(choosePods(_:)))
            auto.state = controller.settings.selectedAddress == nil ? .on : .off
            submenu.addItem(auto)
            for pods in snapshot.allPaired {
                let entry = action(pods.name, #selector(choosePods(_:)))
                entry.representedObject = NSNumber(value: pods.address)
                entry.state = controller.settings.selectedAddress == pods.address ? .on : .off
                submenu.addItem(entry)
            }
            let parent = NSMenuItem(title: Strings.headphones, action: nil, keyEquivalent: "")
            parent.submenu = submenu
            menu.addItem(parent)
        }

        let settings = NSMenu()
        settings.autoenablesItems = false
        let auto = action(Strings.autoTakeOver, #selector(toggleAutoTakeOver))
        auto.state = controller.settings.autoTakeOver ? .on : .off
        auto.toolTip = Strings.autoTakeOverHint
        settings.addItem(auto)
        let pause = action(Strings.pauseOnDisconnect, #selector(togglePause))
        pause.state = controller.settings.pauseOnDisconnect ? .on : .off
        settings.addItem(pause)
        let login = action(Strings.launchAtLogin, #selector(toggleLogin))
        login.state = SMAppService.mainApp.status == .enabled ? .on : .off
        settings.addItem(login)
        if !MediaControl.accessibilityAllowed(prompt: false) {
            settings.addItem(action(Strings.allowMediaKey, #selector(allowMediaKey)))
        }
        let parent = NSMenuItem(title: Strings.settings, action: nil, keyEquivalent: "")
        parent.submenu = settings
        menu.addItem(parent)

        menu.addItem(action(Strings.copyLog, #selector(copyLog)))
        menu.addItem(.separator())
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? ""
        menu.addItem(info("Pods Companion \(version)"))
        menu.addItem(action(Strings.quit, #selector(quit), key: "q"))
    }

    private func stateText(_ snapshot: PodsSnapshot, _ pods: PairedPods) -> String {
        var text: String
        if pods.connected {
            text = Strings.connectedHere
        } else if let phone = snapshot.phone, snapshot.onPhone != nil {
            text = Strings.connectedToPhone(phone.name)
        } else if let other = snapshot.otherComputer {
            text = Strings.connectedToOther(other.name)
        } else if snapshot.nearby != nil {
            text = Strings.nearbyElsewhere
        } else {
            text = Strings.notNearby
        }
        if snapshot.nearby?.message.worn == true { text += " · \(Strings.worn)" }
        return text
    }

    private func batteryText(_ snapshot: PodsSnapshot, _ pods: PairedPods) -> String? {
        if let exact = snapshot.onPhone?.battery {
            let parts = exact.split(separator: "/", omittingEmptySubsequences: false).map { $0 == "-" ? "—" : "\($0)%" }
            if parts.count == 3 { return Strings.budsBattery(left: parts[0], right: parts[1], case: parts[2]) }
            return Strings.battery(parts.first ?? "—")
        }
        guard let message = snapshot.nearby?.message else { return nil }
        func percent(_ value: Int?) -> String { value.map { "\($0)%" } ?? "—" }
        if pods.isSingle { return Strings.battery(percent(message.primary.battery)) }
        return Strings.budsBattery(left: percent(message.left.battery), right: percent(message.right.battery), case: percent(message.caseBattery))
    }

    private func header(_ title: String) -> NSMenuItem {
        let entry = NSMenuItem(title: title, action: nil, keyEquivalent: "")
        entry.attributedTitle = NSAttributedString(string: title, attributes: [.font: NSFont.boldSystemFont(ofSize: NSFont.systemFontSize)])
        entry.isEnabled = false
        return entry
    }

    private func info(_ title: String) -> NSMenuItem {
        let entry = NSMenuItem(title: title, action: nil, keyEquivalent: "")
        entry.isEnabled = false
        return entry
    }

    private func action(_ title: String, _ selector: Selector?, key: String = "") -> NSMenuItem {
        let entry = NSMenuItem(title: title, action: selector, keyEquivalent: key)
        entry.target = self
        return entry
    }

    // MARK: Действия

    @objc private func connectHere() { controller.takeOver(reason: "кнопка") }
    @objc private func giveToPhone() { controller.giveToPhone() }

    @objc private func chooseMode(_ sender: NSMenuItem) {
        if let mode = sender.representedObject as? String { controller.setMode(mode) }
    }

    @objc private func choosePods(_ sender: NSMenuItem) {
        controller.select((sender.representedObject as? NSNumber)?.uint64Value)
    }

    @objc private func toggleAutoTakeOver() { controller.settings.autoTakeOver.toggle() }
    @objc private func togglePause() { controller.settings.pauseOnDisconnect.toggle() }

    @objc private func toggleLogin() {
        do {
            if SMAppService.mainApp.status == .enabled {
                try SMAppService.mainApp.unregister()
            } else {
                try SMAppService.mainApp.register()
            }
        } catch {
            log("запуск при входе: \(error.localizedDescription)")
        }
    }

    @objc private func allowMediaKey() { _ = MediaControl.accessibilityAllowed(prompt: true) }

    @objc private func copyLog() {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(AppLog.shared.text, forType: .string)
        log(Strings.logCopied)
    }

    @objc private func quit() { NSApp.terminate(nil) }
}
