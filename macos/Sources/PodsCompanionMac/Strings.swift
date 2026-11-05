import Foundation

/// Тексты интерфейса на трёх языках, как в Android-приложении: русский, английский, французский.
enum Strings {
    private static let lang = String((Locale.preferredLanguages.first ?? "en").prefix(2))

    private static func t(_ en: String, _ ru: String, _ fr: String) -> String {
        switch lang {
        case "ru": return ru
        case "fr": return fr
        default: return en
        }
    }

    static var noPods: String { t("No paired AirPods", "Нет сопряжённых AirPods", "Aucun AirPods associé") }
    static var noPodsHint: String {
        t("Pair them in System Settings → Bluetooth", "Сопряги их в Системных настройках → Bluetooth",
          "Associez-les dans Réglages Système → Bluetooth")
    }
    static var bluetoothOff: String { t("Bluetooth is off", "Bluetooth выключен", "Bluetooth désactivé") }
    static var bluetoothDenied: String {
        t("Allow Bluetooth: System Settings → Privacy & Security → Bluetooth",
          "Разреши Bluetooth: Системные настройки → Конфиденциальность и безопасность → Bluetooth",
          "Autorisez le Bluetooth : Réglages Système → Confidentialité et sécurité → Bluetooth")
    }
    static var connectedHere: String { t("Connected to this Mac", "Подключены к этому Mac", "Connectés à ce Mac") }
    static func connectedToPhone(_ phone: String) -> String {
        t("Connected to the phone (\(phone))", "Подключены к телефону (\(phone))", "Connectés au téléphone (\(phone))")
    }
    static func connectedToOther(_ name: String) -> String {
        t("Connected to \(name)", "Подключены к \(name)", "Connectés à \(name)")
    }
    static var nearbyElsewhere: String {
        t("Nearby, on another device or in the case", "Рядом, у другого устройства или в кейсе",
          "À proximité, sur un autre appareil ou dans le boîtier")
    }
    static var notNearby: String { t("Not connected", "Не подключены", "Non connectés") }
    static var worn: String { t("worn", "надеты", "portés") }
    static func battery(_ text: String) -> String { t("Battery: \(text)", "Заряд: \(text)", "Batterie : \(text)") }
    static func budsBattery(left: String, right: String, case box: String) -> String {
        t("L \(left) · R \(right) · Case \(box)", "Л \(left) · П \(right) · Кейс \(box)", "G \(left) · D \(right) · Boîtier \(box)")
    }
    static var connectHere: String { t("Connect to Mac", "Подключить к Mac", "Connecter au Mac") }
    static var giveToPhone: String { t("Give to phone", "Отдать телефону", "Donner au téléphone") }
    static var connecting: String { t("Connecting…", "Подключаю…", "Connexion…") }
    static var modesTitle: String { t("Noise control (via phone)", "Режим (через телефон)", "Contrôle du bruit (via le téléphone)") }
    static var modeBusy: String { t("Switching mode…", "Включаю режим…", "Changement de mode…") }
    static func mode(_ mode: String) -> String {
        switch mode {
        case "off": return t("Off", "Выкл", "Désactivé")
        case "anc": return t("Noise cancellation", "Шумоподавление", "Réduction du bruit")
        case "transparency": return t("Transparency", "Прозрачность", "Transparence")
        case "adaptive": return t("Adaptive", "Адаптивный", "Adaptatif")
        default: return mode
        }
    }
    static func phoneLinked(_ phone: String) -> String {
        t("Phone \(phone) is linked", "Телефон \(phone) на связи", "Téléphone \(phone) connecté")
    }
    static var phoneNotLinked: String {
        t("Phone app not found on the network", "Приложение на телефоне не найдено в сети",
          "Application du téléphone introuvable sur le réseau")
    }
    static func linkFailed(_ error: String) -> String {
        t("Network link unavailable: \(error)", "Связь по сети недоступна: \(error)", "Liaison réseau indisponible : \(error)")
    }
    static var micInUse: String {
        t("Microphone in use: the phone won't take the headphones by itself",
          "Микрофон занят: телефон не заберёт наушники сам",
          "Micro utilisé : le téléphone ne prendra pas le casque tout seul")
    }
    static var headphones: String { t("Headphones", "Наушники", "Écouteurs") }
    static var chooseAutomatically: String { t("Choose automatically", "Выбирать самим", "Choisir automatiquement") }
    static var settings: String { t("Settings", "Настройки", "Réglages") }
    static var autoTakeOver: String {
        t("Switch to Mac automatically", "Переключать на Mac само", "Basculer vers le Mac automatiquement")
    }
    static var autoTakeOverHint: String {
        t("Sound starts on the Mac and you wear the headphones: the Mac takes them from the phone.",
          "Включил звук на Mac, наушники надеты — Mac забирает их у телефона.",
          "Le son démarre sur le Mac et vous portez le casque : le Mac le prend au téléphone.")
    }
    static var pauseOnDisconnect: String {
        t("Pause when headphones leave the Mac", "Пауза, когда наушники ушли от Mac", "Pause quand le casque quitte le Mac")
    }
    static var launchAtLogin: String { t("Open at login", "Запускать при входе", "Ouvrir à la connexion") }
    static var allowMediaKey: String {
        t("Allow pausing any player (Accessibility)…", "Разрешить паузу любого плеера (Универсальный доступ)…",
          "Autoriser la pause de tout lecteur (Accessibilité)…")
    }
    static var copyLog: String { t("Copy connection log", "Скопировать журнал соединения", "Copier le journal de connexion") }
    static var logCopied: String { t("Log copied", "Журнал скопирован", "Journal copié") }
    static var quit: String { t("Quit Pods Companion", "Выйти из Pods Companion", "Quitter Pods Companion") }
    static var phoneDidNotTake: String {
        t("The phone didn't take the headphones. Check that it's on the same Wi-Fi and the app runs in the background.",
          "Телефон не забрал наушники. Проверь, что он в той же Wi-Fi сети и приложение работает в фоне.",
          "Le téléphone n'a pas pris le casque. Vérifiez le même Wi-Fi et que l'appli tourne en arrière-plan.")
    }
}
