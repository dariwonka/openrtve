package es.openrtve.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import es.openrtve.R
import java.util.Locale

/** Una opción de los menús del reproductor. [apply] la activa sobre el player. */
data class PlayerOption(
    val label: String,
    val selected: Boolean,
    val apply: (Player) -> Unit,
    /** Se llama al pasar el cursor por la fila (vista previa, sin confirmar). */
    val onFocus: (() -> Unit)? = null,
)

data class PlayerMenu(
    val qualities: List<PlayerOption>,
    val speeds: List<PlayerOption>,
    val audios: List<PlayerOption>,
    val subtitles: List<PlayerOption>,
    /** Atenuador de pantalla: niveles de 0 a 10, el mismo para toda la app. */
    val dimmer: List<PlayerOption> = emptyList(),
)

/**
 * Construye los menús a partir de las pistas reales del stream, como hace la app
 * oficial: calidades por altura, audios e idiomas de subtítulos con nombre legible.
 */
fun buildPlayerMenu(context: Context, player: Player, dimLevel: Int? = null, onDimPreview: (Int) -> Unit = {}, onDim: (Int) -> Unit = {}): PlayerMenu {
    val tracks = player.currentTracks
    val params = player.trackSelectionParameters
    return PlayerMenu(
        dimmer = if (dimLevel == null) emptyList() else (0..es.openrtve.data.MAX_DIM_LEVEL).map { level ->
            PlayerOption(label = if (level == 0) "0" else "$level", selected = dimLevel == level, apply = { onDim(level) }, onFocus = { onDimPreview(level) })
        },
        qualities = qualityOptions(context, tracks, player),
        speeds = SPEEDS.map { speed ->
            PlayerOption(
                label = if (speed == 1f) context.getString(R.string.player_speed_normal) else "${speed}x".replace(".0x", "x"),
                selected = player.playbackParameters.speed == speed,
                apply = { it.setPlaybackSpeed(speed) },
            )
        },
        audios = trackOptions(context, tracks, C.TRACK_TYPE_AUDIO, player),
        subtitles = listOf(
            PlayerOption(
                label = context.getString(R.string.player_subtitles_off),
                selected = params.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) ||
                    tracks.groups.none { it.type == C.TRACK_TYPE_TEXT && it.isSelected },
                apply = { p ->
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()
                },
            ),
        ) + trackOptions(context, tracks, C.TRACK_TYPE_TEXT, player),
    )
}

private fun qualityOptions(context: Context, tracks: Tracks, player: Player): List<PlayerOption> {
    val group = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO } ?: return emptyList()
    val hasOverride = player.trackSelectionParameters.overrides.keys.any { it == group.mediaTrackGroup }
    val byHeight = (0 until group.length)
        .filter { group.isTrackSupported(it) }
        .map { index -> group.getTrackFormat(index).height to index }
        .filter { it.first > 0 }
        .sortedByDescending { it.first }
        .distinctBy { it.first }
    if (byHeight.size < 2) return emptyList()
    val auto = PlayerOption(
        label = context.getString(R.string.player_quality_auto),
        selected = !hasOverride,
        apply = { p ->
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                .build()
        },
    )
    return listOf(auto) + byHeight.map { (height, index) ->
        PlayerOption(
            label = "${height}p",
            selected = hasOverride && group.isTrackSelected(index),
            apply = { p ->
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
                    .build()
            },
        )
    }
}

private fun trackOptions(context: Context, tracks: Tracks, type: Int, player: Player): List<PlayerOption> {
    val options = mutableListOf<PlayerOption>()
    val seen = mutableSetOf<String>()
    tracks.groups.filter { it.type == type }.forEach { group ->
        for (index in 0 until group.length) {
            if (!group.isTrackSupported(index)) continue
            val format = group.getTrackFormat(index)
            val label = trackLabel(context, format)
            if (!seen.add(label)) continue
            options += PlayerOption(
                label = label,
                selected = group.isTrackSelected(index),
                apply = { p ->
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(type, false)
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
                        .build()
                },
            )
        }
    }
    return options
}

/** RTVE etiqueta la versión original como `qaa` y la audiodescripción como `ads`. */
private fun trackLabel(context: Context, format: Format): String {
    val language = format.language?.lowercase()
    val base = when {
        language == "qaa" -> context.getString(R.string.player_track_original)
        language == "ads" || format.roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO != 0 -> context.getString(R.string.player_track_audio_description)
        language == null || language == "und" -> format.label ?: context.getString(R.string.player_track_unknown)
        else -> Locale.forLanguageTag(language).getDisplayLanguage(Locale.getDefault()).replaceFirstChar { it.uppercase() }
    }
    val forced = format.selectionFlags and C.SELECTION_FLAG_FORCED != 0
    return if (forced) "$base (${context.getString(R.string.player_track_forced)})" else base
}

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
