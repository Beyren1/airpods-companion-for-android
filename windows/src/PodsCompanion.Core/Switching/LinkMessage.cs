namespace PodsCompanion.Core.Switching;

/// <summary>
/// Наушники в сообщении связи: <see cref="Hash"/> — 8 hex-символов, <see cref="Here"/> — подключены к отправителю,
/// <see cref="Battery"/> — точный заряд от телефона («96» или «80/85/60»), <see cref="Mode"/> — режим
/// («off», «anc», «transparency», «adaptive»).
/// </summary>
/// <summary>
/// Наушники в сообщении связи. <see cref="InCall"/> — на ПК звонок (занят микрофон), телефону нельзя
/// забирать наушники сам; на проводе это «2» вместо «1».
/// </summary>
public sealed record LinkPods(string Hash, bool Here, string? Battery = null, string? Mode = null, bool InCall = false)
{
    public static readonly string[] Modes = ["off", "anc", "transparency", "adaptive"];

    public string Encode()
    {
        var text = $"{Hash}:{(Here && InCall ? 2 : Here ? 1 : 0)}";
        return Battery == null && Mode == null ? text : $"{text}:{Battery ?? "-"}:{Mode ?? "-"}";
    }

    public static LinkPods? Parse(string text)
    {
        var parts = text.Split(':');
        if (parts.Length is not (2 or 4) || parts[1] is not ("0" or "1" or "2")) return null;
        var battery = parts.Length == 4 && parts[2] != "-" ? parts[2] : null;
        var mode = parts.Length == 4 && Modes.Contains(parts[3]) ? parts[3] : null;
        return new LinkPods(parts[0].ToLowerInvariant(), parts[1] != "0", battery, mode, parts[1] == "2");
    }
}

/// <summary>
/// hi — «я здесь, ответь»; hello — состояние; release — «отпусти эти наушники»; taking — «забираю эти наушники»;
/// mode — «включи этим наушникам режим» (ПК просит телефон: у ПК нет доступа к AAP); modedone — ответ на mode.
/// </summary>
public enum LinkType { Hi, Hello, Release, Taking, Mode, ModeDone, Give }

public enum LinkKind { Phone, Pc }

/// <summary>
/// Сообщение связи между программой на ПК и приложением на телефоне (UDP, домашняя сеть).
/// Одна строка: «PCLINK 1 тип id phone|pc наушники имя». Тот же формат разбирает LinkMessage.kt на телефоне.
/// hi — «я здесь, ответь»; hello — состояние; release — «отпусти эти наушники»; taking — «забираю эти наушники».
/// </summary>
public sealed record LinkMessage(LinkType Type, string Id, LinkKind Kind, IReadOnlyList<LinkPods> Pods, string Name)
{
    public const int Port = PhoneSignal.UdpPort;
    private const string Magic = "PCLINK";
    private const string Version = "1";

    private static readonly Dictionary<LinkType, string> TypeWire = new()
    {
        [LinkType.Hi] = "hi", [LinkType.Hello] = "hello", [LinkType.Release] = "release", [LinkType.Taking] = "taking",
        [LinkType.Mode] = "mode", [LinkType.ModeDone] = "modedone", [LinkType.Give] = "give",
    };

    public string Encode()
    {
        var pods = Pods.Count == 0 ? "-" : string.Join(',', Pods.Select(p => p.Encode()));
        var name = Name.Replace('\n', ' ').Replace('\r', ' ').Trim();
        return $"{Magic} {Version} {TypeWire[Type]} {Id} {(Kind == LinkKind.Pc ? "pc" : "phone")} {pods} {(name.Length == 0 ? "?" : name)}";
    }

    public static LinkMessage? Parse(string text)
    {
        var parts = text.Trim().Split(' ', 7);
        if (parts.Length < 7 || parts[0] != Magic || parts[1] != Version) return null;
        var type = TypeWire.FirstOrDefault(t => t.Value == parts[2]);
        if (type.Value == null) return null;
        LinkKind kind;
        if (parts[4] == "pc") kind = LinkKind.Pc;
        else if (parts[4] == "phone") kind = LinkKind.Phone;
        else return null;
        var pods = new List<LinkPods>();
        if (parts[5] != "-")
        {
            foreach (var item in parts[5].Split(','))
            {
                var parsed = LinkPods.Parse(item);
                if (parsed == null) return null;
                pods.Add(parsed);
            }
        }
        return new LinkMessage(type.Key, parts[3], kind, pods, parts[6]);
    }

    /// <summary>Хэш наушников для сообщений: тот же, что в сигнале <see cref="PhoneSignal"/>.</summary>
    public static string HashOf(ulong address) => Convert.ToHexString(PhoneSignal.AddressHash(address)).ToLowerInvariant();
}
