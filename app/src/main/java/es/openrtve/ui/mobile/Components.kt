package es.openrtve.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import es.openrtve.ui.currentNowMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import es.openrtve.R
import es.openrtve.domain.CatalogItem
import es.openrtve.domain.ContentKind
import es.openrtve.domain.LiveInfo
import es.openrtve.domain.RowLayout
import es.openrtve.domain.imageFor
import es.openrtve.ui.metaLine
import androidx.compose.ui.text.TextStyle
import es.openrtve.ui.scheduleLabel

internal val CardShape = RoundedCornerShape(10.dp)
internal val FeaturedShape = RoundedCornerShape(14.dp)
private val SquareCornerShape = RoundedCornerShape(0.dp)
/** Degradado de las tarjetas con el título sobre la imagen. */
private val TitleScrim = Brush.verticalGradient(0.3f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f))
internal val ScreenPadding = 16.dp


/** Barra superior común: título, flecha atrás opcional y acciones. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScreenTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    TopAppBar(
        title = { Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            }
        },
        actions = { actions() },
    )
}

/** Imagen con proporción fija sobre un fondo neutro, recortada con [shape]. */
@Composable
internal fun Artwork(
    url: String?,
    aspectRatio: Float,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = CardShape,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    Box(
        modifier = modifier
            .aspectRatio(aspectRatio)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface),
    ) {
        url?.let {
            AsyncImage(
                model = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                // Si hay que recortar, mejor perder los pies que la cara.
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize(),
            )
        }
        overlay()
    }
}

/** Barra fina sobre el borde inferior de la imagen: progreso del directo o de "Seguir viendo". */
@Composable
internal fun ProgressStrip(progress: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(Color.White.copy(alpha = 0.35f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(4.dp)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

/** Logo del canal, arriba a la izquierda, como en la app oficial. */
@Composable
internal fun ChannelLogo(url: String, modifier: Modifier = Modifier) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        alignment = Alignment.CenterStart,
        modifier = modifier
            .padding(8.dp)
            .height(22.dp)
            .width(64.dp),
    )
}

/** "● Directo" con punto rojo. */
@Composable
internal fun LiveDot(modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(Color(0xFFE53935)),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = stringResource(R.string.kind_live),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFE53935),
        )
    }
}

@Composable
internal fun LiveBadge(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.kind_live).uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        modifier = modifier
            .padding(6.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFFD32F2F))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun CatalogItem.liveOverlay(): @Composable BoxScope.() -> Unit =
    if (kind == ContentKind.LIVE) { { LiveBadge(Modifier.align(Alignment.TopStart)) } } else { {} }

/**
 * Tarjeta apaisada 16:9 con texto debajo, como `ItemCard` de Findroid. Los
 * directos llevan el logo del canal, "● Directo" y el progreso de la emisión; los
 * programados, su horario. [progress] pinta el avance de "Seguir viendo".
 */
@Composable
internal fun ItemCard(
    item: CatalogItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    /** Posición en un ranking ("Lo más visto"); se pinta grande sobre la imagen. */
    rank: Int? = null,
) {
    val live = item.live
    Column(modifier = modifier.clip(CardShape).clickable(onClick = onClick)) {
        if (live == null) {
            Artwork(item.imageUrl, 16f / 9f, Modifier.fillMaxWidth()) {
                rank?.let { RankBadge(it, Modifier.align(Alignment.BottomStart)) }
                progress?.let { ProgressStrip(it, Modifier.align(Alignment.BottomCenter)) }
            }
            Spacer(Modifier.height(6.dp))
            CardTitle(item.title)
            item.subtitle?.let { CardSubtitle(it) }
        } else {
            LiveItemCard(item, live, rank)
        }
        Spacer(Modifier.height(4.dp))
    }
}

/**
 * Cuerpo de la tarjeta de un directo. Solo aquí se lee el reloj compartido: así
 * el tick de 30 s recompone las tarjetas de directos y no todas las demás.
 */
@Composable
private fun ColumnScope.LiveItemCard(item: CatalogItem, live: LiveInfo, rank: Int?) {
    val now = currentNowMillis()
    val context = LocalContext.current
    val upcoming = live.isUpcomingAt(now)
    Artwork(item.imageUrl, 16f / 9f, Modifier.fillMaxWidth()) {
        live.channelLogoUrl?.let { ChannelLogo(it, Modifier.align(Alignment.TopStart)) }
        rank?.let { RankBadge(it, Modifier.align(Alignment.BottomStart)) }
        live.takeIf { it.isOnAir }?.progressAt(now)?.let { ProgressStrip(it, Modifier.align(Alignment.BottomCenter)) }
    }
    Spacer(Modifier.height(6.dp))
    if (upcoming) {
        Text(
            text = listOfNotNull(live.scheduleLabel(context, now), live.category).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    } else {
        LiveDot()
    }
    CardTitle(item.title)
    if (!upcoming) item.subtitle?.let { CardSubtitle(it) }
}

@Composable
private fun CardTitle(title: String) {
    Text(
        text = title,
        style = CardTitleStyle,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun CardSubtitle(subtitle: String) {
    Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Número de ranking sobre la esquina de la imagen. */
@Composable
internal fun RankBadge(rank: Int, modifier: Modifier = Modifier) {
    Text(
        text = rank.toString(),
        style = MaterialTheme.typography.displaySmall,
        fontWeight = FontWeight.Black,
        color = Color.White,
        modifier = modifier
            .padding(start = 8.dp, bottom = 4.dp)
            .background(Color.Black.copy(alpha = 0.55f), CardShape)
            .padding(horizontal = 8.dp),
    )
}

/** Tarjeta 16:9 con el título sobre la imagen: categorías de Explorar y enlaces de portada. */
@Composable
internal fun ImageTile(title: String, imageUrl: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Artwork(
        url = imageUrl,
        aspectRatio = 16f / 9f,
        modifier = modifier
            .clip(CardShape)
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.fillMaxSize().background(TitleScrim))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp),
        )
    }
}

/** Póster vertical 2:3 (series, cine). */
@Composable
internal fun PosterCard(item: CatalogItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.clip(CardShape).clickable(onClick = onClick)) {
        Artwork(item.imageFor(RowLayout.POSTER), 2f / 3f, Modifier.fillMaxWidth())
        CardCaption(item.title, subtitle = null)
    }
}

/** Póster alto 1:2 (`ColeccionSuper`), con el recorte `imgCol` del programa; el póster normal es el respaldo. */
@Composable
internal fun TallPosterCard(item: CatalogItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.clip(CardShape).clickable(onClick = onClick)) {
        Artwork(item.imageFor(RowLayout.POSTER_TALL), 1f / 2f, Modifier.fillMaxWidth())
        CardCaption(item.title, subtitle = null)
    }
}

/**
 * Destacado único (`ColeccionSuperDestacado`): imagen, título, descripción y botón,
 * como una tarjeta M3 a ancho completo. El primer item de la colección es el destacado.
 */
@Composable
internal fun FeaturedCard(item: CatalogItem, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(FeaturedShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onOpen),
    ) {
        Artwork(item.imageUrl, 16f / 9f, Modifier.fillMaxWidth(), shape = SquareCornerShape) {
            item.live?.channelLogoUrl?.let { ChannelLogo(it, Modifier.align(Alignment.TopStart)) }
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            item.subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Button(onClick = onOpen, modifier = Modifier.padding(top = 12.dp)) {
                Text(stringResource(R.string.action_watch_now))
            }
        }
    }
}

/** Tarjeta cuadrada (radio, música). */
@Composable
internal fun SquareCard(item: CatalogItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.clip(CardShape).clickable(onClick = onClick)) {
        Artwork(item.imageFor(RowLayout.SQUARE), 1f, Modifier.fillMaxWidth(), overlay = item.liveOverlay())
        CardCaption(item.title, item.subtitle)
    }
}

/** Estilo del título de tarjeta. */
internal val CardTitleStyle: TextStyle
    @Composable get() = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)

/**
 * Texto bajo la tarjeta, con la altura que necesite (como en Findroid). Si la
 * fila cambia de alto al desplazarse, `animateItem()` en la portada suaviza el
 * desplazamiento de lo que hay debajo.
 */
@Composable
private fun CardCaption(title: String, subtitle: String?) {
    Spacer(Modifier.height(6.dp))
    CardTitle(title)
    subtitle?.let { CardSubtitle(it) }
    Spacer(Modifier.height(4.dp))
}

/** Carrusel a ancho completo con título superpuesto e indicador de página ("Slide > Home"). */
@Composable
internal fun HeroPager(
    items: List<CatalogItem>,
    onClick: (CatalogItem) -> Unit,
    /** Sin márgenes ni esquinas: el hero de cabecera llega hasta los bordes y bajo la barra de estado. */
    fullBleed: Boolean = false,
) {
    val pagerState = rememberPagerState { items.size }
    Column {
        HorizontalPager(
            state = pagerState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = if (fullBleed) 0.dp else ScreenPadding),
            pageSpacing = if (fullBleed) 0.dp else 12.dp,
        ) { page ->
            val item = items[page]
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // Siempre 16:9, la proporción de la imagen: los carteles de RTVE llevan el
                    // título dibujado y un recorte más alto se lo comía por los lados.
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(if (fullBleed) 0.dp else 14.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable { onClick(item) },
            ) {
                item.imageUrl?.let {
                    AsyncImage(
                        model = it,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.45f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.85f),
                            ),
                        ),
                )
                item.live?.channelLogoUrl?.let { ChannelLogo(it, Modifier.align(Alignment.TopStart).padding(4.dp)) }
                    ?: if (item.kind == ContentKind.LIVE) LiveBadge(Modifier.align(Alignment.TopStart)) else Unit
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(16.dp),
                ) {
                    if (item.live?.isOnAir == true) LiveDot(Modifier.padding(bottom = 4.dp))
                    // Altura fija de dos líneas: así el subtítulo no salta entre páginas de una y dos líneas.
                    val titleStyle = MaterialTheme.typography.titleLarge
                    val titleBlockHeight = with(LocalDensity.current) { (titleStyle.lineHeight * 2).toDp() }
                    Box(Modifier.height(titleBlockHeight), contentAlignment = Alignment.BottomStart) {
                        Text(
                            text = item.title,
                            style = titleStyle,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    item.subtitle?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (items.size > 1) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                // Centrados: el punto activo es más grande y, alineado arriba, hacía "temblar" la fila.
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 10.dp)
                    .height(8.dp),
            ) {
                repeat(items.size) { index ->
                    val active = index == pagerState.currentPage
                    Box(
                        modifier = Modifier
                            .size(if (active) 8.dp else 6.dp)
                            .clip(CircleShape)
                            .background(
                                if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
                            ),
                    )
                }
            }
        }
    }
}

/**
 * Fila de episodio: miniatura a la izquierda y título + metadatos a la derecha.
 * [progress] pinta lo visto, [badge] ("Visto", "Siguiente") y [highlighted] resaltan.
 */
@Composable
internal fun EpisodeRow(
    item: CatalogItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    badge: String? = null,
    highlighted: Boolean = false,
) {
    val context = LocalContext.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .clickable(onClick = onClick)
            .then(if (highlighted) Modifier.background(MaterialTheme.colorScheme.surface) else Modifier)
            .padding(horizontal = ScreenPadding, vertical = 6.dp),
    ) {
        Artwork(item.imageUrl, 16f / 9f, Modifier.width(140.dp)) {
            progress?.let { ProgressStrip(it, Modifier.align(Alignment.BottomCenter)) }
        }
        Column(modifier = Modifier.weight(1f)) {
            badge?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            item.metaLine(context)?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun LoadingPanel() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
internal fun EmptyPanel(message: String, onRetry: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Text(message)
            if (onRetry != null) {
                Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
            }
        }
    }
}
