namespace PodsCompanion.Core.Switching;

/// <summary>Наушники в сообщении связи: <see cref="Hash"/> — 8 hex-символов, <see cref="Here"/> — подключены к отправителю.</summary>
public sealed record LinkPods(string Hash, bool Here);

public enum LinkType { Hi, Hello, Release, Taking }

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
    };

    public string Encode()
    {
        var pods = Pods.Count == 0 ? "-" : string.Join(',', Pods.Select(p => $"{p.Hash}:{(p.Here ? 1 : 0)}"));
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
                var kv = item.Split(':');
                if (kv.Length != 2) return null;
                pods.Add(new LinkPods(kv[0].ToLowerInvariant(), kv[1] == "1"));
            }
        }
        return new LinkMessage(type.Key, parts[3], kind, pods, parts[6]);
    }

    /// <summary>Хэш наушников для сообщений: тот же, что в сигнале <see cref="PhoneSignal"/>.</summary>
    public static string HashOf(ulong address) => Convert.ToHexString(PhoneSignal.AddressHash(address)).ToLowerInvariant();
}
