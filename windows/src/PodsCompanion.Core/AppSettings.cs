using System.Text.Json;

namespace PodsCompanion.Core;

/// <summary>Настройки приложения. Хранятся в %AppData%\PodsCompanion\settings.json.</summary>
public sealed class AppSettings
{
    /// <summary>Забирать наушники на ПК, когда на ПК начинает играть звук, а наушники надеты.</summary>
    public bool AutoTakeOver { get; set; } = true;

    /// <summary>Пауза, когда наушник сняли, и продолжение, когда надели обратно.</summary>
    public bool AutoPause { get; set; } = true;

    /// <summary>Пауза, когда наушники ушли к другому устройству, чтобы звук не пошёл в колонки.</summary>
    public bool PauseOnDisconnect { get; set; } = true;

    /// <summary>Какие наушники показывать, если сопряжено несколько; null — выбирать самим.</summary>
    public ulong? SelectedAddress { get; set; }

    private static readonly string Folder =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "PodsCompanion");

    private static string FilePath => Path.Combine(Folder, "settings.json");

    public static AppSettings Load()
    {
        try
        {
            return File.Exists(FilePath)
                ? JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(FilePath)) ?? new AppSettings()
                : new AppSettings();
        }
        catch (Exception)
        {
            // Испорченный файл не должен мешать запуску: начинаем с настроек по умолчанию.
            return new AppSettings();
        }
    }

    public void Save()
    {
        try
        {
            Directory.CreateDirectory(Folder);
            File.WriteAllText(FilePath, JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
        }
        catch (Exception)
        {
            // Нет доступа к папке: настройки просто не запомнятся.
        }
    }
}
