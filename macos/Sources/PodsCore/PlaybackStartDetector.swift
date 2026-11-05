import Foundation

/// Начало звука на Mac по флагу CoreAudio «устройство вывода работает»: звук должен идти не меньше
/// sustain секунд подряд, чтобы короткие звуки уведомлений не забирали наушники. Срабатывает один раз
/// на каждое начало звука.
public struct PlaybackStartDetector: Sendable {
    public let sustain: TimeInterval
    private var runningSince: Date?
    private var fired = false

    public init(sustain: TimeInterval) { self.sustain = sustain }

    /// true — звук только что «по-настоящему» начался.
    public mutating func update(running: Bool, now: Date) -> Bool {
        guard running else {
            runningSince = nil
            fired = false
            return false
        }
        let since = runningSince ?? now
        runningSince = since
        guard !fired, now.timeIntervalSince(since) >= sustain else { return false }
        fired = true
        return true
    }
}
