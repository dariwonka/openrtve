package es.openrtve.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.openrtve.R

/**
 * Panel de ajustes del reproductor, como el de RTVE Play: calidad, velocidad,
 * audio y subtítulos en una sola hoja. Panel lateral con filas enfocables, así
 * sirve igual para el táctil y para el mando.
 */
@Composable
internal fun PlayerSettingsPanel(menu: PlayerMenu, onSelect: (PlayerOption) -> Unit, onDismiss: () -> Unit) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    val density = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density * 0.75f, density.fontScale),
    ) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(320.dp)
                .background(Color(0xF0141620))
                .verticalScroll(rememberScrollState())
                .padding(vertical = 16.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            val sections = listOf(
                stringResource(R.string.player_quality) to menu.qualities,
                stringResource(R.string.player_audio_track) to menu.audios,
                stringResource(R.string.player_subtitles) to menu.subtitles,
            ).filter { it.second.isNotEmpty() }
            sections.forEachIndexed { sectionIndex, (title, options) ->
                SectionTitle(title)
                options.forEachIndexed { index, option ->
                    val focus = if (sectionIndex == 0 && index == 0) Modifier.focusRequester(firstFocus) else Modifier
                    OptionRow(option, focus) { onSelect(option) }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
    )
}

@Composable
private fun OptionRow(option: PlayerOption, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .background(if (focused) Color(0xFFFF1744) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Text(
            text = option.label,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            modifier = Modifier.weight(1f),
        )
        if (option.selected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = if (focused) Color.White else MaterialTheme.colorScheme.primary)
        }
    }
}

/** Punto actual al saltar con el mando, en grande, y los minutos de diferencia con el de partida. */
@Composable
internal fun SeekIndicator(positionMs: Long, deltaMs: Long) {
    val minutes = Math.round(deltaMs / 60_000.0)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize().wrapContentSize(Alignment.Center),
    ) {
        Text(
            text = formatTime(positionMs),
            color = Color.White,
            fontSize = 72.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.55f), MaterialTheme.shapes.medium)
                .padding(horizontal = 28.dp, vertical = 8.dp),
        )
        Text(
            text = "${if (minutes >= 0) "+" else "−"}${kotlin.math.abs(minutes)} min",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .padding(top = 8.dp)
                .background(Color.Black.copy(alpha = 0.55f), MaterialTheme.shapes.small)
                .padding(horizontal = 14.dp, vertical = 4.dp),
        )
    }
}

/**
 * "Siguiente: título" al final del episodio, con cuenta atrás si la reproducción automática
 * está activa. Es el botón de siguiente: va siempre marcado (borde blanco) porque OK lo pulsa
 * sin tener que mover el cursor (lo gestiona `PlayerActivity.dispatchKeyEvent`).
 */
@Composable
internal fun NextEpisodeCard(title: String, secondsLeft: Int?, onPlay: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Button(
            onClick = onPlay,
            border = androidx.compose.foundation.BorderStroke(3.dp, Color.White),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 24.dp, bottom = 96.dp),
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (secondsLeft != null) stringResource(R.string.player_next_in, title, secondsLeft)
                else stringResource(R.string.player_next, title),
            )
        }
    }
}

internal fun formatTime(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
