package dev.podscompanion.protocol.aap

/**
 * Потоки данных на опкоде 0x17 (датчики головы и другие). Тело сообщения — protobuf:
 * `04 00 04 00 17 00 00 00 10 00 <длина LE16> <protobuf>`.
 *
 * Что видно по журналам с Pro 2 и AirPods 4 (наш разбор, не документация):
 * поле 1 — номер сообщения, поле 2 — отправитель, поле 8 — запрос на поток
 * `{1: номер потока (+0x40 — выключить), 2: 2, 3: параметры}`, поле 12 — наушники после
 * подключения объявляют доступные потоки `{1: номер}`. Пакет из описания LibrePods включает поток 14,
 * а Pro 2 на нашей прошивке объявляют 13, 16, 18, 19, поэтому просим и объявленные.
 */
object AapStreams {
    /** Поток из описания протокола LibrePods (прошивка 7A305). */
    const val DOCUMENTED_HEAD_STREAM = 14

    private const val STREAM_MESSAGE = 0x10
    private const val OFF_FLAG = 0x40

    /** Запрос включить или выключить поток [stream]. При seq = 289/126 и потоке 14 совпадает с пакетами LibrePods. */
    fun request(seq: Int, stream: Int, on: Boolean): ByteArray {
        require(stream in 0 until OFF_FLAG) { "Номер потока $stream не помещается в один байт с флагом" }
        val params = if (on) intArrayOf(0x01, 0x40, 0x9C, 0x00, 0x00) else intArrayOf(0x01, 0x00, 0x00, 0x00, 0x00)
        val inner = intArrayOf(0x08, if (on) stream else stream or OFF_FLAG, 0x10, 0x02, 0x1A, params.size) + params
        // В пакете включения LibrePods поля 2 нет, в пакете выключения есть: повторяем как есть.
        val sender = if (on) intArrayOf() else intArrayOf(0x10, 0x02)
        val body = intArrayOf(0x08) + varint(seq) + sender + intArrayOf(0x42, inner.size) + inner
        return Aap.packet(Opcode.HEAD_TRACKING, 0x00, 0x00, STREAM_MESSAGE, 0x00, body.size and 0xFF, body.size shr 8, *body)
    }

    /** Потоки, которые наушники объявили в этом сообщении (поле 12). Пусто, если сообщение другое. */
    fun announced(data: ByteArray): List<Int> {
        val body = body(data) ?: return emptyList()
        return runCatching {
            fields(body).filter { it.first == 12 && it.second is ByteArray }
                .mapNotNull { (_, value) -> fields(value as ByteArray).firstOrNull { it.first == 1 }?.second as? Long }
                .map { it.toInt() }
        }.getOrDefault(emptyList())
    }

    private fun body(data: ByteArray): ByteArray? {
        if (!Aap.isAapPacket(data) || data.size < 12) return null
        val opcode = (data[4].toInt() and 0xFF) or (data[5].toInt() and 0xFF shl 8)
        if (opcode != Opcode.HEAD_TRACKING || data[8].toInt() != STREAM_MESSAGE) return null
        val length = (data[10].toInt() and 0xFF) or (data[11].toInt() and 0xFF shl 8)
        if (data.size < 12 + length) return null
        return data.copyOfRange(12, 12 + length)
    }

    /** Поля protobuf верхнего уровня: номер поля → число (Long) или байты. */
    private fun fields(bytes: ByteArray): List<Pair<Int, Any>> {
        val result = ArrayList<Pair<Int, Any>>()
        var i = 0
        fun readVarint(): Long {
            var value = 0L
            var shift = 0
            while (true) {
                val b = bytes[i++].toInt() and 0xFF
                value = value or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return value
                shift += 7
            }
        }
        while (i < bytes.size) {
            val tag = readVarint().toInt()
            when (tag and 0x07) {
                0 -> result += (tag shr 3) to readVarint()
                2 -> {
                    val length = readVarint().toInt()
                    result += (tag shr 3) to bytes.copyOfRange(i, i + length)
                    i += length
                }
                else -> error("тип поля ${tag and 7} не разбираем")
            }
        }
        return result
    }

    private fun varint(value: Int): IntArray {
        val out = ArrayList<Int>()
        var v = value
        while (v >= 0x80) {
            out += (v and 0x7F) or 0x80
            v = v ushr 7
        }
        out += v
        return out.toIntArray()
    }
}
