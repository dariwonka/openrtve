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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * Barra superior, la única navegación de la app: a la izquierda, Series y
 * Películas; a la derecha, Inicio y Ajustes. Está en todas las pantallas.
 */
@Composable
internal fun TvTopBar(
    /** Lista que se está viendo; `null` fuera del inicio (una serie, Ajustes). */
    section: ClanSection?,
    onSection: (ClanSection) -> Unit,
    onHome: () -> Unit,
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
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onHome) { Icon(Icons.Filled.Home, contentDescription = stringResource(R.string.tab_home)) }
        IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title)) }
    }
}

/** Inicio: todos los títulos de la lista elegida, en orden alfabético y en cuadrícula. */
@Composable
internal fun TvCatalogScreen(
    state: ClanCatalogUiState,
    section: ClanSection,
    onOpen: (CatalogItem) -> Unit,
    onRetry: () -> Unit,
    reselectRequest: Int = 0,
) {
    val titles = state.catalog.titles(section)
    val error = state.error
    when {
        titles.isNotEmpty() -> key(section) { TvTitleGrid(titles, onOpen, reselectRequest) }
        state.isLoading -> TvCenterMessage(stringResource(R.string.state_loading))
        error != null -> TvCenterMessage(error.text(LocalContext.current), onRetry)
        else -> TvCenterMessage(stringResource(R.string.catalog_empty), onRetry)
    }
}

@Composable
private fun TvTitleGrid(titles: List<CatalogItem>, onOpen: (CatalogItem) -> Unit, reselectRequest: Int) {
    val gridState = rememberLazyGridState()
    // Repetir Inicio vuelve al principio de la lista.
    RootReselectionEffect(reselectRequest) { gridState.scrollToItem(0) }
    ProvideTvVerticalScroll(top = TvFocusScaleRoom) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(TvGridColumns),
            contentPadding = PaddingValues(start = TvHorizontalMargin, end = TvHorizontalMargin, top = TvFocusScaleRoom, bottom = TvVerticalMargin),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.fillMaxSize().tvTopFade(TvFocusScaleRoom),
        ) {
            itemsIndexed(titles, key = { _, it -> it.id }) { index, item ->
                TvItemCard(item, RowLayout.LANDSCAPE, { onOpen(item) }, if (index == 0) Modifier.initialFocus() else Modifier)
            }
        }
    }
}
