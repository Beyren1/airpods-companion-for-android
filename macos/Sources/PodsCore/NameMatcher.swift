import Foundation

/// Сопоставляет имя сопряжённого Bluetooth-устройства («AirPods Max Beyren») с моделью из рекламы.
/// Тот же подход, что NameMatcher.cs и ConnectedNameMatcher на телефоне: счёт — сколько слов названия
/// модели есть в имени, а имя без слова линейки («AirPods» у AirPods 4) не подходит к Pro и Max.
public enum NameMatcher {
    private static let lineWords = ["pro", "max"]

    public static func models(forName deviceName: String) -> Set<PodsModel> {
        let name = words(deviceName)
        let scored = PodsModel.all.map { model in (model, words(model.displayName).filter(name.contains).count) }
        guard let best = scored.map(\.1).max(), best > 0 else { return [] }
        let matched = scored.filter { $0.1 == best }.map(\.0)
        let sameLine = matched.filter { model in
            lineWords.allSatisfy { name.contains($0) || !words(model.displayName).contains($0) }
        }
        return Set(sameLine.isEmpty ? matched : sameLine)
    }

    /// Похоже ли имя на наушники Apple или Beats.
    public static func looksLikePods(_ deviceName: String) -> Bool { !models(forName: deviceName).isEmpty }

    // «AirPods Pro 2 (USB-C)» → [airpods, pro, 2, usb, c]
    static func words(_ text: String) -> Set<String> {
        Set(text.lowercased().components(separatedBy: CharacterSet.alphanumerics.inverted).filter { !$0.isEmpty })
    }
}
