package dev.podscompanion.data.find

/**
 * Где и когда одни наушники последний раз отключились от телефона. Обычно это место, где их
 * положили в кейс или оставили: место телефона в момент отключения.
 *
 * Наушники узнаём по постоянному адресу Bluetooth Classic, поэтому у каждой пары своя запись.
 */
data class LastPlace(
    val address: String,
    /** Имя, под которым наушники подключены к телефону. */
    val name: String,
    /** Когда отключились, мс (System.currentTimeMillis). */
    val disconnectedAtMs: Long,
    /** Координаты телефона; null — места нет (нет разрешения или геолокация выключена). */
    val location: Fix? = null,
) {
    data class Fix(
        val latitude: Double,
        val longitude: Double,
        /** Точность в метрах (радиус круга); null — система не сообщила. */
        val accuracyM: Float?,
        /** Когда телефон определил это место, мс. Бывает раньше отключения, если взяли последнее известное. */
        val fixedAtMs: Long,
    )

    companion object {
        /**
         * Запись в строку для DataStore: поля через «|», имя — последним, чтобы «|» в имени ничего не ломал.
         * Без координат поля пустые.
         */
        fun encode(place: LastPlace): String {
            val fix = place.location
            return listOf(
                place.address,
                place.disconnectedAtMs.toString(),
                fix?.latitude?.toString().orEmpty(),
                fix?.longitude?.toString().orEmpty(),
                fix?.accuracyM?.toString().orEmpty(),
                fix?.fixedAtMs?.toString().orEmpty(),
                place.name,
            ).joinToString(SEPARATOR)
        }

        fun decode(line: String): LastPlace? {
            val parts = line.split(SEPARATOR, limit = FIELDS)
            if (parts.size != FIELDS) return null
            val at = parts[1].toLongOrNull() ?: return null
            val lat = parts[2].toDoubleOrNull()
            val lng = parts[3].toDoubleOrNull()
            val fix = if (lat != null && lng != null) {
                Fix(lat, lng, parts[4].toFloatOrNull(), parts[5].toLongOrNull() ?: at)
            } else {
                null
            }
            return LastPlace(parts[0], parts[6], at, fix)
        }

        private const val SEPARATOR = "|"
        private const val FIELDS = 7
    }
}
