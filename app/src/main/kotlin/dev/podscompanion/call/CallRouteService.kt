package dev.podscompanion.call

import android.os.Build
import android.os.SystemClock
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.InCallService
import androidx.annotation.RequiresApi
import dagger.hilt.android.AndroidEntryPoint
import dev.podscompanion.data.PodsStatus
import dev.podscompanion.data.autopause.AutoPauseLog
import dev.podscompanion.data.calls.CallRoute
import dev.podscompanion.data.calls.CallRoutePolicy
import dev.podscompanion.data.isOffHead
import dev.podscompanion.data.popup.LiveStatus
import dev.podscompanion.data.settings.AppSettings
import dev.podscompanion.data.settings.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Звонок при снятых наушниках — в телефон.
 *
 * Обычному приложению Android не даёт менять, куда идёт звук звонка: это может только служба
 * звонков (InCallService), которую система подключает на время разговора. Подключает она её
 * приложениям с разрешением «Управление текущими звонками»; его выдаёт связь с наушниками через
 * Companion Device Manager (профиль «часы»), см. CallRouteSetup в настройках.
 * Состояние «надеты/сняты» берём у фонового сервиса через [LiveStatus], поэтому фоновый режим нужен.
 */
@AndroidEntryPoint
class CallRouteService : InCallService() {

    @Inject lateinit var liveStatus: LiveStatus
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var log: AutoPauseLog

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val policy = CallRoutePolicy()
    private var settings = AppSettings()
    private var lastOffHead: Boolean? = null

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            policy.onCallStateChanged(SystemClock.elapsedRealtime())
            evaluate()
        }
    }

    override fun onCreate() {
        super.onCreate()
        log.add("звонок: служба звонков подключена")
        scope.launch { settingsRepository.settings.collect { settings = it; evaluate() } }
        scope.launch { liveStatus.status.collect { evaluate() } }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        call.registerCallback(callCallback)
        policy.onCallStateChanged(SystemClock.elapsedRealtime())
        evaluate()
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(callCallback)
        if (calls.isEmpty()) {
            policy.reset()
            lastOffHead = null
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onCallAudioStateChanged(audioState: CallAudioState?) = evaluate()

    // С Android 14 система сообщает о смене маршрута сюда; старый метод выше тоже приходит, но на всякий случай.
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onCallEndpointChanged(callEndpoint: CallEndpoint) = evaluate()

    @Suppress("DEPRECATION") // CallAudioState единственный даёт адрес Bluetooth-устройства звонка
    private fun evaluate() {
        if (!settings.callsToPhone || calls.isEmpty()) return
        val audio = callAudioState ?: return
        val status = liveStatus.status.value?.takeIf { it.connected && it.earKnown }
        val offHead = status?.isOffHead
        if (offHead != null && offHead != lastOffHead) {
            log.add(if (offHead) "звонок: наушники сняты" else "звонок: наушники надеты")
        }
        lastOffHead = offHead
        val onHeadphones = audio.route == CallAudioState.ROUTE_BLUETOOTH && isOurDevice(audio, status)
        when (policy.onUpdate(offHead, onHeadphones, SystemClock.elapsedRealtime())) {
            CallRoute.PHONE -> toPhone(audio)
            CallRoute.HEADPHONES -> toHeadphones(audio, status)
            null -> Unit
        }
    }

    /** Звук идёт в Bluetooth, но в те ли наушники: в машине это может быть магнитола. */
    @Suppress("DEPRECATION")
    private fun isOurDevice(audio: CallAudioState, status: PodsStatus?): Boolean {
        val active = audio.activeBluetoothDevice ?: return true
        val owner = status?.owner ?: return true
        return active.address.equals(owner, ignoreCase = true)
    }

    @Suppress("DEPRECATION")
    private fun toPhone(audio: CallAudioState) {
        val route = if (audio.supportedRouteMask and CallAudioState.ROUTE_EARPIECE != 0) {
            CallAudioState.ROUTE_EARPIECE
        } else {
            CallAudioState.ROUTE_SPEAKER
        }
        log.add("звонок: → в телефон")
        Timber.d("call route -> %d", route)
        setAudioRoute(route)
    }

    @Suppress("DEPRECATION")
    private fun toHeadphones(audio: CallAudioState, status: PodsStatus?) {
        if (audio.supportedRouteMask and CallAudioState.ROUTE_BLUETOOTH == 0) return
        log.add("звонок: → в наушники")
        val device = status?.owner?.let { owner ->
            audio.supportedBluetoothDevices.firstOrNull { it.address.equals(owner, ignoreCase = true) }
        }
        if (device != null) requestBluetoothAudio(device) else setAudioRoute(CallAudioState.ROUTE_BLUETOOTH)
    }
}
