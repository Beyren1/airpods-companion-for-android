import Foundation

/// Журнал соединения: последние 400 строк в памяти. Меню «Скопировать журнал» кладёт его в буфер обмена.
@MainActor
final class AppLog {
    static let shared = AppLog()
    private var lines: [String] = []
    private let formatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss.SSS"
        return f
    }()

    func add(_ message: String) {
        lines.append("\(formatter.string(from: Date()))  \(message)")
        if lines.count > 400 { lines.removeFirst(lines.count - 400) }
    }

    var text: String {
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
        let os = ProcessInfo.processInfo.operatingSystemVersionString
        return (["Pods Companion для Mac \(version), macOS \(os)"] + lines).joined(separator: "\n")
    }
}

@MainActor
func log(_ message: String) { AppLog.shared.add(message) }

/// Выполнить на главном потоке через seconds секунд.
@MainActor
func after(_ seconds: Double, _ work: @escaping @MainActor () -> Void) {
    Task { @MainActor in
        try? await Task.sleep(nanoseconds: UInt64(max(0, seconds) * 1_000_000_000))
        work()
    }
}
