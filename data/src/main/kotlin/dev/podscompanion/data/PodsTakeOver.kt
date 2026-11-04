package dev.podscompanion.data

import android.bluetooth.BluetoothDevice
import dev.podscompanion.bluetooth.connect.PodsConnector
import dev.podscompanion.bluetooth.connect.TakeOverResult
import dev.podscompanion.data.aap.AapLog
import dev.podscompanion.data.link.DeviceLink
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Забрать наушники, которые видно в рекламе, на телефон: и по кнопке на экране, и само,
 * когда на телефоне включили звук. Какой способ сработал, пишется в журнал соединения.
 */
@Singleton
class PodsTakeOver @Inject constructor(
    private val connector: PodsConnector,
    private val log: AapLog,
    private val link: DeviceLink,
) {
    /**
     * Свои наушники узнаём по ключу из рекламы ([PodsStatus.owner]), иначе — по имени среди
     * сопряжённых. [reason] попадает в журнал: по кнопке или само.
     */
    suspend fun takeOver(pods: PodsStatus, reason: String): TakeOverResult {
        val device = pods.owner?.let(connector::device)
            ?: connector.bondedPods().singleOrNull { device -> pods.model?.let { it in ConnectedNameMatcher.modelsForName(connector.nameOf(device)) } == true }
        log.add("забрать ($reason): ${pods.model?.displayName ?: "?"} → ${device?.address ?: "не нашёл среди сопряжённых"}")
        return device?.let { connect(it) } ?: TakeOverResult.Failed
    }

    /** Если наушники у ПК с нашей программой, просим его отпустить и сразу подключаем сами. */
    private suspend fun connect(device: BluetoothDevice): TakeOverResult {
        link.askRelease(device.address)
        return connector.takeOver(device, log::add)
    }

    /** Забрать сопряжённые наушники по адресу Bluetooth (его присылает программа на ПК). */
    suspend fun takeOverAddress(address: String, reason: String): TakeOverResult {
        val device = connector.bondedPods().firstOrNull { it.address.equals(address, ignoreCase = true) }
        log.add("забрать ($reason): ${device?.let(connector::nameOf) ?: "нет среди сопряжённых наушников"}")
        return device?.let { connect(it) } ?: TakeOverResult.Failed
    }

    fun openOutputSwitcher() = connector.openOutputSwitcher()
}
