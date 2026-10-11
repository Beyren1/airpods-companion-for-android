package dev.podscompanion.widget

import android.content.Context
import android.os.Build
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProviders
import androidx.glance.color.DynamicThemeColorProviders
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.material3.ColorProviders as Material3ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.podscompanion.MainActivity
import dev.podscompanion.R
import dev.podscompanion.data.snapshot.StatusSnapshot
import dev.podscompanion.data.snapshot.StatusSnapshotStore

/**
 * Виджет на рабочем столе: заряд левого, правого и кейса (у Max — один заряд).
 * Glance — это Compose для виджетов: тот же стиль кода, но рисует система, а не приложение.
 * Данные берёт из [StatusSnapshotStore], который обновляет фоновый сервис.
 */
class PodsWidget : GlanceAppWidget() {

    /** Hilt не умеет внедрять зависимости в виджет напрямую: берём их через EntryPoint. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun snapshotStore(): StatusSnapshotStore
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = EntryPointAccessors.fromApplication(context, Dependencies::class.java).snapshotStore()
        val initial = store.current()
        provideContent {
            val snapshot by store.snapshot.collectAsState(initial)
            GlanceTheme(colors = widgetColors()) { WidgetContent(snapshot) }
        }
    }
}

class PodsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PodsWidget()
}

@Composable
private fun WidgetContent(snapshot: StatusSnapshot?) {
    val context = LocalContext.current
    Column(
        GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(20.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            snapshot?.name ?: context.getString(R.string.app_name),
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
        )
        Spacer(GlanceModifier.height(2.dp))
        when {
            snapshot == null -> Hint(context.getString(R.string.widget_no_data))
            snapshot.single -> Row(GlanceModifier.fillMaxWidth()) {
                Cell(context.getString(R.string.widget_battery), snapshot.left, snapshot.leftCharging)
            }
            else -> Row(GlanceModifier.fillMaxWidth()) {
                Cell(context.getString(R.string.widget_left), snapshot.left, snapshot.leftCharging)
                Cell(context.getString(R.string.widget_right), snapshot.right, snapshot.rightCharging)
                Cell(context.getString(R.string.widget_case), snapshot.case, snapshot.caseCharging)
            }
        }
        if (snapshot != null && !snapshot.nearby) {
            val time = DateFormat.getTimeFormat(context).format(snapshot.updatedAtMs)
            Hint(context.getString(R.string.widget_last_seen, time))
        }
    }
}

@Composable
private fun androidx.glance.layout.RowScope.Cell(label: String, percent: Int?, charging: Boolean) {
    val context = LocalContext.current
    val low = percent != null && percent <= LOW_PERCENT && !charging
    // Цифры и полоска — главным цветом из обоев, разряженный наушник — красным.
    val accent = if (low) GlanceTheme.colors.error else GlanceTheme.colors.primary
    Column(GlanceModifier.defaultWeight().padding(end = 8.dp)) {
        Text(label, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp), maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                percent?.let { "$it%" } ?: "—",
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            if (charging && percent != null) {
                Image(
                    ImageProvider(R.drawable.ic_widget_bolt),
                    contentDescription = context.getString(R.string.widget_charging),
                    modifier = GlanceModifier.size(18.dp).padding(start = 2.dp),
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                )
            }
        }
        if (percent != null) {
            Spacer(GlanceModifier.height(3.dp))
            LinearProgressIndicator(
                progress = percent / 100f,
                modifier = GlanceModifier.fillMaxWidth().height(4.dp),
                color = accent,
                backgroundColor = GlanceTheme.colors.secondaryContainer,
            )
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp), maxLines = 2)
}

private const val LOW_PERCENT = 20

/**
 * Цвета виджета. На Android 12+ — из обоев, как у системных виджетов Pixel: система сама
 * перекрашивает виджет при смене обоев или тёмной темы. На Android 10–11 цветов из обоев нет,
 * берём свои синие, в тон значку приложения, отдельно для светлой и тёмной темы.
 */
private fun widgetColors(): ColorProviders =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) DynamicThemeColorProviders else FallbackColors

private val FallbackColors = Material3ColorProviders(
    light = lightColorScheme(
        primary = Color(0xFF1B63A8),
        onPrimary = Color.White,
        secondaryContainer = Color(0xFFD6E4F5),
        surface = Color(0xFFF7F9FD),
        onSurface = Color(0xFF181C21),
        onSurfaceVariant = Color(0xFF42474E),
        background = Color(0xFFEAF1FB),
        error = Color(0xFFBA1A1A),
    ),
    dark = darkColorScheme(
        primary = Color(0xFFA2CAFF),
        onPrimary = Color(0xFF00315C),
        secondaryContainer = Color(0xFF2B3A4C),
        surface = Color(0xFF101418),
        onSurface = Color(0xFFE0E2E8),
        onSurfaceVariant = Color(0xFFC2C7CF),
        background = Color(0xFF0B1D2E),
        error = Color(0xFFFFB4AB),
    ),
)

/** Короткая строка заряда для плитки в шторке, где можно показать только текст. */
internal fun batteryText(percent: Int?, charging: Boolean): String {
    val value = percent?.let { "$it%" } ?: "—"
    return if (charging && percent != null) "⚡$value" else value
}
