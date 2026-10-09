package dev.podscompanion.data.find

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.podscompanion.data.ConnectedNameMatcher
import dev.podscompanion.protocol.aap.Aap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Запоминает, где отключились наушники. Зовётся из системной рассылки «устройство отключилось»
 * (см. LastPlaceReceiver в приложении): она приходит, даже если приложение закрыто.
 *
 * Место — это место телефона в момент отключения. Сначала сразу пишем последнее известное
 * системе место (если оно свежее), потом просим у системы текущее и уточняем запись.
 * Без разрешения на геолокацию запоминаем хотя бы время.
 */
@Singleton
class LastPlaceRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: LastPlaceStore,
) {
    /** Наушники Apple: в SDP-записи есть сервис AAP или имя похоже на модель AirPods/Beats. */
    @SuppressLint("MissingPermission")
    fun isApplePods(device: BluetoothDevice): Boolean = runCatching {
        device.uuids.orEmpty().any { it.uuid.toString().equals(Aap.SERVICE_UUID, ignoreCase = true) } ||
            ConnectedNameMatcher.modelsForName(nameOf(device).orEmpty()).isNotEmpty()
    }.getOrDefault(false)

    suspend fun onDisconnected(device: BluetoothDevice) {
        if (!hasConnectPermission() || !isApplePods(device)) return
        val name = nameOf(device) ?: return
        val now = System.currentTimeMillis()
        if (!hasLocationPermission()) {
            store.save(LastPlace(device.address, name, now))
            return
        }
        val recent = lastKnown()?.takeIf { now - it.time <= RECENT_FIX_MS }
        store.save(LastPlace(device.address, name, now, recent?.toFix()))
        val current = withTimeoutOrNull(CURRENT_FIX_TIMEOUT_MS) { currentLocation() }
        val best = listOfNotNull(current, recent).minByOrNull { if (it.hasAccuracy()) it.accuracy else Float.MAX_VALUE }
            ?: lastKnown()
        if (best != null && best !== recent) store.save(LastPlace(device.address, name, now, best.toFix()))
    }

    fun hasLocationPermission(): Boolean = granted(Manifest.permission.ACCESS_FINE_LOCATION) ||
        granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    /** Место можно узнать, когда приложение закрыто: «Разрешать всегда». */
    fun hasBackgroundLocationPermission(): Boolean =
        hasLocationPermission() && granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || granted(Manifest.permission.BLUETOOTH_CONNECT)

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun nameOf(device: BluetoothDevice): String? = runCatching {
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) device.alias else null) ?: device.name
    }.getOrNull()

    private val locationManager get() = context.getSystemService(LocationManager::class.java)

    /** Самое точное из последних известных мест по всем источникам. */
    @SuppressLint("MissingPermission")
    private fun lastKnown(): Location? {
        val manager = locationManager ?: return null
        return runCatching {
            manager.getProviders(true).mapNotNull { manager.getLastKnownLocation(it) }
                .maxByOrNull { it.time }
        }.onFailure { Timber.w(it, "Последнее место недоступно") }.getOrNull()
    }

    /** Текущее место. На Android 10 такого запроса нет — там только последнее известное. */
    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(): Location? {
        val manager = locationManager ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val provider = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && manager.hasProvider(LocationManager.FUSED_PROVIDER) ->
                LocationManager.FUSED_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> return null
        }
        return suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            runCatching {
                manager.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(context)) { location ->
                    if (cont.isActive) cont.resume(location)
                }
            }.onFailure {
                Timber.w(it, "Текущее место недоступно")
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    private fun Location.toFix() = LastPlace.Fix(
        latitude = latitude,
        longitude = longitude,
        accuracyM = if (hasAccuracy()) accuracy else null,
        fixedAtMs = time,
    )

    private companion object {
        /** Последнее известное место старше двух минут не пишем сразу: телефон мог уехать. */
        const val RECENT_FIX_MS = 2 * 60_000L

        /** Рассылке система даёт меньше минуты: ждём текущее место не дольше 20 с. */
        const val CURRENT_FIX_TIMEOUT_MS = 20_000L
    }
}
