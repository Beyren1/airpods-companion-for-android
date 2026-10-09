package dev.podscompanion.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import dev.podscompanion.data.settings.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** После перезагрузки телефона поднимает сервис, если фоновый режим включён. */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var settings: SettingsRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // goAsync: даём ресиверу дочитать настройки из DataStore, не блокируя главный поток.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                PodsService.startIfEnabled(context.applicationContext, settings)
            } finally {
                pending.finish()
            }
        }
    }
}
