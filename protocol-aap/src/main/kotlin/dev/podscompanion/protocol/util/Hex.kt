package dev.podscompanion.protocol.util

/** Утилиты для hex-дампов: в логах и тестах удобнее читать "07 19 01 ..." чем массив чисел. */
object Hex {
    fun encode(bytes: ByteArray, separator: String = " "): String =
        bytes.joinToString(separator) { "%02X".format(it.toInt() and 0xFF) }

    /** Принимает "0719 01-0E", "07 19 01 0e" и т. п.: пробелы, двоеточия и дефисы игнорируются. */
    fun decode(text: String): ByteArray {
        val clean = text.filter { it.isLetterOrDigit() }
        require(clean.length % 2 == 0) { "Нечётное число hex-символов: $text" }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}

/** Байт без знака. В Kotlin Byte знаковый (-128..127), как signed char в C++. */
internal fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF
