package dev.podscompanion.protocol.aap

/**
 * Паспорт наушников: наушники присылают его сами вскоре после подключения (опкод 0x1D).
 *
 * Пакет по публичному реверсу (LibrePods): после заголовка несколько служебных байтов, затем
 * строки, разделённые нулевым байтом: имя, номер модели (A2699), производитель (Apple Inc.),
 * серийный номер, прошивка, ещё версии, идентификатор обновлений, серийные номера левого
 * и правого наушника. Точное число служебных байтов не знаем, поэтому ищем строку производителя
 * и считаем поля от неё. Пустые поля — наушники их не прислали или пакет оказался другим.
 */
data class AapDeviceInfo(
    val name: String? = null,
    /** Номер модели Apple, например A2699 (AirPods Pro 2 USB-C). */
    val modelNumber: String? = null,
    val manufacturer: String? = null,
    val serialNumber: String? = null,
    /** Версия прошивки, например 7E93. */
    val firmware: String? = null,
    val leftSerialNumber: String? = null,
    val rightSerialNumber: String? = null,
) {
    companion object {
        private val MODEL_NUMBER = Regex("A\\d{4}")
        private val SERIAL = Regex("[A-Z0-9]{8,16}")
        private val VERSION = Regex("[0-9A-Za-z.]{2,16}")

        /** null — в пакете нет ни одного поля, которое мы узнаём. */
        fun parse(data: ByteArray): AapDeviceInfo? {
            if (data.size <= 6) return null
            val strings = split(data.copyOfRange(6, data.size))
            val maker = strings.indexOfFirst { it != null && (it.startsWith("Apple") || it.startsWith("Beats")) }
            val model = strings.indexOfFirst { it != null && MODEL_NUMBER.matches(it) }
            val anchor = when {
                maker >= 0 -> maker
                model >= 0 -> model + 1
                else -> return null
            }
            fun at(index: Int, pattern: Regex? = null) =
                strings.getOrNull(index)?.trim()?.takeIf { it.isNotEmpty() && (pattern == null || pattern.matches(it)) }
            val info = AapDeviceInfo(
                name = at(anchor - 2),
                modelNumber = at(anchor - 1, MODEL_NUMBER),
                manufacturer = at(maker),
                serialNumber = at(anchor + 1, SERIAL),
                firmware = at(anchor + 2, VERSION),
                leftSerialNumber = at(anchor + 6, SERIAL),
                rightSerialNumber = at(anchor + 7, SERIAL),
            )
            return info.takeIf { it != AapDeviceInfo() }
        }

        /**
         * Строки между нулевыми байтами, по одной на каждый промежуток, включая пустые: иначе
         * пропущенное наушниками поле сдвинуло бы все следующие. Служебные байты в начале дают
         * «строки» с непечатными символами: на их месте null.
         */
        private fun split(payload: ByteArray): List<String?> {
            val parts = ArrayList<String?>()
            var start = 0
            for (i in 0..payload.size) {
                if (i == payload.size || payload[i] == 0.toByte()) {
                    val text = String(payload, start, i - start, Charsets.UTF_8)
                    parts += text.takeIf { t -> t.none { it.isISOControl() || it == '\uFFFD' } }
                    start = i + 1
                }
            }
            return parts
        }
    }
}
