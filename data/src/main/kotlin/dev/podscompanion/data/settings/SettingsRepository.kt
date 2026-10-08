package dev.podscompanion.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
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

    private companion object {
        val BACKGROUND = booleanPreferencesKey("background_enabled")
        val AUTO_PAUSE = booleanPreferencesKey("auto_pause")
        val DEBUG = booleanPreferencesKey("debug_enabled")
    }
}
