package dev.podscompanion.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat
import dev.podscompanion.R
import dev.podscompanion.bluetooth.scan.ConnectedAudioDevice
import dev.podscompanion.bluetooth.scan.ConnectedAudioDevices
import dev.podscompanion.data.ConnectedNameMatcher
import dev.podscompanion.data.aap.AapRepository
import dev.podscompanion.data.aap.AapSessionState
import dev.podscompanion.data.autopause.AutoPauseLog
import dev.podscompanion.data.media.AppModeSwitcher
import dev.podscompanion.data.media.MediaRepository
import dev.podscompanion.data.media.SmartResume
import dev.podscompanion.data.settings.AppSettings
import dev.podscompanion.protocol.aap.AapCommand
import dev.podscompanion.protocol.aap.ListeningMode
import dev.podscompanion.protocol.advertising.Capability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Музыкальные функции фонового сервиса:
 * - автозапуск плеера, когда подключились наушники Apple;
 * - режим шумоподавления под приложение, которое сейчас играет;
 * - умное продолжение: после долгого перерыва трек отматывается на несколько секунд назад.
 * Каждая включается своим переключателем в настройках.
 */
internal class MusicAutomation(
    private val context: Context,
    private val media: MediaRepository,
    private val aap: AapRepository,
    private val connectedAudio: ConnectedAudioDevices,
    private val log: AutoPauseLog,
    private val settings: () -> AppSettings,
) {
    private val smartResume = SmartResume()
    private val modeSwitcher = AppModeSwitcher()
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private var launchJob: Job? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    fun run(scope: CoroutineScope, settingsFlow: Flow<AppSettings>) {
        scope.launch { watchConnections(scope) }
        scope.launch {
            settingsFlow.map { it.appModes }.distinctUntilChanged()
                .flatMapLatest { enabled -> if (enabled) playingWithHeadphones() else flowOf(null) }
                .collect { state ->
                    val (pkg, connected) = state ?: Pair<String?, List<AapSessionState.Connected>>(null, emptyList())
                    // Наушники отключили или функцию выключили: при следующем подключении правило сработает снова.
                    // На паузе (pkg == null) не сбрасываем, чтобы не перебить режим, который пользователь выбрал сам.
                    if (connected.isEmpty()) modeSwitcher.reset() else if (pkg != null) applyAppMode(pkg, connected)
                }
        }
    }

    // ---------- Умное продолжение ----------

    /** Автопауза: наушники сняли. */
    fun onAutoPause(nowMs: Long) = smartResume.onPaused(nowMs)

    /** Наушники надели, сейчас пойдёт «play»: после долгого перерыва сначала отматываем назад. */
    fun beforeAutoResume(nowMs: Long) {
        val rewind = smartResume.shouldRewind(nowMs)
        if (!rewind || !settings().smartResume) return
        if (media.rewind(smartResume.rewindMs)) log.add("→ назад на ${smartResume.rewindMs / 1000} с")
    }

    // ---------- Автозапуск плеера ----------

    /** Следим за подключёнными наушниками: появились новые наушники Apple — запускаем плеер. */
    private suspend fun watchConnections(scope: CoroutineScope) {
        var known: Set<String>? = null
        connectedAudio.devices().collect { devices ->
            // null — нет разрешения «Устройства поблизости»: не знаем, что подключено.
            val apple = devices?.filter(::isApple)?.map { it.device.address }?.toSet() ?: return@collect
            val previous = known
            known = apple
            // Первый список после запуска сервиса — то, что уже было подключено: это не новое подключение.
            if (previous == null || (apple - previous).isEmpty()) return@collect
            val current = settings()
            val pkg = current.autoLaunchPackage
            if (!current.autoLaunch || pkg == null) return@collect
            launchJob?.cancel()
            launchJob = scope.launch { autoLaunch(pkg) }
        }
    }

    private suspend fun autoLaunch(pkg: String) {
        // Ждём, пока звук переключится на наушники, иначе первые секунды сыграют из динамика.
        delay(ROUTE_SETTLE_MS)
        if (media.anyPlaying()) {
            log.add("автозапуск: уже что-то играет")
            return
        }
        val name = media.appLabel(pkg)
        if (media.play(pkg) && waitPlaying(pkg, 3_000)) {
            log.add("автозапуск: $name, продолжение")
            return
        }
        if (media.playViaBrowser(pkg) && waitPlaying(pkg, 4_000)) {
            log.add("автозапуск: $name, включён в фоне")
            return
        }
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent == null) {
            log.add("автозапуск: $name не найден")
            return
        }
        // Открыть чужое приложение из фона Android разрешает только с правом «Поверх других приложений».
        if (Settings.canDrawOverlays(context) && runCatching { context.startActivity(intent) }.isSuccess) {
            log.add("автозапуск: $name открыт")
            // Плеер открылся: ждём его сессию и жмём «play».
            repeat(LAUNCH_WAIT_STEPS) {
                delay(POLL_MS)
                if (media.isPlaying(pkg)) return
                if (media.play(pkg)) return
            }
            return
        }
        log.add("автозапуск: $name — уведомление")
        showOpenPlayer(name, intent)
    }

    private suspend fun waitPlaying(pkg: String, timeoutMs: Long): Boolean {
        repeat((timeoutMs / POLL_MS).toInt()) {
            if (media.isPlaying(pkg)) return true
            delay(POLL_MS)
        }
        return media.isPlaying(pkg)
    }

    /** Открыть плеер сами не смогли: уведомление, по нажатию на которое он откроется. */
    private fun showOpenPlayer(name: String, intent: Intent) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.player_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        notifications.createNotificationChannel(channel)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pods)
            .setContentTitle(context.getString(R.string.player_open_title, name))
            .setContentText(context.getString(R.string.player_open_text))
            .setContentIntent(PendingIntent.getActivity(context, 3, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setAutoCancel(true)
            .setTimeoutAfter(NOTIFICATION_TIMEOUT_MS)
            .build()
        runCatching { notifications.notify(NOTIFICATION_ID, notification) }.onFailure { Timber.w(it, "player notification") }
    }

    // ---------- Режим под приложение ----------

    /** Какое приложение играет (null — ничего) и какие наушники подключены напрямую. */
    private fun playingWithHeadphones(): Flow<Pair<String?, List<AapSessionState.Connected>>?> = combine(
        media.nowPlaying.map { it?.takeIf { np -> np.playing }?.packageName }.distinctUntilChanged(),
        aap.state.map { sessions -> sessions.sessions.filterIsInstance<AapSessionState.Connected>() }
            .distinctUntilChanged { a, b -> a.map { it.address } == b.map { it.address } },
    ) { pkg, connected -> pkg to connected }

    private fun applyAppMode(pkg: String, connected: List<AapSessionState.Connected>) {
        val mode = modeSwitcher.onPlaying(pkg, settings().appModeRules) ?: return
        connected.forEach { session ->
            if (!supports(session, mode)) return@forEach
            log.add("режим для ${media.appLabel(pkg)}: $mode · ${session.deviceName}")
            aap.send(session.address, AapCommand.SetListeningMode(mode))
        }
    }

    /** Наушники умеют этот режим: сообщают режим по AAP, а «Адаптивный» есть не у всех. */
    private fun supports(session: AapSessionState.Connected, mode: ListeningMode): Boolean {
        // Свежее состояние: режим мог смениться с момента, как пришёл список подключений.
        val device = (aap.state.value.sessions.firstOrNull { it.address == session.address } as? AapSessionState.Connected)?.device
            ?: session.device
        val current = device.listeningMode ?: return false
        if (current == mode) return false
        if (mode != ListeningMode.ADAPTIVE) return true
        val models = ConnectedNameMatcher.modelsForName(session.deviceName)
        return models.isEmpty() || models.any { Capability.ADAPTIVE_AUDIO in it.capabilities }
    }

    private fun isApple(device: ConnectedAudioDevice): Boolean =
        device.supportsAap || ConnectedNameMatcher.knownModels(listOf(device.name)).isNotEmpty()

    private companion object {
        const val ROUTE_SETTLE_MS = 2_000L
        const val POLL_MS = 500L
        const val LAUNCH_WAIT_STEPS = 20
        const val CHANNEL_ID = "player"
        const val NOTIFICATION_ID = 3
        const val NOTIFICATION_TIMEOUT_MS = 60_000L
    }
}
