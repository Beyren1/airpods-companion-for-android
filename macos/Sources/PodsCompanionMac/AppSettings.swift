import Foundation

/// Настройки в UserDefaults (~/Library/Preferences/dev.podscompanion.mac.plist).
@MainActor
final class AppSettings {
    private let defaults = UserDefaults.standard

    init() {
        defaults.register(defaults: ["autoTakeOver": true, "pauseOnDisconnect": true])
    }

    /// Включили звук на Mac, наушники надеты — Mac забирает их у телефона.
    var autoTakeOver: Bool {
        get { defaults.bool(forKey: "autoTakeOver") }
        set { defaults.set(newValue, forKey: "autoTakeOver") }
    }

    /// Наушники ушли от Mac — пауза, чтобы звук не пошёл в динамики.
    var pauseOnDisconnect: Bool {
        get { defaults.bool(forKey: "pauseOnDisconnect") }
        set { defaults.set(newValue, forKey: "pauseOnDisconnect") }
    }

    /// Наушники, выбранные в меню (адрес); nil — выбирать самим.
    var selectedAddress: UInt64? {
        get { (defaults.object(forKey: "selectedAddress") as? NSNumber)?.uint64Value }
        set { defaults.set(newValue.map { NSNumber(value: $0) }, forKey: "selectedAddress") }
    }
}
