package dev.podscompanion.protocol.aap

/**
 * Режим шумоподавления, который выбрал пользователь, пока наушники его не подтвердили.
 *
 * Без этого выбор иногда «не срабатывал»: команда терялась, если соединение рвалось в момент
 * отправки, а пока наушники не ответили, они могли прислать уведомление со старым режимом,
 * и экран прыгал назад. Теперь:
 * - пока запрос жив, экран показывает выбранный режим ([shown]);
 * - если подтверждения нет [retryMs], команда уходит ещё раз (до [maxAttempts] раз);
 * - после переподключения команда повторяется, если выбор свежее [ttlMs];
 * - наушники так и не согласились (например, шумоподавление с одним наушником) — запрос
 *   снимается, и экран показывает то, что сообщили наушники.
 *
 * Без Android, время передаётся снаружи: так проверяется тестами на JVM.
 * Вызывается и из интерфейса, и из соединения, поэтому методы synchronized.
 */
class ModeRequest(
    private val retryMs: Long = 700,
    private val maxAttempts: Int = 3,
    private val ttlMs: Long = 10_000,
) {
    private var mode: ListeningMode? = null
    private var createdAtMs = 0L
    private var sentAtMs = 0L
    private var attempts = 0

    /** Выбранный, но ещё не подтверждённый режим; null — ничего не ждём. */
    @get:Synchronized
    val pending: ListeningMode? get() = mode

    /** Пользователь выбрал режим, команда отправлена. */
    @Synchronized
    fun request(mode: ListeningMode, nowMs: Long) {
        this.mode = mode
        createdAtMs = nowMs
        sentAtMs = nowMs
        attempts = 1
    }

    /** Наушники сообщили свой режим. Совпал с выбранным — запрос выполнен. */
    @Synchronized
    fun onReported(reported: ListeningMode) {
        if (reported == mode) mode = null
    }

    /**
     * Проверка по таймеру. Возвращает режим, который пора отправить ещё раз, или null.
     * Попытки кончились — запрос снимается (см. [pending]).
     */
    @Synchronized
    fun due(nowMs: Long): ListeningMode? {
        val current = mode ?: return null
        if (nowMs - sentAtMs < retryMs) return null
        if (attempts >= maxAttempts || nowMs - createdAtMs > ttlMs) {
            mode = null
            return null
        }
        attempts++
        sentAtMs = nowMs
        return current
    }

    /** Соединение установлено заново: повторить свежий выбор, старый забыть. */
    @Synchronized
    fun onReconnected(nowMs: Long): ListeningMode? {
        val current = mode ?: return null
        if (nowMs - createdAtMs > ttlMs) {
            mode = null
            return null
        }
        attempts = 1
        sentAtMs = nowMs
        return current
    }

    /** Состояние для экрана: пока ждём подтверждения, показываем выбранный режим. */
    fun shown(device: AapDeviceState): AapDeviceState {
        val wanted = pending ?: return device
        return if (device.listeningMode == wanted) device else device.copy(listeningMode = wanted)
    }
}
