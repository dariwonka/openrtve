package es.openrtve.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import es.openrtve.R
import es.openrtve.data.CatalogRepository
import es.openrtve.domain.CatalogItem
import es.openrtve.domain.RowLayout
import es.openrtve.ui.RootReselectionEffect
import es.openrtve.ui.catalogEntryOf
import es.openrtve.ui.rememberSearchScreen
import es.openrtve.ui.text

/**
 * Buscador: el campo arriba y, debajo, lo que RTVE encuentra con el perfil
 * infantil, en la misma cuadrícula que el inicio. Primero las series (abren su
 * página) y después los vídeos sueltos (se reproducen).
 */
@Composable
fun TvSearchScreen(
    repository: CatalogRepository,
    onOpenItem: (CatalogItem) -> Unit,
    knownIds: Set<String>,
    pinnedIds: Set<String>,
    onAdd: (CatalogItem) -> Unit,
    onTogglePin: (CatalogItem) -> Unit,
    onRemove: (CatalogItem) -> Unit,
    isRemovalUnlocked: () -> Boolean,
    onUnlockRemoval: () -> Unit,
    reselectRequest: Int = 0,
) {
    var menuItem by remember { mutableStateOf<CatalogItem?>(null) }
    var pinFor by remember { mutableStateOf<CatalogItem?>(null) }
    val screen = rememberSearchScreen(repository)
    val viewModel = screen.viewModel
    val state = screen.state
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val gridState = rememberLazyGridState()
    RootReselectionEffect(reselectRequest) { gridState.scrollToItem(0) }

    val results = state.results
    val searchError = state.searchError
    // Una serie solo enseña su título; un vídeo conserva debajo el nombre de su serie.
    val found = remember(results) { results?.let { it.programs.map { program -> program.copy(subtitle = null) } + it.videos.filter { v -> v.durationMs?.let { ms -> ms >= es.openrtve.ui.EPISODE_MIN_MS } != false} }.orEmpty().distinctBy { it.kind to it.id } }
    SearchMenus(menuItem, pinFor, knownIds, pinnedIds, { menuItem = it }, { pinFor = it }, onAdd, onTogglePin, onRemove, isRemovalUnlocked, onUnlockRemoval)
    ProvideTvCenteredScroll {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(TvGridColumns),
            contentPadding = PaddingValues(start = TvHorizontalMargin, end = TvHorizontalMargin, top = TvFocusScaleRoom, bottom = TvVerticalMargin),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            fullWidth("field") {
                TvSearchBar(
                    query = state.query,
                    onQueryChange = viewModel::setQuery,
                    // "Buscar" en el teclado lleva a los resultados, como abajo en el mando.
                    onSearch = { focusManager.moveFocus(FocusDirection.Down) },
                    reselectRequest = reselectRequest,
                )
            }
            when {
                !state.isTextSearch -> fullWidth("status") { TvInlineMessage(stringResource(R.string.search_min_length), StatusPadding) }
                searchError != null -> fullWidth("status") { TvInlineMessage(searchError.text(context), StatusPadding, { viewModel.retrySearch() }) }
                results == null -> fullWidth("status") { TvInlineMessage(stringResource(R.string.state_loading), StatusPadding) }
                found.isEmpty() -> fullWidth("status") { TvInlineMessage(stringResource(R.string.search_empty), StatusPadding) }
                else -> {
                    itemsIndexed(found, key = { _, it -> "${it.kind}-${it.id}" }, contentType = { _, _ -> "title" }) { index, item ->
                        val entryId = catalogEntryOf(item)?.id
                        TvItemCard(
                            item, RowLayout.LANDSCAPE, { onOpenItem(item) }, Modifier.wrapRowFocus(index, found.size),
                            onLongClick = if (entryId != null) ({ menuItem = item }) else null,
                            favorite = entryId != null && entryId in pinnedIds,
                        )
                    }
                    if (results.hasMoreVideos) {
                        fullWidth("more") {
                            LaunchedEffect(results.videosPage) { viewModel.loadMoreVideos() }
                            TvInlineMessage(stringResource(R.string.state_loading), StatusPadding)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchMenus(
    menuItem: CatalogItem?,
    pinFor: CatalogItem?,
    knownIds: Set<String>,
    pinnedIds: Set<String>,
    onMenu: (CatalogItem?) -> Unit,
    onPinFor: (CatalogItem?) -> Unit,
    onAdd: (CatalogItem) -> Unit,
    onTogglePin: (CatalogItem) -> Unit,
    onRemove: (CatalogItem) -> Unit,
    isRemovalUnlocked: () -> Boolean,
    onUnlockRemoval: () -> Unit,
) {
    menuItem?.let { item ->
        val entryId = catalogEntryOf(item)?.id
        val inList = entryId != null && entryId in knownIds
        val pinLabel = stringResource(if (entryId != null && entryId in pinnedIds) R.string.menu_unpin else R.string.menu_pin)
        val options = buildList<Pair<String, () -> Unit>> {
            add(pinLabel to { onTogglePin(item) })
            if (inList) {
                add(stringResource(R.string.menu_remove) to { if (isRemovalUnlocked()) onRemove(item) else onPinFor(item) })
            } else {
                add(stringResource(R.string.menu_add) to { onAdd(item) })
            }
        }
        TvItemMenu(title = item.title, options = options, onDismiss = { onMenu(null) })
    }
    pinFor?.let { item ->
        es.openrtve.ui.PinDialog(
            name = item.title,
            unlocked = false,
            onConfirm = {
                onUnlockRemoval()
                onPinFor(null)
                onRemove(item)
            },
            onDismiss = { onPinFor(null) },
        )
    }
}

private fun LazyGridScope.fullWidth(key: String, content: @Composable () -> Unit) {
    item(key = key, contentType = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

/** Los avisos de la búsqueda dejan aire bajo el campo. */
private val StatusPadding = Modifier.padding(vertical = 24.dp)

/**
 * Barra de búsqueda de TV, como la de Google TV: un botón enfocable que muestra lo
 * escrito y que solo al pulsarlo abre el campo y el teclado. Un campo de texto
 * enfocado sin más abría el teclado de la TV al entrar en Buscar y se quedaba
 * con todas las flechas del mando.
 */
@Composable
private fun TvSearchBar(query: String, onQueryChange: (String) -> Unit, onSearch: () -> Unit, reselectRequest: Int) {
    var editing by remember { mutableStateOf(false) }
    RootReselectionEffect(reselectRequest) { editing = true }
    /** Al cerrar el campo: `null` si no se ha editado aún, si no, si hay que bajar a los resultados. */
    var closedGoingDown by remember { mutableStateOf<Boolean?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val field = remember { FocusRequester() }
    val bar = remember { FocusRequester() }
    val hint = stringResource(R.string.search_hint)
    if (!editing) {
        TvFocusSurface(
            onClick = { editing = true },
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .height(56.dp)
                .focusRequester(bar)
                .initialFocus(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
            ) {
                Icon(Icons.Filled.Search, contentDescription = null)
                Spacer(Modifier.width(14.dp))
                Text(
                    text = query.ifEmpty { hint },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (query.isEmpty()) 0.6f else 1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    } else {
        // Al terminar (Buscar en el teclado, atrás o abajo) vuelve a ser la barra.
        var fieldFocused by remember { mutableStateOf(false) }
        fun finish(moveDown: Boolean) {
            if (!editing) return
            keyboard?.hide()
            closedGoingDown = moveDown
            editing = false
        }
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            placeholder = { androidx.compose.material3.Text(hint) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { finish(moveDown = true) }),
            shape = RoundedCornerShape(28.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.onBackground,
                unfocusedBorderColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                focusedTextColor = MaterialTheme.colorScheme.onBackground,
                unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .height(56.dp)
                .focusRequester(field)
                // Se cierra al perder el foco que ya tenía (atrás, o el foco se fue a otra parte).
                .onFocusChanged {
                    if (it.isFocused) fieldFocused = true else if (fieldFocused) finish(moveDown = false)
                }
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                        finish(moveDown = true)
                        true
                    } else {
                        false
                    }
                },
        )
        LaunchedEffect(Unit) {
            field.requestFocus()
            keyboard?.show()
        }
    }
    // Al cerrar el campo el foco vuelve a la barra y, si se buscó, baja a los resultados.
    LaunchedEffect(editing) {
        val goDown = closedGoingDown ?: return@LaunchedEffect
        if (editing) return@LaunchedEffect
        withFrameNanos { }
        runCatching { bar.requestFocus() }
        if (goDown) onSearch()
        closedGoingDown = null
    }
}
