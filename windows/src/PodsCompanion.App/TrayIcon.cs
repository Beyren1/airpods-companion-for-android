using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Text;
using System.Runtime.InteropServices;
using Microsoft.Win32;
using PodsCompanion.Core;
using PodsCompanion.Core.Advertising;
using Forms = System.Windows.Forms;

namespace PodsCompanion.App;

/// <summary>
/// Значок в трее с зарядом цифрой, как у значка батареи ноутбука: видно без наведения мышкой.
/// У вкладышей — заряд того наушника, что разрядился сильнее. Когда заряд неизвестен — обычный значок.
/// </summary>
internal static class TrayIcon
{
    /// <summary>Заряд для значка: из рекламы рядом или точный от телефона.</summary>
    public static int? Percent(PodsSnapshot s)
    {
        if (s.Nearby is { } n)
        {
            var m = n.Message;
            if (m.Model?.Kind == PodsKind.Single) return m.Primary.Battery;
            if (m.Left.Battery is { } l && m.Right.Battery is { } r) return Math.Min(l, r);
            return m.Left.Battery ?? m.Right.Battery;
        }
        if (s.PhoneBattery is { } phone)
        {
            var parts = phone.Split('/');
            int? min = null;
            // «96» у Max, «80/85/60» у вкладышей: кейс (третье число) не считаем.
            for (int i = 0; i < Math.Min(parts.Length, 2); i++)
                if (int.TryParse(parts[i], out var p)) min = min is { } m2 ? Math.Min(m2, p) : p;
            return min;
        }
        return null;
    }

    /// <summary>Значок с числом; вызывающий отвечает за Dispose старого.</summary>
    public static Icon Draw(int percent)
    {
        int size = Forms.SystemInformation.SmallIconSize.Width;
        using var bitmap = new Bitmap(size, size);
        using (var g = Graphics.FromImage(bitmap))
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.TextRenderingHint = TextRenderingHint.AntiAliasGridFit;
            // «100» не помещается в 16 точек тем же шрифтом: три цифры пишем мельче.
            var text = percent.ToString();
            float fontSize = size * (text.Length >= 3 ? 0.48f : 0.68f);
            using var font = new Font("Segoe UI", fontSize, FontStyle.Bold, GraphicsUnit.Pixel);
            var color = percent <= 20 ? Color.FromArgb(0xE8, 0x3B, 0x3B) : LightTaskbar() ? Color.Black : Color.White;
            using var brush = new SolidBrush(color);
            using var format = new StringFormat { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Center };
            g.DrawString(text, font, brush, new RectangleF(-2, 0, size + 4, size), format);
        }
        var handle = bitmap.GetHicon();
        try
        {
            using var temp = Icon.FromHandle(handle);
            return (Icon)temp.Clone();
        }
        finally
        {
            DestroyIcon(handle);
        }
    }

    /// <summary>Светлая панель задач — тёмные цифры, тёмная — белые.</summary>
    private static bool LightTaskbar()
    {
        using var key = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize");
        return key?.GetValue("SystemUsesLightTheme") is int light && light == 1;
    }

    [DllImport("user32.dll")]
    private static extern bool DestroyIcon(IntPtr handle);
}
