package dev.podscompanion.ui.battery

import android.app.Activity
import android.app.AppOpsManager
import android.companion.AssociationRequest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.SpeakerPhone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.podscompanion.ui.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Что нужно разделу «Звонки». */
data class CallRouteUi(
    val enabled: Boolean = false,
    val backgroundEnabled: Boolean = false,
    val onEnabledChange: (Boolean) -> Unit = {},
    /** Музыка и видео при снятых наушниках — в динамик телефона. */
    val mediaEnabled: Boolean = false,
    val onMediaChange: (Boolean) -> Unit = {},
)

private const val MANAGE_ONGOING_CALLS = "android.permission.MANAGE_ONGOING_CALLS"

private const val OP_MANAGE_ONGOING_CALLS = "android:manage_ongoing_calls"

/**
 * Разрешение выдаётся двумя путями: ролью «часы» (обычная выдача разрешения) или командой adb
 * `appops set … allow` (режим app op). Telecom принимает любой из них, поэтому проверяем оба:
 * обычная проверка разрешения режим app op не видит.
 */
private fun hasCallPermission(context: Context): Boolean {
    if (context.checkSelfPermission(MANAGE_ONGOING_CALLS) == PackageManager.PERMISSION_GRANTED) return true
    val mode = runCatching {
        context.getSystemService(AppOpsManager::class.java)
            .unsafeCheckOpNoThrow(OP_MANAGE_ONGOING_CALLS, Process.myUid(), context.packageName)
    }.getOrNull()
    return mode == AppOpsManager.MODE_ALLOWED
}

/**
 * Звонок при снятых наушниках — в телефон.
 *
 * Менять звук звонка Android разрешает только приложению с «Управлением текущими звонками».
 * Обычному приложению это разрешение даёт связь с устройством через Companion Device Manager
 * в профиле «часы»: при включении система покажет своё окно подтверждения. Если не выйдет,
 * подсказываем команду adb, которой разрешение выдаётся вручную.
 */
@Composable
internal fun CallRouteSection(ui: CallRouteUi, address: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(hasCallPermission(context)) }
    var showManual by rememberSaveable { mutableStateOf(false) }
    // Разрешение могли выдать через adb или отозвать в настройках, пока экран был в фоне.
    LifecycleResumeEffect(Unit) {
        granted = hasCallPermission(context)
        onPauseOrDispose {}
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            Toast.makeText(context, R.string.calls_setup_declined, Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            // Система выдаёт права профиля чуть позже, чем закрывается окно.
            repeat(10) {
                if (hasCallPermission(context)) {
                    granted = true
                    ui.onEnabledChange(true)
                    return@launch
                }
                delay(300)
            }
            showManual = true
        }
    }

    fun enable() {
        when {
            hasCallPermission(context) -> { granted = true; ui.onEnabledChange(true) }
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> showManual = true
            else -> associate(
                context, address,
                onSender = { launcher.launch(IntentSenderRequest.Builder(it).build()) },
                onFailure = { showManual = true },
                profile = AssociationRequest.DEVICE_PROFILE_WATCH,
            )
        }
    }

    val notes = buildList {
        add(stringResource(R.string.calls_to_phone_hint))
        if (!ui.backgroundEnabled) add(stringResource(R.string.gestures_need_background))
        if (ui.enabled && !granted) add(stringResource(R.string.calls_no_permission))
    }
    val mediaNotes = buildList {
        add(stringResource(R.string.media_to_phone_hint))
        if (!ui.backgroundEnabled) add(stringResource(R.string.gestures_need_background))
    }
    SectionTitle(stringResource(R.string.section_off_head))
    SettingsGroup {
        row {
            SwitchRow(
                stringResource(R.string.media_to_phone_title), ui.mediaEnabled, ui.onMediaChange,
                icon = Icons.Filled.SpeakerPhone,
                description = mediaNotes.joinToString("\n"),
            )
        }
        row {
            SwitchRow(
                stringResource(R.string.calls_to_phone_title), ui.enabled,
                { on -> if (on) enable() else ui.onEnabledChange(false) },
                icon = Icons.Filled.PhoneInTalk,
                description = notes.joinToString("\n"),
            )
        }
    }

    if (showManual) {
        val command = "adb shell appops set ${context.packageName} MANAGE_ONGOING_CALLS allow"
        AlertDialog(
            onDismissRequest = { showManual = false },
            title = { Text(stringResource(R.string.calls_setup_failed_title)) },
            text = { SelectionContainer { Text(stringResource(R.string.calls_setup_failed_text, command)) } },
            confirmButton = { TextButton(onClick = { showManual = false }) { Text(stringResource(R.string.ok)) } },
        )
    }
}
