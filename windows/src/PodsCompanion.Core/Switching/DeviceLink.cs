using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using System.Text;

namespace PodsCompanion.Core.Switching;

/// <summary>Телефон или другой ПК, который слышно в сети: <see cref="Pods"/> — какие наушники у него и в каком состоянии.</summary>
public sealed record LinkPeer(string Id, LinkKind Kind, string Name, IPAddress Address, IReadOnlyList<LinkPods> Pods, DateTime LastSeen)
{
    public LinkPods? Holding(string hash) => Pods.FirstOrDefault(p => p.Hash == hash && p.Here);
}

/// <summary>
/// Постоянная связь с приложением на телефоне и с этой же программой на других ПК (UDP, порт 47820).
/// Раз в <see cref="HelloPeriod"/> и при каждом изменении сообщаем, какие наушники подключены к этому ПК:
/// широковещательно и напрямую каждому известному устройству (с выключенным экраном телефон может
/// не слышать широковещание). Кто забирает наушники, сначала просит держателя отпустить их.
/// </summary>
public sealed class DeviceLink : IDisposable
{
    public static readonly TimeSpan HelloPeriod = TimeSpan.FromSeconds(60);

    /// <summary>Сколько устройство считается на связи без новых сообщений.</summary>
    public static readonly TimeSpan PeerTimeout = TimeSpan.FromSeconds(150);

    /// <summary>Сколько ждать, пока держатель отпустит наушники.</summary>
    public static readonly TimeSpan ReleaseWait = TimeSpan.FromSeconds(1);

    /// <summary>Телефону нужно открыть канал к наушникам и дождаться подтверждения режима.</summary>
    public static readonly TimeSpan ModeWait = TimeSpan.FromSeconds(15);

    private readonly string id = Random.Shared.Next().ToString("x8");
    private readonly Dictionary<string, LinkPeer> peers = [];
    private readonly ConcurrentDictionary<string, TaskCompletionSource<string?>> modeWaits = new();
    private readonly Func<IReadOnlyList<LinkPods>> ownPods;
    private readonly Action<string> log;
    private UdpClient? udp;
    private CancellationTokenSource? cts;
    private Timer? timer;

    /// <summary>Телефон или другой ПК просит отпустить наушники с этим хэшем.</summary>
    public event Action<string>? ReleaseRequested;

    /// <summary>Список устройств или их состояние поменялись.</summary>
    public event Action? PeersChanged;

    public DeviceLink(Func<IReadOnlyList<LinkPods>> ownPods, Action<string> log)
    {
        this.ownPods = ownPods;
        this.log = log;
    }

    public IReadOnlyList<LinkPeer> Peers
    {
        get
        {
            lock (peers) return peers.Values.Where(p => DateTime.UtcNow - p.LastSeen < PeerTimeout).ToList();
        }
    }

    public void Start()
    {
        try
        {
            udp = new UdpClient(AddressFamily.InterNetwork) { EnableBroadcast = true };
            udp.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
            udp.Client.Bind(new IPEndPoint(IPAddress.Any, LinkMessage.Port));
        }
        catch (Exception e)
        {
            log($"связь с телефоном недоступна: {e.Message}");
            udp?.Dispose();
            udp = null;
            return;
        }
        cts = new CancellationTokenSource();
        _ = ReceiveLoopAsync(cts.Token);
        timer = new Timer(_ => _ = SayHelloAsync(LinkType.Hi), null, TimeSpan.Zero, HelloPeriod);
    }

    /// <summary>Наше состояние поменялось: сразу сообщаем остальным.</summary>
    public Task NotifyChangedAsync() => SayHelloAsync(LinkType.Hello);

    /// <summary>
    /// Перед тем как забрать наушники: если они у телефона или другого ПК, просим отпустить
    /// и ждём не дольше <see cref="ReleaseWait"/>. Так ПК не перетягивает их, а телефон заранее ставит музыку на паузу.
    /// </summary>
    public async Task RequestReleaseAsync(ulong podsAddress)
    {
        var hash = LinkMessage.HashOf(podsAddress);
        var holders = Peers.Where(p => p.Holding(hash) != null).ToList();
        if (holders.Count == 0) return;
        var message = Message(LinkType.Release, [new LinkPods(hash, false)]);
        foreach (var holder in holders)
        {
            log($"прошу {holder.Name} отпустить наушники");
            await SendAsync(message, new IPEndPoint(holder.Address, LinkMessage.Port));
        }
        var until = DateTime.UtcNow + ReleaseWait;
        while (DateTime.UtcNow < until && Peers.Any(p => p.Holding(hash) != null)) await Task.Delay(100);
        log(Peers.Any(p => p.Holding(hash) != null) ? "не отпустили, забираю сам" : "наушники отпустили");
    }

    /// <summary>
    /// Включить наушникам режим через телефон: у Windows нет доступа к каналу AAP без драйвера.
    /// Возвращает режим, который наушники подтвердили, или null.
    /// </summary>
    public async Task<string?> SetModeAsync(ulong podsAddress, string mode)
    {
        var hash = LinkMessage.HashOf(podsAddress);
        var phone = Peers.Where(p => p.Kind == LinkKind.Phone).MaxBy(p => p.LastSeen);
        if (phone == null) return null;
        var wait = new TaskCompletionSource<string?>(TaskCreationOptions.RunContinuationsAsynchronously);
        modeWaits[hash] = wait;
        log($"режим {mode} через телефон {phone.Name}");
        await SendAsync(Message(LinkType.Mode, [new LinkPods(hash, false, null, mode)]), new IPEndPoint(phone.Address, LinkMessage.Port));
        var done = await Task.WhenAny(wait.Task, Task.Delay(ModeWait)) == wait.Task ? wait.Task.Result : null;
        modeWaits.TryRemove(hash, out _);
        log(done != null ? $"режим включён: {done}" : "режим не включился");
        return done;
    }

    private LinkMessage Message(LinkType type, IReadOnlyList<LinkPods> pods) =>
        new(type, id, LinkKind.Pc, pods, Environment.MachineName);

    private async Task SayHelloAsync(LinkType type)
    {
        var message = Message(type, ownPods());
        await SendAsync(message, new IPEndPoint(IPAddress.Broadcast, LinkMessage.Port));
        foreach (var peer in Peers) await SendAsync(message, new IPEndPoint(peer.Address, LinkMessage.Port));
        PeersChanged?.Invoke(); // кто-то мог пропасть по таймауту
    }

    private async Task SendAsync(LinkMessage message, IPEndPoint to)
    {
        if (udp == null) return;
        try
        {
            await udp.SendAsync(Encoding.UTF8.GetBytes(message.Encode()), to);
        }
        catch (Exception)
        {
            // Сети нет или она без широковещания: попробуем в следующий раз.
        }
    }

    private async Task ReceiveLoopAsync(CancellationToken token)
    {
        while (!token.IsCancellationRequested && udp != null)
        {
            UdpReceiveResult result;
            try
            {
                result = await udp.ReceiveAsync(token);
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch (SocketException)
            {
                // Windows сообщает так о недоставленных пакетах: продолжаем слушать.
                continue;
            }
            catch (ObjectDisposedException)
            {
                return;
            }
            // Сигнал «забери наушники» (PCSW) идёт на тот же порт, но он для телефона.
            var message = LinkMessage.Parse(Encoding.UTF8.GetString(result.Buffer));
            if (message == null || message.Id == id) continue;
            OnMessage(message, result.RemoteEndPoint.Address);
        }
    }

    private void OnMessage(LinkMessage message, IPAddress from)
    {
        // Ответ на просьбу о режиме не меняет, что подключено к телефону: список наушников не трогаем.
        if (message.Type == LinkType.ModeDone)
        {
            foreach (var pods in message.Pods)
                if (modeWaits.TryGetValue(pods.Hash, out var wait)) wait.TrySetResult(pods.Mode);
            return;
        }
        bool isNew;
        lock (peers)
        {
            isNew = !peers.TryGetValue(message.Id, out var old) || DateTime.UtcNow - old.LastSeen >= PeerTimeout;
            // В просьбах (release, mode) только нужные наушники: прежнее состояние устройства сохраняем.
            var pods = message.Type is LinkType.Hi or LinkType.Hello ? message.Pods : old?.Pods ?? [];
            peers[message.Id] = new LinkPeer(message.Id, message.Kind, message.Name, from, pods, DateTime.UtcNow);
        }
        if (isNew) log($"{(message.Kind == LinkKind.Phone ? "телефон" : "ПК")} на связи: {message.Name} ({from})");
        switch (message.Type)
        {
            case LinkType.Hi:
                _ = SendAsync(Message(LinkType.Hello, ownPods()), new IPEndPoint(from, LinkMessage.Port));
                break;
            case LinkType.Release:
                foreach (var pods in message.Pods) ReleaseRequested?.Invoke(pods.Hash);
                break;
        }
        PeersChanged?.Invoke();
    }

    public void Dispose()
    {
        timer?.Dispose();
        cts?.Cancel();
        udp?.Dispose();
    }
}
