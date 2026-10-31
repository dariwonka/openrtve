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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import es.openrtve.R
import es.openrtve.data.CatalogRepository
import es.openrtve.domain.ExploreCategory
import es.openrtve.ui.header
import es.openrtve.ui.rememberExploreScreen
import es.openrtve.ui.text
import androidx.compose.ui.res.stringResource

@Composable
fun TvExploreScreen(
    repository: CatalogRepository,
    onOpenCategory: (ExploreCategory) -> Unit,
) {
    val screen = rememberExploreScreen(repository, tv = true)
    val viewModel = screen.viewModel
    val state = screen.state
    val context = LocalContext.current
    val initialFocus = rememberInitialFocus()

    Column(Modifier.fillMaxSize()) {
        TvScreenTitle(stringResource(R.string.tab_explore))
        when {
        state.isLoading -> TvCenterMessage(stringResource(R.string.state_loading))
        state.groups.isEmpty() && state.error != null -> TvCenterMessage(state.error.text(context), viewModel::retry)
        else -> ProvideTvVerticalScroll(top = 56.dp) { LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 180.dp),
            contentPadding = PaddingValues(start = TvHorizontalMargin, end = TvHorizontalMargin, top = TvFocusScaleRoom, bottom = TvVerticalMargin),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxSize().tvTopFade(TvFocusScaleRoom),
        ) {
            state.groups.forEachIndexed { groupIndex, group ->
                group.header(context)?.let { title ->
                    item(key = "group-$groupIndex", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
                // Una misma portada puede estar en dos grupos (TDP en Temáticas y en Canales).
                itemsIndexed(group.categories, key = { _, it -> "$groupIndex/${it.contentUrl}" }) { index, category ->
                    val focus = if (index == 0 && groupIndex == 0) Modifier.initialFocus(initialFocus) else Modifier
                    TvImageTile(category.title, category.imageUrl, { onOpenCategory(category) }, focus.fillMaxWidth(), category.fallbackImageUrl)
                }
            }
        } }
        }
    }
}
