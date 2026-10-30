package dev.podscompanion.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.podscompanion.MainActivity
import dev.podscompanion.R
import dev.podscompanion.bluetooth.route.AudioRoute
import dev.podscompanion.bluetooth.route.looksLikeCar
import dev.podscompanion.bluetooth.scan.ConnectedAudioDevice
import dev.podscompanion.bluetooth.scan.ConnectedAudioDevices
import dev.podscompanion.data.ConnectedNameMatcher
import dev.podscompanion.data.aap.AapLog
import dev.podscompanion.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * «Звук в машину»: телефон подключён и к машине, и к наушникам — музыка и звонки идут в машину.
 *
 * Android выводит звук в одно устройство, обычно в подключённое последним. Достали наушники
 * из кейса уже в машине — звук ушёл в них. Тогда просим систему вернуть звук машине
 * ([AudioRoute.makeActive]), а если она не пускает — отключаем наушники от звука и звонков,
 * и Android сам переводит всё на машину. Машину отключили (вышли из машины) — подключаем
 * наушники обратно. Если ничего не вышло, показываем уведомление с окном выбора устройства.
 *
 * Пробуем не больше [MAX_ATTEMPTS] раз за поездку: если пользователь сам выбрал наушники
 * или они упорно подключаются снова, не спорим с ним.
 */
internal class CarAudioPriority(
    private val context: Context,
    private val connectedAudio: ConnectedAudioDevices,
    private val route: AudioRoute,
    private val aapLog: AapLog,
) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private var settings = AppSettings()
    private var inCar = false
    private var attempts = 0
    private var loggedActive = false

    /** Наушники, которые отключили сами: после машины подключаем их обратно. */
    private val released = mutableMapOf<String, BluetoothDevice>()

    fun run(scope: CoroutineScope, settingsFlow: Flow<AppSettings>) {
        ensureChannel()
        scope.launch {
            // Проверки идут по одной: пока ждём, что система переключит звук, новые списки устройств
            // копятся, и следующая проверка берёт самый свежий.
            combine(connectedAudio.devices().filterNotNull(), settingsFlow) { devices, s -> devices to s }
                .conflate()
                .collect { (devices, s) ->
                    settings = s
                    check(devices)
                }
        }
    }

    private suspend fun check(devices: List<ConnectedAudioDevice>) {
        val cars = devices.filter(::isCar)
        if (!settings.carAudio || cars.isEmpty()) {
            if (inCar) leftCar(settings.carAudio)
            return
        }
        if (!inCar) {
            inCar = true
            attempts = 0
            loggedActive = false
            log("подключена ${cars.joinToString { it.name }}")
        }
        if (devices.none(::isPods)) {
            cancelNotification()
            return
        }
        // Даём Android самому выбрать активное устройство: сразу после подключения оно меняется.
        delay(SETTLE_MS)
        val now = connectedAudio.devices().filterNotNull().first()
        val car = now.firstOrNull(::isCar) ?: return
        val pods = now.filter(::isPods)
        if (pods.isEmpty() || !settings.carAudio) return
        if (route.isActive(car.device)) {
            if (!loggedActive) log("звук уже в машине ${car.name}")
            loggedActive = true
            cancelNotification()
            return
        }
        loggedActive = false
        if (attempts >= MAX_ATTEMPTS) {
            log("звук в ${pods.joinToString { it.name }}, попытки закончились — показываю уведомление")
            showNotification(car.name)
            return
        }
        attempts++
        log("звук ушёл в ${pods.joinToString { it.name }}, перевожу в ${car.name} (попытка $attempts)")

        if (route.makeActive(car.device, ::log)) {
            delay(SWITCH_WAIT_MS)
            if (route.isActive(car.device)) {
                log("звук в машине")
                cancelNotification()
                return
            }
            log("система приняла запрос, но звук не переключился")
        }

        var disconnected = false
        for (p in pods) {
            if (route.disconnectAudio(p.device, ::log)) {
                released[p.device.address] = p.device
                disconnected = true
            }
        }
        if (disconnected) {
            delay(SWITCH_WAIT_MS)
            val after = connectedAudio.devices().filterNotNull().first()
            if (after.none(::isPods) || route.isActive(car.device)) {
                log("наушники отключены от звука, звук в машине")
                cancelNotification()
                return
            }
        }
        log("переключить не вышло — показываю уведомление")
        showNotification(car.name)
    }

    /** Машину отключили: вернуть звук наушникам, которые отключали сами. */
    private suspend fun leftCar(enabled: Boolean) {
        inCar = false
        cancelNotification()
        log("машина отключилась")
        val toReturn = released.values.toList()
        released.clear()
        if (!enabled) return
        for (device in toReturn) {
            log("возвращаю звук в ${device.address.takeLast(5)}")
            route.reconnectAudio(device, ::log)
        }
    }

    private fun isCar(device: ConnectedAudioDevice): Boolean =
        !isPods(device) && settings.isCar(device.device.address, device.device.looksLikeCar())

    private fun isPods(device: ConnectedAudioDevice): Boolean =
        device.supportsAap || ConnectedNameMatcher.knownModels(listOf(device.name)).isNotEmpty()

    private fun log(message: String) = aapLog.add("машина: $message")

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.car_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.car_channel_description) }
        manager.createNotificationChannel(channel)
    }

    private fun showNotification(carName: String) {
        val open = PendingIntent.getActivity(
            context,
            3,
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OUTPUT_SWITCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pods)
            .setContentTitle(context.getString(R.string.car_title, carName))
            .setContentText(context.getString(R.string.car_text))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun cancelNotification() = manager.cancel(NOTIFICATION_ID)

    private companion object {
        const val CHANNEL_ID = "car_audio"
        const val NOTIFICATION_ID = 4
        const val MAX_ATTEMPTS = 3
        const val SETTLE_MS = 2_500L
        const val SWITCH_WAIT_MS = 3_000L
    }
}
