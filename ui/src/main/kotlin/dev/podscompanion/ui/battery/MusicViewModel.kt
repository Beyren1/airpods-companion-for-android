package dev.podscompanion.ui.battery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.podscompanion.data.media.MediaRepository
import dev.podscompanion.data.media.NowPlaying
import dev.podscompanion.data.media.PlayerApp
import dev.podscompanion.data.settings.AppSettings
import dev.podscompanion.data.settings.SettingsRepository
import dev.podscompanion.protocol.aap.ListeningMode
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Музыка: текущий трек для карточки и экран настроек «Музыка». */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MusicViewModel @Inject constructor(
    private val media: MediaRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    /** Есть доступ к уведомлениям: без него плееров не видно. */
    val access: StateFlow<Boolean> = media.access

    /** Текущий трек для карточки; null — карточка выключена или ничего не играет. */
    val nowPlaying: StateFlow<NowPlaying?> = settingsRepository.settings
        .map { it.nowPlayingCard }
        .distinctUntilChanged()
        .flatMapLatest { enabled -> if (enabled) media.nowPlaying else flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _players = MutableStateFlow<List<PlayerApp>>(emptyList())

    /** Музыкальные приложения на телефоне: для выбора плеера и правил режимов. */
    val players: StateFlow<List<PlayerApp>> = _players.asStateFlow()

    /** Вернулись на экран (например, из системных настроек): перепроверить доступ и список плееров. */
    fun refresh() {
        media.refreshAccess()
        viewModelScope.launch {
            _players.value = withContext(Dispatchers.IO) { media.playerApps() }
        }
    }

    fun onButton(button: MediaButton) = when (button) {
        MediaButton.PREVIOUS -> media.previous()
        MediaButton.PLAY_PAUSE -> media.playPause()
        MediaButton.NEXT -> media.next()
    }

    fun setNowPlayingCard(value: Boolean) = edit { settingsRepository.setNowPlayingCard(value) }
    fun setAutoLaunch(value: Boolean) = edit { settingsRepository.setAutoLaunch(value) }
    fun setAutoLaunchPackage(value: String) = edit { settingsRepository.setAutoLaunchPackage(value) }
    fun setAppModes(value: Boolean) = edit { settingsRepository.setAppModes(value) }
    fun setAppModeRule(packageName: String, mode: ListeningMode?) = edit { settingsRepository.setAppModeRule(packageName, mode) }
    fun setSmartResume(value: Boolean) = edit { settingsRepository.setSmartResume(value) }

    private fun edit(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
