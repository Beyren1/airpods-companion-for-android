using System;
using System.Collections.Generic;
using Microsoft.Win32;

namespace PodsCompanion.Core.Media;

/// <summary>
/// Занят ли микрофон на ПК (звонок в Discord, Teams, Telegram, Zoom…). Windows отмечает это в реестре
/// для значка микрофона на панели задач: у программы, которая сейчас пишет звук, LastUsedTimeStop = 0.
/// Название программы не нужно, только сам факт.
/// </summary>
public static class MicrophoneUse
{
    private const string Store = @"Software\Microsoft\Windows\CurrentVersion\CapabilityAccessManager\ConsentStore\microphone";

    public static bool InUse()
    {
        try
        {
            using var root = Registry.CurrentUser.OpenSubKey(Store);
            if (root == null) return false;
            foreach (var key in Apps(root))
                using (key)
                    if (Active(key)) return true;
        }
        catch (Exception)
        {
            // Нет доступа к ключу или его формат поменялся: считаем, что микрофон свободен.
        }
        return false;
    }

    /// <summary>Приложения из Store — прямо в ключе, обычные программы — в подключе NonPackaged.</summary>
    private static IEnumerable<RegistryKey> Apps(RegistryKey root)
    {
        foreach (var name in root.GetSubKeyNames())
        {
            var key = root.OpenSubKey(name);
            if (key == null) continue;
            if (name == "NonPackaged")
            {
                using (key)
                    foreach (var exe in key.GetSubKeyNames())
                        if (key.OpenSubKey(exe) is { } app) yield return app;
            }
            else yield return key;
        }
    }

    private static bool Active(RegistryKey app) =>
        app.GetValue("LastUsedTimeStart") is long start && start != 0 &&
        app.GetValue("LastUsedTimeStop") is long stop && stop == 0;
}
