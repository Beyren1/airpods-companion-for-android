using System.Text.RegularExpressions;

namespace PodsCompanion.Core.Advertising;

/// <summary>
/// Сопоставляет имя сопряжённого Bluetooth-устройства («AirPods Pro Beyren») с моделью из рекламы.
/// Тот же подход, что ConnectedNameMatcher в Android-приложении: счёт = сколько слов названия модели
/// есть в имени, и имя без слова линейки («AirPods» у AirPods 4) не подходит к Pro и Max.
/// </summary>
public static partial class NameMatcher
{
    private static readonly string[] LineWords = ["pro", "max"];

    public static IReadOnlySet<PodsModel> ModelsForName(string deviceName)
    {
        var name = Words(deviceName);
        var scored = PodsModel.All.Select(m => (Model: m, Score: Words(m.DisplayName).Count(name.Contains))).ToList();
        int best = scored.Max(s => s.Score);
        if (best == 0) return new HashSet<PodsModel>();
        var matched = scored.Where(s => s.Score == best).Select(s => s.Model).ToList();
        var sameLine = matched.Where(m => LineWords.All(w => name.Contains(w) || !Words(m.DisplayName).Contains(w))).ToList();
        return (sameLine.Count > 0 ? sameLine : matched).ToHashSet();
    }

    /// <summary>Похоже ли имя на наушники Apple или Beats.</summary>
    public static bool LooksLikePods(string deviceName) => ModelsForName(deviceName).Count > 0;

    // «AirPods Pro 2 (USB-C)» → [airpods, pro, 2, usb, c]
    private static HashSet<string> Words(string text) =>
        NonWord().Split(text.ToLowerInvariant()).Where(w => w.Length > 0).ToHashSet();

    [GeneratedRegex(@"[^\p{L}\p{N}]+")]
    private static partial Regex NonWord();
}
