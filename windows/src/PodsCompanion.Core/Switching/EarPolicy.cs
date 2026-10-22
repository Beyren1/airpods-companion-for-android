using PodsCompanion.Core.Advertising;

namespace PodsCompanion.Core.Switching;

/// <summary>Что сделать с музыкой после очередного пакета рекламы.</summary>
public enum EarAction
{
    None,
    Pause,
    Resume,
}

/// <summary>
/// Пауза, как у iPhone: сняли один наушник (или Max с головы) — пауза, надели обратно — играет дальше.
/// Чистый класс: на вход только пакеты, поэтому проверяется тестами.
/// </summary>
public sealed class EarPolicy
{
    private bool? fullyWorn;

    public EarAction OnMessage(ProximityPairingMessage message)
    {
        if (message.Model is { EarDetection: false }) return EarAction.None;
        bool now = message.Model?.Kind == PodsKind.Single
            ? message.Primary.InEar
            : message.Left.InEar && message.Right.InEar;
        var previous = fullyWorn;
        fullyWorn = now;
        return (previous, now) switch
        {
            (true, false) => EarAction.Pause,
            (false, true) => EarAction.Resume,
            _ => EarAction.None,
        };
    }

    /// <summary>Наушники ушли к другому устройству: начинаем отслеживать заново.</summary>
    public void Reset() => fullyWorn = null;
}
