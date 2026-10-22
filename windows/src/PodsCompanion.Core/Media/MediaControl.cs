using Windows.Media.Control;

namespace PodsCompanion.Core.Media;

/// <summary>
/// Музыка и видео на ПК через системные медиаэлементы Windows (то же, что показывает
/// всплывающее окно громкости): Spotify, браузеры, плееры. Отсюда узнаём, что звук начался,
/// и ставим на паузу, когда наушники сняли.
/// </summary>
public sealed class MediaControl
{
    private GlobalSystemMediaTransportControlsSessionManager? manager;
    private readonly Dictionary<string, GlobalSystemMediaTransportControlsSession> watched = [];
    private readonly Dictionary<string, bool> playing = [];
    private readonly List<GlobalSystemMediaTransportControlsSession> pausedByUs = [];
    private readonly object gate = new();

    /// <summary>Какое-то приложение начало играть (вызывается из фонового потока).</summary>
    public event Action<string>? PlaybackStarted;

    public async Task StartAsync()
    {
        manager = await GlobalSystemMediaTransportControlsSessionManager.RequestAsync();
        manager.SessionsChanged += (_, _) => WatchSessions();
        WatchSessions();
    }

    /// <summary>Сейчас что-то играет.</summary>
    public bool AnyPlaying
    {
        get
        {
            lock (gate) return playing.Values.Any(p => p);
        }
    }

    /// <summary>Поставить на паузу всё, что играет, и запомнить, что именно.</summary>
    public async Task<bool> PauseAllAsync()
    {
        var sessions = Sessions().Where(IsPlaying).ToList();
        foreach (var s in sessions)
        {
            if (await s.TryPauseAsync())
                lock (gate) pausedByUs.Add(s);
        }
        return sessions.Count > 0;
    }

    /// <summary>Продолжить то, что поставили на паузу мы (а не пользователь).</summary>
    public async Task ResumeOursAsync()
    {
        List<GlobalSystemMediaTransportControlsSession> toResume;
        lock (gate)
        {
            toResume = [.. pausedByUs];
            pausedByUs.Clear();
        }
        foreach (var s in toResume)
        {
            try
            {
                await s.TryPlayAsync();
            }
            catch (Exception)
            {
                // Приложение уже закрыли.
            }
        }
    }

    public void ForgetPaused()
    {
        lock (gate) pausedByUs.Clear();
    }

    private IReadOnlyList<GlobalSystemMediaTransportControlsSession> Sessions() =>
        manager?.GetSessions() ?? (IReadOnlyList<GlobalSystemMediaTransportControlsSession>)[];

    private static bool IsPlaying(GlobalSystemMediaTransportControlsSession s)
    {
        try
        {
            return s.GetPlaybackInfo().PlaybackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing;
        }
        catch (Exception)
        {
            return false;
        }
    }

    private void WatchSessions()
    {
        foreach (var s in Sessions())
        {
            lock (gate)
            {
                // Один и тот же плеер может прийти новым объектом: подписываемся на каждый новый.
                if (watched.TryGetValue(s.SourceAppUserModelId, out var known) && ReferenceEquals(known, s)) continue;
                watched[s.SourceAppUserModelId] = s;
                playing[s.SourceAppUserModelId] = IsPlaying(s);
            }
            s.PlaybackInfoChanged += OnPlaybackInfoChanged;
        }
    }

    private void OnPlaybackInfoChanged(GlobalSystemMediaTransportControlsSession s, PlaybackInfoChangedEventArgs args)
    {
        bool now = IsPlaying(s);
        bool was;
        lock (gate)
        {
            was = playing.TryGetValue(s.SourceAppUserModelId, out var p) && p;
            playing[s.SourceAppUserModelId] = now;
            // Пользователь сам включил то, что мы ставили на паузу: больше не наше.
            if (now) pausedByUs.Remove(s);
        }
        if (now && !was) PlaybackStarted?.Invoke(s.SourceAppUserModelId);
    }
}
