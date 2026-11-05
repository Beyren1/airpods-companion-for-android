import CryptoKit
import Foundation

/// Сигнал телефону «забери наушники» (UDP-пакет на порт 47820; такой же разбирает PcSignal.kt):
/// «PCSW», версия 1, команда 1 (забрать), 4 байта SHA-256 от адреса наушников, 2 байта номера нажатия.
/// Сам адрес не рассылаем: телефон сравнивает хэш со своими сопряжёнными наушниками.
/// Программа для Windows рассылает его ещё и BLE-рекламой; macOS не даёт программам рассылать
/// данные производителя в рекламе, поэтому Mac шлёт только по Wi-Fi.
public enum PhoneSignal {
    public static let udpPort: UInt16 = 47_820
    /// Сколько секунд рассылать: телефон с выключенным экраном может пропустить первые пакеты.
    public static let duration = 8

    /// Адрес как его пишет Android: «70:F9:4A:9D:33:92».
    public static func androidAddress(_ address: UInt64) -> String {
        (0..<6).map { String(format: "%02X", (address >> (8 * (5 - UInt64($0)))) & 0xFF) }.joined(separator: ":")
    }

    /// Адрес из строки IOBluetooth («70-f9-4a-9d-33-92») или Android («70:F9:4A:9D:33:92»).
    public static func parseAddress(_ text: String) -> UInt64? {
        let hex = text.filter(\.isHexDigit)
        guard hex.count == 12 else { return nil }
        return UInt64(hex, radix: 16)
    }

    public static func addressHash(_ address: UInt64) -> [UInt8] {
        Array(SHA256.hash(data: Data(androidAddress(address).utf8)).prefix(4))
    }

    public static func payload(address: UInt64, nonce: UInt16) -> [UInt8] {
        Array("PCSW".utf8) + [1, 1] + addressHash(address) + [UInt8(nonce >> 8), UInt8(nonce & 0xFF)]
    }
}
