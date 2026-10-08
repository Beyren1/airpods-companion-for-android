package dev.podscompanion

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.podscompanion.bluetooth.log.PacketLog
import timber.log.Timber

@HiltAndroidApp
class PodsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        PacketLog.enabled = BuildConfig.PACKET_LOGGING
    }
}
