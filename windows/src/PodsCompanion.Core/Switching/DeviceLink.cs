using System.Net;
using System.Net.Sockets;
using System.Text;

namespace PodsCompanion.Core.Switching;

/// <summary>Телефон, который слышно в сети: <see cref="Pods"/> — какие наушники у него подключены.</summary>
public sealed record LinkPeer(string Id, string Name, IPAddress Address, IReadOnlyList<LinkPods> Pods, DateTime LastSeen);

/// <summary>
/// Постоянная связь с приложением на телефоне по домашней сети (UDP, порт 47820).
/// ПК раз в <see cref="HelloPeriod"/> сообщает, какие наушники у него подключены: широковещательно
/// и напрямую каждому известному телефону (с выключенным экраном телефон может не слышать широковещание).
/// Телефон отвечает своим состоянием и может попросить ПК отпустить наушники.
/// </summary>
public sealed class DeviceLink : IDisposable
{
    public static readonly TimeSpan HelloPeriod = TimeSpan.FromSeconds(60);

    /// <summary>Сколько телефон считается на связи без новых сообщений.</summary>
    public static readonly TimeSpan PeerTimeout = TimeSpan.FromSeconds(150);

    private readonly string id = Random.Shared.Next().ToString("x8");
    private readonly Dictionary<string, LinkPeer> peers = [];
    private readonly Func<IReadOnlyList<LinkPods>> ownPods;
    private readonly Action<string> log;
    private UdpClient? udp;
    private CancellationTokenSource? cts;
    private Timer? timer;

    /// <summary>Телефон просит отпустить наушники с этим хэшем.</summary>
    public event Action<string>? ReleaseRequested;

    /// <summary>Список телефонов или их состояние поменялись.</summary>
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

    /// <summary>Наше состояние поменялось: сразу сообщаем телефонам.</summary>
    public Task NotifyChangedAsync() => SayHelloAsync(LinkType.Hello);

    /// <summary>Предупредить телефоны, что ПК забирает эти наушники: пусть заранее поставят музыку на паузу.</summary>
    public Task SendTakingAsync(ulong podsAddress) =>
        SendToPeersAsync(Message(LinkType.Taking, [new LinkPods(LinkMessage.HashOf(podsAddress), false)]));

    private LinkMessage Message(LinkType type, IReadOnlyList<LinkPods> pods) =>
        new(type, id, LinkKind.Pc, pods, Environment.MachineName);

    private async Task SayHelloAsync(LinkType type)
    {
        var message = Message(type, ownPods());
        await SendAsync(message, new IPEndPoint(IPAddress.Broadcast, LinkMessage.Port));
        await SendToPeersAsync(message);
        PeersChanged?.Invoke(); // кто-то мог пропасть по таймауту
    }

    private async Task SendToPeersAsync(LinkMessage message)
    {
        foreach (var peer in Peers) await SendAsync(message, new IPEndPoint(peer.Address, LinkMessage.Port));
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
            if (message == null || message.Id == id || message.Kind != LinkKind.Phone) continue;
            OnMessage(message, result.RemoteEndPoint.Address);
        }
    }

    private void OnMessage(LinkMessage message, IPAddress from)
    {
        bool isNew;
        lock (peers)
        {
            isNew = !peers.TryGetValue(message.Id, out var old) || DateTime.UtcNow - old.LastSeen >= PeerTimeout;
            peers[message.Id] = new LinkPeer(message.Id, message.Name, from, message.Pods, DateTime.UtcNow);
        }
        if (isNew) log($"телефон на связи: {message.Name} ({from})");
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
