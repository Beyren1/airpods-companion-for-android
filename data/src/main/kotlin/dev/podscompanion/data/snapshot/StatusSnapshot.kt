package dev.podscompanion.data.snapshot

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.podscompanion.data.PodsStatus
import dev.podscompanion.protocol.aap.ListeningMode
import dev.podscompanion.protocol.advertising.Capability
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Последний известный заряд для виджета и плитки в шторке. Их показывает система, когда
 * приложение может быть закрыто, поэтому снимок хранится в файле, а не только в памяти.
 * [nearby] = false — наушников сейчас не видно, цифры последние известные.
 */
data class StatusSnapshot(
    val name: String,
    /** Один заряд на всё (Max): левого, правого и кейса нет. */
    val single: Boolean,
    val left: Int?,
    val right: Int?,
    val case: Int?,
    val leftCharging: Boolean = false,
    val rightCharging: Boolean = false,
    val caseCharging: Boolean = false,
    val mode: ListeningMode? = null,
    val nearby: Boolean = true,
    val updatedAtMs: Long = 0,
) {
    /** Одинаковые по содержанию снимки: время обновления не важно. */
    fun sameContent(other: StatusSnapshot?) = other != null && copy(updatedAtMs = 0) == other.copy(updatedAtMs = 0)

    fun encode(): String = listOf(
        name.replace(SEP, " "), single, left, right, case, leftCharging, rightCharging, caseCharging, mode?.name, nearby, updatedAtMs,
    ).joinToString(SEP) { it?.toString() ?: "" }

    companion object {
        private const val SEP = "|"

        fun from(status: PodsStatus, nowMs: Long): StatusSnapshot {
            val model = status.model
            val single = model != null && Capability.STEREO_BUDS !in model.capabilities
            return StatusSnapshot(
                name = model?.displayName ?: "AirPods",
                single = single,
                left = if (single) status.primary.battery?.percent else status.left.battery?.percent,
                right = if (single) null else status.right.battery?.percent,
                case = if (single) null else status.caseBattery?.percent,
                leftCharging = if (single) status.primary.charging else status.left.charging,
                rightCharging = !single && status.right.charging,
                caseCharging = !single && status.caseCharging,
                mode = status.aap?.listeningMode?.takeIf { it != ListeningMode.UNKNOWN },
                nearby = true,
                updatedAtMs = nowMs,
            )
        }

        fun decode(text: String): StatusSnapshot? = runCatching {
            val p = text.split(SEP)
            StatusSnapshot(
                name = p[0],
                single = p[1].toBoolean(),
                left = p[2].toIntOrNull(),
                right = p[3].toIntOrNull(),
                case = p[4].toIntOrNull(),
                leftCharging = p[5].toBoolean(),
                rightCharging = p[6].toBoolean(),
                caseCharging = p[7].toBoolean(),
                mode = p[8].takeIf { it.isNotEmpty() }?.let(ListeningMode::valueOf),
                nearby = p[9].toBoolean(),
                updatedAtMs = p[10].toLong(),
            )
        }.getOrNull()
    }
}

private val Context.snapshotStore by preferencesDataStore(name = "status_snapshot")

@Singleton
class StatusSnapshotStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val snapshot: Flow<StatusSnapshot?> = context.snapshotStore.data
        .map { prefs -> prefs[KEY]?.let(StatusSnapshot::decode) }
        .distinctUntilChanged()

    suspend fun current(): StatusSnapshot? = snapshot.first()

    suspend fun save(snapshot: StatusSnapshot) {
        context.snapshotStore.edit { it[KEY] = snapshot.encode() }
    }

    /** Наушники пропали из виду: цифры оставляем, помечаем как последние известные. */
    suspend fun markAway() {
        val last = current() ?: return
        if (last.nearby) save(last.copy(nearby = false))
    }

    private companion object {
        val KEY = stringPreferencesKey("snapshot")
    }
}
