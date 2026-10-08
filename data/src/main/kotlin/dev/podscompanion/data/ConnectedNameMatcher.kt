package dev.podscompanion.data

import dev.podscompanion.protocol.advertising.PodsModel

/**
 * Сопоставляет имя подключённого Bluetooth-устройства с моделью из рекламы.
 *
 * Сначала имя сравнивается со всем каталогом моделей: счёт = сколько слов из названия модели есть
 * в имени. «AirPods Max» → обе модели Max (по 2 слова), а не Pro 2 (1 слово). Только потом смотрим,
 * какие из этих моделей сейчас рядом. Иначе, если Max не видны, «AirPods Max» совпало бы с Pro 2
 * по одному слову «AirPods».
 */
object ConnectedNameMatcher {

    fun score(model: PodsModel, deviceName: String): Int {
        val name = words(deviceName)
        return words(model.displayName).count { it in name }
    }

    /** Модели каталога, на которые имя похоже больше всего; пусто, если имя не похоже ни на одну. */
    fun modelsForName(deviceName: String): Set<PodsModel> {
        val scored = PodsModel.entries.map { it to score(it, deviceName) }
        val best = scored.maxOf { it.second }
        if (best == 0) return emptySet()
        return scored.filter { it.second == best }.map { it.first }.toSet()
    }

    /** Все модели, на которые похожи имена подключённых устройств. */
    fun knownModels(deviceNames: List<String>): Set<PodsModel> =
        deviceNames.flatMapTo(mutableSetOf()) { modelsForName(it) }

    /** Модель из [candidates] (наушники рядом), которая однозначно соответствует одному из имён. */
    fun bestMatch(candidates: Collection<PodsModel>, deviceNames: List<String>): PodsModel? =
        candidates.distinct().filter { it in knownModels(deviceNames) }.singleOrNull()

    // «AirPods Pro 2 (USB-C)» → [airpods, pro, 2, usb, c]
    private fun words(text: String): Set<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.toSet()
}
