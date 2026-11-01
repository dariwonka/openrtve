package es.openrtve.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import es.openrtve.R
import es.openrtve.data.CatalogRepository
import es.openrtve.data.WatchHistory
import androidx.compose.runtime.remember
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

/**
 * Portada al estilo Findroid: lista vertical de secciones, cada una con su propio
 * carrusel. La raíz no lleva barra superior: el hero llega hasta el borde y la
 * marca y los ajustes flotan sobre él.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortadaScreen(
    repository: CatalogRepository,
    url: String,
    title: String,
    onBack: (() -> Unit)?,
    onOpenItem: (CatalogItem) -> Unit,
    onOpenRow: (HomeRow, String) -> Unit,
    onOpenSettings: (() -> Unit)? = null,
    history: WatchHistory? = null,
    onOpenLink: (HomeLink) -> Unit = {},
    /** "Seguir viendo": continuar al tocar y quitar desde el menú de la tarjeta. */
    onResume: (CatalogItem) -> Unit = onOpenItem,
    onRemoveFromHistory: (CatalogItem) -> Unit = {},
    reselectRequest: Int = 0,
) {
    val screen = rememberPortadaScreen(repository, url, history)
    val viewModel = screen.viewModel
    val state = screen.state
    val resumable = screen.resumable
    val isRoot = onBack == null

    Column(Modifier.fillMaxSize()) {
        if (!isRoot) {
            ScreenTopBar(title = title.ifBlank { state.title }, onBack = onBack)
        }
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            val startsWithHero = state.sections.firstOrNull()?.row?.layout == RowLayout.HERO
            val listState = rememberLazyListState()
            RootReselectionEffect(reselectRequest) { listState.animateScrollToItem(0) }
            // La marca flota sobre el hero; en cuanto se hace scroll desaparece para no tapar tarjetas.
            val showBrand by remember {
                derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 200 }
            }
            when {
                state.isLoading -> LoadingPanel()
                // Sin conexión no es "portada vacía": se dice qué falló.
                state.sections.isEmpty() -> EmptyPanel(
                    state.error?.text(LocalContext.current) ?: stringResource(R.string.state_empty_home),
                    viewModel::refresh,
                )
                else -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (isRoot && !startsWithHero) {
                        item(key = "top-inset") { Spacer(Modifier.height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 40.dp)) }
                    }
                    if (state.isStale) {
                        item(key = "stale") { StaleBanner() }
                    }
                    val heroFirst = startsWithHero && state.sections.first().state !is SectionState.Failed
                    if (resumable.isNotEmpty() && !heroFirst) {
                        item(key = "continue") { ContinueWatchingRow(resumable, onResume, onOpenItem, onRemoveFromHistory, Modifier.animateItem()) }
                    }
                    items(state.sections, key = { it.row.id }) { section ->
                        SectionView(
                            section = section,
                            fullBleedHero = isRoot && section.row.id == state.sections.first().row.id,
                            onOpenItem = onOpenItem,
                            onOpenLink = onOpenLink,
                            onSeeAll = { onOpenRow(section.row, section.title) },
                            onRetry = { viewModel.retrySection(section.row) },
                            // Como en Findroid: si una fila cambia de alto, las de debajo se desplazan animadas.
                            modifier = Modifier.animateItem(),
                        )
                        // Tras el hero, como en la app oficial y en Findroid.
                        if (heroFirst && resumable.isNotEmpty() && section.row.id == state.sections.first().row.id) {
                            Spacer(Modifier.height(22.dp))
                            ContinueWatchingRow(resumable, onResume, onOpenItem, onRemoveFromHistory)
                        }
                    }
                }
            }
            if (isRoot) {
                val brandVisible = showBrand || state.isLoading || state.sections.isEmpty()
                androidx.compose.animation.AnimatedVisibility(
                    visible = brandVisible,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.TopCenter),
                ) {
                    FloatingBrandBar(onOpenSettings)
                }
                // Sin la marca, el contenido pasaría por debajo de la hora y los iconos:
                // la barra de estado lleva el fondo de la pantalla, como en Material 3.
                androidx.compose.animation.AnimatedVisibility(
                    visible = !brandVisible,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.TopCenter),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .windowInsetsTopHeight(WindowInsets.statusBars)
                            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.92f)),
                    )
                }
            }
        }
    }
}

/** Marca y ajustes sobre un degradado, en lugar de una barra de aplicación que solo ocupa. */
@Composable
private fun FloatingBrandBar(onOpenSettings: (() -> Unit)?) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.7f), 1f to Color.Transparent))
            .statusBarsPadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = ScreenPadding, end = 4.dp)
                .height(48.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            if (onOpenSettings != null) {
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title), tint = Color.White)
                }
            }
        }
    }
}

@Composable
internal fun StaleBanner() {
    Text(
        text = stringResource(R.string.state_stale),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp),
    )
}

@Composable
private fun SectionView(
    section: HomeSection,
    fullBleedHero: Boolean,
    onOpenItem: (CatalogItem) -> Unit,
    onOpenLink: (HomeLink) -> Unit,
    onSeeAll: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val layout = section.row.layout
    val isLinks = section.state is SectionState.Links
    Column(modifier) {
        // El hero no lleva cabecera: la imagen y el título ya son la cabecera.
        if (layout != RowLayout.HERO && section.title.isNotBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = ScreenPadding, end = 4.dp),
            ) {
                Text(
                    text = section.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // Los enlaces ya están todos en la fila, y una fila corta se ve entera: sin "ver todo".
                if (!isLinks && ((section.state as? SectionState.Loaded)?.items?.size ?: 0) >= SEE_ALL_MIN_ITEMS) {
                    IconButton(onClick = onSeeAll) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.action_see_all))
                    }
                }
            }
        }
        when (val state = section.state) {
            SectionState.Loading -> PlaceholderRow(layout, fullBleedHero)
            is SectionState.Links -> LazyRow(
                contentPadding = PaddingValues(horizontal = ScreenPadding),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.links, key = { it.url }) { link ->
                    ImageTile(link.title, link.imageUrl, { onOpenLink(link) }, Modifier.width(LandscapeWidth))
                }
            }
            is SectionState.Failed -> Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = ScreenPadding),
            ) {
                Text(
                    text = state.error.text(context),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
            }
            is SectionState.Loaded -> when (layout) {
                RowLayout.HERO -> HeroPager(state.items, onOpenItem, fullBleed = fullBleedHero)
                RowLayout.FEATURED -> state.items.firstOrNull()?.let { item ->
                    FeaturedCard(item, { onOpenItem(item) }, Modifier.padding(horizontal = ScreenPadding).fillMaxWidth())
                }
                else -> LazyRow(
                    contentPadding = PaddingValues(horizontal = ScreenPadding),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(state.items, key = { _, it -> it.id }) { index, item ->
                        when (layout) {
                            RowLayout.POSTER -> PosterCard(item, { onOpenItem(item) }, Modifier.width(PosterWidth))
                            RowLayout.POSTER_TALL -> TallPosterCard(item, { onOpenItem(item) }, Modifier.width(PosterWidth))
                            RowLayout.SQUARE -> SquareCard(item, { onOpenItem(item) }, Modifier.width(SquareWidth))
                            RowLayout.RANKED -> ItemCard(item, { onOpenItem(item) }, Modifier.width(LandscapeWidth), rank = index + 1)
                            RowLayout.LANDSCAPE, RowLayout.HERO, RowLayout.FEATURED -> ItemCard(item, { onOpenItem(item) }, Modifier.width(LandscapeWidth))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaceholderRow(layout: RowLayout, fullBleedHero: Boolean) {
    // Los dos destacados ocupan el ancho; solo el hero de cabecera va a sangre.
    if (layout == RowLayout.HERO || layout == RowLayout.FEATURED) {
        val fullBleed = fullBleedHero && layout == RowLayout.HERO
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (fullBleed) 0.dp else ScreenPadding)
                .aspectRatio(16f / 9f)
                .clip(if (fullBleed) RectangleShape else CardShape)
                .background(MaterialTheme.colorScheme.surface),
        )
        return
    }
    val width = when (layout) {
        RowLayout.POSTER, RowLayout.POSTER_TALL -> PosterWidth
        RowLayout.SQUARE -> SquareWidth
        RowLayout.LANDSCAPE, RowLayout.RANKED, RowLayout.HERO, RowLayout.FEATURED -> LandscapeWidth
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(horizontal = ScreenPadding),
    ) {
        repeat(3) {
            Box(
                modifier = Modifier
                    .width(width)
                    .aspectRatio(layout.aspectRatio)
                    .clip(CardShape)
                    .background(MaterialTheme.colorScheme.surface),
            )
        }
    }
}

private val LandscapeWidth = 220.dp
private val PosterWidth = 130.dp
private val SquareWidth = 150.dp
