package dev.podscompanion.data.aap

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.podscompanion.protocol.advertising.ProximityKeys
import dev.podscompanion.protocol.util.Hex
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Ключи рекламы одних наушников; [address] — их постоянный адрес Bluetooth Classic. */
data class OwnPodsKeys(val address: String, val irk: ByteArray?, val encryptionKey: ByteArray?) {
    val keys get() = ProximityKeys(irk, encryptionKey)

    override fun equals(other: Any?) = other is OwnPodsKeys && address == other.address &&
        irk.contentEquals(other.irk) && encryptionKey.contentEquals(other.encryptionKey)
    override fun hashCode() = address.hashCode()
}

private val Context.keyStore by preferencesDataStore(name = "proximity_keys")

/**
 * Ключи рекламы наушников, которые хоть раз подключались к телефону напрямую. Храним, чтобы
 * узнавать свои наушники в рекламе и до прямого подключения. Файл приложения, резервное
 * копирование выключено (allowBackup=false).
 */
@Singleton
class ProximityKeyStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val keys: Flow<List<OwnPodsKeys>> = context.keyStore.data
        .map { prefs -> prefs[KEY].orEmpty().mapNotNull(::decode) }
        .distinctUntilChanged()

    suspend fun save(keys: OwnPodsKeys) {
        context.keyStore.edit { prefs ->
            val others = prefs[KEY].orEmpty().filterNot { it.startsWith(keys.address + SEPARATOR) }
            prefs[KEY] = others.toSet() + encode(keys)
        }
    }

    private fun encode(k: OwnPodsKeys) =
        listOf(k.address, k.irk?.let { Hex.encode(it, "") }.orEmpty(), k.encryptionKey?.let { Hex.encode(it, "") }.orEmpty())
            .joinToString(SEPARATOR)

    private fun decode(line: String): OwnPodsKeys? {
        val parts = line.split(SEPARATOR)
        if (parts.size != 3) return null
        fun key(text: String) = text.takeIf { it.isNotEmpty() }?.let { runCatching { Hex.decode(it) }.getOrNull() }
        return OwnPodsKeys(parts[0], key(parts[1]), key(parts[2]))
    }

    private companion object {
        val KEY = stringSetPreferencesKey("keys")
        const val SEPARATOR = "|"
    }
}
