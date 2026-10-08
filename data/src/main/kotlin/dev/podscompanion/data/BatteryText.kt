package dev.podscompanion.data

import dev.podscompanion.protocol.advertising.BatteryLevel

/**
 * В advertising заряд приходит десятками с округлением вниз: 99 % → «9» → 90.
 * Поэтому показываем «90+%», а «100%» только для полного заряда.
 * Когда появится точный заряд из AAP, здесь будет просто «97%».
 */
fun BatteryLevel.displayText(): String = if (percent >= 100) "100%" else "$percent+%"
