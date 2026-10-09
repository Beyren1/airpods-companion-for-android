package dev.podscompanion.service

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ComponentName
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
import android.provider.Settings
import android.service.quicksettings.TileService
import android.view.KeyEvent
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.podscompanion.bluetooth.scan.ScanIntensity
import dev.podscompanion.data.PodsRepository
import dev.podscompanion.data.PodsStatus
import dev.podscompanion.bluetooth.scan.ConnectedAudioDevices
import dev.podscompanion.data.aap.AapRepository
import dev.podscompanion.data.autopause.AutoPauseLog
import dev.podscompanion.data.media.MediaRepository
import dev.podscompanion.data.battery.LowBatteryWatcher
import dev.podscompanion.data.gestures.HeadGestureController
import dev.podscompanion.data.popup.CaseOpenDetector
import dev.podscompanion.data.popup.LiveStatus
import dev.podscompanion.popup.CasePopupActivity
import dev.podscompanion.data.autopause.EarDetectionPolicy
import dev.podscompanion.data.autopause.MediaAction
import dev.podscompanion.data.autopause.WornState
import dev.podscompanion.data.aap.AapSessionState
import dev.podscompanion.data.settings.AppSettings
import dev.podscompanion.data.settings.SettingsRepository
import dev.podscompanion.data.snapshot.StatusSnapshot
import dev.podscompanion.data.snapshot.StatusSnapshotStore
import dev.podscompanion.data.stats.UsageStatsStore
import dev.podscompanion.tile.PodsTileService
import dev.podscompanion.widget.PodsWidget
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
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
    @Inject lateinit var headGestures: HeadGestureController
    @Inject lateinit var snapshotStore: StatusSnapshotStore
    @Inject lateinit var liveStatus: LiveStatus
    @Inject lateinit var mediaRepository: MediaRepository
    @Inject lateinit var aapRepository: AapRepository
    @Inject lateinit var connectedAudio: ConnectedAudioDevices
    @Inject lateinit var usageStore: UsageStatsStore

    private val caseOpenDetector = CaseOpenDetector()
    private val lowBattery = LowBatteryWatcher()

    /** Последнее состояние для виджета и плитки: пишем в файл, только когда изменились цифры. */
    private val latestStatus = MutableStateFlow<PodsStatus?>(null)

    private lateinit var notifications: PodsNotifications
    private lateinit var lowBatteryNotifications: LowBatteryNotifications
    private lateinit var audioManager: AudioManager
    private lateinit var music: MusicAutomation
    private lateinit var usage: UsageTracking

    /** Все наушники из последнего скана: статистике нужна модель каждой подключённой пары. */
    @Volatile private var latestNearby: List<PodsStatus> = emptyList()
    private val policy = EarDetectionPolicy()

    private var scanJob: Job? = null
    private var scanIntensity: ScanIntensity? = null
    private var lastWorn: Boolean? = null
    private var lastLoggedName: String? = null
    private var lastActivityMs = 0L
    private var settings = AppSettings()

    private var disconnectPauseJob: Job? = null
    private var autoPauseRetryJob: Job? = null

    /** Подключили/отключили Bluetooth-наушники: меняем частоту скана, при отключении страхуем паузу. */
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = restartScanIfIntensityChanged()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            restartScanIfIntensityChanged()
            if (removedDevices.any { it.isSink && it.type in BLUETOOTH_MEDIA_TYPES }) onBluetoothAudioRemoved()
        }
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
        lowBatteryNotifications = LowBatteryNotifications(this).apply { ensureChannel() }

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
        headGestures.run(lifecycleScope)
        lifecycleScope.launch { publishSnapshots() }
        music = MusicAutomation(this, mediaRepository, aapRepository, connectedAudio, autoPauseLog) { settings }
        music.run(lifecycleScope, settingsRepository.settings)
        lifecycleScope.launch { aapRepository.state.collect { evaluateAutoPause() } }
        usage = UsageTracking(audioManager, connectedAudio, aapRepository, usageStore) { latestNearby }
        usage.run(lifecycleScope)
        startScan()
    }

    override fun onDestroy() {
        // Сервис остановлен: виджет и плитка покажут цифры как последние известные.
        val context = applicationContext
        val usageToFlush = if (::usage.isInitialized) usage else null
        CoroutineScope(Dispatchers.IO).launch {
            usageToFlush?.flush()
            snapshotStore.markAway()
            refreshSurfaces(context)
        }
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
                .onEach { latestNearby = listOfNotNull(it.primary) + it.others }
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
        latestNearby = emptyList()
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

    private suspend fun publishSnapshots() {
        latestStatus
            .map { status -> status?.let { StatusSnapshot.from(it, nowMs = 0) } }
            .distinctUntilChanged()
            .collect { snapshot ->
                if (snapshot == null) {
                    snapshotStore.markAway()
                } else {
                    snapshotStore.save(snapshot.copy(updatedAtMs = System.currentTimeMillis()))
                }
                refreshSurfaces(this)
            }
    }

    private fun onStatus(status: PodsStatus?) {
        notifications.update(status)
        latestStatus.value = status
        liveStatus.update(status)
        maybeShowCasePopup(status)
        checkLowBattery(status)
        evaluateAutoPause()
    }

    /**
     * Автопауза. Зовётся на каждое новое состояние из рекламы и на каждое событие AAP: «вынули»
     * по AAP приходит сразу, даже если рекламы наушников телефон сейчас не слышит.
     */
    private fun evaluateAutoPause() {
        if (!settings.autoPause) return
        val status = latestStatus.value
        // Обычно наушники видны в рекламе, и на их состояние уже наложено AAP с правильными сторонами.
        // Только если рекламы сейчас не слышно, решаем по AAP напрямую.
        val aapSession = if (status == null) aapSessionFor(null) else null
        val worn = status?.let(WornState::fromAdvertising) ?: aapSession?.let { WornState.fromAap(it.device, it.deviceName) }
        val name = status?.model?.displayName ?: aapSession?.deviceName
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
        // или пользователь слушает через динамик. Играет — по системе или по плееру: в первые
        // доли секунды после «play» система ещё может говорить, что звука нет. Плееры спрашиваем,
        // только когда наушники сняты: это запрос к системе, а состояния приходят много раз в секунду.
        val playing = bluetoothAudioConnected() &&
            (audioManager.isMusicActive || (worn == false && mediaRepository.anyPlaying()))
        val now = SystemClock.elapsedRealtime()
        when (policy.onUpdate(worn, playing, now)) {
            MediaAction.PAUSE -> {
                autoPauseLog.add("→ пауза")
                music.onAutoPause(now)
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
            }
            MediaAction.RESUME -> {
                autoPauseLog.add("→ продолжение")
                music.beforeAutoResume(now)
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            }
            null -> Unit
        }
        scheduleAutoPauseRetry(now)
    }

    /** Смену состояния отложили из-за задержки после прошлого действия: проверим ещё раз, когда она кончится. */
    private fun scheduleAutoPauseRetry(now: Long) {
        val at = policy.retryAtMs()
        autoPauseRetryJob?.cancel()
        if (at == null) return
        autoPauseRetryJob = lifecycleScope.launch {
            delay((at - now).coerceAtLeast(0) + 50)
            evaluateAutoPause()
        }
    }

    /** Прямое подключение к главным наушникам, по которому знаем ухо. */
    private fun aapSessionFor(status: PodsStatus?): AapSessionState.Connected? {
        val sessions = aapRepository.state.value
        if (status != null && status.connected) {
            (sessions.forModel(status.model) as? AapSessionState.Connected)?.takeIf { it.device.earKnown }?.let { return it }
        }
        return sessions.sessions.filterIsInstance<AapSessionState.Connected>().filter { it.device.earKnown }.singleOrNull()
    }

    /**
     * Наушники отключились (положили в кейс, связь пропала), а музыка продолжила играть в динамик.
     * Плееры должны сами останавливаться по системному «звук сейчас пойдёт в динамик», но не все это
     * делают, а наша пауза по уху могла не успеть: AirPods рвут связь сразу, как закрыли кейс.
     * Поэтому несколько секунд после отключения следим: заиграло из динамика — ставим на паузу.
     */
    private fun onBluetoothAudioRemoved() {
        if (!settings.autoPause) return
        disconnectPauseJob?.cancel()
        disconnectPauseJob = lifecycleScope.launch {
            repeat(DISCONNECT_CHECKS) {
                delay(DISCONNECT_CHECK_MS)
                // Наушники вернулись (переподключение) — звук снова идёт в них, ничего не трогаем.
                if (bluetoothAudioConnected()) return@launch
                if (audioManager.isMusicActive) {
                    autoPauseLog.add("отключились, звук в динамике → пауза")
                    sendMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
                    return@launch
                }
            }
        }
    }

    private fun sendMediaKey(code: Int) {
        Timber.d("auto-pause: key %d", code)
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun checkLowBattery(status: PodsStatus?) {
        if (!settings.lowBatteryAlerts) return
        val thresholds = LowBatteryWatcher.thresholdsFor(settings.lowBatteryThreshold)
        lowBattery.onStatus(status, thresholds, SystemClock.elapsedRealtime())?.let(lowBatteryNotifications::handle)
    }

    /** Первый раз открыли кейс этих наушников рядом с телефоном: показываем карточку с зарядом. */
    private fun maybeShowCasePopup(status: PodsStatus?) {
        val show = caseOpenDetector.onStatus(status, settings.casePopupShown)
        if (!show || status == null || !settings.casePopup || !Settings.canDrawOverlays(this)) return
        // Сразу помечаем в памяти, чтобы не открыть окно дважды, пока DataStore пишет файл.
        settings = settings.copy(casePopupShown = settings.casePopupShown + status.modelId)
        lifecycleScope.launch { settingsRepository.markCasePopupShown(status.modelId) }
        runCatching {
            startActivity(
                Intent(this, CasePopupActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
        }.onFailure { Timber.w(it, "case popup") }
    }

    private fun bluetoothAudioConnected(): Boolean =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type in BLUETOOTH_MEDIA_TYPES
        }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    companion object {
        /** Перерисовать виджет и попросить систему обновить плитку. */
        private suspend fun refreshSurfaces(context: Context) {
            runCatching { PodsWidget().updateAll(context) }
            runCatching { TileService.requestListeningState(context, ComponentName(context, PodsTileService::class.java)) }
        }

        private const val IDLE_AFTER_MS = 2 * 60_000L
        private const val WATCHDOG_PERIOD_MS = 30_000L

        /** Музыка после отключения наушников: проверяем каждые полсекунды в течение 5 с. */
        private const val DISCONNECT_CHECK_MS = 500L
        private const val DISCONNECT_CHECKS = 10

        /** Куда Bluetooth-наушники выводят музыку: A2DP, а на Android 12+ ещё LE Audio. */
        private val BLUETOOTH_MEDIA_TYPES = buildSet {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(AudioDeviceInfo.TYPE_BLE_HEADSET)
        }

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
