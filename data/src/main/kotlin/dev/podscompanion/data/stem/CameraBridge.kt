package dev.podscompanion.data.stem

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Связь со службой специальных возможностей, которая видит открытую камеру и нажимает в ней
 * кнопку съёмки (сама служба — в модуле app). Без root приложение не может «нажать клавишу»
 * за камеру, а служба может нажать кнопку на экране, как это сделал бы палец.
 */
object CameraBridge {
    /** Имя класса службы: модуль data не видит модуль app, поэтому строкой. */
    const val SERVICE_CLASS = "dev.podscompanion.camera.CameraShutterService"

    private val _open = MutableStateFlow(false)

    /** Сейчас на экране камера. */
    val cameraOpen: StateFlow<Boolean> = _open.asStateFlow()

    /** Нажать кнопку съёмки; null — служба не запущена. Возвращает false, если кнопку не нашли. */
    @Volatile var shutter: (() -> Boolean)? = null

    fun setCameraOpen(open: Boolean) {
        _open.value = open
    }

    /** Служба включена в настройках Android («Специальные возможности»). */
    fun serviceEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val ours = ComponentName(context.packageName, SERVICE_CLASS)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == ours }
    }
}
