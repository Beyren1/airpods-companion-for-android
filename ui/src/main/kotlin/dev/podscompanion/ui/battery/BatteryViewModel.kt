package dev.podscompanion.ui.battery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.podscompanion.bluetooth.scan.BluetoothUnavailableException
import dev.podscompanion.bluetooth.scan.ScanIntensity
import dev.podscompanion.data.PodsRepository
import dev.podscompanion.data.PodsStatus
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

sealed interface BatteryUiState {
    data object Searching : BatteryUiState
    data class Found(val status: PodsStatus) : BatteryUiState
    data object BluetoothOff : BatteryUiState
    data class Error(val message: String) : BatteryUiState
}

@HiltViewModel
class BatteryViewModel @Inject constructor(
    repository: PodsRepository,
) : ViewModel() {

    /**
     * Пока экран открыт, сканируем в LOW_LATENCY: реакция ~1 с. Фоновый экономный режим будет в сервисе.
     * WhileSubscribed(5000): при повороте экрана скан не перезапускается, при уходе в фон
     * останавливается через 5 с.
     */
    val state: StateFlow<BatteryUiState> = repository.observeNearest(ScanIntensity.LOW_LATENCY)
        .map<PodsStatus?, BatteryUiState> { status ->
            if (status == null) BatteryUiState.Searching else BatteryUiState.Found(status)
        }
        .catch { e ->
            emit(
                if (e is BluetoothUnavailableException) BatteryUiState.BluetoothOff
                else BatteryUiState.Error(e.message ?: e.toString()),
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BatteryUiState.Searching)
}
