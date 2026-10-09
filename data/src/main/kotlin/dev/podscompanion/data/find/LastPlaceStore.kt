package dev.podscompanion.data.find

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.lastPlaceStore by preferencesDataStore(name = "last_places")

/** Последнее место каждой пары наушников. Файл приложения, в резервную копию не попадает. */
@Singleton
class LastPlaceStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Все пары, самые свежие сверху. */
    val places: Flow<List<LastPlace>> = context.lastPlaceStore.data
        .map { prefs -> prefs[KEY].orEmpty().mapNotNull(LastPlace::decode).sortedByDescending { it.disconnectedAtMs } }
        .distinctUntilChanged()

    /** Записать место пары; старая запись этой пары заменяется. */
    suspend fun save(place: LastPlace) {
        context.lastPlaceStore.edit { prefs ->
            val others = prefs[KEY].orEmpty().filter { LastPlace.decode(it)?.address != place.address }
            prefs[KEY] = others.toSet() + LastPlace.encode(place)
        }
    }

    suspend fun remove(address: String) {
        context.lastPlaceStore.edit { prefs ->
            prefs[KEY] = prefs[KEY].orEmpty().filter { LastPlace.decode(it)?.address != address }.toSet()
        }
    }

    private companion object {
        val KEY = stringSetPreferencesKey("places")
    }
}
