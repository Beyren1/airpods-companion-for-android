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
import dev.podscompanion.data.aap.AapSessions
import dev.podscompanion.protocol.aap.AapCommand
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.withTimeoutOrNull
import android.os.SystemClock
import dev.podscompanion.protocol.aap.AxisRecorder
import dev.podscompanion.protocol.aap.HeadCalibrator

private const val PREPARE_MS = 1_500L
private const val RECORD_MS = 4_000L

/** Калибровка жестов головой: кивнуть, затем покачать головой. */
sealed interface CalibrationState {
    enum class Step { NOD, SHAKE }

    data object Idle : CalibrationState
    data class Recording(val step: Step, val progress: Float) : CalibrationState
    data object Done : CalibrationState
    /** [details] — сколько пакетов пришло и размах углов: по ним видно, что пошло не так. */
    data class Failed(val noData: Boolean, val details: String = "") : CalibrationState
}

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
    private val aapLogger: AapLog,
) : ViewModel() {

    /** Прямое подключение к наушникам (расширенный режим) и его журнал для отладки. */
    val aapSessions: StateFlow<AapSessions> = aapRepository.state
    val aapLog: StateFlow<List<String>> = aapLogger.lines

    /** Кнопка «Проверить расширенный режим». */
    fun checkAap() = aapRepository.retryNow()

    /** Команда наушникам: режим, настройка или свои байты из отладки. */
    fun send(address: String, command: AapCommand) = aapRepository.send(address, command)

    /** Переименовать наушники [address]. */
    fun rename(address: String, name: String) = aapRepository.send(address, AapCommand.Rename(name))

    fun setHeadGestures(value: Boolean) {
        viewModelScope.launch { settingsRepository.setHeadGestures(value) }
    }

    private val _calibration = MutableStateFlow<CalibrationState>(CalibrationState.Idle)
    val calibration: StateFlow<CalibrationState> = _calibration.asStateFlow()
    private var calibrationJob: Job? = null

    /**
     * Калибровка: включаем датчики головы, записываем кивки, затем покачивания, и по ним
     * определяем, какой угол за что отвечает. Удалась — сохраняем и включаем жесты.
     */
    fun startCalibration(address: String) {
        calibrationJob?.cancel()
        calibrationJob = viewModelScope.launch {
            aapRepository.send(address, AapCommand.StartHeadTracking)
            try {
                val nod = record(address, CalibrationState.Step.NOD)
                val shake = record(address, CalibrationState.Step.SHAKE)
                val result = HeadCalibrator.calibrate(nod, shake)
                val details = "кивок: ${nod.size} пак., размах ${nod.ranges().joinToString("/")}; " +
                    "покачивание: ${shake.size} пак., размах ${shake.ranges().joinToString("/")}"
                aapLogger.add("калибровка жестов: $details")
                if (result == null) {
                    _calibration.value = CalibrationState.Failed(noData = nod.size == 0 && shake.size == 0, details = details)
                } else {
                    settingsRepository.setHeadCalibration(result)
                    settingsRepository.setHeadGestures(true)
                    _calibration.value = CalibrationState.Done
                }
            } finally {
                aapRepository.send(address, AapCommand.StopHeadTracking)
            }
        }
    }

    fun dismissCalibration() {
        calibrationJob?.cancel()
        _calibration.value = CalibrationState.Idle
    }

    private suspend fun record(address: String, step: CalibrationState.Step): AxisRecorder = coroutineScope {
        val recorder = AxisRecorder()
        _calibration.value = CalibrationState.Recording(step, 0f)
        delay(PREPARE_MS) // успеть прочитать подсказку
        val start = SystemClock.elapsedRealtime()
        val ticker = launch {
            while (true) {
                val progress = ((SystemClock.elapsedRealtime() - start).toFloat() / RECORD_MS).coerceAtMost(1f)
                _calibration.value = CalibrationState.Recording(step, progress)
                delay(100)
            }
        }
        withTimeoutOrNull(RECORD_MS) {
            aapRepository.headMotion.filter { it.address == address }.collect { recorder.add(it.motion.axes) }
        }
        ticker.cancel()
        recorder
    }

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

    fun setCasePopup(value: Boolean) {
        viewModelScope.launch { settingsRepository.setCasePopup(value) }
    }

    fun setDebugEnabled(value: Boolean) {
        viewModelScope.launch { settingsRepository.setDebugEnabled(value) }
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
