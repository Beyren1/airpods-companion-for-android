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
class EarDetectionPolicy(private val confirmations: Int = 2) {
    private var stableWorn: Boolean? = null
    private var candidate: Boolean? = null
    private var candidateCount = 0
    private var pausedByUs = false

    /**
     * @param worn наушники полностью надеты (оба в ушах; у Max — на голове); null — наушников не видно.
     * @param musicPlaying играет ли сейчас музыка на телефоне.
     */
    fun onUpdate(worn: Boolean?, musicPlaying: Boolean): MediaAction? {
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

        val previous = stableWorn
        stableWorn = worn
        if (previous == null) return null // первое состояние после появления наушников: ничего не делаем

        return when {
            !worn && musicPlaying -> {
                pausedByUs = true
                MediaAction.PAUSE
            }
            worn && pausedByUs -> {
                pausedByUs = false
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
