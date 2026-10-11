package dev.podscompanion.camera

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.provider.MediaStore
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.podscompanion.data.stem.CameraBridge
import timber.log.Timber

/**
 * Служба специальных возможностей для затвора камеры: замечает, что на экране камера, и по нажатию
 * ножки нажимает в ней кнопку съёмки. Окна других приложений служба не читает: только имя
 * приложения на экране и, пока открыта камера, её кнопки.
 */
class CameraShutterService : AccessibilityService() {

    /** Приложения камеры на этом телефоне: те, что умеют «снять фото» по запросу Android. */
    private var cameraPackages: Set<String> = emptySet()

    override fun onServiceConnected() {
        cameraPackages = listOf(
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
            Intent(MediaStore.ACTION_IMAGE_CAPTURE),
        ).flatMap { intent -> runCatching { packageManager.queryIntentActivities(intent, 0) }.getOrDefault(emptyList()) }
            .map { it.activityInfo.packageName }
            .toSet()
        Timber.d("camera apps: %s", cameraPackages)
        CameraBridge.shutter = ::pressShutter
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // Поверх камеры Android рисует значок «камера включена» и шторку: камеру при этом не закрыли.
        if (pkg == SYSTEM_UI) return
        CameraBridge.setCameraOpen(pkg in cameraPackages)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        CameraBridge.shutter = null
        CameraBridge.setCameraOpen(false)
        super.onDestroy()
    }

    /** Найти на экране кнопку съёмки и нажать её. */
    private fun pressShutter(): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() !in cameraPackages) return false
        val button = find(root) { node -> node.viewIdResourceName?.lowercase()?.contains("shutter") == true }
            ?: find(root) { node ->
                val text = node.contentDescription?.toString()?.lowercase() ?: return@find false
                SHUTTER_WORDS.any { it in text }
            }
            ?: return false
        var target: AccessibilityNodeInfo? = button
        while (target != null && !target.isClickable) target = target.parent
        return target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun find(node: AccessibilityNodeInfo, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node.isVisibleToUser && match(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            find(child, match)?.let { return it }
        }
        return null
    }

    private companion object {
        const val SYSTEM_UI = "com.android.systemui"

        /** Как кнопку съёмки подписывают камеры на разных языках (для TalkBack). */
        val SHUTTER_WORDS = listOf(
            "shutter", "take photo", "take picture", "capture",
            "затвор", "сделать фото", "сделать снимок", "снять",
            "déclencheur", "prendre une photo",
        )
    }
}
