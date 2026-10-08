package dev.podscompanion.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.podscompanion.protocol.aap.HeadCalibration
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

data class AppSettings(
    /** Фоновый сервис: уведомление с зарядом и автопауза, пока приложение закрыто. */
    val backgroundEnabled: Boolean = false,
    /** Пауза музыки, когда наушник вынут из уха (и продолжение, когда вставлен обратно). */
    val autoPause: Boolean = true,
    /** Отладка: сырые пакеты и журналы на экране настроек приложения. */
    val debugEnabled: Boolean = false,
    /** Отвечать на звонок кивком и отклонять покачиванием головы. */
    val headGestures: Boolean = false,
    /** Калибровка жестов; null — ещё не калибровали. */
    val headCalibration: HeadCalibration? = null,
)

// DataStore — асинхронная замена SharedPreferences: файл с ключами, изменения приходят как Flow.
private val Context.settingsStore by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val settings: Flow<AppSettings> = context.settingsStore.data
        .map { prefs ->
            AppSettings(
                backgroundEnabled = prefs[BACKGROUND] ?: false,
                autoPause = prefs[AUTO_PAUSE] ?: true,
                debugEnabled = prefs[DEBUG] ?: false,
                headGestures = prefs[HEAD_GESTURES] ?: false,
                headCalibration = prefs[HEAD_CALIBRATION]?.let(::parseCalibration),
            )
        }
        .distinctUntilChanged()

    suspend fun setBackgroundEnabled(value: Boolean) {
        context.settingsStore.edit { it[BACKGROUND] = value }
    }

    suspend fun setAutoPause(value: Boolean) {
        context.settingsStore.edit { it[AUTO_PAUSE] = value }
    }

    suspend fun setDebugEnabled(value: Boolean) {
        context.settingsStore.edit { it[DEBUG] = value }
    }

    suspend fun setHeadGestures(value: Boolean) {
        context.settingsStore.edit { it[HEAD_GESTURES] = value }
    }

    suspend fun setHeadCalibration(value: HeadCalibration) {
        context.settingsStore.edit {
            it[HEAD_CALIBRATION] = with(value) { "$nodAxis,$shakeAxis,$nodThreshold,$shakeThreshold" }
        }
    }

    // Калибровка хранится одной строкой «ось кивка, ось поворота, порог кивка, порог поворота».
    private fun parseCalibration(text: String): HeadCalibration? {
        val parts = text.split(',').mapNotNull { it.trim().toIntOrNull() }
        if (parts.size != 4) return null
        return HeadCalibration(parts[0], parts[1], parts[2], parts[3])
    }

    private companion object {
        val BACKGROUND = booleanPreferencesKey("background_enabled")
        val AUTO_PAUSE = booleanPreferencesKey("auto_pause")
        val DEBUG = booleanPreferencesKey("debug_enabled")
        val HEAD_GESTURES = booleanPreferencesKey("head_gestures")
        val HEAD_CALIBRATION = stringPreferencesKey("head_calibration")
    }
}
