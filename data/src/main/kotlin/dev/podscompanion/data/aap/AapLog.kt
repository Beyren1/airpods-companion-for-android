package dev.podscompanion.data.aap

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

    private val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    fun add(message: String) {
        val line = "${format.format(Date())}  $message"
        _lines.update { (it + line).takeLast(MAX_LINES) }
    }

    private companion object {
        const val MAX_LINES = 40
    }
}
