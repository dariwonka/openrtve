package es.openrtve.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage

/**
 * Imagen de una tarjeta de categoría o de enlace. RTVE deja a veces imágenes rotas
 * (404) en su configuración: primero se prueba la propia, después [fallbackUrl]
 * (la de la otra configuración) y, si tampoco hay, queda un fondo de marca con
 * el color de la app en vez de una tarjeta vacía.
 */
@Composable
internal fun CategoryArtwork(imageUrl: String?, fallbackUrl: String?, brandColor: Color, modifier: Modifier = Modifier) {
    var current by remember(imageUrl, fallbackUrl) { mutableStateOf(imageUrl ?: fallbackUrl) }
    Box(
        modifier.background(
            Brush.linearGradient(listOf(brandColor, lerp(brandColor, Color.Black, 0.65f))),
        ),
    ) {
        current?.let { url ->
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                onError = { current = fallbackUrl?.takeIf { it != url } },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
