package es.openrtve.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import es.openrtve.AppContainer
import es.openrtve.R
import es.openrtve.ui.SettingsViewModel
import es.openrtve.ui.formatBytes

@Composable
fun TvSettingsScreen(container: AppContainer, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val viewModel: SettingsViewModel = viewModel(
        factory = viewModelFactory {
            initializer { SettingsViewModel(container.settings, container.documentCache, context.applicationContext) }
        },
    )
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val cacheBytes by viewModel.cacheBytes.collectAsStateWithLifecycle()
    val cleared = stringResource(R.string.settings_cache_cleared)
    val versionName = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?" }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(TvRowPadding)
            .padding(vertical = TvVerticalMargin),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        TvSettingsLabel(stringResource(R.string.settings_playback))
        TvSwitchRow(stringResource(R.string.settings_pip), stringResource(R.string.settings_pip_summary), settings.pictureInPictureOnLeave, Modifier.initialFocus()) { v ->
            viewModel.update { it.copy(pictureInPictureOnLeave = v) }
        }
        TvSwitchRow(stringResource(R.string.settings_autoplay), stringResource(R.string.settings_autoplay_summary), settings.autoplayNext) { v ->
            viewModel.update { it.copy(autoplayNext = v) }
        }
        Text(stringResource(R.string.settings_max_quality), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.settings_max_quality_summary), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(0, 1080, 720, 576, 360).forEach { height ->
                TvChoiceButton(if (height == 0) stringResource(R.string.player_quality_auto) else "${height}p", settings.maxVideoHeight == height) {
                    viewModel.update { it.copy(maxVideoHeight = height) }
                }
            }
        }
        TvSwitchRow(stringResource(R.string.settings_subtitles), stringResource(R.string.settings_subtitles_summary), settings.subtitlesByDefault) { v ->
            viewModel.update { it.copy(subtitlesByDefault = v) }
        }
        Spacer(Modifier.height(8.dp))
        TvSettingsLabel(stringResource(R.string.settings_storage))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = { viewModel.clearCache { onMessage(cleared) } }) { Text(stringResource(R.string.settings_clear_cache)) }
            Text(stringResource(R.string.settings_cache_size, cacheBytes?.let(::formatBytes) ?: "…"), style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(Modifier.height(8.dp))
        TvSettingsLabel(stringResource(R.string.settings_about))
        Text(stringResource(R.string.settings_version, versionName), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.settings_about_summary), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
    }
}

@Composable
private fun TvSettingsLabel(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
}

/** `ListItem` de tv-material: toda la fila es enfocable y el centro del mando conmuta el interruptor. */
@Composable
private fun TvSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        selected = false,
        onClick = { onChange(!checked) },
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = modifier.fillMaxWidth(0.7f),
    )
}
