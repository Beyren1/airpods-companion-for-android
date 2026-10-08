package dev.podscompanion.data

import dev.podscompanion.protocol.advertising.PodsModel

/**
 * Сопоставляет имя подключённого Bluetooth-устройства с моделью из рекламы.
 * Счёт = сколько слов из названия модели есть в имени устройства:
 * «AirPods Max» → Max: 2, Pro 2: 1. Если лучший счёт делят несколько моделей, совпадения нет.
 */
object ConnectedNameMatcher {

    fun score(model: PodsModel, deviceName: String): Int {
        val name = words(deviceName)
        return words(model.displayName).count { it in name }
    }

    /** Модель (из [candidates]), которая однозначно лучше всех подходит к одному из имён. */
    fun bestMatch(candidates: Collection<PodsModel>, deviceNames: List<String>): PodsModel? {
        if (candidates.isEmpty() || deviceNames.isEmpty()) return null
        val scored = candidates.distinct().map { model ->
            model to deviceNames.maxOf { score(model, it) }
        }
        val best = scored.maxOf { it.second }
        if (best == 0) return null
        return scored.filter { it.second == best }.singleOrNull()?.first
    }

    // «AirPods Pro 2 (USB-C)» → [airpods, pro, 2, usb, c]
    private fun words(text: String): Set<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.toSet()
}
