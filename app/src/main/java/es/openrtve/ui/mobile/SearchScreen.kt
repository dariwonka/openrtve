package es.openrtve.ui.mobile

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import es.openrtve.R
import es.openrtve.data.CatalogRepository
import es.openrtve.domain.CatalogItem
import es.openrtve.domain.RowLayout
import es.openrtve.domain.SearchResults
import es.openrtve.domain.MediaForm
import es.openrtve.domain.mediaForm
import es.openrtve.ui.LoadMoreOnScrollEnd
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import es.openrtve.ui.FilterState
import es.openrtve.ui.RootReselectionEffect
import es.openrtve.ui.rememberSearchScreen
import es.openrtve.ui.text

/** Buscador: campo de texto, filtros rápidos de la app oficial y resultados por bloque. */
@Composable
fun SearchScreen(
    repository: CatalogRepository,
    onOpenItem: (CatalogItem) -> Unit,
    reselectRequest: Int = 0,
) {
    val screen = rememberSearchScreen(repository)
    val viewModel = screen.viewModel
    val state = screen.state
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val field = remember { FocusRequester() }
    RootReselectionEffect(reselectRequest) {
        field.requestFocus()
        withFrameNanos { }
        keyboard?.show()
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            singleLine = true,
            placeholder = { Text(stringResource(R.string.search_hint)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = viewModel::clearQuery) {
                        Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.search_clear))
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            modifier = Modifier
                .focusRequester(field)
                .fillMaxWidth()
                .padding(horizontal = ScreenPadding, vertical = 12.dp),
        )
        if (state.isSearching) LinearProgressIndicator(Modifier.fillMaxWidth())

        if (state.isTextSearch) {
            when {
                state.searchError != null -> EmptyPanel(state.searchError.text(context), viewModel::retrySearch)
                else -> SearchResultsView(
                    results = state.results,
                    isLoadingMore = state.isLoadingMoreVideos,
                    onLoadMore = viewModel::loadMoreVideos,
                    onOpenItem = onOpenItem,
                )
            }
        } else {
            if (state.filters.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = ScreenPadding, vertical = 4.dp),
                ) {
                    state.filters.forEach { filter ->
                        FilterChip(
                            selected = filter == state.selectedFilter,
                            onClick = { viewModel.selectFilter(filter) },
                            label = { Text(filter.title) },
                        )
                    }
                }
            }
            when (val filter = state.filter) {
                FilterState.Loading -> if (state.filters.isEmpty()) Box(Modifier.fillMaxSize()) else LoadingPanel()
                is FilterState.Failed -> EmptyPanel(filter.error.text(context), viewModel::retryFilter)
                is FilterState.Loaded -> if (filter.items.isEmpty()) {
                    EmptyPanel(stringResource(R.string.state_empty_module), viewModel::retryFilter)
                } else {
                    ItemGrid(filter.items, RowLayout.POSTER, onOpenItem)
                }
            }
        }
    }
}

@Composable
private fun SearchResultsView(
    results: SearchResults?,
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit,
    onOpenItem: (CatalogItem) -> Unit,
) {
    val listState = rememberLazyListState()
    LoadMoreOnScrollEnd(listState, threshold = 5, enabled = results?.hasMoreVideos == true, loadMore = onLoadMore)
    when {
        results == null -> Box(Modifier.fillMaxSize())
        results.isEmpty -> EmptyPanel(stringResource(R.string.search_empty))
        else -> LazyColumn(
            state = listState,
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (results.programs.isNotEmpty()) {
                item(key = "programs-header", contentType = "heading") { SectionHeader(stringResource(R.string.search_programs)) }
                item(key = "programs", contentType = "program-row") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = ScreenPadding),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(results.programs, key = { it.id }) { item ->
                            PosterCard(item, { onOpenItem(item) }, Modifier.width(130.dp))
                        }
                    }
                }
            }
            if (results.videos.isNotEmpty()) {
                item(key = "videos-header", contentType = "heading") { SectionHeader(stringResource(R.string.search_videos)) }
                items(results.videos, key = { it.id }, contentType = { "video" }) { item ->
                    EpisodeRow(
                        item, onClick = { onOpenItem(item) },
                        badge = if (item.mediaForm == MediaForm.EXTRA) item.mediaTypeName else item.mediaSubtypeName,
                    )
                }
                if (isLoadingMore) {
                    item(key = "videos-loading") { LoadingMoreRow() }
                } else if (results.hasMoreVideos) {
                    item(key = "videos-more") {
                        Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                            TextButton(onClick = onLoadMore) { Text(stringResource(R.string.program_load_more)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 4.dp),
    )
}
