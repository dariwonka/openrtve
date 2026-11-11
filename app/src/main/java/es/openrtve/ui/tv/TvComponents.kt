package es.openrtve.ui.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.filled.Star
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.LocalContext
import es.openrtve.ui.currentNowMillis
import androidx.compose.ui.text.TextStyle
import es.openrtve.ui.scheduleLabel
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import es.openrtve.R
import es.openrtve.domain.CatalogItem
import es.openrtve.domain.LiveInfo
import es.openrtve.domain.RowLayout
import es.openrtve.domain.imageFor

/**
 * Si la pantalla ya dio su foco inicial. Es estado de la pantalla, no del elemento
 * que lo pide: un elemento de una lista perezosa sale de la composición al hacer
 * scroll y, al volver (también cuando la lista lo compone para buscar adónde
 * mover el foco), traía un "foco inicial" nuevo que se lo quitaba al usuario.
 */
@Stable
internal class ScreenInitialFocus {
    var granted by mutableStateOf(false)
}

private val LocalScreenInitialFocus = staticCompositionLocalOf<ScreenInitialFocus?> { null }

/** Un foco inicial por pantalla (o diálogo): se crea al entrar y se olvida al salir. */
@Composable
internal fun ProvideScreenInitialFocus(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalScreenInitialFocus provides remember { ScreenInitialFocus() }, content = content)
}

/**
 * Pide el foco al entrar en la pantalla. Sin esto, al abrir un destino desde el
 * drawer el foco se queda en el drawer y la pantalla parece "muerta". Con
 * [enabled] a `false` (un botón aún deshabilitado) espera a que se pueda enfocar.
 * Solo lo obtiene el primero que lo consigue en cada pantalla.
 */
@Composable
internal fun Modifier.initialFocus(enabled: Boolean = true, requester: FocusRequester? = null): Modifier {
    val screen = LocalScreenInitialFocus.current ?: remember { ScreenInitialFocus() }
    val requester = requester ?: remember { FocusRequester() }
    LaunchedEffect(screen, enabled) {
        if (enabled) requestFocusUntilGranted(requester) { screen.granted }
    }
    return onFocusChanged { if (it.hasFocus) screen.granted = true }.focusRequester(requester)
}

/**
 * Pide el foco hasta que llega, durante un instante. Al abrir la app, Android da su
 * foco inicial (al menú lateral) cuando la ventana gana el foco, después de la
 * primera composición: una sola petición se perdía. [granted] lo confirma quien
 * observa el foco de verdad, no el que la petición no lance excepción.
 */
internal suspend fun requestFocusUntilGranted(requester: FocusRequester, granted: () -> Boolean) {
    repeat(FOCUS_ATTEMPTS) {
        if (granted()) return
        // El destino puede ser un ítem perezoso: se espera a que esté compuesto y medido.
        withFrameNanos { }
        runCatching { requester.requestFocus() }
        delay(FOCUS_RETRY_MS)
    }
}

private const val FOCUS_ATTEMPTS = 20
private const val FOCUS_RETRY_MS = 60L

/* Guía de diseño de TV: márgenes de overscan de 48 dp en horizontal y 27 dp en vertical. */
internal val TvHorizontalMargin = 48.dp
internal val TvVerticalMargin = 27.dp
internal val TvRowPadding = PaddingValues(horizontal = TvHorizontalMargin)
internal val TvCardShape = RoundedCornerShape(12.dp)
/** Color del selector: el borde de la tarjeta enfocada. */
internal val TvFocusColor = Color(0xFFFF1744)

/**
 * Separación entre la imagen de una tarjeta y su título. Al enfocarla, la imagen
 * crece un 8 % (un 4 % hacia abajo: 9 dp en un póster) y lleva un borde de 3 dp
 * y un brillo: con menos aire, el halo del foco tocaba el título.
 */
internal val TvCardCaptionGap = 18.dp

/**
 * Superficie enfocable con la respuesta al foco que piden las guías de TV:
 * escala 1.08 y borde rojo. Es la base de todas las tarjetas.
 */
@Composable
internal fun TvFocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = TvCardShape,
    /** Pulsación larga del botón central del mando: el menú contextual de Google TV. */
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f, pressedScale = 1.08f),
        // Borde rojo y grueso: se distingue sobre cualquier carátula, también las claras.
        // Sin brillo alrededor: su sombra es lo más caro de pintar al mover el foco.
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(4.dp, TvFocusColor), shape = shape),
        ),
        modifier = modifier,
        content = content,
    )
}

@Composable
internal fun TvArtwork(url: String?, modifier: Modifier = Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surface)) {
        url?.let {
            AsyncImage(
                model = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
internal fun TvProgressStrip(progress: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(5.dp)
            .background(Color.White.copy(alpha = 0.35f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(5.dp)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
internal fun TvChannelLogo(url: String, modifier: Modifier = Modifier) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        alignment = Alignment.CenterStart,
        modifier = modifier
            .padding(10.dp)
            .height(26.dp)
            .width(80.dp),
    )
}

@Composable
internal fun TvLiveDot(modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(Color(0xFFE53935)),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.kind_live),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFE53935),
        )
    }
}

/** Tarjeta de contenido según la presentación de la fila. El texto va debajo, fuera de la zona que escala. */
internal val TvCardTitleStyle: TextStyle
    @Composable get() = MaterialTheme.typography.titleSmall

@Composable
internal fun TvItemCard(
    item: CatalogItem,
    layout: RowLayout,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    /** Posición en un ranking ("Lo más visto"); se pinta grande sobre la imagen. */
    rank: Int? = null,
    onLongClick: (() -> Unit)? = null,
    /** Favorito: lleva una estrella roja arriba a la derecha. */
    favorite: Boolean = false,
) {
    val live = item.live
    Column(modifier) {
        if (live == null) {
            TvFocusSurface(onClick = onClick, modifier = Modifier.fillMaxWidth().aspectRatio(layout.aspectRatio), onLongClick = onLongClick) {
                TvArtwork(item.imageFor(layout), Modifier.fillMaxSize())
                rank?.let { TvRankBadge(it, Modifier.align(Alignment.BottomStart)) }
                if (favorite) {
                    androidx.tv.material3.Icon(
                        androidx.compose.material.icons.Icons.Filled.Star,
                        contentDescription = null,
                        tint = TvFocusColor,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp),
                    )
                }
                progress?.let { TvProgressStrip(it, Modifier.align(Alignment.BottomCenter)) }
            }
            Spacer(Modifier.height(TvCardCaptionGap))
            TvCardTitle(item.title, layout)
            if (!layout.isVertical) item.subtitle?.let { TvCardSubtitle(it) }
        } else {
            TvLiveItemCard(item, live, layout, onClick, rank)
        }
    }
}

/**
 * Cuerpo de la tarjeta de un directo. Solo aquí se lee el reloj compartido: así
 * el tick de 30 s recompone las tarjetas de directos y no todas las demás.
 */
@Composable
private fun ColumnScope.TvLiveItemCard(item: CatalogItem, live: LiveInfo, layout: RowLayout, onClick: () -> Unit, rank: Int?) {
    val now = currentNowMillis()
    val context = LocalContext.current
    val upcoming = live.isUpcomingAt(now)
    TvFocusSurface(onClick = onClick, modifier = Modifier.fillMaxWidth().aspectRatio(layout.aspectRatio)) {
        TvArtwork(item.imageFor(layout), Modifier.fillMaxSize())
        live.channelLogoUrl?.let { TvChannelLogo(it, Modifier.align(Alignment.TopStart)) }
        rank?.let { TvRankBadge(it, Modifier.align(Alignment.BottomStart)) }
        live.takeIf { it.isOnAir }?.progressAt(now)?.let { TvProgressStrip(it, Modifier.align(Alignment.BottomCenter)) }
    }
    Spacer(Modifier.height(TvCardCaptionGap))
    if (upcoming) {
        Text(
            text = listOfNotNull(live.scheduleLabel(context, now), live.category).joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    } else {
        TvLiveDot()
    }
    TvCardTitle(item.title, layout)
    if (!layout.isVertical && !upcoming) item.subtitle?.let { TvCardSubtitle(it) }
}

@Composable
private fun TvCardTitle(title: String, layout: RowLayout) {
    Text(
        text = title,
        style = TvCardTitleStyle,
        maxLines = if (layout.isVertical) 2 else 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun TvCardSubtitle(subtitle: String) {
    Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Número de ranking sobre la esquina de la imagen. */
@Composable
internal fun TvRankBadge(rank: Int, modifier: Modifier = Modifier) {
    Text(
        text = rank.toString(),
        style = MaterialTheme.typography.displaySmall,
        fontWeight = FontWeight.Black,
        color = Color.White,
        modifier = modifier
            .padding(start = 10.dp, bottom = 6.dp)
            .background(Color.Black.copy(alpha = 0.55f), TvCardShape)
            .padding(horizontal = 10.dp),
    )
}

@Composable
@OptIn(ExperimentalTvMaterial3Api::class)
internal fun TvChoiceButton(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    // El chip de filtro de Material para TV: seleccionado con su marca y relleno, y
    // con la respuesta al foco estándar. Se activa al pulsar, no al pasar por él.
    FilterChip(selected = selected, onClick = onClick, modifier = modifier) {
        Text(label, maxLines = 1)
    }
}

@Composable
internal fun TvScreenTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = TvHorizontalMargin, end = TvHorizontalMargin, top = TvVerticalMargin, bottom = 16.dp),
    )
}

/**
 * Aviso dentro de la página (cargando, vacío, error con reintento), sin ocupar toda
 * la pantalla. [focusRetry] da el foco al reintento cuando no hay nada más que enfocar.
 */
@Composable
internal fun TvInlineMessage(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    focusRetry: Boolean = false,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        Text(message, style = MaterialTheme.typography.titleMedium)
        if (onRetry != null) {
            Button(onClick = onRetry, modifier = Modifier.initialFocus(enabled = focusRetry)) { Text(stringResource(R.string.action_retry)) }
        }
    }
}

@Composable
internal fun TvCenterMessage(message: String, onRetry: (() -> Unit)? = null) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        Spacer(Modifier.weight(1f))
        Text(message, style = MaterialTheme.typography.titleLarge)
        if (onRetry != null) {
            // Con el mando, un aviso sin foco obliga a buscar el botón a ciegas.
            Button(onClick = onRetry, modifier = Modifier.initialFocus()) { Text(stringResource(R.string.action_retry)) }
        }
        Spacer(Modifier.weight(1f))
    }
}
