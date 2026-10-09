package dev.podscompanion.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.podscompanion.data.settings.SettingsRepository
import dev.podscompanion.data.stats.PairUsage
import dev.podscompanion.data.stats.UsageStatsStore
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Вкладка «Статистика»: по карточке на каждую пару наушников. */
@HiltViewModel
class StatsViewModel @Inject constructor(
    private val store: UsageStatsStore,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    /** null — статистика ещё читается из файла. */
    val pairs: StateFlow<List<PairUsage>?> = store.history
        .map { it?.sorted() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Статистика считается только в фоновом режиме: без него на экране подсказка. */
    val backgroundEnabled: StateFlow<Boolean> = settingsRepository.settings
        .map { it.backgroundEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    init {
        // Сервис мог ещё не запуститься (или выключен): читаем файл сами.
        viewModelScope.launch { store.load() }
    }

    fun clear() {
        viewModelScope.launch { store.clear() }
    }
}
