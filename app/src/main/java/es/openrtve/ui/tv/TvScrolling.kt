package es.openrtve.ui.tv

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Desplazamiento con el mando, como en Google TV.
 *
 * En TV, Compose deja por defecto lo enfocado al 30 % de la pantalla en los dos
 * ejes. En horizontal está bien (se ve lo anterior y lo siguiente de la fila),
 * pero en vertical la fila anterior quedaba cortada a media altura arriba, con
 * los títulos partidos. Google TV coloca la fila enfocada arriba, con su título
 * entero, y la anterior sale de la pantalla: eso hace [ProvideTvVerticalScroll].
 * Las filas horizontales recuperan el comportamiento estándar con [TvLazyRow].
 */

/** El comportamiento de TV estándar, guardado para las filas horizontales de dentro. */
@OptIn(ExperimentalFoundationApi::class)
private val LocalTvRowSpec = staticCompositionLocalOf<BringIntoViewSpec?> { null }

/**
 * Las listas verticales de [content] dejan lo enfocado a [top] del borde superior:
 * por encima caben el título de su fila (o sus pestañas) y nada de la fila anterior.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ProvideTvVerticalScroll(top: Dp = TvFocusedRowTop, content: @Composable () -> Unit) {
    val rowSpec = LocalTvRowSpec.current ?: LocalBringIntoViewSpec.current
    val topPx = with(LocalDensity.current) { top.toPx() }
    val spec = remember(topPx) { TopAlignedSpec(topPx) }
    CompositionLocalProvider(
        LocalTvRowSpec provides rowSpec,
        LocalBringIntoViewSpec provides spec,
        content = content,
    )
}

/** `LazyRow` con el desplazamiento horizontal estándar de TV aunque esté dentro de [ProvideTvVerticalScroll]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TvLazyRow(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    content: LazyListScope.() -> Unit,
) {
    WithTvRowScroll {
        LazyRow(
            modifier = modifier,
            state = state,
            contentPadding = contentPadding,
            horizontalArrangement = horizontalArrangement,
            verticalAlignment = verticalAlignment,
            content = content,
        )
    }
}

/** Para otros contenedores horizontales (las pestañas de temporada). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun WithTvRowScroll(content: @Composable () -> Unit) {
    val rowSpec = LocalTvRowSpec.current
    if (rowSpec == null) content() else CompositionLocalProvider(LocalBringIntoViewSpec provides rowSpec, content = content)
}

/**
 * Las cuadrículas de [content] mantienen la fila enfocada en el centro de la
 * pantalla: se ve la anterior y la siguiente. Solo la primera fila (y la última)
 * quedan en el borde, porque la lista no puede desplazarse más allá.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ProvideTvCenteredScroll(content: @Composable () -> Unit) {
    val rowSpec = LocalTvRowSpec.current ?: LocalBringIntoViewSpec.current
    val spec = remember { CenteredSpec() }
    CompositionLocalProvider(
        LocalTvRowSpec provides rowSpec,
        LocalBringIntoViewSpec provides spec,
        content = content,
    )
}

/** Centra lo enfocado en el contenedor; los extremos de la lista los limita el propio scroll. */
@OptIn(ExperimentalFoundationApi::class)
private class CenteredSpec : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        offset - (containerSize - size) / 2f
}

/** Lleva lo enfocado a [topPx] del borde; el final de la lista lo limita el propio scroll. */
@OptIn(ExperimentalFoundationApi::class)
private class TopAlignedSpec(private val topPx: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - topPx
}

/** Margen superior + título de fila + separación: la fila enfocada arranca aquí. */
internal val TvFocusedRowTop = 76.dp

/** Aire para que la tarjeta enfocada (ampliada un 8 % con borde y brillo) no se corte en el borde de la lista. */
internal val TvFocusScaleRoom = 16.dp

/**
 * Funde el borde superior con el fondo: lo que sale por arriba al bajar (la última
 * línea de la fila anterior) se desvanece en vez de quedar cortado a ras.
 */
@Composable
internal fun Modifier.tvTopFade(height: Dp = TvVerticalMargin): Modifier {
    val color = androidx.tv.material3.MaterialTheme.colorScheme.background
    return drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.verticalGradient(0f to color, 1f to color.copy(alpha = 0f), endY = height.toPx()),
            size = size.copy(height = height.toPx()),
        )
    }
}
