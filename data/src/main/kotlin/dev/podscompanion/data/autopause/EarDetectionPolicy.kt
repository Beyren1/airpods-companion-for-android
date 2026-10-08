package dev.podscompanion.data.autopause

enum class MediaAction { PAUSE, RESUME }

/**
 * Решает, когда ставить музыку на паузу и когда продолжать. Как на iPhone:
 * вынули наушник (или сняли Max) во время воспроизведения → пауза;
 * вернули обратно → продолжаем, но только если паузу ставили мы.
 *
 * Флаги «в ухе» в advertising иногда мигают, поэтому новое состояние принимается,
 * только если пришло [confirmations] пакетов подряд. Класс без Android, тестируется на JVM.
 */
class EarDetectionPolicy(
    private val confirmations: Int = 2,
    /** После паузы/продолжения столько миллисекунд не реагируем на обратное: гасит «эхо» от запоздавших пакетов. */
    private val cooldownMs: Long = 3_000,
) {
    private var stableWorn: Boolean? = null
    private var candidate: Boolean? = null
    private var candidateCount = 0
    private var pausedByUs = false
    private var lastActionAtMs = Long.MIN_VALUE / 2

    /**
     * @param worn наушники полностью надеты (оба в ушах; у Max — на голове); null — наушников не видно.
     * @param musicPlaying играет ли сейчас музыка на телефоне.
     * @param nowMs монотонное время (elapsedRealtime).
     */
    fun onUpdate(worn: Boolean?, musicPlaying: Boolean, nowMs: Long): MediaAction? {
        if (worn == null) {
            reset()
            return null
        }
        if (worn != candidate) {
            candidate = worn
            candidateCount = 0
        }
        candidateCount++
        if (candidateCount < confirmations || worn == stableWorn) return null
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

    /** Пользователь сам нажал play/выключил настройку: забываем, что пауза была наша. */
    fun reset() {
        stableWorn = null
        candidate = null
        candidateCount = 0
        pausedByUs = false
    }
}
