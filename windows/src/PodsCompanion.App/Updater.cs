using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Reflection;
using System.Text.Json;
using System.Threading.Tasks;

namespace PodsCompanion.App;

/// <summary>
/// Проверка обновлений: смотрит выпуски репозитория на GitHub с тегом windows-v*, и если там версия
/// новее этой, по кнопке скачивает установщик и запускает его. Установщик сам закрывает старую копию
/// и запускает новую.
/// </summary>
internal static class Updater
{
    private const string ReleasesUrl = "https://api.github.com/repos/Beyren1/airpods-companion-for-android/releases?per_page=30";
    private const string TagPrefix = "windows-v";
    private const string SetupName = "PodsCompanion-Setup.exe";

    public sealed record Update(Version Version, string DownloadUrl);

    public static Version Current =>
        Assembly.GetEntryAssembly()?.GetName().Version is { } v ? new Version(v.Major, v.Minor, Math.Max(v.Build, 0)) : new Version(0, 0, 0);

    /// <summary>Новая версия или null, если её нет или сеть недоступна.</summary>
    public static async Task<Update?> CheckAsync()
    {
        try
        {
            using var http = Client();
            using var doc = JsonDocument.Parse(await http.GetStringAsync(ReleasesUrl));
            Update? best = null;
            foreach (var release in doc.RootElement.EnumerateArray())
            {
                if (release.GetProperty("draft").GetBoolean() || release.GetProperty("prerelease").GetBoolean()) continue;
                var tag = release.GetProperty("tag_name").GetString() ?? "";
                if (!tag.StartsWith(TagPrefix) || !Version.TryParse(tag[TagPrefix.Length..], out var version)) continue;
                var asset = release.GetProperty("assets").EnumerateArray()
                    .FirstOrDefault(a => a.GetProperty("name").GetString() == SetupName);
                if (asset.ValueKind != JsonValueKind.Object) continue;
                if (version > Current && (best == null || version > best.Version))
                    best = new Update(version, asset.GetProperty("browser_download_url").GetString()!);
            }
            return best;
        }
        catch (Exception)
        {
            return null;
        }
    }

    /// <summary>Скачивает установщик и запускает его тихо; после этого программу нужно закрыть.</summary>
    public static async Task InstallAsync(Update update)
    {
        var path = Path.Combine(Path.GetTempPath(), $"PodsCompanion-Setup-{update.Version}.exe");
        using (var http = Client())
        await using (var file = File.Create(path))
        {
            await using var stream = await http.GetStreamAsync(update.DownloadUrl);
            await stream.CopyToAsync(file);
        }
        Process.Start(new ProcessStartInfo(path, "/SILENT /SUPPRESSMSGBOXES /NORESTART") { UseShellExecute = true });
    }

    private static HttpClient Client()
    {
        var http = new HttpClient { Timeout = TimeSpan.FromMinutes(2) };
        // GitHub отвечает только запросам с User-Agent.
        http.DefaultRequestHeaders.UserAgent.ParseAdd($"PodsCompanion/{Current}");
        return http;
    }
}
