package es.openrtve.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import es.openrtve.R
import es.openrtve.data.CatalogRepository
import es.openrtve.data.WatchEntry
import es.openrtve.data.WatchHistory
import es.openrtve.ui.currentNowMillis
import es.openrtve.domain.CatalogItem
import es.openrtve.domain.HomeLink
import es.openrtve.domain.HomeRow
import es.openrtve.domain.RowLayout
import es.openrtve.domain.SEE_ALL_MIN_ITEMS
import es.openrtve.ui.HomeSection
import es.openrtve.ui.SectionState
import es.openrtve.ui.rememberPortadaScreen
import es.openrtve.ui.RootReselectionEffect
import es.openrtve.ui.text
import es.openrtve.ui.portadaTitle

/**
 * Portada de TV como en JetStream: carrusel destacado arriba y filas horizontales
 * con foco recuperable. El foco inicial va a la primera fila con contenido y, al
 * volver atrás, a la fila en la que estaba el usuario.
 */
@Composable
fun TvPortadaScreen(
    repository: CatalogRepository,
    url: String,
    title: String,
    onOpenItem: (CatalogItem) -> Unit,
    onOpenRow: (HomeRow, String) -> Unit,
    history: WatchHistory? = null,
    onOpenLink: (HomeLink) -> Unit = {},
    /** "Seguir viendo": continuar al pulsar; con pulsación larga, ficha o quitar. */
    onResume: (CatalogItem) -> Unit = onOpenItem,
    onRemoveFromHistory: (CatalogItem) -> Unit = {},
    reselectRequest: Int = 0,
) {
    val screen = rememberPortadaScreen(repository, url, history)
    val viewModel = screen.viewModel
    val state = screen.state
    val resumable = screen.resumable
    // Las filas cargan en paralelo: el foco se coloca una sola vez por entrada en la
    // pantalla (al abrirla o al volver atrás) y nunca se mueve mientras el usuario
    // ya navega. Vuelve a la tarjeta donde estaba; si ya no existe, a su fila; si
    // tampoco, a la primera fila con contenido.
    var focusedRowId by rememberSaveable { mutableStateOf<Int?>(null) }
    var focusedItemKey by rememberSaveable { mutableStateOf<String?>(null) }
    var focusPlaced by remember { mutableStateOf(false) }
    val restoreFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val focusTarget = focusedRowId?.takeIf { id -> id == CONTINUE_ROW_ID && resumable.isNotEmpty() || state.sections.any { it.row.id == id && it.state.isFocusable } }
        ?: state.sections.firstOrNull { it.state.isFocusable }?.row?.id
    val itemTarget = focusedItemKey?.takeIf { key ->
        focusTarget == focusedRowId && key in focusKeys(focusTarget, state.sections, resumable)
    }
    LaunchedEffect(focusTarget, itemTarget) {
        if (focusTarget != null) requestFocusUntilGranted(restoreFocus) { focusPlaced }
    }
    fun rowModifier(rowId: Int): Modifier = Modifier
        .onFocusChanged {
            if (it.hasFocus) {
                focusedRowId = rowId
                focusPlaced = true
            }
        }
        .then(if (rowId == focusTarget && itemTarget == null) Modifier.focusRequester(restoreFocus) else Modifier)
    fun itemModifier(rowId: Int, key: String): Modifier = Modifier
        .onFocusChanged { if (it.hasFocus) focusedItemKey = key }
        .then(if (rowId == focusTarget && key == itemTarget) Modifier.focusRequester(restoreFocus) else Modifier)

    RootReselectionEffect(reselectRequest) {
        focusedRowId = null
        focusedItemKey = null
        focusPlaced = false
        listState.scrollToItem(0)
        if (state.sections.any { it.state.isFocusable }) {
            requestFocusUntilGranted(restoreFocus) { focusPlaced }
        }
    }
    when {
        state.isLoading -> TvCenterMessage(stringResource(R.string.state_loading))
        state.sections.isEmpty() -> TvCenterMessage(
            state.error?.text(LocalContext.current) ?: stringResource(R.string.state_empty_home),
            viewModel::refresh,
        )
        else -> ProvideTvVerticalScroll { LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = TvVerticalMargin),
            verticalArrangement = Arrangement.spacedBy(28.dp),
            modifier = Modifier.fillMaxSize().tvTopFade(),
        ) {
            if (title.isNotBlank()) {
                item(key = "title") { TvScreenTitle(portadaTitle(title, state.title)) }
            } else {
                item(key = "top") { Spacer(Modifier.height(TvVerticalMargin)) }
            }
            val firstId = state.sections.first().row.id
            items(state.sections, key = { it.row.id }, contentType = { it.row.layout }) { section ->
                if (resumable.isNotEmpty() && section.row.id == firstId && section.row.layout != RowLayout.HERO) {
                    TvContinueWatching(resumable, onResume, onOpenItem, onRemoveFromHistory, rowModifier(CONTINUE_ROW_ID)) { itemModifier(CONTINUE_ROW_ID, it) }
                    Spacer(Modifier.height(28.dp))
                }
                TvSection(
                    section = section,
                    // Como en Findroid: las filas se recolocan con animación si otra cambia de alto.
                    modifier = Modifier.animateItem().then(rowModifier(section.row.id)),
                    itemModifier = { key -> itemModifier(section.row.id, key) },
                    onOpenItem = onOpenItem,
                    onOpenLink = onOpenLink,
                    onSeeAll = { onOpenRow(section.row, section.title) },
                    onRetry = { viewModel.retrySection(section.row) },
                )
                if (resumable.isNotEmpty() && section.row.id == firstId && section.row.layout == RowLayout.HERO) {
                    Spacer(Modifier.height(28.dp))
                    TvContinueWatching(resumable, onResume, onOpenItem, onRemoveFromHistory, rowModifier(CONTINUE_ROW_ID)) { itemModifier(CONTINUE_ROW_ID, it) }
                }
            }
        } }
    }
}

@Composable
private fun TvContinueWatching(
    entries: List<WatchEntry>,
    onResume: (CatalogItem) -> Unit,
    onOpenDetails: (CatalogItem) -> Unit,
    onRemove: (CatalogItem) -> Unit,
    modifier: Modifier,
    itemModifier: (key: String) -> Modifier,
) {
    var options by remember { mutableStateOf<CatalogItem?>(null) }
    Column {
        TvSectionTitle(stringResource(R.string.continue_watching))
        Spacer(Modifier.height(12.dp))
        TvLazyRow(
            contentPadding = TvRowPadding,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            modifier = modifier.focusRestorer(),
        ) {
            items(entries, key = { it.item.id }) { entry ->
                TvItemCard(
                    entry.item,
                    RowLayout.LANDSCAPE,
                    { onResume(entry.item) },
                    Modifier.width(TvLandscapeWidth).animateItem().then(itemModifier(entry.item.id)),
                    progress = entry.progress,
                    onLongClick = { options = entry.item },
                )
            }
        }
    }
    options?.let { item ->
        TvContinueWatchingOptions(
            title = item.title,
            onResume = { options = null; onResume(item) },
            onOpenDetails = { options = null; onOpenDetails(item) },
            onRemove = { options = null; onRemove(item) },
            onDismiss = { options = null },
        )
    }
}

/** Menú contextual de una tarjeta de "Seguir viendo", como el de Google TV. */
@Composable
private fun TvContinueWatchingOptions(
    title: String,
    onResume: () -> Unit,
    onOpenDetails: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        // El diálogo es otra "pantalla": su primer botón recibe el foco al abrirse.
        ProvideScreenInitialFocus {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surface, TvCardShape)
                    .padding(24.dp)
                    .width(360.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Button(onClick = onResume, modifier = Modifier.fillMaxWidth().initialFocus()) { Text(stringResource(R.string.action_continue)) }
                Button(onClick = onOpenDetails, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.continue_open_details)) }
                Button(onClick = onRemove, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.continue_remove)) }
            }
        }
    }
}

@Composable
private fun TvSection(
    section: HomeSection,
    modifier: Modifier,
    /** Recuerda qué tarjeta tiene el foco y la recupera al volver (ver TvPortadaScreen). */
    itemModifier: (key: String) -> Modifier,
    onOpenItem: (CatalogItem) -> Unit,
    onOpenLink: (HomeLink) -> Unit,
    onSeeAll: () -> Unit,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    val layout = section.row.layout
    Column {
        if (layout != RowLayout.HERO && section.title.isNotBlank()) {
            TvSectionTitle(section.title)
            Spacer(Modifier.height(12.dp))
        }
        when (val state = section.state) {
            SectionState.Loading -> Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.padding(TvRowPadding),
            ) {
                repeat(if (layout == RowLayout.HERO) 2 else 4) {
                    Box(
                        modifier = Modifier.width(if (layout == RowLayout.HERO) TvHeroWidth else layout.cardWidth())
                            .aspectRatio(layout.aspectRatio)
                            .clip(TvCardShape)
                            .background(MaterialTheme.colorScheme.surface),
                    )
                }
            }
            is SectionState.Failed -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(TvRowPadding),
            ) {
                Text(state.error.text(context), style = MaterialTheme.typography.bodyLarge)
                Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
            }
            // Los enlaces ya están todos en la fila: sin "ver todo".
            is SectionState.Links -> TvLazyRow(
                contentPadding = TvRowPadding,
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                modifier = modifier.focusRestorer(),
            ) {
                items(state.links, key = { it.url }) { link ->
                    TvImageTile(link.title, link.imageUrl, { onOpenLink(link) }, Modifier.width(TvLandscapeWidth).then(itemModifier(link.url)))
                }
            }
            is SectionState.Loaded -> when (layout) {
                RowLayout.HERO -> TvHeroRow(state.items, onOpenItem, modifier, itemModifier)
                RowLayout.FEATURED -> state.items.firstOrNull()?.let { item ->
                    TvFeaturedCard(item, { onOpenItem(item) }, modifier.padding(TvRowPadding))
                }
                else -> TvLazyRow(
                    contentPadding = TvRowPadding,
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    modifier = modifier.focusRestorer(),
                ) {
                    itemsIndexed(state.items, key = { _, it -> it.id }) { index, item ->
                        TvItemCard(
                            item,
                            layout,
                            { onOpenItem(item) },
                            Modifier.width(layout.cardWidth()).then(itemModifier(item.id)),
                            rank = (index + 1).takeIf { layout == RowLayout.RANKED },
                        )
                    }
                    if (state.items.size >= SEE_ALL_MIN_ITEMS) item(key = "see-all") { TvSeeAllCard(layout, onSeeAll) }
                }
            }
        }
    }
}

/**
 * Destacado único (`ColeccionSuperDestacado`): imagen ancha y, al lado, título,
 * descripción y "Ver ahora". Una sola superficie enfocable.
 */
@Composable
private fun TvFeaturedCard(item: CatalogItem, onOpen: () -> Unit, modifier: Modifier) {
    TvFocusSurface(onClick = onOpen, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.width(TvHeroWidth).aspectRatio(16f / 9f)) {
                TvArtwork(item.imageUrl, Modifier.fillMaxSize())
                item.live?.channelLogoUrl?.let { TvChannelLogo(it, Modifier.align(Alignment.TopStart)) }
            }
            Column(
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.weight(1f).padding(horizontal = 24.dp, vertical = 16.dp),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                item.subtitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Text(
                    text = stringResource(R.string.action_watch_now),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
        }
    }
}

/**
 * Destacados como fila de tarjetas anchas con el título superpuesto, al estilo
 * de SmartTube: sin banner a pantalla completa, así se ven varias filas de golpe.
 */
@Composable
private fun TvHeroRow(items: List<CatalogItem>, onOpenItem: (CatalogItem) -> Unit, modifier: Modifier, itemModifier: (key: String) -> Modifier) {
    TvLazyRow(
        contentPadding = TvRowPadding,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        modifier = modifier.focusRestorer(),
    ) {
        items(items, key = { it.id }) { item ->
            TvFocusSurface(onClick = { onOpenItem(item) }, modifier = Modifier.width(TvHeroWidth).aspectRatio(16f / 9f).then(itemModifier(item.id))) {
                TvArtwork(item.imageUrl, Modifier.fillMaxSize())
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.9f))),
                )
                item.live?.channelLogoUrl?.let { TvChannelLogo(it, Modifier.align(Alignment.TopStart)) }
                item.live?.takeIf { it.isOnAir }?.progressAt(currentNowMillis())?.let { TvProgressStrip(it, Modifier.align(Alignment.BottomCenter)) }
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(16.dp),
                ) {
                    if (item.live?.isOnAir == true) TvLiveDot(Modifier.padding(bottom = 4.dp))
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    item.subtitle?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

private val TvHeroWidth = 380.dp

/** Claves de las tarjetas de una fila, para saber si la que tenía el foco sigue ahí. */
private fun focusKeys(rowId: Int?, sections: List<HomeSection>, resumable: List<WatchEntry>): Set<String> = when (rowId) {
    null -> emptySet()
    CONTINUE_ROW_ID -> resumable.mapTo(mutableSetOf()) { it.item.id }
    else -> when (val state = sections.firstOrNull { it.row.id == rowId }?.state) {
        is SectionState.Loaded -> state.items.mapTo(mutableSetOf()) { it.id }
        is SectionState.Links -> state.links.mapTo(mutableSetOf()) { it.url }
        else -> emptySet()
    }
}

/** Las filas que ya pintan algo enfocable: sus tarjetas o sus enlaces. */
private val SectionState.isFocusable: Boolean
    get() = this is SectionState.Loaded || this is SectionState.Links

/** "Seguir viendo" no es una fila de la portada: id propio fuera del rango de las filas. */
private const val CONTINUE_ROW_ID = -2
