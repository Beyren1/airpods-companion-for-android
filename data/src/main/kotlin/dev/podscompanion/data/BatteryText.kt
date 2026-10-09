package dev.podscompanion.data

import dev.podscompanion.protocol.advertising.BatteryLevel

/**
 * В advertising заряд приходит десятками с округлением вниз: 99 % → «9» → 90.
 * Поэтому показываем «90+%», а «100%» только для полного заряда.
 * Точный заряд из AAP ([exact]) показываем как есть: «97%».
 */
fun BatteryLevel.displayText(exact: Boolean = false): String =
    if (exact || percent >= 100) "$percent%" else "$percent+%"
