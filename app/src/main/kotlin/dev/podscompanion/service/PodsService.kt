package dev.podscompanion.service

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.podscompanion.bluetooth.scan.ScanIntensity
import dev.podscompanion.data.PodsRepository
import dev.podscompanion.data.PodsStatus
import dev.podscompanion.data.autopause.AutoPauseLog
import dev.podscompanion.data.autopause.EarDetectionPolicy
import dev.podscompanion.data.autopause.MediaAction
import dev.podscompanion.data.settings.AppSettings
import dev.podscompanion.data.settings.SettingsRepository
import dev.podscompanion.protocol.advertising.Capability
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Фоновый сервис: держит уведомление с зарядом и делает автопаузу.
 *
 * Экономия батареи:
 * - скан с аппаратным фильтром (телефон не просыпается из-за чужих пакетов);
 * - LOW_LATENCY только пока подключено Bluetooth-аудио (нужна быстрая автопауза), иначе LOW_POWER;
 * - если наушников не видно [IDLE_AFTER_MS] и Bluetooth-аудио не подключено, скан останавливается;
 * - будит его включение экрана, подключение Bluetooth-устройства или включение Bluetooth.
 */
@AndroidEntryPoint
class PodsService : LifecycleService() {

    @Inject lateinit var repository: PodsRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var autoPauseLog: AutoPauseLog

    private lateinit var notifications: PodsNotifications
    private lateinit var audioManager: AudioManager
    private val policy = EarDetectionPolicy()

    private var scanJob: Job? = null
    private var scanIntensity: ScanIntensity? = null
    private var lastWorn: Boolean? = null
    private var lastLoggedName: String? = null
    private var lastActivityMs = 0L
    private var settings = AppSettings()

    /** Подключили/отключили Bluetooth-наушники: меняем частоту скана. */
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = restartScanIfIntensityChanged()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = restartScanIfIntensityChanged()
    }

    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Timber.d("wake: %s", intent.action)
            startScan()
        }
    }

    override fun onCreate() {
        super.onCreate()
        notifications = PodsNotifications(this)
        audioManager = getSystemService(AudioManager::class.java)
        notifications.ensureChannel()

        try {
            ServiceCompat.startForeground(
                this,
                PodsNotifications.NOTIFICATION_ID,
                notifications.build(null),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
            )
        } catch (e: Exception) {
            // Android 14+: без разрешения на Bluetooth система не даст запустить такой сервис.
            Timber.e(e, "startForeground failed")
            stopSelf()
            return
        }

        registerWakeReceiver()
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)

        lifecycleScope.launch {
            settingsRepository.settings.collect { new ->
                settings = new
                if (!new.backgroundEnabled) stopSelf()
                if (!new.autoPause) policy.reset()
            }
        }
        lifecycleScope.launch { idleWatchdog() }
        startScan()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(wakeReceiver) }
        runCatching { audioManager.unregisterAudioDeviceCallback(audioDeviceCallback) }
        super.onDestroy()
    }

    private fun registerWakeReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            // Получать события подключения устройств можно только с разрешением BLUETOOTH_CONNECT.
            if (hasConnectPermission()) addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        }
        ContextCompat.registerReceiver(this, wakeReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun desiredIntensity() =
        if (bluetoothAudioConnected()) ScanIntensity.LOW_LATENCY else ScanIntensity.LOW_POWER

    private fun restartScanIfIntensityChanged() {
        if (scanJob?.isActive == true && scanIntensity != desiredIntensity()) {
            scanJob?.cancel()
            scanJob = null
        }
        startScan()
    }

    private fun startScan() {
        if (scanJob?.isActive == true) return
        lastActivityMs = SystemClock.elapsedRealtime()
        val intensity = desiredIntensity()
        scanIntensity = intensity
        Timber.d("scan start %s", intensity)
        scanJob = lifecycleScope.launch {
            repository.observeNearby(intensity)
                .map { it.primary }
                .catch { e ->
                    Timber.w(e, "scan stopped")
                    emit(null)
                }
                .distinctUntilChanged()
                .collect { status ->
                    if (status != null) lastActivityMs = SystemClock.elapsedRealtime()
                    onStatus(status)
                }
        }
    }

    private fun stopScan() {
        Timber.d("scan idle")
        scanJob?.cancel()
        scanJob = null
        onStatus(null)
    }

    private suspend fun idleWatchdog() {
        while (true) {
            delay(WATCHDOG_PERIOD_MS)
            val idleFor = SystemClock.elapsedRealtime() - lastActivityMs
            if (scanJob?.isActive == true && idleFor > IDLE_AFTER_MS && !bluetoothAudioConnected()) {
                stopScan()
            }
        }
    }

    private fun onStatus(status: PodsStatus?) {
        notifications.update(status)
        if (!settings.autoPause) return

        val worn = status?.let(::isWorn)
        val name = status?.model?.displayName
        if (worn != lastWorn || name != lastLoggedName) {
            lastWorn = worn
            lastLoggedName = name
            val state = when (worn) {
                null -> "наушников не видно"
                true -> "надеты"
                false -> "сняты"
            }
            // Модель в журнале показывает, если решение принималось по чужим наушникам рядом.
            autoPauseLog.add(if (name != null) "$state · $name" else state)
        }
        // Музыку трогаем, только если звук идёт в Bluetooth: иначе это чужие наушники рядом
        // или пользователь слушает через динамик.
        val playing = audioManager.isMusicActive && bluetoothAudioConnected()
        when (policy.onUpdate(worn, playing, SystemClock.elapsedRealtime())) {
            MediaAction.PAUSE -> {
                autoPauseLog.add("→ пауза")
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
            }
            MediaAction.RESUME -> {
                autoPauseLog.add("→ продолжение")
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            }
            null -> Unit
        }
    }

    private fun isWorn(status: PodsStatus): Boolean {
        val model = status.model
        return if (model != null && Capability.STEREO_BUDS !in model.capabilities) {
            status.primary.inEar
        } else {
            status.left.inEar && status.right.inEar
        }
    }

    private fun sendMediaKey(code: Int) {
        Timber.d("auto-pause: key %d", code)
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun bluetoothAudioConnected(): Boolean =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val IDLE_AFTER_MS = 2 * 60_000L
        private const val WATCHDOG_PERIOD_MS = 30_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, PodsService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PodsService::class.java))
        }

        /** Для BootReceiver: запустить, если пользователь включил фоновый режим. */
        suspend fun startIfEnabled(context: Context, settings: SettingsRepository) {
            if (settings.settings.first().backgroundEnabled) start(context)
        }
    }
}
