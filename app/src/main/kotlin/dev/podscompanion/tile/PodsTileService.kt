package dev.podscompanion.tile

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dagger.hilt.android.AndroidEntryPoint
import dev.podscompanion.R
import dev.podscompanion.data.aap.AapRepository
import dev.podscompanion.data.aap.AapSessionState
import dev.podscompanion.data.snapshot.StatusSnapshot
import dev.podscompanion.data.snapshot.StatusSnapshotStore
import dev.podscompanion.protocol.aap.AapCommand
import dev.podscompanion.protocol.aap.ListeningMode
import dev.podscompanion.protocol.aap.modeCycle
import dev.podscompanion.widget.batteryText
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Плитка в шторке: имя наушников и заряд, нажатие переключает режим шумоподавления
 * (по кругу из тех, что выбраны для кнопки наушников). Долгое нажатие открывает приложение.
 */
@AndroidEntryPoint
class PodsTileService : TileService() {

    @Inject lateinit var store: StatusSnapshotStore
    @Inject lateinit var aap: AapRepository

    private val scope = MainScope()
    private var listening: Job? = null
    private var snapshot: StatusSnapshot? = null

    override fun onStartListening() {
        listening?.cancel()
        listening = scope.launch {
            store.snapshot.collect {
                snapshot = it
                render(it, note = null)
            }
        }
    }

    override fun onStopListening() {
        listening?.cancel()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        scope.launch { cycleMode() }
    }

    private suspend fun cycleMode() {
        render(snapshot, note = getString(R.string.tile_connecting))
        // Пока ждём, подписка держит прямое подключение открытым.
        val session = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            aap.state.map { state ->
                val connected = state.sessions.filterIsInstance<AapSessionState.Connected>()
                connected.firstOrNull { it.device.listeningMode != null } ?: connected.firstOrNull()
            }.filterNotNull().first()
        }
        if (session == null) {
            render(snapshot, note = getString(R.string.tile_no_connection))
            return
        }
        val next = nextMode(session.device.listeningMode, session.device.modeCycle)
        aap.send(session.address, AapCommand.SetListeningMode(next))
        render(snapshot, note = getString(modeTitle(next)))
    }

    private fun render(snapshot: StatusSnapshot?, note: String?) {
        val tile = qsTile ?: return
        tile.label = snapshot?.name ?: getString(R.string.tile_label)
        tile.subtitle = note ?: snapshot?.let(::batteryLine) ?: getString(R.string.tile_no_data)
        tile.state = if (snapshot?.nearby == true) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_pods)
        tile.updateTile()
    }

    private fun batteryLine(s: StatusSnapshot): String =
        if (s.single) {
            batteryText(s.left, s.leftCharging)
        } else {
            listOfNotNull(
                getString(R.string.notif_left, batteryText(s.left, s.leftCharging)),
                getString(R.string.notif_right, batteryText(s.right, s.rightCharging)),
                s.case?.let { getString(R.string.notif_case, batteryText(it, s.caseCharging)) },
            ).joinToString(" · ")
        }

    private fun modeTitle(mode: ListeningMode) = when (mode) {
        ListeningMode.OFF -> R.string.tile_mode_off
        ListeningMode.NOISE_CANCELLATION -> R.string.tile_mode_nc
        ListeningMode.TRANSPARENCY -> R.string.tile_mode_transparency
        ListeningMode.ADAPTIVE -> R.string.tile_mode_adaptive
        ListeningMode.UNKNOWN -> R.string.tile_label
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 6_000L
        private val ORDER = listOf(ListeningMode.OFF, ListeningMode.TRANSPARENCY, ListeningMode.ADAPTIVE, ListeningMode.NOISE_CANCELLATION)

        /** Следующий режим по кругу: из выбранных для кнопки наушников, иначе прозрачность ↔ шумоподавление. */
        internal fun nextMode(current: ListeningMode?, cycle: Set<ListeningMode>?): ListeningMode {
            val modes = ORDER.filter { cycle != null && it in cycle }.takeIf { it.size >= 2 }
                ?: listOf(ListeningMode.TRANSPARENCY, ListeningMode.NOISE_CANCELLATION)
            val index = modes.indexOf(current)
            return modes[(index + 1) % modes.size]
        }
    }
}
