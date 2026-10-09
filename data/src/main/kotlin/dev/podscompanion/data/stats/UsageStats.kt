package dev.podscompanion.data.stats

import java.time.LocalDate

/** Сколько секунд за день наушники были в ушах, играла музыка и шёл разговор. */
data class UsageTotals(val wornSec: Long = 0, val musicSec: Long = 0, val callSec: Long = 0) {
    operator fun plus(other: UsageTotals) =
        UsageTotals(wornSec + other.wornSec, musicSec + other.musicSec, callSec + other.callSec)

    val isEmpty: Boolean get() = wornSec == 0L && musicSec == 0L && callSec == 0L
}

/**
 * Статистика одной пары наушников. Пару узнаём по постоянному адресу Bluetooth Classic
 * (тот, что в настройках Bluetooth), поэтому две пары одной модели считаются отдельно.
 */
data class PairUsage(
    val address: String,
    /** Имя, под которым пара подключена к телефону («AirPods Pro Beyren»). */
    val name: String,
    /** Модель из рекламы; null — ещё не узнали (рисуем общую картинку). */
    val modelId: Int?,
    val days: Map<LocalDate, UsageTotals> = emptyMap(),
    /** Когда пара последний раз была подключена (мс, System.currentTimeMillis): по нему сортируем карточки. */
    val lastUsedMs: Long = 0,
) {
    fun on(day: LocalDate): UsageTotals = days[day] ?: UsageTotals()

    /** Сумма за [count] дней, заканчивая [today]. */
    fun lastDays(today: LocalDate, count: Int): UsageTotals =
        (0 until count).fold(UsageTotals()) { sum, back -> sum + on(today.minusDays(back.toLong())) }
}

/** Что сейчас происходит с одной подключённой парой (один «тик» счётчика). */
data class PairActivity(
    val address: String,
    val name: String,
    val modelId: Int?,
    val worn: Boolean,
    val music: Boolean,
    val call: Boolean,
)

/** Вся статистика: по паре на карточку. */
data class UsageHistory(val pairs: Map<String, PairUsage> = emptyMap()) {

    /**
     * Добавить [seconds] к сегодняшним счётчикам каждой подключённой пары. Подключённая пара
     * получает запись, даже если её не надевали: на экране видно, что она была.
     */
    fun record(activity: List<PairActivity>, day: LocalDate, seconds: Long, nowMs: Long): UsageHistory {
        if (activity.isEmpty()) return this
        val updated = pairs.toMutableMap()
        for (a in activity) {
            val old = updated[a.address] ?: PairUsage(a.address, a.name, a.modelId)
            val add = UsageTotals(
                wornSec = if (a.worn) seconds else 0,
                musicSec = if (a.music) seconds else 0,
                callSec = if (a.call) seconds else 0,
            )
            updated[a.address] = old.copy(
                name = a.name,
                // Модель не забываем, если в этот раз её не удалось определить.
                modelId = a.modelId ?: old.modelId,
                days = if (add.isEmpty) old.days else old.days + (day to old.on(day) + add),
                lastUsedMs = nowMs,
            )
        }
        return UsageHistory(updated)
    }

    /** Убрать дни старше [keepDays]: на экране нужны только последние дни, файл не должен расти. */
    fun trimmed(today: LocalDate, keepDays: Int = KEEP_DAYS): UsageHistory {
        val first = today.minusDays(keepDays.toLong() - 1)
        return UsageHistory(pairs.mapValues { (_, p) -> p.copy(days = p.days.filterKeys { !it.isBefore(first) }) })
    }

    /** Пары для экрана: недавние первыми. */
    fun sorted(): List<PairUsage> = pairs.values.sortedByDescending { it.lastUsedMs }

    /**
     * Текстовый формат, по строке на запись (поля через табуляцию):
     * `P адрес модель время имя` — пара, `D адрес дата в_ушах музыка звонки` — день.
     */
    fun encode(): String = buildString {
        for (p in pairs.values) {
            append(listOf("P", p.address, p.modelId ?: -1, p.lastUsedMs, clean(p.name)).joinToString(SEP)).append('\n')
            for ((day, t) in p.days.toSortedMap()) {
                append(listOf("D", p.address, day, t.wornSec, t.musicSec, t.callSec).joinToString(SEP)).append('\n')
            }
        }
    }

    companion object {
        const val KEEP_DAYS = 30
        private const val SEP = "\t"

        private fun clean(name: String) = name.replace('\t', ' ').replace('\n', ' ')

        /** Повреждённые строки пропускаем: лучше потерять один день, чем всю статистику. */
        fun decode(text: String): UsageHistory {
            val pairs = LinkedHashMap<String, PairUsage>()
            val days = HashMap<String, MutableMap<LocalDate, UsageTotals>>()
            for (line in text.lineSequence()) {
                val p = line.split(SEP)
                runCatching {
                    when (p[0]) {
                        "P" -> pairs.put(
                            p[1],
                            PairUsage(
                                address = p[1],
                                name = p.drop(4).joinToString(" "),
                                modelId = p[2].toInt().takeIf { it >= 0 },
                                lastUsedMs = p[3].toLong(),
                            ),
                        )
                        "D" -> days.getOrPut(p[1]) { HashMap() }
                            .put(LocalDate.parse(p[2]), UsageTotals(p[3].toLong(), p[4].toLong(), p[5].toLong()))
                        else -> null
                    }
                }
            }
            return UsageHistory(pairs.mapValues { (address, pair) -> pair.copy(days = days[address].orEmpty()) })
        }
    }
}

/**
 * Кому засчитать музыку и разговор, если подключено несколько пар. Android не говорит открыто,
 * в какие наушники идёт звук, поэтому считаем так: звук слушают те, что в ушах; если в ушах
 * никого не видно, а подключена одна пара — она.
 */
object UsageAttribution {
    data class Connected(val address: String, val name: String, val modelId: Int?, val worn: Boolean)

    fun attribute(connected: List<Connected>, musicActive: Boolean, callActive: Boolean): List<PairActivity> {
        val wornPairs = connected.filter { it.worn }
        val listeners = wornPairs.ifEmpty { connected.singleOrNull()?.let(::listOf).orEmpty() }.map { it.address }.toSet()
        return connected.map {
            val listening = it.address in listeners
            PairActivity(
                address = it.address,
                name = it.name,
                modelId = it.modelId,
                worn = it.worn,
                // Во время звонка музыка обычно на паузе; если нет — считаем только разговор.
                music = listening && musicActive && !callActive,
                call = listening && callActive,
            )
        }
    }
}
