package dev.podscompanion.data.route

/** Куда перевести музыку и видео. */
enum class MediaRoute { PHONE, HEADPHONES }

/**
 * Решает, когда уводить звук (музыку, видео, звуки приложений) из снятых наушников в телефон
 * и когда возвращать его обратно.
 *
 * - Сняли наушники (Max не на голове, ни одного AirPods в ушах) и через [offDelayMs] они всё ещё
 *   сняты — звук в телефон. Задержка гасит случайное «снято» на долю секунды, когда поправляешь наушники.
 * - Надели (хотя бы один наушник в ухе) — обратно в наушники, но только если увели мы.
 * - Уводим один раз за снятие и только в первые [armWindowMs]: если звук потом снова пошёл в наушники
 *   (человек сам выбрал их в окне вывода звука), не спорим с ним до следующего снятия.
 *   Окно нужно и для только что подключённых наушников: Android делает их активными не сразу.
 * - Во время звонка ([busy]) ждём: звонками занимается отдельная настройка.
 *
 * Класс без Android, тестируется на JVM.
 */
class OffHeadMediaPolicy(
    private val offDelayMs: Long = 1_500,
    private val armWindowMs: Long = 30_000,
) {
    private var offSinceMs: Long? = null

    /** Звук сейчас в телефоне, потому что его увели мы. */
    var movedByUs: Boolean = false
        private set

    /** Наушники сняты, а звук ещё не уведён: сервису стоит проверить ещё раз чуть позже. */
    fun waiting(nowMs: Long): Boolean {
        val since = offSinceMs ?: return false
        return !movedByUs && nowMs - since < offDelayMs + armWindowMs
    }

    /**
     * @param offHead наушники сняты; null — неизвестно (нет данных об ушах), тогда ничего не меняем.
     * @param podsActive звук сейчас идёт в эти наушники.
     * @param busy идёт звонок.
     * @param nowMs монотонное время (elapsedRealtime).
     */
    fun onUpdate(offHead: Boolean?, podsActive: Boolean, busy: Boolean, nowMs: Long): MediaRoute? {
        if (offHead == null) return null
        if (!offHead) {
            offSinceMs = null
            if (!movedByUs) return null
            movedByUs = false
            return MediaRoute.HEADPHONES
        }
        val since = offSinceMs ?: nowMs.also { offSinceMs = it }
        if (movedByUs || busy || !podsActive) return null
        val offFor = nowMs - since
        if (offFor < offDelayMs || offFor > offDelayMs + armWindowMs) return null
        movedByUs = true
        return MediaRoute.PHONE
    }

    /** Увести не вышло: звук остался в наушниках. До следующего снятия больше не пробуем. */
    fun onMoveFailed() {
        movedByUs = false
        offSinceMs = Long.MIN_VALUE / 2
    }

    /**
     * Звук вернули в наушники помимо [onUpdate] (перед автопродолжением музыки).
     * true — звук уводили мы, и его действительно надо вернуть.
     */
    fun takeRestore(): Boolean {
        if (!movedByUs) return false
        movedByUs = false
        offSinceMs = null
        return true
    }

    /** Наушники отключились или функцию выключили: помнить нечего. */
    fun reset() {
        offSinceMs = null
        movedByUs = false
    }
}
