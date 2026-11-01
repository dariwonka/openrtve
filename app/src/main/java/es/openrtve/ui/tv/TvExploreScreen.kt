package es.openrtve.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import es.openrtve.R
import es.openrtve.data.CatalogRepository
import es.openrtve.domain.CatalogItem
import es.openrtve.domain.ExploreCategory
import es.openrtve.domain.RowLayout
import es.openrtve.ui.header
import es.openrtve.ui.RootReselectionEffect
import es.openrtve.ui.rememberExploreScreen
import es.openrtve.ui.text
import androidx.compose.ui.res.stringResource

@Composable
fun TvExploreScreen(
    repository: CatalogRepository,
    onOpenCategory: (ExploreCategory) -> Unit,
    /** Los canales temáticos son directos: se reproducen al pulsarlos. */
    onOpenItem: (CatalogItem) -> Unit,
    reselectRequest: Int = 0,
) {
    val screen = rememberExploreScreen(repository, tv = true)
    val viewModel = screen.viewModel
    val state = screen.state
    val context = LocalContext.current
    val gridState = rememberLazyGridState()
    // Repetir Explorar en el menú vuelve arriba y a la primera categoría.
    val firstTile = remember { FocusRequester() }
    var firstTileFocused by remember { mutableStateOf(false) }
    RootReselectionEffect(reselectRequest) {
        gridState.scrollToItem(0)
        if (state.groups.any { it.categories.isNotEmpty() }) {
            firstTileFocused = false
            requestFocusUntilGranted(firstTile) { firstTileFocused }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TvScreenTitle(stringResource(R.string.tab_explore))
        when {
        state.isLoading -> TvCenterMessage(stringResource(R.string.state_loading))
        state.groups.isEmpty() && state.error != null -> TvCenterMessage(state.error.text(context), viewModel::retry)
        else -> ProvideTvVerticalScroll(top = 56.dp) { LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 180.dp),
            contentPadding = PaddingValues(start = TvHorizontalMargin, end = TvHorizontalMargin, top = TvFocusScaleRoom, bottom = TvVerticalMargin),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxSize().tvTopFade(TvFocusScaleRoom),
        ) {
            state.groups.forEachIndexed { groupIndex, group ->
                item(key = "group-$groupIndex", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                    Text(
                        text = group.header(context),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                itemsIndexed(
                    group.categories,
                    key = { _, it -> "$groupIndex/${it.contentUrl}" },
                    contentType = { _, it -> if (it.nowPlaying != null) "live" else "tile" },
                ) { index, category ->
                    val focus = if (index == 0 && groupIndex == 0) {
                        Modifier.initialFocus().focusRequester(firstTile).onFocusChanged { firstTileFocused = it.isFocused }
                    } else {
                        Modifier
                    }
                    val live = category.nowPlaying
                    if (live != null) {
                        TvItemCard(live, RowLayout.LANDSCAPE, { onOpenItem(live) }, focus.fillMaxWidth())
                    } else {
                        TvImageTile(category.title, category.imageUrl, { onOpenCategory(category) }, focus.fillMaxWidth(), category.fallbackImageUrl)
                    }
                }
            }
        } }
        }
    }
}
