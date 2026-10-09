package dev.podscompanion.data.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.media.MediaBrowserService
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/** Что сейчас играет: для карточки на главном экране и в окне кейса. */
data class NowPlaying(
    val packageName: String,
    val appName: String,
    val title: String?,
    val artist: String?,
    val art: Bitmap?,
    val playing: Boolean,
    val canSkipNext: Boolean,
    val canSkipPrevious: Boolean,
)

/** Музыкальное приложение, которое можно выбрать для автозапуска или режима шумоподавления. */
data class PlayerApp(val packageName: String, val label: String)

/**
 * Плееры на телефоне через стандартный механизм Android (MediaSession): видно любой плеер —
 * Spotify, Яндекс Музыку, YouTube Music и другие, — без подключения к каждому сервису отдельно.
 * Нужен доступ к уведомлениям ([MediaNotificationListener]).
 *
 * Команды (пауза, перемотка) каждый раз берут свежий список плееров у системы, поэтому работают
 * и без подписки на [nowPlaying].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class MediaRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sessionManager = context.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(context, MediaNotificationListener::class.java)

    private val _access = MutableStateFlow(checkAccess())

    /** Пользователь дал доступ к уведомлениям: без него плееров не видно. */
    val access: StateFlow<Boolean> = _access.asStateFlow()

    init {
        // Слушателя подключили (дали доступ, перезагрузка) или отключили — перепроверяем доступ.
        scope.launch { MediaNotificationListener.connected.collect { refreshAccess() } }
    }

    /** Перепроверить доступ: зовём, когда пользователь вернулся из системных настроек. */
    fun refreshAccess() {
        _access.value = checkAccess()
    }

    /** Текущий трек; null — ничего не играет, плеер закрыт или нет доступа. */
    val nowPlaying: StateFlow<NowPlaying?> = access
        .flatMapLatest { granted -> if (granted) sessions() else flowOf(null) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    fun playPause() {
        val controller = current() ?: return
        if (controller.isPlaying()) controller.transportControls.pause() else controller.transportControls.play()
    }

    fun next() {
        current()?.transportControls?.skipToNext()
    }

    fun previous() {
        current()?.transportControls?.skipToPrevious()
    }

    /** Играет ли сейчас какой-нибудь плеер. */
    fun anyPlaying(): Boolean = controllers().any { it.isPlaying() }

    /** Пакет плеера, который сейчас играет; null — ничего не играет. */
    fun playingPackage(): String? = controllers().firstOrNull { it.isPlaying() }?.packageName

    /**
     * Отмотать текущий трек на [ms] назад. Вызываем на паузе, перед продолжением.
     * false — плеера нет или он не умеет перематывать.
     */
    fun rewind(ms: Long): Boolean {
        val controller = current() ?: return false
        val state = controller.playbackState ?: return false
        if ((state.actions and PlaybackState.ACTION_SEEK_TO) == 0L) return false
        val position = state.currentPosition()
        if (position <= 0) return false
        controller.transportControls.seekTo((position - ms).coerceAtLeast(0))
        return true
    }

    /** Включить музыку в плеере [packageName], если он уже открыт. false — его сессии нет. */
    fun play(packageName: String): Boolean {
        val controller = controllers().firstOrNull { it.packageName == packageName } ?: return false
        controller.transportControls.play()
        return true
    }

    fun isPlaying(packageName: String): Boolean =
        controllers().any { it.packageName == packageName && it.isPlaying() }

    /**
     * Включить музыку в закрытом плеере, не открывая его окно: подключаемся к его MediaBrowserService
     * так же, как это делает Android в «продолжить прослушивание» в шторке, и жмём «play».
     * Работает не во всех плеерах: некоторые пускают к себе только известные приложения.
     */
    suspend fun playViaBrowser(packageName: String): Boolean = withContext(Dispatchers.Main) {
        val service = runCatching {
            context.packageManager
                .queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(packageName), 0)
                .firstOrNull()?.serviceInfo
        }.getOrNull() ?: return@withContext false
        var browser: MediaBrowser? = null
        val played = withTimeoutOrNull(BROWSER_TIMEOUT_MS) {
            suspendCancellableCoroutine<Boolean> { cont ->
                val callback = object : MediaBrowser.ConnectionCallback() {
                    override fun onConnected() {
                        val ok = runCatching {
                            MediaController(context, browser!!.sessionToken).transportControls.play()
                        }.onFailure { Timber.w(it, "browser play") }.isSuccess
                        if (cont.isActive) cont.resume(ok)
                    }

                    override fun onConnectionFailed() {
                        if (cont.isActive) cont.resume(false)
                    }
                }
                // EXTRA_RECENT: просим «последнее прослушанное», как системная карточка продолжения.
                val hints = Bundle().apply { putBoolean(MediaBrowserService.BrowserRoot.EXTRA_RECENT, true) }
                browser = MediaBrowser(context, ComponentName(service.packageName, service.name), callback, hints)
                runCatching { browser?.connect() }.onFailure { if (cont.isActive) cont.resume(false) }
            }
        } ?: false
        // Даём плееру запустить свой сервис воспроизведения, потом отключаемся.
        if (played) delay(BROWSER_HOLD_MS)
        runCatching { browser?.disconnect() }
        played
    }

    /** Музыкальные приложения на телефоне: объявившие MediaBrowserService и те, у кого есть открытый плеер. */
    fun playerApps(): List<PlayerApp> {
        val pm = context.packageManager
        val packages = buildSet {
            runCatching { pm.queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE), 0) }
                .getOrDefault(emptyList())
                .forEach { add(it.serviceInfo.packageName) }
            controllers().forEach { add(it.packageName) }
            remove(context.packageName)
        }
        return packages
            .filter { pm.getLaunchIntentForPackage(it) != null }
            .map { PlayerApp(it, appLabel(it)) }
            .sortedBy { it.label.lowercase() }
    }

    fun appLabel(packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    private fun checkAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    private fun controllers(): List<MediaController> = runCatching {
        sessionManager.getActiveSessions(listener)
    }.getOrElse {
        // SecurityException: доступ к уведомлениям забрали.
        _access.value = false
        emptyList()
    }

    /** Плеер, которым управляем: тот, что играет, иначе последний активный (система сортирует по свежести). */
    private fun current(): MediaController? = pick(controllers())

    private fun pick(list: List<MediaController>): MediaController? =
        list.firstOrNull { it.isPlaying() } ?: list.firstOrNull()

    private fun sessions(): Flow<NowPlaying?> = callbackFlow {
        val handler = Handler(Looper.getMainLooper())
        var tracked = emptyList<MediaController>()
        val art = ArtCache()

        fun publish() {
            trySend(pick(tracked)?.let { toNowPlaying(it, art) })
        }

        val callback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
            override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
            override fun onSessionDestroyed() = publish()
        }

        fun track(list: List<MediaController>?) {
            tracked.forEach { it.unregisterCallback(callback) }
            tracked = list.orEmpty().filter { it.packageName != context.packageName }
            tracked.forEach { it.registerCallback(callback, handler) }
            publish()
        }

        val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { track(it) }
        val registered = runCatching {
            sessionManager.addOnActiveSessionsChangedListener(sessionsListener, listener, handler)
            track(sessionManager.getActiveSessions(listener))
        }.onFailure {
            Timber.w(it, "media sessions")
            _access.value = false
            trySend(null)
        }.isSuccess

        awaitClose {
            if (registered) runCatching { sessionManager.removeOnActiveSessionsChangedListener(sessionsListener) }
            tracked.forEach { it.unregisterCallback(callback) }
        }
    }

    private fun toNowPlaying(controller: MediaController, art: ArtCache): NowPlaying {
        val metadata = controller.metadata
        val actions = controller.playbackState?.actions ?: 0L
        return NowPlaying(
            packageName = controller.packageName,
            appName = appLabel(controller.packageName),
            title = metadata?.text(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE),
            artist = metadata?.text(
                MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
            ),
            art = art.get(
                metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
            ),
            playing = controller.isPlaying(),
            canSkipNext = (actions and PlaybackState.ACTION_SKIP_TO_NEXT) != 0L,
            canSkipPrevious = (actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0L,
        )
    }

    private fun MediaMetadata.text(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> getText(key)?.toString()?.takeIf { it.isNotBlank() } }

    /**
     * Обложки бывают по несколько мегапикселей, а показываем мы 56–64 dp. Уменьшаем один раз на картинку:
     * состояние воспроизведения меняется часто, а обложка та же.
     */
    private class ArtCache {
        private var source: Bitmap? = null
        private var scaled: Bitmap? = null

        fun get(bitmap: Bitmap?): Bitmap? {
            if (bitmap == null) return null
            if (bitmap !== source) {
                source = bitmap
                scaled = runCatching {
                    val side = maxOf(bitmap.width, bitmap.height)
                    if (side <= ART_SIZE_PX) bitmap
                    else Bitmap.createScaledBitmap(bitmap, bitmap.width * ART_SIZE_PX / side, bitmap.height * ART_SIZE_PX / side, true)
                }.getOrNull()
            }
            return scaled
        }
    }

    private companion object {
        const val ART_SIZE_PX = 256
        const val BROWSER_TIMEOUT_MS = 5_000L
        const val BROWSER_HOLD_MS = 2_000L

        fun MediaController.isPlaying(): Boolean = playbackState?.state.let {
            it == PlaybackState.STATE_PLAYING || it == PlaybackState.STATE_BUFFERING
        }

        /** Позиция сейчас: у играющего трека система хранит позицию на момент последнего обновления. */
        fun PlaybackState.currentPosition(): Long {
            if (state != PlaybackState.STATE_PLAYING) return position
            val elapsed = SystemClock.elapsedRealtime() - lastPositionUpdateTime
            return position + (elapsed * playbackSpeed).toLong()
        }
    }
}
