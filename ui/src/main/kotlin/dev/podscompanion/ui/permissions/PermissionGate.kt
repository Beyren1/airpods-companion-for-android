package dev.podscompanion.ui.permissions

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.podscompanion.ui.R

/**
 * Показывает [content], только когда есть разрешения на скан. Иначе объясняет, зачем они нужны,
 * и запрашивает их по кнопке (а не сразу при запуске: так просит Google в гайдлайнах).
 */
@Composable
fun ScanPermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ScanPermissions.granted(context)) }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        granted = result.values.all { it }
        denied = !granted
    }

    if (granted) {
        content()
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.perm_scan_title), style = MaterialTheme.typography.headlineSmall)
        val reason = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            R.string.perm_scan_reason
        } else {
            R.string.perm_location_reason
        }
        Text(stringResource(reason), style = MaterialTheme.typography.bodyLarge)
        if (denied) {
            Text(
                stringResource(R.string.perm_denied_hint),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Button(onClick = { launcher.launch(ScanPermissions.required) }) {
            Text(stringResource(R.string.perm_grant))
        }
    }
}
