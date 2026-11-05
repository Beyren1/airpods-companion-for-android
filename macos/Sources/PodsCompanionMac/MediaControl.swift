import AppKit
import ApplicationServices
import Foundation

/// Пауза плеера, когда наушники ушли от Mac (чтобы звук не заиграл из динамиков).
/// Сначала команда «пауза» через системный MediaRemote (как у кнопок в Пункте управления);
/// если звук всё ещё идёт и есть разрешение «Универсальный доступ» — нажимаем клавишу ⏯ за пользователя.
@MainActor
enum MediaControl {
    private typealias SendCommand = @convention(c) (Int32, CFDictionary?) -> Bool
    private static let pauseCommand: Int32 = 1

    private static let sendCommand: SendCommand? = {
        guard let handle = dlopen("/System/Library/PrivateFrameworks/MediaRemote.framework/MediaRemote", RTLD_NOW),
              let symbol = dlsym(handle, "MRMediaRemoteSendCommand") else { return nil }
        return unsafeBitCast(symbol, to: SendCommand.self)
    }()

    static func pauseAll() {
        let sent = sendCommand?(pauseCommand, nil) ?? false
        log(sent ? "пауза: команда MediaRemote" : "пауза: MediaRemote недоступен")
        after(1.5) {
            guard AudioActivity.outputRunning else { return }
            if AXIsProcessTrusted() {
                pressPlayPause()
                log("пауза: звук ещё шёл, нажал ⏯")
            } else {
                log("пауза не сработала: звук идёт, а разрешения «Универсальный доступ» нет")
            }
        }
    }

    /// Есть ли разрешение «Универсальный доступ» для клавиши ⏯; с prompt — показать системный запрос.
    static func accessibilityAllowed(prompt: Bool) -> Bool {
        let options = ["AXTrustedCheckOptionPrompt": prompt] as CFDictionary
        return AXIsProcessTrustedWithOptions(options)
    }

    /// Медиаклавиша ⏯ (NX_KEYTYPE_PLAY = 16): нажатие и отпускание.
    private static func pressPlayPause() {
        let key = 16
        for down in [true, false] {
            let flags = NSEvent.ModifierFlags(rawValue: down ? 0xA00 : 0xB00)
            let data1 = (key << 16) | ((down ? 0xA : 0xB) << 8)
            let event = NSEvent.otherEvent(
                with: .systemDefined, location: .zero, modifierFlags: flags, timestamp: 0,
                windowNumber: 0, context: nil, subtype: 8, data1: data1, data2: -1)
            event?.cgEvent?.post(tap: .cghidEventTap)
        }
    }
}
