package dev.podscompanion.data.battery

import dev.podscompanion.data.PodsStatus
import dev.podscompanion.protocol.advertising.Capability

/** Часть наушников со своим зарядом. У Max один заряд — [SINGLE]. */
enum class BatteryPart { LEFT, RIGHT, CASE, SINGLE }

/** Часть с низким зарядом: что показать в уведомлении. */
data class LowPart(val part: BatteryPart, val percent: Int, val charging: Boolean)

sealed interface LowBatteryEvent {
    /** Заряд опустился ниже нового порога: показать (или обновить) уведомление со звуком. */
    data class Alert(val device: String, val status: PodsStatus, val parts: List<LowPart>) : LowBatteryEvent

    /** Всё, о чём предупреждали, зарядилось или стоит на зарядке: уведомление больше не нужно. */
    data class Dismiss(val device: String) : LowBatteryEvent
}

/**
 * Решает, когда предупредить о низком заряде. Чистая логика без Android, чтобы её можно было
 * проверить тестами.
 *
 * Пороги, например 20 и 10: о каждой части (левый, правый, кейс) предупреждаем один раз при
 * падении до 20 % и ещё раз до 10 %. Снова предупредить о пороге можно, только когда часть
 * зарядилась выше него с запасом [REARM_MARGIN] — иначе заряд, скачущий 20↔21, звенел бы без конца.
 *
 * Значение должно продержаться [STABLE_MS]: на пару секунд после вынимания наушника приложение
 * может перепутать левый и правый, и без задержки пришло бы ложное предупреждение.
 *
 * Только свои наушники (подключённые или узнанные по ключу): о чужих рядом не предупреждаем.
 */
class LowBatteryWatcher {

    private class PartState {
        /** Сколько порогов уже пройдено и о скольких предупредили (0 — ни об одном). */
        var notified = 0
        var lowSinceMs: Long? = null
        var highSinceMs: Long? = null
        var last: LowPart? = null
    }

    private val states = mutableMapOf<Pair<String, BatteryPart>, PartState>()

    /** Устройства, для которых уведомление сейчас показано. */
    private val shown = mutableSetOf<String>()

    /** [thresholds] — пороги в процентах, порядок любой. [nowMs] — монотонное время. */
    fun onStatus(status: PodsStatus?, thresholds: List<Int>, nowMs: Long): LowBatteryEvent? {
        val own = status?.takeIf { it.connected || it.owner != null }
        if (own == null || thresholds.isEmpty()) {
            resetTimers(except = null)
            return null
        }
        val device = deviceKey(own)
        resetTimers(except = device)
        val sorted = thresholds.sortedDescending()

        var alert = false
        for ((part, reading) in readings(own)) {
            val state = states.getOrPut(device to part) { PartState() }
            if (reading == null) {
                state.lowSinceMs = null
                state.highSinceMs = null
                continue
            }
            state.last = reading

            // Ниже скольких порогов сейчас заряд. На зарядке не предупреждаем.
            val zone = if (reading.charging) 0 else zoneOf(reading.percent, sorted)
            if (zone > state.notified) {
                if (state.lowSinceMs == null) state.lowSinceMs = nowMs
                if (nowMs - state.lowSinceMs!! >= STABLE_MS) {
                    state.notified = zone
                    state.lowSinceMs = null
                    alert = true
                }
            } else {
                state.lowSinceMs = null
            }

            // Зарядился выше пройденного порога с запасом: о нём снова можно предупредить.
            val recovered = zoneOf(reading.percent - REARM_MARGIN, sorted)
            if (recovered < state.notified) {
                if (state.highSinceMs == null) state.highSinceMs = nowMs
                if (nowMs - state.highSinceMs!! >= STABLE_MS) {
                    state.notified = recovered
                    state.highSinceMs = null
                }
            } else {
                state.highSinceMs = null
            }
        }

        val low = BatteryPart.entries.mapNotNull { part ->
            states[device to part]?.takeIf { it.notified > 0 }?.last
        }
        return when {
            alert -> LowBatteryEvent.Alert(device, own, low)
            low.all { it.charging } -> if (shown.remove(device)) LowBatteryEvent.Dismiss(device) else null
            else -> null
        }.also { if (it is LowBatteryEvent.Alert) shown += device }
    }

    private fun resetTimers(except: String?) {
        for ((key, state) in states) {
            if (key.first == except) continue
            state.lowSinceMs = null
            state.highSinceMs = null
        }
    }

    private fun zoneOf(percent: Int, sortedDesc: List<Int>) = sortedDesc.count { percent <= it }

    private fun deviceKey(status: PodsStatus) = status.owner ?: "model:${status.modelId}"

    private fun readings(status: PodsStatus): List<Pair<BatteryPart, LowPart?>> {
        val model = status.model
        if (model != null && Capability.STEREO_BUDS !in model.capabilities) {
            return listOf(BatteryPart.SINGLE to status.primary.battery?.let { LowPart(BatteryPart.SINGLE, it.percent, status.primary.charging) })
        }
        // Заряд кейса «по памяти» (крышка закрыта) устарел: по нему не решаем.
        val case = status.caseBattery?.takeIf { !status.caseBatteryRemembered }
        return listOf(
            BatteryPart.LEFT to status.left.battery?.let { LowPart(BatteryPart.LEFT, it.percent, status.left.charging) },
            BatteryPart.RIGHT to status.right.battery?.let { LowPart(BatteryPart.RIGHT, it.percent, status.right.charging) },
            BatteryPart.CASE to case?.let { LowPart(BatteryPart.CASE, it.percent, status.caseCharging) },
        )
    }

    companion object {
        const val STABLE_MS = 15_000L
        const val REARM_MARGIN = 5

        /** Пороги по настройке: выбранный и всегда ещё 10 %, если выбран выше. */
        fun thresholdsFor(first: Int): List<Int> = if (first > 10) listOf(first, 10) else listOf(first)
    }
}
