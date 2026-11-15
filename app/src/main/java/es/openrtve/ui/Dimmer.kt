package es.openrtve.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import es.openrtve.data.MAX_DIM_LEVEL

/** Opacidad de la capa negra de cada nivel: 0 = nada; el último deja la pantalla casi negra, pero aún legible. */
fun dimAlpha(level: Int): Float = when (level.coerceIn(0, MAX_DIM_LEVEL)) {
    0 -> 0f
    1 -> 0.12f
    2 -> 0.24f
    3 -> 0.36f
    4 -> 0.46f
    5 -> 0.56f
    6 -> 0.65f
    7 -> 0.73f
    8 -> 0.80f
    9 -> 0.86f
    else -> 0.92f
}

/**
 * Atenuador: una capa negra semitransparente encima de todo (también del vídeo). Solo se dibuja:
 * no coge el foco ni los pulsaciones del mando, así que no estorba. Va la última de la pantalla.
 */
@Composable
fun DimOverlay(level: Int) {
    val alpha = dimAlpha(level)
    if (alpha > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = alpha)))
}
