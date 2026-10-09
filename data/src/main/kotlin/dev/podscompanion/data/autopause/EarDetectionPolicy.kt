package dev.podscompanion.data.autopause

enum class MediaAction { PAUSE, RESUME }

/**
 * Решает, когда ставить музыку на паузу и когда продолжать. Как на iPhone:
 * вынули наушник (или сняли Max) во время воспроизведения → пауза;
 * вернули обратно → продолжаем, но только если паузу ставили мы.
 *
 * Подключённые наушники шлют advertising редко (у Max по журналу раз в ~5 с), и каждое
 * дополнительное подтверждение стоит целый интервал. Поэтому по умолчанию реагируем на первый же
 * пакет, а «эхо» от запоздавшего пакета второго наушника гасит [cooldownMs].
 * Класс без Android, тестируется на JVM.
 */
class EarDetectionPolicy(
    private val confirmationsToPause: Int = 1,
    private val confirmationsToResume: Int = 1,
    /** После паузы/продолжения столько миллисекунд не реагируем на обратное: гасит «эхо» от запоздавших пакетов. */
    private val cooldownMs: Long = 3_000,
    /**
     * Сколько наушников может быть «не видно», прежде чем забыть их состояние. Короткие пропуски
     * (телефон с выключенным экраном реже слушает эфир) не должны сбрасывать паузу: иначе после
     * пропуска первое «сняты» считалось начальным состоянием и паузы не было.
     */
    private val forgetAfterMs: Long = 60_000,
) {
    private var stableWorn: Boolean? = null
    private var candidate: Boolean? = null
    private var candidateCount = 0
    private var pausedByUs = false
    private var lastActionAtMs = Long.MIN_VALUE / 2
    private var unknownSinceMs: Long? = null

    /**
     * @param worn наушники полностью надеты (оба в ушах; у Max — на голове); null — наушников не видно.
     * @param musicPlaying играет ли сейчас музыка на телефоне.
     * @param nowMs монотонное время (elapsedRealtime).
     */
    fun onUpdate(worn: Boolean?, musicPlaying: Boolean, nowMs: Long): MediaAction? {
        if (worn == null) {
            val since = unknownSinceMs ?: nowMs.also { unknownSinceMs = it }
            if (nowMs - since >= forgetAfterMs) reset()
            return null
        }
        unknownSinceMs = null
        if (worn != candidate) {
            candidate = worn
            candidateCount = 0
        }
        candidateCount++
        val needed = if (worn) confirmationsToResume else confirmationsToPause
        if (candidateCount < needed || worn == stableWorn) return null
        if (nowMs - lastActionAtMs < cooldownMs) return null

        val previous = stableWorn
        stableWorn = worn
        if (previous == null) return null // первое состояние после появления наушников: ничего не делаем

        return when {
            !worn && musicPlaying -> {
                pausedByUs = true
                lastActionAtMs = nowMs
                MediaAction.PAUSE
            }
            worn && pausedByUs -> {
                pausedByUs = false
                lastActionAtMs = nowMs
                MediaAction.RESUME
            }
            else -> null
        }
    }

    /**
     * Смена состояния пришла во время [cooldownMs] и пока отложена. Новых пакетов с тем же состоянием
     * может не прийти (одинаковые состояния дальше не передаются), поэтому сервис сам повторяет
     * проверку в возвращённое время. null — ничего не ждёт.
     */
    fun retryAtMs(): Long? =
        if (candidate != null && candidate != stableWorn) lastActionAtMs + cooldownMs else null

    /** Пользователь сам нажал play/выключил настройку: забываем, что пауза была наша. */
    fun reset() {
        stableWorn = null
        candidate = null
        candidateCount = 0
        pausedByUs = false
        unknownSinceMs = null
    }
}
