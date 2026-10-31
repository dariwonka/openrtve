package es.openrtve.ui.tv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import es.openrtve.R
import es.openrtve.domain.CatalogItem
import es.openrtve.domain.RowLayout
import es.openrtve.ui.FichaFacts
import es.openrtve.ui.metaLine

/**
 * Ficha de TV según la página de detalles de Google TV y el ejemplo JetStream de
 * Compose for TV: el fondo a pantalla completa y, encima, una página con scroll
 * vertical. Arriba, la cabecera (título, datos, sinopsis y botones) ocupa casi
 * toda la pantalla y deja asomar la primera fila; debajo, una fila por sección
 * con su título. El foco baja de fila en fila y la página se desplaza sola.
 */
@Composable
internal fun TvDetailsScaffold(
    backdropUrl: String?,
    header: @Composable ColumnScope.() -> Unit,
    sections: LazyListScope.() -> Unit,
) {
    val background = MaterialTheme.colorScheme.background
    val listState = rememberLazyListState()
    // Al bajar a las filas el fondo se oscurece: entonces lo importante es el contenido.
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 } }
    val dim by animateFloatAsState(if (scrolled) 0.75f else 0f, label = "tv-details-dim")
    val scope = rememberCoroutineScope()
    Box(
        Modifier
            .fillMaxSize()
            .background(background),
    ) {
        AsyncImage(
            model = backdropUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                // Algunos carteles son casi blancos: el lado derecho conserva un velo.
                .background(Brush.horizontalGradient(0f to background, 0.55f to background.copy(alpha = 0.8f), 1f to background.copy(alpha = 0.35f))),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.5f to background.copy(alpha = 0f), 1f to background)),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(background.copy(alpha = dim)),
        )
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = TvVerticalMargin),
            verticalArrangement = Arrangement.spacedBy(32.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "header") {
                Column(
                    verticalArrangement = Arrangement.Bottom,
                    modifier = Modifier
                        // Con el foco en la cabecera, la página entera vuelve arriba: el
                        // título no puede quedar cortado por haber bajado antes a una fila.
                        .onFocusChanged { if (it.hasFocus) scope.launch { listState.animateScrollToItem(0) } }
                        .fillParentMaxHeight(HEADER_FRACTION)
                        .fillMaxWidth(0.6f)
                        .padding(TvRowPadding)
                        .padding(top = TvVerticalMargin),
                    content = header,
                )
            }
            sections()
        }
    }
}

/** Una sección de la ficha: título y su contenido (normalmente una fila horizontal). */
internal fun LazyListScope.tvDetailsSection(key: String, title: @Composable () -> String, content: @Composable ColumnScope.() -> Unit) {
    item(key = key) {
        Column {
            TvSectionTitle(title())
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

/** Fila de capítulos o extras: tarjetas apaisadas con título y datos, que se reproducen al pulsar. */
@Composable
internal fun TvEpisodesRow(
    items: List<CatalogItem>,
    onPlay: (CatalogItem) -> Unit,
    progressOf: (CatalogItem) -> Float? = { null },
    badgeOf: @Composable (CatalogItem) -> String? = { null },
    hasMore: Boolean = false,
    isLoadingMore: Boolean = false,
    onLoadMore: () -> Unit = {},
    listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
) {
    LazyRow(
        state = listState,
        contentPadding = TvRowPadding,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.focusRestorer(),
    ) {
        items(items, key = { it.id }) { item ->
            TvEpisodeCard(
                item = item,
                onClick = { onPlay(item) },
                modifier = Modifier.width(TvEpisodeWidth),
                progress = progressOf(item),
                badge = badgeOf(item),
            )
        }
        if (hasMore) {
            item(key = "more") {
                TvFocusSurface(onClick = onLoadMore, modifier = Modifier.width(TvEpisodeWidth).aspectRatio(16f / 9f)) {
                    Text(
                        stringResource(if (isLoadingMore) R.string.state_loading else R.string.program_load_more),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }
    }
}

/** Fila de similares: pósters que abren su ficha. */
@Composable
internal fun TvRelatedRow(items: List<CatalogItem>, onOpen: (CatalogItem) -> Unit) {
    LazyRow(
        contentPadding = TvRowPadding,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.focusRestorer(),
    ) {
        items(items, key = { it.id }) { item ->
            TvItemCard(item, RowLayout.POSTER, { onOpen(item) }, Modifier.width(TvPosterWidth))
        }
    }
}

/** Ficha técnica en filas etiqueta/valor, a la izquierda como la cabecera. */
@Composable
internal fun TvFactsPanel(facts: FichaFacts) {
    // Enfocable aunque solo sea texto: con el mando es la única forma de que la
    // página baje hasta aquí; si no, el reparto quedaba cortado e inalcanzable.
    Column(
        Modifier
            .fillMaxWidth(0.6f)
            .padding(TvRowPadding)
            .focusable(),
    ) {
        facts.rows.forEach { (label, value) ->
            Row(Modifier.padding(vertical = 4.dp)) {
                Text(
                    text = stringResource(label),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    modifier = Modifier.width(180.dp),
                )
                Text(text = value, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TvEpisodeCard(
    item: CatalogItem,
    onClick: () -> Unit,
    modifier: Modifier,
    progress: Float? = null,
    badge: String? = null,
) {
    val context = LocalContext.current
    Column(modifier) {
        TvFocusSurface(onClick = onClick, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            TvArtwork(item.imageUrl, Modifier.fillMaxSize())
            progress?.let { TvProgressStrip(it, Modifier.align(Alignment.BottomCenter)) }
        }
        Spacer(Modifier.height(10.dp))
        badge?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        item.metaLine(context)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Parte de la pantalla que ocupa la cabecera: la primera fila asoma por debajo. */
private const val HEADER_FRACTION = 0.74f
internal val TvEpisodeWidth = 300.dp
