package dev.podscompanion.data.stats

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Context.usageStore by preferencesDataStore(name = "usage_stats")

/**
 * Статистика использования на телефоне. Сервис добавляет секунды каждые несколько секунд в
 * память ([history] сразу видит экран), а в файл пишет раз в минуту: так не изнашиваем память
 * телефона частой записью. При остановке сервиса [flush] дописывает остаток.
 */
@Singleton
class UsageStatsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val mutex = Mutex()
    private val _history = MutableStateFlow<UsageHistory?>(null)
    private var dirty = false

    /** null — файл ещё не прочитан. */
    val history: StateFlow<UsageHistory?> = _history.asStateFlow()

    suspend fun load(): UsageHistory = mutex.withLock { loadLocked() }

    private suspend fun loadLocked(): UsageHistory {
        _history.value?.let { return it }
        val loaded = context.usageStore.data.map { it[KEY].orEmpty() }.first().let(UsageHistory::decode)
        _history.value = loaded
        return loaded
    }

    suspend fun record(activity: List<PairActivity>, day: LocalDate, seconds: Long, nowMs: Long): Unit = mutex.withLock {
        if (activity.isEmpty()) return@withLock
        _history.value = loadLocked().record(activity, day, seconds, nowMs)
        dirty = true
    }

    suspend fun flush(today: LocalDate = LocalDate.now()): Unit = mutex.withLock {
        if (!dirty) return@withLock
        val trimmed = loadLocked().trimmed(today)
        _history.value = trimmed
        context.usageStore.edit { it[KEY] = trimmed.encode() }
        dirty = false
    }

    /** Кнопка «Сбросить статистику». */
    suspend fun clear(): Unit = mutex.withLock {
        _history.value = UsageHistory()
        dirty = false
        context.usageStore.edit { it.remove(KEY) }
    }

    private companion object {
        val KEY = stringPreferencesKey("history")
    }
}
