package dev.podscompanion.protocol.link

import dev.podscompanion.protocol.aap.ListeningMode
import dev.podscompanion.protocol.advertising.PcSignal

/**
 * Сообщение связи между приложением на телефоне и программой на ПК (UDP, порт [PORT], домашняя сеть).
 * Одна строка текста, тот же формат разбирает LinkMessage.cs на ПК:
 *
 * `PCLINK 1 <тип> <id> <phone|pc> <наушники> <имя устройства>`
 *
 * - id — случайный номер установки, чтобы узнавать свои же широковещательные пакеты;
 * - наушники — через запятую «хэш:1» или «хэш:0» (1 — подключены к отправителю), «-» если нет;
 *   хэш — первые 4 байта SHA-256 от адреса наушников (как в PcSignal), сам адрес не рассылаем;
 *   дальше по желанию «:заряд:режим» — заряд «96» или «80/85/60» (левый/правый/кейс, «-» неизвестно),
 *   режим «off», «anc», «transparency», «adaptive»; «-» — не знаем;
 * - имя — до конца строки, может содержать пробелы.
 */
data class LinkMessage(
    val type: Type,
    val id: String,
    val kind: Kind,
    val pods: List<LinkPods>,
    val name: String,
) {
    enum class Type(val wire: String) {
        /** «Я здесь, ответь»: при запуске и время от времени широковещательно. */
        HI("hi"),
        /** Состояние отправителя; ответ на [HI] и при каждом изменении. */
        HELLO("hello"),
        /** «Отпусти эти наушники, я их забираю»: [pods] — какие. */
        RELEASE("release"),
        /** «Забираю эти наушники»: получатель заранее ставит свою музыку на паузу. */
        TAKING("taking"),
        /** «Включи этим наушникам режим»: режим — в [LinkPods.mode]. ПК просит телефон, у ПК нет доступа к AAP. */
        MODE("mode"),
        /** Ответ на [MODE]: режим, который наушники подтвердили; без режима — не получилось. */
        MODE_DONE("modedone"),
    }

    enum class Kind(val wire: String) { PHONE("phone"), PC("pc") }

    fun encode(): String {
        val podsText = if (pods.isEmpty()) "-" else pods.joinToString(",", transform = LinkPods::encode)
        val safeName = name.replace('\n', ' ').replace('\r', ' ').trim().ifEmpty { "?" }
        return "$MAGIC $VERSION ${type.wire} $id ${kind.wire} $podsText $safeName"
    }

    companion object {
        /** Тот же порт, что у сигнала «забери наушники»: телефон слушает один порт. */
        const val PORT = PcSignal.UDP_PORT
        const val MAGIC = "PCLINK"
        const val VERSION = "1"

        fun parse(text: String): LinkMessage? {
            val parts = text.trim().split(' ', limit = 7)
            if (parts.size < 7 || parts[0] != MAGIC || parts[1] != VERSION) return null
            val type = Type.entries.firstOrNull { it.wire == parts[2] } ?: return null
            val kind = Kind.entries.firstOrNull { it.wire == parts[4] } ?: return null
            val pods = if (parts[5] == "-") emptyList() else parts[5].split(',').map { LinkPods.parse(it) ?: return null }
            return LinkMessage(type, parts[3], kind, pods, parts[6])
        }
    }
}

/**
 * Наушники в сообщении связи: [hash] — 8 hex-символов, [here] — подключены к отправителю,
 * [battery] — точный заряд по AAP («96» или «80/85/60»), [mode] — режим шумоподавления.
 */
data class LinkPods(
    val hash: String,
    val here: Boolean,
    val battery: String? = null,
    val mode: ListeningMode? = null,
) {
    fun encode(): String {
        val base = "$hash:${if (here) 1 else 0}"
        if (battery == null && mode == null) return base
        return "$base:${battery ?: "-"}:${mode?.let(MODE_WIRE::get) ?: "-"}"
    }

    companion object {
        private val MODE_WIRE = mapOf(
            ListeningMode.OFF to "off",
            ListeningMode.NOISE_CANCELLATION to "anc",
            ListeningMode.TRANSPARENCY to "transparency",
            ListeningMode.ADAPTIVE to "adaptive",
        )

        fun parse(text: String): LinkPods? {
            val parts = text.split(':')
            if (parts.size != 2 && parts.size != 4) return null
            if (parts[1] != "0" && parts[1] != "1") return null
            val battery = parts.getOrNull(2)?.takeIf { it != "-" }
            val mode = parts.getOrNull(3)?.let { wire -> MODE_WIRE.entries.firstOrNull { it.value == wire }?.key }
            return LinkPods(parts[0].lowercase(), parts[1] == "1", battery, mode)
        }
    }
}
