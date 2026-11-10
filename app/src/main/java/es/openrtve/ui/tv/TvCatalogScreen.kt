package es.openrtve.ui.tv

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.launch
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import es.openrtve.R
import es.openrtve.domain.CatalogItem
import es.openrtve.domain.RowLayout
import es.openrtve.ui.ClanCatalogUiState
import es.openrtve.ui.ClanSection
import es.openrtve.ui.RootReselectionEffect
import es.openrtve.ui.text

/** Columnas de todas las cuadrículas de la app: títulos y vídeos. */
internal const val TvGridColumns = 4

/**
 * Barra superior, la única navegación de la app: a la izquierda, Series (que es
 * también el inicio) y Películas; a la derecha, Buscar y Ajustes. Está en todas las pantallas.
 */
@Composable
internal fun TvTopBar(
    /** Lista que se está viendo; `null` fuera del inicio (una serie, Ajustes). */
    section: ClanSection?,
    onSection: (ClanSection) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = TvHorizontalMargin, end = TvHorizontalMargin, top = TvVerticalMargin, bottom = 8.dp)
            .focusGroup(),
    ) {
        TvChoiceButton(stringResource(R.string.catalog_series), section == ClanSection.SERIES) { onSection(ClanSection.SERIES) }
        TvChoiceButton(stringResource(R.string.catalog_movies), section == ClanSection.MOVIES) { onSection(ClanSection.MOVIES) }
        TvChoiceButton(stringResource(R.string.catalog_favorites), section == ClanSection.FAVORITES) { onSection(ClanSection.FAVORITES) }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.tab_search)) }
        IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title)) }
    }
}

/**
 * En una cuadrícula, la derecha en la última columna pasa a la primera tarjeta de
 * la fila siguiente y la izquierda en la primera columna, a la última de la fila
 * anterior: la lista se recorre de corrido, como un texto.
 */
@Composable
internal fun Modifier.wrapRowFocus(index: Int, count: Int, columns: Int = TvGridColumns): Modifier {
    val focusManager = LocalFocusManager.current
    return onPreviewKeyEvent { event ->
        when {
            event.type != KeyEventType.KeyDown -> false
            event.key == Key.DirectionRight && index % columns == columns - 1 && index < count - 1 ->
                focusManager.moveFocus(FocusDirection.Next)
            event.key == Key.DirectionLeft && index % columns == 0 && index > 0 ->
                focusManager.moveFocus(FocusDirection.Previous)
            else -> false
        }
    }
}

/** Inicio: todos los títulos de la lista elegida, en orden alfabético y en cuadrícula. */
@Composable
internal fun TvCatalogScreen(
    state: ClanCatalogUiState,
    section: ClanSection,
    onOpen: (CatalogItem) -> Unit,
    onRetry: () -> Unit,
    gridState: LazyGridState,
    focusId: String?,
    onToggleFavorite: (CatalogItem) -> Unit,
    onRemove: (CatalogItem) -> Unit,
    reselectRequest: Int = 0,
) {
    val isFavorites = section == ClanSection.FAVORITES
    val titles = if (isFavorites) state.favorites else state.catalog.titles(section)
    val error = state.error
    when {
        titles.isNotEmpty() -> key(section) {
            TvTitleGrid(
                titles = titles,
                novelties = if (section == ClanSection.SERIES) state.novelties else emptyList(),
                favoriteIds = state.favoriteIds,
                onOpen = onOpen,
                onToggleFavorite = onToggleFavorite,
                onRemove = onRemove,
                gridState = gridState,
                focusId = focusId,
                reselectRequest = reselectRequest,
            )
        }
        isFavorites -> TvCenterMessage(stringResource(R.string.favorites_empty))
        state.isLoading -> TvCenterMessage(stringResource(R.string.state_loading))
        error != null -> TvCenterMessage(error.text(LocalContext.current), onRetry)
        else -> TvCenterMessage(stringResource(R.string.catalog_empty), onRetry)
    }
}

private const val CarouselFocus = Int.MAX_VALUE

@Composable
private fun TvTitleGrid(
    titles: List<CatalogItem>,
    novelties: List<CatalogItem>,
    favoriteIds: Set<String>,
    onOpen: (CatalogItem) -> Unit,
    onToggleFavorite: (CatalogItem) -> Unit,
    onRemove: (CatalogItem) -> Unit,
    gridState: LazyGridState,
    focusId: String?,
    reselectRequest: Int,
) {
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    var focusedIndex by remember { mutableIntStateOf(-1) }
    var menuItem by remember { mutableStateOf<CatalogItem?>(null) }
    // Al volver de una serie, el foco es de la serie que se abrió; si no, del primer título.
    val target = titles.indexOfFirst { it.id == focusId }.coerceAtLeast(0)
    val toStart: () -> Unit = {
        scope.launch {
            gridState.scrollToItem(0)
            withFrameNanos { }
            runCatching { first.requestFocus() }
        }
    }
    // Repetir Inicio vuelve al principio de la lista.
    RootReselectionEffect(reselectRequest) { gridState.scrollToItem(0) }
    // Atrás desde un título o del carrusel lleva al primero; desde el primero sale de la app.
    BackHandler(enabled = focusedIndex != 0 && focusedIndex != -1 && menuItem == null, onBack = toStart)
    ProvideTvCenteredScroll {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(TvGridColumns),
            contentPadding = PaddingValues(start = TvHorizontalMargin, end = TvHorizontalMargin, top = TvFocusScaleRoom, bottom = TvVerticalMargin),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (novelties.isNotEmpty()) {
                item(key = "novelties", span = { GridItemSpan(maxLineSpan) }, contentType = "novelties") {
                    TvNoveltiesRow(
                        items = novelties,
                        favoriteIds = favoriteIds,
                        onOpen = onOpen,
                        onMenu = { menuItem = it },
                        onFocused = { focusedIndex = CarouselFocus },
                    )
                }
            }
            itemsIndexed(titles, key = { _, it -> it.id }, contentType = { _, _ -> "title" }) { index, item ->
                val start = when {
                    index == target -> Modifier.initialFocus(requester = if (index == 0) first else null)
                    index == 0 -> Modifier.focusRequester(first)
                    else -> Modifier
                }
                TvItemCard(
                    item, RowLayout.LANDSCAPE, { onOpen(item) },
                    start
                        .onFocusChanged { if (it.hasFocus) focusedIndex = index else if (focusedIndex == index) focusedIndex = -1 }
                        .wrapRowFocus(index, titles.size),
                    onLongClick = { menuItem = item },
                    favorite = item.id in favoriteIds,
                )
            }
        }
    }
    menuItem?.let { item ->
        TvItemMenu(
            item = item,
            isFavorite = item.id in favoriteIds,
            onFavorite = { onToggleFavorite(item) },
            onRemove = {
                onRemove(item)
                scope.launch {
                    withFrameNanos { }
                    runCatching { first.requestFocus() }
                }
            },
            onDismiss = { menuItem = null },
        )
    }
}

/** Carrusel de novedades: como mucho 10 tarjetas del mismo ancho que las de la cuadrícula. */
@Composable
private fun TvNoveltiesRow(
    items: List<CatalogItem>,
    favoriteIds: Set<String>,
    onOpen: (CatalogItem) -> Unit,
    onMenu: (CatalogItem) -> Unit,
    onFocused: () -> Unit,
) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val cardWidth = (screenWidth - TvHorizontalMargin * 2 - 20.dp * (TvGridColumns - 1)) / TvGridColumns
    Column {
        Text(stringResource(R.string.catalog_novelties), style = MaterialTheme.typography.titleLarge)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(vertical = TvFocusScaleRoom),
        ) {
            items(items, key = { it.id }) { item ->
                TvItemCard(
                    item, RowLayout.LANDSCAPE, { onOpen(item) },
                    Modifier.width(cardWidth).onFocusChanged { if (it.hasFocus) onFocused() },
                    onLongClick = { onMenu(item) },
                    favorite = item.id in favoriteIds,
                )
            }
        }
    }
}

/** Menú de pulsación larga: favoritos y eliminar de la lista. */
@Composable
private fun TvItemMenu(
    item: CatalogItem,
    isFavorite: Boolean,
    onFavorite: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val firstOption = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstOption.requestFocus() } }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .width(380.dp)
                .background(MaterialTheme.colorScheme.surface, TvCardShape)
                .padding(24.dp),
        ) {
            Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
            Button(onClick = { onFavorite(); onDismiss() }, modifier = Modifier.fillMaxWidth().focusRequester(firstOption)) {
                Text(stringResource(if (isFavorite) R.string.menu_remove_favorite else R.string.menu_add_favorite))
            }
            Button(onClick = { onRemove(); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.menu_remove))
            }
        }
    }
}
