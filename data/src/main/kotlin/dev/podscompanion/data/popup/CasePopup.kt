package dev.podscompanion.data.popup

import dev.podscompanion.data.PodsStatus
import dev.podscompanion.protocol.advertising.Capability
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Решает, когда показать окно «кейс открыт». Окно всплывает один раз для каждой модели наушников:
 * при первом открытии кейса рядом с телефоном. Модели, для которых окно уже было, передаём в [shown].
 *
 * Кейс открыт: хотя бы один наушник в кейсе и заряд кейса пришёл в этом пакете (при закрытой
 * крышке наушники кейс не сообщают, мы показываем запомненный). Чужие наушники рядом отсекаем
 * по уровню сигнала: свой кейс открывают у телефона.
 */
class CaseOpenDetector(private val minRssi: Int = -65) {
    private var wasOpen = false

    /** true — пора показать окно для [status]. */
    fun onStatus(status: PodsStatus?, shown: Set<Int>): Boolean {
        val open = status != null && isOpen(status)
        val opened = open && !wasOpen
        wasOpen = open
        return opened && status != null && status.modelId !in shown
    }

    private fun isOpen(status: PodsStatus): Boolean {
        val model = status.model
        if (model != null && Capability.CHARGING_CASE !in model.capabilities) return false
        return status.rssi >= minRssi &&
            status.caseBattery != null && !status.caseBatteryRemembered &&
            (status.left.inCase || status.right.inCase)
    }

    companion object {
        /** Окно закрываем, когда крышку закрыли или наушники пропали из виду. */
        fun isClosed(status: PodsStatus?): Boolean =
            status == null || status.caseBatteryRemembered || (!status.left.inCase && !status.right.inCase)
    }
}

/** Последнее состояние от фонового сервиса: по нему окно обновляет цифры, пока открыто. */
@Singleton
class LiveStatus @Inject constructor() {
    private val _status = MutableStateFlow<PodsStatus?>(null)
    val status: StateFlow<PodsStatus?> = _status.asStateFlow()

    fun update(status: PodsStatus?) {
        _status.value = status
    }
}
