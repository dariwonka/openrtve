package es.openrtve.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import es.openrtve.R
import es.openrtve.data.CatalogRepository
import es.openrtve.domain.CatalogItem
import es.openrtve.domain.RowLayout
import es.openrtve.ui.FilterState
import es.openrtve.ui.rememberSearchScreen
import es.openrtve.ui.text

/** Búsqueda en TV: campo arriba, filtros rápidos en una fila desplazable y rejilla debajo. */
@Composable
fun TvSearchScreen(
    repository: CatalogRepository,
    onOpenItem: (CatalogItem) -> Unit,
) {
    val screen = rememberSearchScreen(repository)
    val viewModel = screen.viewModel
    val state = screen.state
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    Column(Modifier.fillMaxSize()) {
        TvScreenTitle(stringResource(R.string.tab_search))
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            singleLine = true,
            placeholder = { androidx.compose.material3.Text(stringResource(R.string.search_hint)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            // "Buscar" en el teclado lleva a los resultados, como abajo en el mando.
            keyboardActions = KeyboardActions(onSearch = {
                keyboard?.hide()
                focusManager.moveFocus(FocusDirection.Down)
            }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.onBackground,
                unfocusedBorderColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                focusedTextColor = MaterialTheme.colorScheme.onBackground,
                unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .padding(start = TvHorizontalMargin)
                // Un campo de texto se queda las flechas para mover el cursor: abajo no
                // salía nunca de él y los resultados eran inalcanzables con el mando.
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                        keyboard?.hide()
                        focusManager.moveFocus(FocusDirection.Down)
                    } else {
                        false
                    }
                }
                .initialFocus(),
        )
        Spacer(Modifier.height(16.dp))
        if (!state.isTextSearch && state.filters.isNotEmpty()) {
            // Fila desplazable: un Row normal aplastaba el último filtro a una letra por línea.
            LazyRow(
                contentPadding = TvRowPadding,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.focusRestorer(),
            ) {
                items(state.filters, key = { it.contentUrl }) { filter ->
                    TvChoiceButton(filter.title, filter == state.selectedFilter) { viewModel.selectFilter(filter) }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        val results = state.results
        when {
            !state.isTextSearch -> when (val filter = state.filter) {
                FilterState.Loading -> TvCenterMessage(stringResource(R.string.state_loading))
                is FilterState.Failed -> TvCenterMessage(filter.error.text(context), viewModel::retryFilter)
                is FilterState.Loaded -> if (filter.items.isEmpty()) {
                    TvCenterMessage(stringResource(R.string.state_empty_module), viewModel::retryFilter)
                } else {
                    TvItemGrid(filter.items, RowLayout.POSTER, onOpenItem, takesInitialFocus = false)
                }
            }
            state.searchError != null -> TvCenterMessage(state.searchError.text(context), viewModel::retrySearch)
            results == null -> Box(Modifier.fillMaxSize())
            results.isEmpty -> TvCenterMessage(stringResource(R.string.search_empty))
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = RowLayout.LANDSCAPE.gridCellWidth()),
                contentPadding = PaddingValues(start = TvHorizontalMargin, end = TvHorizontalMargin, bottom = TvVerticalMargin),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (results.programs.isNotEmpty()) {
                    item(key = "programs", span = { GridItemSpan(maxLineSpan) }) {
                        Text(stringResource(R.string.search_programs), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    }
                    // Programas como pósters en una fila, como en el móvil; los vídeos, en rejilla.
                    item(key = "programs-row", span = { GridItemSpan(maxLineSpan) }) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                            modifier = Modifier.focusRestorer(),
                        ) {
                            items(results.programs, key = { "p-${it.id}" }) { item ->
                                TvItemCard(item, RowLayout.POSTER, { onOpenItem(item) }, Modifier.width(TvPosterWidth))
                            }
                        }
                    }
                }
                if (results.videos.isNotEmpty()) {
                    item(key = "videos", span = { GridItemSpan(maxLineSpan) }) {
                        Text(stringResource(R.string.search_videos), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    }
                    items(results.videos, key = { "v-${it.id}" }) { item ->
                        TvItemCard(item, RowLayout.LANDSCAPE, { onOpenItem(item) })
                    }
                    if (results.hasMoreVideos) {
                        item(key = "more-videos") {
                            TvFocusSurface(onClick = viewModel::loadMoreVideos, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                                Text(
                                    stringResource(if (state.isLoadingMoreVideos) R.string.state_loading else R.string.program_load_more),
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.align(Alignment.Center),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
