package es.openrtve.ui.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import es.openrtve.AppContainer
import es.openrtve.R
import es.openrtve.ui.SettingsViewModel
import es.openrtve.ui.formatBytes

@Composable
fun SettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onMessage: (String) -> Unit,
) {
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

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title = stringResource(R.string.settings_title), onBack = onBack)
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SectionLabel(stringResource(R.string.settings_playback))
            SwitchRow(
                title = stringResource(R.string.settings_pip),
                summary = stringResource(R.string.settings_pip_summary),
                checked = settings.pictureInPictureOnLeave,
            ) { value -> viewModel.update { it.copy(pictureInPictureOnLeave = value) } }
            SwitchRow(
                title = stringResource(R.string.settings_autoplay),
                summary = stringResource(R.string.settings_autoplay_summary),
                checked = settings.autoplayNext,
            ) { value -> viewModel.update { it.copy(autoplayNext = value) } }
            ListItem(
                colors = settingsItemColors(),
                headlineContent = { Text(stringResource(R.string.settings_max_quality)) },
                supportingContent = { Text(stringResource(R.string.settings_max_quality_summary)) },
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = ScreenPadding, vertical = 4.dp),
            ) {
                QUALITY_CHOICES.forEach { height ->
                    FilterChip(
                        selected = settings.maxVideoHeight == height,
                        onClick = { viewModel.update { it.copy(maxVideoHeight = height) } },
                        label = { Text(if (height == 0) stringResource(R.string.player_quality_auto) else "${height}p") },
                    )
                }
            }
            SwitchRow(
                title = stringResource(R.string.settings_subtitles),
                summary = stringResource(R.string.settings_subtitles_summary),
                checked = settings.subtitlesByDefault,
            ) { value -> viewModel.update { it.copy(subtitlesByDefault = value) } }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionLabel(stringResource(R.string.settings_storage))
            ListItem(
                colors = settingsItemColors(),
                headlineContent = { Text(stringResource(R.string.settings_clear_cache)) },
                supportingContent = {
                    Text(stringResource(R.string.settings_cache_size, cacheBytes?.let(::formatBytes) ?: "…"))
                },
                modifier = Modifier.clickable { viewModel.clearCache { onMessage(cleared) } },
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionLabel(stringResource(R.string.settings_about))
            ListItem(
                colors = settingsItemColors(),
                headlineContent = { Text(stringResource(R.string.settings_version, versionName)) },
                supportingContent = { Text(stringResource(R.string.settings_about_summary)) },
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp),
    )
}

@Composable
private fun SwitchRow(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    // Toda la fila es el interruptor: un solo objetivo táctil y TalkBack lo anuncia una vez, como interruptor.
    ListItem(
        colors = settingsItemColors(),
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    )
}


private val QUALITY_CHOICES = listOf(0, 1080, 720, 576, 360)

/** Filas sobre el fondo de la pantalla: sin franjas de otro color entre secciones y chips. */
@Composable
private fun settingsItemColors() = ListItemDefaults.colors(containerColor = Color.Transparent)
