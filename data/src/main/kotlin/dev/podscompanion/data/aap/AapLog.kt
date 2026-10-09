package dev.podscompanion.data.aap

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Журнал AAP: что отправили (→), что получили (←) и смены состояния соединения.
 * Кольцевой буфер в памяти: запись — одна строка без копирования всего списка, как было раньше
 * на каждом пакете. Читается только по запросу («Отправить журнал соединения» в «Об устройствах»).
 */
@Singleton
class AapLog @Inject constructor() {
    private val buffer = ArrayDeque<String>(MAX_LINES)

    // DateTimeFormatter, в отличие от SimpleDateFormat, можно звать из нескольких потоков сразу:
    // журнал пишут соединения со всеми наушниками параллельно.
    private val format = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    fun add(message: String) {
        val line = "${format.format(LocalTime.now())}  $message"
        synchronized(buffer) {
            if (buffer.size == MAX_LINES) buffer.removeFirst()
            buffer.addLast(line)
        }
    }

    /** Весь журнал одним текстом, старые строки сверху. */
    fun text(): String = synchronized(buffer) { buffer.joinToString("\n") }

    private companion object {
        const val MAX_LINES = 400
    }
}
