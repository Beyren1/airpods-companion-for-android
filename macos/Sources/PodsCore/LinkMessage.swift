import Foundation

/// Наушники в сообщении связи: hash — 8 hex-символов, here — подключены к отправителю, battery — точный
/// заряд от телефона («96» или «80/85/60»), mode — режим. inCall — на компьютере звонок (занят микрофон),
/// телефону нельзя забирать наушники сам; на проводе это «2» вместо «1».
public struct LinkPods: Equatable, Sendable {
    public static let modes = ["off", "anc", "transparency", "adaptive"]

    public let hash: String
    public let here: Bool
    public let battery: String?
    public let mode: String?
    public let inCall: Bool

    public init(hash: String, here: Bool, battery: String? = nil, mode: String? = nil, inCall: Bool = false) {
        self.hash = hash
        self.here = here
        self.battery = battery
        self.mode = mode
        self.inCall = inCall
    }

    public func encode() -> String {
        let text = "\(hash):\(here && inCall ? 2 : here ? 1 : 0)"
        return battery == nil && mode == nil ? text : "\(text):\(battery ?? "-"):\(mode ?? "-")"
    }

    public static func parse(_ text: String) -> LinkPods? {
        let parts = text.split(separator: ":", omittingEmptySubsequences: false).map(String.init)
        guard parts.count == 2 || parts.count == 4, ["0", "1", "2"].contains(parts[1]) else { return nil }
        let battery = parts.count == 4 && parts[2] != "-" ? parts[2] : nil
        let mode = parts.count == 4 && modes.contains(parts[3]) ? parts[3] : nil
        return LinkPods(hash: parts[0].lowercased(), here: parts[1] != "0", battery: battery, mode: mode, inCall: parts[1] == "2")
    }
}

/// Сообщение связи между программой на компьютере и приложением на телефоне (UDP, домашняя сеть).
/// Одна строка: «PCLINK 1 тип id phone|pc наушники имя». Тот же формат у LinkMessage.kt и LinkMessage.cs:
/// для телефона Mac — такой же «pc», как программа для Windows.
public struct LinkMessage: Equatable, Sendable {
    /// hi — «я здесь, ответь»; hello — состояние; release — «отпусти эти наушники»; taking — «забираю»;
    /// mode — «включи этим наушникам режим» (просим телефон); modedone — ответ на mode; give — «забери у меня».
    public enum Kind: String, Sendable { case phone, pc }

    public enum MessageType: String, Sendable, CaseIterable {
        case hi, hello, release, taking, mode, modedone, give
    }

    public static let port: UInt16 = PhoneSignal.udpPort

    public let type: MessageType
    public let id: String
    public let kind: Kind
    public let pods: [LinkPods]
    public let name: String

    public init(type: MessageType, id: String, kind: Kind, pods: [LinkPods], name: String) {
        self.type = type
        self.id = id
        self.kind = kind
        self.pods = pods
        self.name = name
    }

    public func encode() -> String {
        let podsText = pods.isEmpty ? "-" : pods.map { $0.encode() }.joined(separator: ",")
        let clean = name.replacingOccurrences(of: "\n", with: " ").replacingOccurrences(of: "\r", with: " ")
            .trimmingCharacters(in: .whitespaces)
        return "PCLINK 1 \(type.rawValue) \(id) \(kind.rawValue) \(podsText) \(clean.isEmpty ? "?" : clean)"
    }

    public static func parse(_ text: String) -> LinkMessage? {
        let parts = text.trimmingCharacters(in: .whitespacesAndNewlines)
            .split(separator: " ", maxSplits: 6, omittingEmptySubsequences: false).map(String.init)
        guard parts.count == 7, parts[0] == "PCLINK", parts[1] == "1",
              let type = MessageType(rawValue: parts[2]), let kind = Kind(rawValue: parts[4]) else { return nil }
        var pods: [LinkPods] = []
        if parts[5] != "-" {
            for item in parts[5].split(separator: ",") {
                guard let parsed = LinkPods.parse(String(item)) else { return nil }
                pods.append(parsed)
            }
        }
        return LinkMessage(type: type, id: parts[3], kind: kind, pods: pods, name: parts[6])
    }

    /// Хэш наушников для сообщений: тот же, что в сигнале PhoneSignal.
    public static func hash(of address: UInt64) -> String {
        PhoneSignal.addressHash(address).map { String(format: "%02x", $0) }.joined()
    }
}
