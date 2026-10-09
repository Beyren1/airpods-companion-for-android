package dev.podscompanion.widget

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
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
            GlanceTheme { WidgetContent(snapshot) }
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
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            snapshot?.name ?: context.getString(R.string.app_name),
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
        )
        Spacer(GlanceModifier.height(4.dp))
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
    Column(GlanceModifier.defaultWeight()) {
        Text(label, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp), maxLines = 1)
        Text(
            batteryText(percent, charging),
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp), maxLines = 2)
}

internal fun batteryText(percent: Int?, charging: Boolean): String {
    val value = percent?.let { "$it%" } ?: "—"
    return if (charging && percent != null) "⚡$value" else value
}
