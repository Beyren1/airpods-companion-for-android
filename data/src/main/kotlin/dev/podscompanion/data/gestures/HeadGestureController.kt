package dev.podscompanion.data.gestures

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.podscompanion.data.ConnectedNameMatcher
import dev.podscompanion.data.aap.AapLog
import dev.podscompanion.data.aap.AapRepository
import dev.podscompanion.data.aap.AapSessionState
import dev.podscompanion.data.settings.SettingsRepository
import dev.podscompanion.protocol.aap.AapCommand
import dev.podscompanion.protocol.aap.HeadCalibration
import dev.podscompanion.protocol.aap.HeadGesture
import dev.podscompanion.protocol.aap.HeadGestureDetector
import dev.podscompanion.protocol.advertising.Capability
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Жесты головой во время входящего звонка: кивок — ответить, покачивание — отклонить.
 *
 * Датчики головы включаются только пока звонит телефон: поток идёт десятки раз в секунду
 * и расходует заряд наушников. Нужны разрешения «Телефон» (узнать, что звонят) и
 * «Ответ на звонки». Работает для обычных звонков; звонки мессенджеров Android так не отдаёт.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class HeadGestureController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val aap: AapRepository,
    private val log: AapLog,
) {
    /** Запускается сервисом и живёт, пока работает фоновый режим. */
    fun run(scope: CoroutineScope) = scope.launch {
        settings.settings
            .map { if (it.headGestures) it.headCalibration else null }
            .distinctUntilChanged()
            .collectLatest { calibration ->
                if (calibration == null) return@collectLatest
                ringing().distinctUntilChanged().collectLatest { ringing ->
                    if (ringing) handleRinging(calibration)
                }
            }
    }

    private suspend fun handleRinging(calibration: HeadCalibration) = coroutineScope {
        // Пока звонит, держим прямое подключение открытым (без подписчиков оно закрывается).
        val keepAlive = launch { aap.state.collect {} }
        try {
            val session = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                aap.state.map { sessions ->
                    sessions.sessions.filterIsInstance<AapSessionState.Connected>().firstOrNull(::supportsGestures)
                }.filterNotNull().first()
            } ?: return@coroutineScope
            val address = session.address
            log.add("${session.deviceName}: звонок, жду кивок или покачивание")
            aap.send(address, AapCommand.StartHeadTracking)
            try {
                val detector = HeadGestureDetector(calibration)
                val gesture = aap.headMotion
                    .filter { it.address == address }
                    .mapNotNull { detector.onSample(it.timeMs, it.motion.axes) }
                    .first()
                log.add("${session.deviceName}: ${if (gesture == HeadGesture.NOD) "кивок — отвечаю" else "покачивание — отклоняю"}")
                act(gesture)
            } finally {
                aap.send(address, AapCommand.StopHeadTracking)
            }
        } finally {
            keepAlive.cancel()
        }
    }

    private fun supportsGestures(session: AapSessionState.Connected): Boolean =
        ConnectedNameMatcher.modelsForName(session.deviceName).let { models ->
            models.isEmpty() || models.any { Capability.HEAD_GESTURES in it.capabilities }
        }

    @SuppressLint("MissingPermission") // проверяем ниже
    @Suppress("DEPRECATION") // замены для приложений-компаньонов нет, методы работают
    private fun act(gesture: HeadGesture) {
        if (!granted(Manifest.permission.ANSWER_PHONE_CALLS)) {
            log.add("нет разрешения «Ответ на звонки»")
            return
        }
        val telecom = context.getSystemService(TelecomManager::class.java)
        runCatching {
            when (gesture) {
                HeadGesture.NOD -> telecom.acceptRingingCall()
                HeadGesture.SHAKE -> telecom.endCall()
            }
        }.onFailure { log.add("звонок: ${it.message}") }
    }

    /** true, пока телефон звонит. Без разрешения «Телефон» всегда false. */
    @SuppressLint("MissingPermission") // проверяем в начале
    private fun ringing(): Flow<Boolean> {
        if (!granted(Manifest.permission.READ_PHONE_STATE)) return flowOf(false)
        val telephony = context.getSystemService(TelephonyManager::class.java)
        return callbackFlow {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        trySend(state == TelephonyManager.CALL_STATE_RINGING)
                    }
                }
                telephony.registerTelephonyCallback(ContextCompat.getMainExecutor(context), callback)
                awaitClose { telephony.unregisterTelephonyCallback(callback) }
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        trySend(state == TelephonyManager.CALL_STATE_RINGING)
                    }
                }
                @Suppress("DEPRECATION")
                telephony.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                @Suppress("DEPRECATION")
                awaitClose { telephony.listen(listener, PhoneStateListener.LISTEN_NONE) }
            }
        }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000L
    }
}
