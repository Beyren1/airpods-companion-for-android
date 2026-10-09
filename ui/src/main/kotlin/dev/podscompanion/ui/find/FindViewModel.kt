package dev.podscompanion.ui.find

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.podscompanion.bluetooth.scan.ConnectedAudioDevices
import dev.podscompanion.data.find.FindSound
import dev.podscompanion.data.find.LastPlace
import dev.podscompanion.data.find.LastPlaceStore
import dev.podscompanion.data.find.NearbySignal
import dev.podscompanion.data.find.SignalMeter
import dev.podscompanion.data.find.SignalReading
import dev.podscompanion.data.find.SoundSide
import dev.podscompanion.protocol.advertising.PodsModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Подключённые сейчас AirPods: на них можно включить звук. */
data class ConnectedPods(val address: String, val name: String)

/** Наушники в эфире для «горячо/холодно». */
data class FindTarget(
    val key: String,
    val model: PodsModel?,
    /** Свои наушники (узнали по ключу): имя из настроек Bluetooth. */
    val ownName: String?,
    /** null — давно не слышно. */
    val reading: SignalReading?,
)

/** Вкладка «Найти»: звук на наушниках, «горячо/холодно» и последнее место. */
@HiltViewModel
class FindViewModel @Inject constructor(
    private val sound: FindSound,
    private val signal: NearbySignal,
    store: LastPlaceStore,
    connectedAudio: ConnectedAudioDevices,
) : ViewModel() {

    val places: StateFlow<List<LastPlace>?> = store.places
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val connected: StateFlow<List<ConnectedPods>> = connectedAudio.devices()
        .map { list -> list.orEmpty().filter { it.supportsAap }.map { ConnectedPods(it.device.address, it.name) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val playing: StateFlow<SoundSide?> = sound.playing

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val _targets = MutableStateFlow<List<FindTarget>>(emptyList())
    val targets: StateFlow<List<FindTarget>> = _targets.asStateFlow()

    /** Какие наушники ищем; null — выбираем сами: свои, а если своих не слышно — с самым сильным сигналом. */
    private val _selected = MutableStateFlow<String?>(null)
    val selected: StateFlow<String?> = _selected.asStateFlow()

    private var searchJob: Job? = null

    /** false — звук включить не во что: Bluetooth-наушники не подключены. */
    fun play(side: SoundSide): Boolean {
        if (sound.output() == null) return false
        sound.play(side)
        return true
    }

    fun stopSound() = sound.stop()

    fun select(key: String) {
        _selected.value = key
    }

    fun startSearch() {
        if (searchJob != null) return
        _searching.value = true
        val meters = HashMap<String, SignalMeter>()
        val info = HashMap<String, Pair<PodsModel?, String?>>()
        val lastSeen = HashMap<String, Long>()
        fun publish() {
            val now = SystemClock.elapsedRealtime()
            _targets.value = meters.map { (key, meter) ->
                val (model, name) = info[key] ?: (null to null)
                FindTarget(key, model, name, meter.reading(now))
            }.sortedWith(
                compareByDescending<FindTarget> { it.ownName != null }
                    .thenByDescending { it.reading?.rssi ?: Int.MIN_VALUE },
            )
        }
        searchJob = viewModelScope.launch {
            launch {
                while (true) {
                    delay(TICK_MS)
                    // Наушники, которых давно не слышно, убираем из списка (кроме выбранных).
                    val now = SystemClock.elapsedRealtime()
                    val gone = lastSeen.filter { (key, at) -> key != _selected.value && now - at > FORGET_AFTER_MS }.keys
                    gone.forEach { meters.remove(it); info.remove(it); lastSeen.remove(it) }
                    publish()
                }
            }
            signal.observe()
                // Bluetooth выключили или скан не запустился: поиск просто останавливается.
                .catch { stopSearch() }
                .collect { sample ->
                    meters.getOrPut(sample.key) { SignalMeter() }.add(sample.rssi, sample.atMs)
                    info[sample.key] = sample.model to sample.ownerName
                    lastSeen[sample.key] = sample.atMs
                }
        }
    }

    fun stopSearch() {
        searchJob?.cancel()
        searchJob = null
        _searching.value = false
        _targets.value = emptyList()
    }

    override fun onCleared() {
        stopSearch()
        sound.stop()
    }

    private companion object {
        /** Как часто обновлять стрелку «теплее/холоднее». */
        const val TICK_MS = 500L
        const val FORGET_AFTER_MS = 30_000L
    }
}
