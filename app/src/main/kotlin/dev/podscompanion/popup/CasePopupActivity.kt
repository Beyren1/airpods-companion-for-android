package dev.podscompanion.popup

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.podscompanion.data.PodsStatus
import dev.podscompanion.data.popup.CaseOpenDetector
import dev.podscompanion.data.popup.LiveStatus
import dev.podscompanion.ui.battery.CasePopupCard
import dev.podscompanion.ui.theme.PodsCompanionTheme
import javax.inject.Inject
import kotlinx.coroutines.delay

/**
 * Прозрачное окно с карточкой снизу. Его открывает фоновый сервис, когда открыли кейс.
 * Закрывается кнопкой, нажатием мимо карточки, через [CLOSE_AFTER_MS] после закрытия крышки
 * и само через [MAX_SHOWN_MS].
 */
@AndroidEntryPoint
class CasePopupActivity : ComponentActivity() {

    @Inject lateinit var liveStatus: LiveStatus

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PodsCompanionTheme {
                val status by liveStatus.status.collectAsStateWithLifecycle()
                // Последний пакет с открытым кейсом: карточка не мигает, пока окно уезжает.
                var shown by remember { mutableStateOf<PodsStatus?>(null) }
                var visible by remember { mutableStateOf(false) }
                var closing by remember { mutableStateOf(false) }
                val close = { visible = false; closing = true }
                if (!CaseOpenDetector.isClosed(status)) shown = status

                LaunchedEffect(Unit) {
                    visible = true
                    delay(MAX_SHOWN_MS)
                    close()
                }
                LaunchedEffect(CaseOpenDetector.isClosed(status)) {
                    if (CaseOpenDetector.isClosed(status)) {
                        delay(CLOSE_AFTER_MS)
                        close()
                    }
                }
                LaunchedEffect(closing) {
                    if (closing) {
                        delay(ANIMATION_MS)
                        finish()
                    }
                }

                Box(
                    Modifier
                        .fillMaxSize()
                        .clickable(remember { MutableInteractionSource() }, indication = null) { close() },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    AnimatedVisibility(
                        visible = visible && shown != null,
                        enter = slideInVertically { it },
                        exit = slideOutVertically { it },
                    ) {
                        shown?.let { pods ->
                            CasePopupCard(
                                pods, onClose = close,
                                modifier = Modifier
                                    .navigationBarsPadding()
                                    .padding(12.dp)
                                    // Нажатие по карточке не закрывает окно.
                                    .clickable(remember { MutableInteractionSource() }, indication = null) {},
                            )
                        }
                    }
                }
            }
        }
        // Окно открыли, а кейс уже закрыт: показывать нечего.
        if (CaseOpenDetector.isClosed(liveStatus.status.value)) finish()
    }

    companion object {
        private const val MAX_SHOWN_MS = 30_000L
        private const val CLOSE_AFTER_MS = 2_000L
        private const val ANIMATION_MS = 300L
    }
}
