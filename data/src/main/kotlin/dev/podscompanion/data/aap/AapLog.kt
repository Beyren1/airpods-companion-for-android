package dev.podscompanion.data.aap

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Журнал AAP для отладки: что отправили (→), что получили (←) и смены состояния соединения. */
@Singleton
class AapLog @Inject constructor() {
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    // DateTimeFormatter, в отличие от SimpleDateFormat, можно звать из нескольких потоков сразу:
    // журнал пишут соединения со всеми наушниками параллельно.
    private val format = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    fun add(message: String) {
        val line = "${format.format(LocalTime.now())}  $message"
        _lines.update { (it + line).takeLast(MAX_LINES) }
    }

    private companion object {
        const val MAX_LINES = 40
    }
}
