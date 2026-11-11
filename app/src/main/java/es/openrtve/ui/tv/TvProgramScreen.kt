package es.openrtve.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import es.openrtve.R
import es.openrtve.data.CatalogRepository
import es.openrtve.data.WatchHistory
import es.openrtve.domain.CatalogItem
import es.openrtve.ui.EPISODE_MIN_MS
import es.openrtve.ui.rememberProgramScreen
import es.openrtve.ui.text
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/**
 * Página de una serie: su título y, debajo, todos sus vídeos completos en una
 * cuadrícula que baja hasta el final (las páginas siguientes se piden solas al
 * acercarse). Sin sinopsis, temporadas, extras ni similares: cada tarjeta lleva
 * sus datos y se reproduce al pulsarla.
 */
@Composable
fun TvProgramScreen(
    repository: CatalogRepository,
    history: WatchHistory,
    program: CatalogItem,
    onPlay: (CatalogItem) -> Unit,
    /** Vídeos que no se enseñan aquí: los que ya salen sueltos en Películas y los eliminados. */
    hiddenIds: Set<String> = emptySet(),
) {
    val screen = rememberProgramScreen(repository, history, program)
    val viewModel = screen.viewModel
    val state = screen.state
    val episodes = state.episodes
    // Los vídeos de menos de 4 minutos (avances, cortes) no se enseñan.
    val shown = remember(episodes.items, hiddenIds) {
        episodes.items.filter { it.id !in hiddenIds && it.durationMs?.let { ms -> ms >= EPISODE_MIN_MS } != false }
    }
    val error = episodes.error
    val context = LocalContext.current
    val gridState = rememberLazyGridState()

    // Dos filas antes del final ya se pide la página siguiente; un fallo espera a su reintento.
    LaunchedEffect(gridState, episodes.page, episodes.hasMore, error) {
        if (!episodes.hasMore || error != null) return@LaunchedEffect
        snapshotFlow {
            val info = gridState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: -1) to info.totalItemsCount
        }
            .distinctUntilChanged()
            .filter { (last, total) -> total > 0 && last >= total - 1 - 2 * TvGridColumns }
            .collect { viewModel.loadMore() }
    }

    Column(Modifier.fillMaxSize()) {
        TvScreenTitle(state.title)
        when {
            shown.isNotEmpty() -> ProvideTvCenteredScroll {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(TvGridColumns),
                    contentPadding = PaddingValues(start = TvHorizontalMargin, end = TvHorizontalMargin, top = TvFocusScaleRoom, bottom = TvVerticalMargin),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(shown, key = { _, it -> it.id }, contentType = { _, _ -> "video" }) { index, item ->
                        val entry = screen.entriesById[item.id]
                        TvEpisodeCard(
                            item = item,
                            onClick = { onPlay(item) },
                            modifier = (if (index == 0) Modifier.initialFocus() else Modifier).wrapRowFocus(index, shown.size),
                            progress = entry?.takeIf { it.inProgress }?.progress,
                            badge = if (entry?.finished == true) stringResource(R.string.episode_watched) else null,
                        )
                    }
                    if (episodes.hasMore || error != null) {
                        item(key = "more", span = { GridItemSpan(maxLineSpan) }) {
                            TvInlineMessage(
                                message = error?.text(context) ?: stringResource(R.string.state_loading),
                                onRetry = if (error != null) ({ viewModel.retry() }) else null,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                    }
                }
            }
            error != null -> TvCenterMessage(error.text(context)) { viewModel.retry() }
            episodes.isLoading || episodes.hasMore -> TvCenterMessage(stringResource(R.string.state_loading))
            else -> TvCenterMessage(stringResource(R.string.program_empty))
        }
    }
}

