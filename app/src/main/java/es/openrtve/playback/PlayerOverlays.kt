package es.openrtve.playback

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.toBitmap
import es.openrtve.R
import es.openrtve.domain.PreviewSprite

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

/** Miniatura sobre la barra mientras se arrastra: recorta la región del sprite que toca. */
@Composable
internal fun ScrubPreview(sprite: PreviewSprite, positionMs: Long, fraction: Float) {
    val context = LocalContext.current
    val cue = sprite.cueAt(positionMs) ?: return
    val bitmap by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, sprite.imageUrl) {
        val result = context.imageLoader.execute(ImageRequest.Builder(context).data(sprite.imageUrl).build())
        value = result.image?.toBitmap()
    }
    val image = bitmap ?: return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = 200.dp
        val height = width * cue.height / cue.width.coerceAtLeast(1)
        val maxX = maxWidth - width
        val x = (maxWidth * fraction - width / 2).coerceIn(0.dp, maxX.coerceAtLeast(0.dp))
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(x = x, y = (-92).dp),
        ) {
            Canvas(
                Modifier
                    .size(width, height)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black),
            ) {
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawBitmap(
                        image,
                        android.graphics.Rect(cue.x, cue.y, cue.x + cue.width, cue.y + cue.height),
                        android.graphics.RectF(0f, 0f, size.width, size.height),
                        null,
                    )
                }
            }
            Text(
                text = formatTime(positionMs),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/** "Siguiente: título" al final del episodio, con cuenta atrás si la reproducción automática está activa. */
@Composable
internal fun NextEpisodeCard(title: String, secondsLeft: Int?, onPlay: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Button(
            onClick = onPlay,
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
