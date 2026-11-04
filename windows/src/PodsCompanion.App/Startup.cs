using System;
using Microsoft.Win32;

namespace PodsCompanion.App;

/// <summary>Запуск вместе с Windows: запись в HKCU\...\Run, права администратора не нужны.</summary>
internal static class Startup
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string ValueName = "PodsCompanion";

    public static bool Enabled
    {
        get
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKey);
            return key?.GetValue(ValueName) is string;
        }
        set
        {
            using var key = Registry.CurrentUser.CreateSubKey(RunKey);
            if (value) key.SetValue(ValueName, $"\"{Environment.ProcessPath}\" --background");
            else key.DeleteValue(ValueName, throwOnMissingValue: false);
        }
    }
}
