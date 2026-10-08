package dev.podscompanion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import dev.podscompanion.ui.battery.BatteryRoute
import dev.podscompanion.ui.theme.PodsCompanionTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PodsCompanionTheme {
                BatteryRoute(showDebug = BuildConfig.PACKET_LOGGING)
            }
        }
    }
}
