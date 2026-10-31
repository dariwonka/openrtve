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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
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
 * Foco inicial de una pantalla: se concede una sola vez cada vez que la pantalla
 * entra en composición. Se crea a nivel de pantalla, no dentro de un ítem de una
 * lista perezosa: así un ítem que sale y vuelve al hacer scroll no roba el foco.
 */
@Stable
internal class InitialFocus {
    val requester = FocusRequester()
    var granted by mutableStateOf(false)
}

@Composable
internal fun rememberInitialFocus(): InitialFocus = remember { InitialFocus() }

/**
 * Pide el foco al entrar en la pantalla. Sin esto, al abrir un destino desde el
 * drawer el foco se queda en el drawer y la pantalla parece "muerta". Con
 * [enabled] a `false` (un botón aún deshabilitado) espera a que se pueda enfocar.
 */
@Composable
internal fun Modifier.initialFocus(focus: InitialFocus = rememberInitialFocus(), enabled: Boolean = true): Modifier {
    LaunchedEffect(focus, enabled) {
        if (enabled) requestFocusUntilGranted(focus.requester) { focus.granted }
    }
    return onFocusChanged { if (it.hasFocus) focus.granted = true }.focusRequester(focus.requester)
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
internal val TvLandscapeWidth = 260.dp
/** Degradado de las tarjetas con el título sobre la imagen. */
private val TvTitleScrim = Brush.verticalGradient(0.3f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))
internal val TvPosterWidth = 150.dp
internal val TvSquareWidth = 180.dp

/**
 * Ancho mínimo de celda en las rejillas de TV. Más estrecho que en las filas: con
 * el menú lateral y los márgenes quedan unos 780 dp útiles y 260 dp solo daban
 * para dos tarjetas enormes; así salen tres apaisadas o cinco pósters.
 */
internal fun RowLayout.gridCellWidth(): Dp = when (this) {
    RowLayout.HERO, RowLayout.FEATURED, RowLayout.LANDSCAPE, RowLayout.RANKED -> 220.dp
    RowLayout.POSTER, RowLayout.POSTER_TALL -> 130.dp
    RowLayout.SQUARE -> 160.dp
}

internal fun RowLayout.cardWidth(): Dp = when (this) {
    RowLayout.HERO, RowLayout.FEATURED, RowLayout.LANDSCAPE, RowLayout.RANKED -> TvLandscapeWidth
    RowLayout.POSTER, RowLayout.POSTER_TALL -> TvPosterWidth
    RowLayout.SQUARE -> TvSquareWidth
}

/**
 * Superficie enfocable con la respuesta al foco que piden las guías de TV:
 * escala 1.1, borde claro y un ligero brillo. Es la base de todas las tarjetas.
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
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface), shape = shape),
        ),
        glow = ClickableSurfaceDefaults.glow(
            focusedGlow = Glow(elevationColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f), elevation = 12.dp),
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

@Composable
internal fun TvLiveBadge(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.kind_live).uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        modifier = modifier
            .padding(10.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFFD32F2F))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
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
) {
    val live = item.live
    Column(modifier) {
        if (live == null) {
            TvFocusSurface(onClick = onClick, modifier = Modifier.fillMaxWidth().aspectRatio(layout.aspectRatio), onLongClick = onLongClick) {
                TvArtwork(item.imageFor(layout), Modifier.fillMaxSize())
                rank?.let { TvRankBadge(it, Modifier.align(Alignment.BottomStart)) }
                progress?.let { TvProgressStrip(it, Modifier.align(Alignment.BottomCenter)) }
            }
            Spacer(Modifier.height(10.dp))
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
    Spacer(Modifier.height(10.dp))
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

/** Última tarjeta de cada fila: abre la rejilla completa sin sacar el foco de la fila. */
@Composable
internal fun TvSeeAllCard(layout: RowLayout, onClick: () -> Unit) {
    Column(Modifier.width(layout.cardWidth())) {
        TvFocusSurface(onClick = onClick, modifier = Modifier.fillMaxWidth().aspectRatio(layout.aspectRatio)) {
            Text(
                text = stringResource(R.string.action_see_all),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
internal fun TvImageTile(title: String, imageUrl: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TvFocusSurface(onClick = onClick, modifier = modifier.aspectRatio(16f / 9f)) {
        TvArtwork(imageUrl, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(TvTitleScrim))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(14.dp),
        )
    }
}

@Composable
internal fun TvChoiceButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Text(label)
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

@Composable
internal fun TvSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(TvRowPadding),
    )
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
