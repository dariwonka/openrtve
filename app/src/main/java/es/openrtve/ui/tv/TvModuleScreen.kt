package es.openrtve.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
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

@Composable
fun TvModuleScreen(
    repository: CatalogRepository,
    row: HomeRow,
    title: String,
    onOpenItem: (CatalogItem) -> Unit,
    onError: (String) -> Unit,
) {
    val screen = rememberModuleScreen(repository, row, title, onError)
    val viewModel = screen.viewModel
    val state = screen.state

    Column(Modifier.fillMaxSize()) {
        TvScreenTitle(state.title)
        when {
            state.isLoading -> TvCenterMessage(stringResource(R.string.state_loading))
            state.items.isEmpty() -> TvCenterMessage(
                state.failure?.text(LocalContext.current) ?: stringResource(R.string.state_empty_module),
                viewModel::refresh,
            )
            else -> TvItemGrid(state.items, row.layout, onOpenItem)
        }
    }
}

@Composable
internal fun TvItemGrid(
    items: List<CatalogItem>,
    layout: RowLayout,
    onOpenItem: (CatalogItem) -> Unit,
    /** Sin foco inicial cuando la pantalla ya lo da a otra cosa (el campo del buscador). */
    takesInitialFocus: Boolean = true,
) {
    // En rejilla el hero no tiene sentido: se degrada a apaisada.
    val gridLayout = layout.gridLayout
    val initialFocus = rememberInitialFocus()
    ProvideTvVerticalScroll(top = TvFocusScaleRoom) { LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = gridLayout.gridCellWidth()),
        contentPadding = PaddingValues(start = TvHorizontalMargin, end = TvHorizontalMargin, top = TvFocusScaleRoom, bottom = TvVerticalMargin),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.fillMaxSize().tvTopFade(TvFocusScaleRoom),
    ) {
        itemsIndexed(items, key = { _, it -> it.id }) { index, item ->
            TvItemCard(
                item,
                gridLayout,
                { onOpenItem(item) },
                if (index == 0 && takesInitialFocus) Modifier.initialFocus(initialFocus) else Modifier,
                rank = (index + 1).takeIf { gridLayout == RowLayout.RANKED },
            )
        }
    } }
}
