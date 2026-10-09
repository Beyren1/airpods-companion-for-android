package dev.podscompanion.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.podscompanion.protocol.advertising.BatteryLevel
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

private val Context.caseStore by preferencesDataStore(name = "case_battery")

/**
 * Заряд кейса приходит только когда крышка открыта и в кейсе есть наушник.
 * Запоминаем последнее значение для каждой модели и показываем его, пока свежего нет.
 */
@Singleton
class CaseBatteryCache @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val memory = mutableMapOf<Int, Int>()

    suspend fun apply(status: PodsStatus): PodsStatus {
        val key = keyFor(status.modelId)
        val fresh = status.caseBattery
        if (fresh != null) {
            remember(status.modelId, fresh.percent)
            return status
        }
        val remembered = memory[status.modelId]
            ?: context.caseStore.data.first()[key]?.also { memory[status.modelId] = it }
            ?: return status
        return status.copy(caseBattery = BatteryLevel(remembered), caseCharging = false, caseBatteryRemembered = true)
    }

    /**
     * Запомнить заряд кейса. Кроме рекламы при открытой крышке, его присылает прямое подключение,
     * пока наушник лежит в кейсе: так заряд запоминается, даже если крышку у телефона не открывали.
     */
    suspend fun remember(modelId: Int, percent: Int) {
        if (memory[modelId] == percent) return
        memory[modelId] = percent
        context.caseStore.edit { it[keyFor(modelId)] = percent }
    }

    private fun keyFor(modelId: Int) = intPreferencesKey("model_%04x".format(modelId))
}
