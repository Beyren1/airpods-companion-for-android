package dev.podscompanion.ui.battery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.podscompanion.bluetooth.scan.BluetoothUnavailableException
import dev.podscompanion.bluetooth.scan.ScanIntensity
import dev.podscompanion.data.PodsRepository
import dev.podscompanion.data.NearbyPods
import dev.podscompanion.data.aap.AapLog
import dev.podscompanion.data.aap.AapRepository
import dev.podscompanion.data.aap.AapSessionState
import dev.podscompanion.protocol.aap.ListeningMode
import dev.podscompanion.data.autopause.AutoPauseLog
import dev.podscompanion.data.settings.AppSettings
import dev.podscompanion.data.settings.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface BatteryUiState {
    data object Searching : BatteryUiState
    data class Found(val nearby: NearbyPods) : BatteryUiState
    data object BluetoothOff : BatteryUiState
    data class Error(val message: String) : BatteryUiState
}

@HiltViewModel
class BatteryViewModel @Inject constructor(
    private val repository: PodsRepository,
    private val settingsRepository: SettingsRepository,
    autoPauseLog: AutoPauseLog,
    private val aapRepository: AapRepository,
    aapLog: AapLog,
) : ViewModel() {

    /** Прямое подключение к наушникам (расширенный режим) и его журнал для отладки. */
    val aapState: StateFlow<AapSessionState> = aapRepository.state
    val aapLog: StateFlow<List<String>> = aapLog.lines

    /** Кнопка «Проверить расширенный режим». */
    fun checkAap() = aapRepository.retryNow()

    fun setListeningMode(mode: ListeningMode) = aapRepository.setListeningMode(mode)

    /** Журнал автопаузы из сервиса, показывается в карточке отладки. */
    val autoPauseLines: StateFlow<List<String>> = autoPauseLog.lines

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun setBackgroundEnabled(value: Boolean) {
        viewModelScope.launch { settingsRepository.setBackgroundEnabled(value) }
    }

    fun setAutoPause(value: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoPause(value) }
    }

    /** Каждое новое значение перезапускает скан с нуля (и заново выбирает главные наушники). */
    private val restarts = MutableStateFlow(0)

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    private var refreshTimeout: Job? = null

    /**
     * Пока экран открыт, сканируем в LOW_LATENCY: реакция ~1 с. Фоновый экономный режим будет в сервисе.
     * WhileSubscribed(5000): при повороте экрана скан не перезапускается, при уходе в фон
     * останавливается через 5 с.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<BatteryUiState> = restarts
        // flatMapLatest: при новом restarts старый скан отменяется (stopScan), стартует новый.
        .flatMapLatest {
            repository.observeNearby(ScanIntensity.LOW_LATENCY)
                .map<NearbyPods, BatteryUiState> { nearby ->
                    if (nearby.primary == null && nearby.others.isEmpty()) BatteryUiState.Searching else BatteryUiState.Found(nearby)
                }
                .onStart { emit(BatteryUiState.Searching) }
                .catch { e ->
                    emit(
                        if (e is BluetoothUnavailableException) BatteryUiState.BluetoothOff
                        else BatteryUiState.Error(e.message ?: e.toString()),
                    )
                }
        }
        .onEach { if (it !is BatteryUiState.Searching) stopRefreshing() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BatteryUiState.Searching)

    /** Свайп вниз: перезапуск скана. Индикатор крутится до первого результата, но не дольше 5 с. */
    fun refresh() {
        _refreshing.value = true
        restarts.value++
        refreshTimeout?.cancel()
        refreshTimeout = viewModelScope.launch {
            delay(5_000)
            _refreshing.value = false
        }
    }

    private fun stopRefreshing() {
        refreshTimeout?.cancel()
        _refreshing.value = false
    }
}
