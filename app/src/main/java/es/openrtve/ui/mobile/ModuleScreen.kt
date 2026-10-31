package es.openrtve.ui.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import es.openrtve.R
import es.openrtve.data.CatalogRepository
import es.openrtve.domain.CatalogItem
import es.openrtve.domain.HomeRow
import es.openrtve.domain.RowLayout
import es.openrtve.ui.rememberModuleScreen
import es.openrtve.ui.text
import androidx.compose.ui.platform.LocalContext

/** Rejilla completa de una fila ("Ver todo"). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModuleScreen(
    repository: CatalogRepository,
    row: HomeRow,
    title: String,
    onBack: () -> Unit,
    onOpenItem: (CatalogItem) -> Unit,
    onError: (String) -> Unit,
) {
    val screen = rememberModuleScreen(repository, row, title, onError)
    val viewModel = screen.viewModel
    val state = screen.state

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title = state.title, onBack = onBack)
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(Modifier.fillMaxSize()) {
                if (state.isStale) StaleBanner()
                when {
                    state.isLoading -> LoadingPanel()
                    state.items.isEmpty() -> EmptyPanel(
                        state.failure?.text(LocalContext.current) ?: stringResource(R.string.state_empty_module),
                        viewModel::refresh,
                    )
                    else -> ItemGrid(state.items, row.layout, onOpenItem)
                }
            }
        }
    }
}

@Composable
internal fun ItemGrid(items: List<CatalogItem>, layout: RowLayout, onOpenItem: (CatalogItem) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = if (layout.isVertical) 110.dp else 160.dp),
        contentPadding = PaddingValues(ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(items, key = { _, it -> it.id }) { index, item ->
            when (val gridLayout = layout.gridLayout) {
                RowLayout.POSTER -> PosterCard(item, { onOpenItem(item) })
                RowLayout.POSTER_TALL -> TallPosterCard(item, { onOpenItem(item) })
                RowLayout.SQUARE -> SquareCard(item, { onOpenItem(item) })
                RowLayout.RANKED -> ItemCard(item, { onOpenItem(item) }, rank = index + 1)
                RowLayout.LANDSCAPE, RowLayout.HERO, RowLayout.FEATURED -> ItemCard(item, { onOpenItem(item) })
            }
        }
    }
}
