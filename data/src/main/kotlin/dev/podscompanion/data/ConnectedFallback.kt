package dev.podscompanion.data

import dev.podscompanion.data.aap.AapOverlay
import dev.podscompanion.protocol.aap.AapDeviceState
import dev.podscompanion.protocol.aap.EarState
import dev.podscompanion.protocol.advertising.BatteryLevel
import dev.podscompanion.protocol.advertising.Capability
import dev.podscompanion.protocol.advertising.PodState
import dev.podscompanion.protocol.advertising.PodsModel

/**
 * Карточка подключённых наушников, когда их рекламы не слышно.
 *
 * Некоторые прошивки (Samsung A56 с AirPods Pro 1) не отдают приложению BLE-пакеты Apple, хотя
 * наушники подключены и играют. Тогда показываем то, что знаем без рекламы: имя из Bluetooth,
 * заряд из системы (HFP) и, если удалось, всё из прямого подключения (AAP).
 *
 * Чистый объект без Android, тестируется на JVM.
 */
object ConnectedFallback {

    /**
     * @param name имя подключённого устройства («AirPods Pro»).
     * @param systemBattery заряд, который наушники сообщили телефону по HFP (у AirPods это меньший из двух).
     * @param aap данные прямого подключения, если оно установлено.
     */
    fun status(name: String, systemBattery: Int?, aap: AapDeviceState?, nowMs: Long): PodsStatus {
        val model = modelFor(name)
        // Без AAP по отдельности заряд неизвестен: показываем общий на обоих, так честнее, чем пусто.
        val pod = PodState(battery = systemBattery?.let(::BatteryLevel), charging = false, inEar = false)
        val base = PodsStatus(
            model = model,
            modelId = model?.modelId ?: 0,
            left = pod,
            right = pod,
            primary = pod,
            caseBattery = null,
            caseCharging = false,
            lidCounter = 0,
            colorCode = 0,
            // Сигнала нет: так окно «кейс открыт» и прочее, что опирается на близость, не сработает.
            rssi = NO_RSSI,
            lastSeenMs = nowMs,
            rawHex = "",
            connected = true,
            advertised = false,
            earKnown = false,
        )
        if (aap == null) return base
        return AapOverlay.apply(base, aap, aap.batteryPrimaryIsLeft ?: true)
            .copy(earKnown = earKnown(model, aap))
    }

    /**
     * Модель по имени. «AirPods Pro» похоже сразу на Pro, Pro 2 и Pro 3; если имя совпадает
     * с названием модели слово в слово, берём её, иначе — только если подходит ровно одна.
     */
    fun modelFor(name: String): PodsModel? {
        val models = ConnectedNameMatcher.modelsForName(name)
        val words = words(name)
        return models.singleOrNull { words(it.displayName) == words } ?: models.singleOrNull()
    }

    /** Можно ли судить по AAP, надеты ли наушники: у вкладышей нужны оба уха. */
    private fun earKnown(model: PodsModel?, aap: AapDeviceState): Boolean {
        if (!aap.earKnown) return false
        val stereo = model?.capabilities?.contains(Capability.STEREO_BUDS) ?: (aap.single == null)
        return !stereo || aap.secondaryEar != EarState.UNKNOWN
    }

    private fun words(text: String): Set<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.toSet()

    const val NO_RSSI = -127
}
