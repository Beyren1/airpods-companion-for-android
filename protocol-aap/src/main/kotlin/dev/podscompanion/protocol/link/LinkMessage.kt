package dev.podscompanion.protocol.link

import dev.podscompanion.protocol.advertising.PcSignal

/**
 * Сообщение связи между приложением на телефоне и программой на ПК (UDP, порт [PORT], домашняя сеть).
 * Одна строка текста, тот же формат разбирает LinkMessage.cs на ПК:
 *
 * `PCLINK 1 <тип> <id> <phone|pc> <наушники> <имя устройства>`
 *
 * - id — случайный номер установки, чтобы узнавать свои же широковещательные пакеты;
 * - наушники — «хэш:1» или «хэш:0» через запятую (1 — подключены к отправителю), «-» если нет;
 *   хэш — первые 4 байта SHA-256 от адреса наушников (как в PcSignal), сам адрес не рассылаем;
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
    }

    enum class Kind(val wire: String) { PHONE("phone"), PC("pc") }

    fun encode(): String {
        val podsText = if (pods.isEmpty()) "-" else pods.joinToString(",") { "${it.hash}:${if (it.here) 1 else 0}" }
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
            val pods = if (parts[5] == "-") emptyList() else parts[5].split(',').map { item ->
                val (hash, here) = item.split(':').takeIf { it.size == 2 } ?: return null
                LinkPods(hash.lowercase(), here == "1")
            }
            return LinkMessage(type, parts[3], kind, pods, parts[6])
        }
    }
}

/** Наушники в сообщении связи: [hash] — 8 hex-символов, [here] — подключены к отправителю. */
data class LinkPods(val hash: String, val here: Boolean)
