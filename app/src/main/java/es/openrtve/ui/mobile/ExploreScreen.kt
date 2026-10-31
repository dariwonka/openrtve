package es.openrtve.ui.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import es.openrtve.R
import es.openrtve.data.CatalogRepository
import es.openrtve.domain.ExploreCategory
import es.openrtve.ui.header
import es.openrtve.ui.RootReselectionEffect
import es.openrtve.ui.rememberExploreScreen
import es.openrtve.ui.text

/** Categorías del menú oficial como rejilla de tarjetas con imagen. */
@Composable
fun ExploreScreen(
    repository: CatalogRepository,
    onOpenCategory: (ExploreCategory) -> Unit,
    onOpenSettings: () -> Unit,
    reselectRequest: Int = 0,
) {
    val screen = rememberExploreScreen(repository)
    val viewModel = screen.viewModel
    val state = screen.state
    val context = LocalContext.current
    val gridState = rememberLazyGridState()
    RootReselectionEffect(reselectRequest) { gridState.animateScrollToItem(0) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // Título grande dentro del contenido en vez de una barra: ocupa lo mismo y se lee mejor.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = ScreenPadding, end = 4.dp, top = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.tab_explore),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title))
            }
        }
        when {
            state.isLoading -> LoadingPanel()
            state.groups.isEmpty() && state.error != null -> EmptyPanel(state.error.text(context), viewModel::retry)
            else -> LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = 160.dp),
                contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, bottom = ScreenPadding),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                state.groups.forEachIndexed { groupIndex, group ->
                    group.header(context)?.let { title ->
                        item(key = "group-$groupIndex", span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                            )
                        }
                    }
                    // Una misma portada puede estar en dos grupos (TDP en Temáticas y en Canales).
                    items(group.categories, key = { "$groupIndex/${it.contentUrl}" }) { category ->
                        ImageTile(category.title, category.imageUrl, { onOpenCategory(category) }, Modifier.fillMaxWidth(), category.fallbackImageUrl)
                    }
                }
            }
        }
    }
}
