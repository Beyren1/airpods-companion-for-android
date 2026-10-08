package dev.podscompanion.data.aap

import dev.podscompanion.data.ConnectedNameMatcher
import dev.podscompanion.protocol.aap.AapDeviceState
import dev.podscompanion.protocol.advertising.PodsModel

/** Состояние прямого подключения к одним наушникам (расширенный режим). */
sealed interface AapSessionState {
    val deviceName: String
    val address: String

    data class Connecting(override val deviceName: String, override val address: String, val attempt: Int) : AapSessionState

    data class Connected(
        override val deviceName: String,
        override val address: String,
        val method: String,
        val device: AapDeviceState,
    ) : AapSessionState

    data class Failed(
        override val deviceName: String,
        override val address: String,
        val reason: FailureReason,
        val details: String,
        val retryInSec: Int,
    ) : AapSessionState
}

enum class FailureReason {
    /** Android не дал создать L2CAP-сокет: на этой прошивке без root не работает. */
    SOCKET_BLOCKED,

    /** Сокет есть, но наушники не приняли подключение или оборвали его. */
    CONNECTION_FAILED,
}

/** Прямые подключения ко всем наушникам Apple, подключённым к телефону (их может быть двое). */
data class AapSessions(
    /** Нет разрешения «Устройства поблизости»: не знаем, что подключено. */
    val noPermission: Boolean = false,
    val sessions: List<AapSessionState> = emptyList(),
) {
    /**
     * Сессия для наушников этой модели. Сопоставляем по имени устройства: «AirPods Max» → Max.
     * Если подходят несколько (одни названы просто «AirPods»), берём самое точное имя.
     * Без этого при двух подключённых наушниках данные Max ложились на карточку Pro 2.
     */
    fun forModel(model: PodsModel?): AapSessionState? {
        if (model == null) return sessions.singleOrNull()
        return sessions
            .map { it to ConnectedNameMatcher.modelsForName(it.deviceName) }
            .filter { (_, models) -> model in models }
            .minByOrNull { (_, models) -> models.size }
            ?.first
            // Переименованные наушники («Мои уши»): если такая сессия одна, считаем, что это они.
            ?: sessions.filter { ConnectedNameMatcher.modelsForName(it.deviceName).isEmpty() }.singleOrNull()
    }
}
