using System.Globalization;

namespace PodsCompanion.App;

/// <summary>Тексты интерфейса на трёх языках, как в Android-приложении: русский, английский, французский.</summary>
internal static class Strings
{
    private static readonly string Lang = CultureInfo.CurrentUICulture.TwoLetterISOLanguageName;

    private static string T(string en, string ru, string fr) => Lang switch
    {
        "ru" => ru,
        "fr" => fr,
        _ => en,
    };

    public static string AppName => "Pods Companion";
    public static string NoPods => T("No paired AirPods", "Нет сопряжённых AirPods", "Aucun AirPods associé");
    public static string NoPodsHint => T(
        "Pair the headphones in Windows Settings → Bluetooth & devices.",
        "Сопряги наушники в Параметрах Windows → Bluetooth и устройства.",
        "Associez les écouteurs dans Paramètres Windows → Bluetooth et appareils.");
    public static string BluetoothOff => T("Bluetooth is off", "Bluetooth выключен", "Bluetooth désactivé");
    public static string ConnectedToPc => T("Connected to this PC", "Подключены к этому ПК", "Connectés à ce PC");
    public static string NearbyElsewhere => T(
        "Nearby, connected to another device or in the case",
        "Рядом, подключены к другому устройству или в кейсе",
        "À proximité, connectés à un autre appareil ou dans le boîtier");
    public static string ConnectedToPhone(string phone) => T(
        $"Connected to the phone ({phone})", $"Подключены к телефону ({phone})", $"Connectés au téléphone ({phone})");
    public static string PhoneLinked(string phone) => T(
        $"Phone: {phone}, linked", $"Телефон: {phone}, на связи", $"Téléphone : {phone}, connecté");
    public static string PhoneNotLinked => T(
        "Phone app not found on the network",
        "Приложение на телефоне не найдено в сети",
        "Application du téléphone introuvable sur le réseau");
    public static string ConnectedToOtherPc(string pc) => T(
        $"Connected to PC {pc}", $"Подключены к ПК {pc}", $"Connectés au PC {pc}");
    public static string Mode(string mode) => mode switch
    {
        "off" => T("Off", "Выкл", "Désactivé"),
        "anc" => T("Noise cancel", "Шумоподавл.", "Réduction"),
        "transparency" => T("Transparency", "Прозрачность", "Transparence"),
        "adaptive" => T("Adaptive", "Адаптивный", "Adaptatif"),
        _ => mode,
    };
    public static string ModeViaPhone(string phone) => T(
        $"Modes are switched through the phone ({phone}).",
        $"Режимы переключаются через телефон ({phone}).",
        $"Les modes passent par le téléphone ({phone}).");
    public static string ModeBusy => T("Switching mode…", "Включаю режим…", "Changement de mode…");
    public static string HotkeyHint => T(
        "Ctrl+Alt+P: switch between PC and phone",
        "Ctrl+Alt+P — перекинуть между ПК и телефоном",
        "Ctrl+Alt+P : basculer entre PC et téléphone");
    public static string UpdateTo(string version) => T(
        $"Update to {version}", $"Обновить до версии {version}", $"Mettre à jour vers {version}");
    public static string Updating => T("Downloading update…", "Скачиваю обновление…", "Téléchargement…");
    public static string UpdateFailed => T(
        "Update failed, try again", "Не вышло обновить, нажмите ещё раз", "Échec, réessayer");
    public static string NotNearby => T("Not nearby", "Не рядом", "Pas à proximité");
    public static string Left => T("Left", "Левый", "Gauche");
    public static string Right => T("Right", "Правый", "Droit");
    public static string Case => T("Case", "Кейс", "Boîtier");
    public static string Headphones => T("Headphones", "Наушники", "Casque");
    public static string Charging => T("charging", "заряжается", "en charge");
    public static string BothInEars => T("Both in ears", "Оба в ушах", "Les deux dans les oreilles");
    public static string OneInEar => T("One in ear", "Один в ухе", "Un dans l’oreille");
    public static string OnHead => T("On head", "На голове", "Sur la tête");
    public static string NotWorn => T("Not worn", "Не надеты", "Non portés");
    public static string ConnectToPc => T("Connect to PC", "Подключить к ПК", "Connecter au PC");
    public static string Disconnect => T("Give to phone", "Отдать телефону", "Donner au téléphone");
    public static string Connecting => T("Connecting…", "Подключаю…", "Connexion…");
    public static string AutoTakeOver => T(
        "Switch to PC when sound starts here",
        "Забирать на ПК, когда здесь начинается звук",
        "Basculer sur le PC quand le son démarre ici");
    public static string AutoTakeOverHint => T(
        "Only if the headphones are worn.",
        "Только если наушники надеты.",
        "Seulement si les écouteurs sont portés.");
    public static string AutoPause => T(
        "Pause when I take them off",
        "Пауза, когда снимаю наушники",
        "Pause quand je les retire");
    public static string PauseOnDisconnect => T(
        "Pause when they switch to another device",
        "Пауза, когда наушники уходят к другому устройству",
        "Pause quand ils passent à un autre appareil");
    public static string StartWithWindows => T("Start with Windows", "Запускать вместе с Windows", "Lancer avec Windows");
    public static string CopyLog => T("Copy log", "Скопировать журнал", "Copier le journal");
    public static string LogCopied => T("Copied", "Скопировано", "Copié");
    public static string Exit => T("Exit", "Выход", "Quitter");
    public static string Open => T("Open", "Открыть", "Ouvrir");
    public static string Battery(int? percent) => percent is { } p ? $"{p}%" : "—";
}
