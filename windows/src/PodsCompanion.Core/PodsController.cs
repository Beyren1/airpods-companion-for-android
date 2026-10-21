using PodsCompanion.Core.Advertising;
using PodsCompanion.Core.Bluetooth;
using PodsCompanion.Core.Media;
using PodsCompanion.Core.Switching;

namespace PodsCompanion.Core;

/// <summary>Что показывать в окне и в трее.</summary>
public sealed record PodsSnapshot(
    PairedPods? Pods,
    NearbyPods? Nearby,
    IReadOnlyList<PairedPods> AllPaired,
    bool Busy,
    bool BluetoothOff);

/// <summary>
/// Всё приложение без интерфейса: реклама рядом, сопряжённые наушники, пауза по датчику уха
/// и переключение «как у iPhone и Mac» — включил звук на ПК, и надетые наушники переходят на ПК.
/// </summary>
public sealed class PodsController : IDisposable
{
    /// <summary>Сколько ждать, пока наушники подключатся к ПК после команды.</summary>
    private static readonly TimeSpan ConnectWait = TimeSpan.FromSeconds(10);

    /// <summary>Наше же «продолжить» плеер сообщает как новое начало звука: его не считаем.</summary>
    private static readonly TimeSpan OwnResumeQuiet = TimeSpan.FromSeconds(5);

    /// <summary>Не получилось забрать: не пробуем снова на каждое нажатие «играть», иначе плеер встаёт на паузу раз за разом.</summary>
    private static readonly TimeSpan FailedQuiet = TimeSpan.FromMinutes(2);

    /// <summary>После ручного «Отключить» не забираем наушники обратно сразу же.</summary>
    private static readonly TimeSpan ManualReleaseQuiet = TimeSpan.FromSeconds(10);

    private readonly AdvertisementScanner scanner = new();
    private readonly PairedHeadphones paired = new();
    private readonly MediaControl media = new();
    private readonly EarPolicy earPolicy = new();
    private readonly Timer refreshTimer;
    private readonly object gate = new();
    private readonly LinkedList<string> log = new();

    private int busy;
    private bool bluetoothOff;
    private DateTime releasedAt = DateTime.MinValue;
    /// <summary>До этого момента не забираем наушники сами: после своей паузы/продолжения или неудачной попытки.</summary>
    private DateTime autoQuietUntil = DateTime.MinValue;
    private readonly Dictionary<ulong, bool> lastConnected = [];

    public AppSettings Settings { get; } = AppSettings.Load();

    /// <summary>Состояние поменялось (вызывается из фонового потока).</summary>
    public event Action? Changed;

    public PodsController()
    {
        scanner.PacketReceived += OnPacket;
        scanner.Stopped += error =>
        {
            bluetoothOff = true;
            Log($"скан остановлен: {error}");
            Changed?.Invoke();
        };
        paired.ConnectionChanged += OnConnectionChanged;
        media.PlaybackStarted += OnPlaybackStarted;
        // Список сопряжённых перечитываем время от времени: пользователь мог сопрячь новые наушники,
        // а скан перезапускаем, если Bluetooth включили обратно.
        refreshTimer = new Timer(_ => _ = RefreshAsync(), null, Timeout.Infinite, Timeout.Infinite);
    }

    public async Task StartAsync()
    {
        await RefreshAsync();
        try
        {
            await media.StartAsync();
        }
        catch (Exception e)
        {
            Log($"медиа недоступны: {e.Message}");
        }
        refreshTimer.Change(TimeSpan.FromSeconds(15), TimeSpan.FromSeconds(15));
    }

    private async Task RefreshAsync()
    {
        try
        {
            await paired.RefreshAsync();
            foreach (var p in paired.Current)
                lock (gate) lastConnected.TryAdd(p.Address, p.Connected);
            scanner.Start();
            bluetoothOff = false;
        }
        catch (Exception e)
        {
            bluetoothOff = true;
            Log($"Bluetooth недоступен: {e.Message}");
        }
        Changed?.Invoke();
    }

    public PodsSnapshot Snapshot()
    {
        var all = paired.Current;
        var pods = Choose(all);
        var nearby = pods == null ? null : scanner.Tracker.Best(pods.Models, DateTime.UtcNow);
        return new PodsSnapshot(pods, nearby, all, busy > 0, bluetoothOff);
    }

    /// <summary>
    /// Какие наушники главные: выбранные пользователем, иначе подключённые к ПК, иначе те,
    /// чья модель слышна рядом сильнее всего (их скорее всего сейчас и носят).
    /// </summary>
    private PairedPods? Choose(IReadOnlyList<PairedPods> all)
    {
        if (Settings.SelectedAddress is { } selected && all.FirstOrDefault(p => p.Address == selected) is { } chosen) return chosen;
        var connected = all.FirstOrDefault(p => p.Connected);
        if (connected != null) return connected;
        var now = DateTime.UtcNow;
        return all
            .Select(p => (Pods: p, Nearby: scanner.Tracker.Best(p.Models, now)))
            .OrderByDescending(x => x.Nearby?.Message.Worn == true)
            .ThenByDescending(x => x.Nearby?.Rssi ?? int.MinValue)
            .Select(x => x.Pods)
            .FirstOrDefault();
    }

    // ---------- Подключение ----------

    /// <summary>
    /// Подключить наушники к ПК. Плееры не трогаем: раньше ставили их на паузу на время подключения,
    /// и при неудачной попытке это превращалось в паузу на каждое нажатие «играть».
    /// </summary>
    public async Task<bool> TakeOverAsync(string reason)
    {
        var pods = Snapshot().Pods;
        if (pods == null) return false;
        if (pods.Connected) return true;
        if (Interlocked.Increment(ref busy) > 1)
        {
            Interlocked.Decrement(ref busy);
            return false;
        }
        Changed?.Invoke();
        try
        {
            Log($"забрать на ПК ({reason}): {pods.Name}");
            int sent = await Task.Run(() => BluetoothAudioControl.Connect(pods.AddressHex, Log));
            Log($"команда подключения отправлена {sent} звуковым устройствам");
            bool ok = sent > 0 && await WaitConnectedAsync(pods.Address, ConnectWait);
            Log(ok ? "наушники подключились к ПК" : "наушники не подключились");
            if (ok) earPolicy.Reset();
            autoQuietUntil = DateTime.UtcNow + (ok ? OwnResumeQuiet : FailedQuiet);
            return ok;
        }
        catch (Exception e)
        {
            Log($"ошибка подключения: {e.Message}");
            autoQuietUntil = DateTime.UtcNow + FailedQuiet;
            return false;
        }
        finally
        {
            Interlocked.Decrement(ref busy);
            Changed?.Invoke();
        }
    }

    /// <summary>Отключить наушники от ПК, чтобы их забрал телефон.</summary>
    public async Task ReleaseAsync()
    {
        var pods = Snapshot().Pods;
        if (pods == null || !pods.Connected) return;
        releasedAt = DateTime.UtcNow;
        Log($"отпустить: {pods.Name}");
        try
        {
            int sent = await Task.Run(() => BluetoothAudioControl.Disconnect(pods.AddressHex, Log));
            Log($"команда отключения отправлена {sent} звуковым устройствам");
        }
        catch (Exception e)
        {
            Log($"ошибка отключения: {e.Message}");
        }
    }

    private async Task<bool> WaitConnectedAsync(ulong address, TimeSpan timeout)
    {
        var until = DateTime.UtcNow + timeout;
        while (DateTime.UtcNow < until)
        {
            if (paired.Current.Any(p => p.Address == address && p.Connected)) return true;
            await Task.Delay(250);
        }
        return false;
    }

    // ---------- События ----------

    private void OnPlaybackStarted(string app)
    {
        if (!Settings.AutoTakeOver || busy > 0) return;
        if (DateTime.UtcNow < autoQuietUntil || DateTime.UtcNow - releasedAt < ManualReleaseQuiet) return;
        var snapshot = Snapshot();
        if (snapshot.Pods is not { Connected: false }) return;
        // Забираем только надетые наушники: лежащие в кейсе или на столе пусть остаются, где были.
        if (snapshot.Nearby?.Message.Worn != true) return;
        Log($"звук на ПК ({app}), наушники надеты");
        _ = TakeOverAsync("звук на ПК");
    }

    private void OnConnectionChanged(PairedPods pods)
    {
        bool was;
        lock (gate)
        {
            was = lastConnected.TryGetValue(pods.Address, out var c) && c;
            lastConnected[pods.Address] = pods.Connected;
        }
        if (was == pods.Connected) return;
        Log(pods.Connected ? $"{pods.Name} подключены к ПК" : $"{pods.Name} отключены от ПК");
        if (!pods.Connected)
        {
            earPolicy.Reset();
            // Наушники ушли (к телефону или выключились): не даём музыке заиграть из колонок.
            if (Settings.PauseOnDisconnect && media.AnyPlaying)
            {
                _ = Task.Run(async () =>
                {
                    await media.PauseAllAsync();
                    media.ForgetPaused();
                    Log("пауза: наушники ушли от ПК");
                });
            }
        }
        Changed?.Invoke();
    }

    private void OnPacket()
    {
        var snapshot = Snapshot();
        if (Settings.AutoPause && snapshot is { Pods.Connected: true, Nearby: { } nearby })
        {
            switch (earPolicy.OnMessage(nearby.Message))
            {
                case EarAction.Pause:
                    _ = media.PauseAllAsync();
                    Log("пауза: наушник сняли");
                    break;
                case EarAction.Resume:
                    autoQuietUntil = DateTime.UtcNow + OwnResumeQuiet;
                    _ = media.ResumeOursAsync();
                    Log("продолжить: наушники надели");
                    break;
            }
        }
        Changed?.Invoke();
    }

    // ---------- Журнал ----------

    public void Log(string message)
    {
        lock (log)
        {
            log.AddLast($"{DateTime.Now:HH:mm:ss.fff}  {message}");
            while (log.Count > 400) log.RemoveFirst();
        }
    }

    public string LogText()
    {
        lock (log) return string.Join(Environment.NewLine, log);
    }

    public void Dispose()
    {
        refreshTimer.Dispose();
        scanner.Dispose();
        paired.Dispose();
    }
}
