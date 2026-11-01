package es.openrtve.ui.mobile

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.unit.dp
import es.openrtve.R
import es.openrtve.data.CatalogRepository
import es.openrtve.data.WatchHistory
import es.openrtve.domain.CatalogItem
import es.openrtve.ui.LoadMoreOnScrollEnd
import es.openrtve.ui.PlaySuggestion
import es.openrtve.ui.ProgramTab
import es.openrtve.ui.ProgramUiState
import es.openrtve.ui.facts
import es.openrtve.ui.contentSections
import es.openrtve.ui.structure
import es.openrtve.ui.label
import es.openrtve.ui.metaLine
import es.openrtve.ui.programTabs
import es.openrtve.ui.rememberProgramScreen
import es.openrtve.ui.shareLink
import es.openrtve.ui.titleRes

/**
 * Ficha de programa: cabecera con imagen, botón grande (Reproducir, Continuar o
 * Siguiente según el historial) y sinopsis; debajo, temporadas y episodios con su
 * progreso. Sigue la plantilla de [FichaLayout], la misma que en LibreTres.
 */
@Composable
fun ProgramScreen(
    repository: CatalogRepository,
    history: WatchHistory,
    program: CatalogItem,
    onBack: () -> Unit,
    onOpenItem: (CatalogItem) -> Unit,
    /** Reproducir desde el principio lo que se estaba viendo; sin él, el botón grande sólo continúa. */
    onPlay: ((CatalogItem, restart: Boolean) -> Unit)? = null,
    /** Abrir la ficha de un similar o una pieza de una colección. */
    onOpenRelated: (CatalogItem) -> Unit = onOpenItem,
) {
    val screen = rememberProgramScreen(repository, history, program)
    val viewModel = screen.viewModel
    val state = screen.state
    val suggestion = screen.suggestion
    val context = LocalContext.current
    val detail = state.detail
    val sections = state.contentSections
    val tabs = programTabs(state)
    val columns = relatedColumns()
    val (current, selectTab) = rememberSelectedTab(tabs)
    val listState = rememberLazyListState()
    val activeSection = sections.firstOrNull { it.tab == current }
    LoadMoreOnScrollEnd(
        listState,
        threshold = 4,
        enabled = activeSection?.hasMore == true && activeSection.error == null,
        contentKey = activeSection?.progressKey,
        loadMore = { activeSection?.let(viewModel::loadMore) },
    )

    FichaLayout(
        title = state.title,
        listState = listState,
        onBack = onBack,
        onShare = detail?.webUrl?.let { url -> { context.shareLink(state.title, url) } },
        header = { scrollOffset ->
            FichaHeader(
                backdropUrl = detail?.imageUrl ?: program.imageUrl,
                title = state.title,
                scrollOffset = scrollOffset,
                meta = detail?.metaLine(),
                claim = detail?.emission,
                synopsis = detail?.description?.let { html -> remember(html) { AnnotatedString.fromHtml(html) } },
                // En un contenedor (un ciclo de cine) reparto y dirección no son de ninguna obra.
                facts = detail?.facts?.takeUnless { state.structure.isCollection },
                progress = (suggestion as? PlaySuggestion.Continue)?.entry?.progress,
                actions = suggestion?.let { target ->
                    {
                        PrimaryPlayButton(
                            suggestion = target,
                            onPlay = { item, restart -> if (onPlay != null) onPlay(item, restart) else onOpenItem(item) },
                            canRestart = onPlay != null,
                        )
                    }
                },
            )
        },
        tabs = if (tabs.size > 1) {
            { FichaTabs(tabs, current, selectTab) }
        } else {
            null
        },
    ) {
        if (tabs.size == 1 && current != null) item(key = "section-title") { FichaSectionTitle(stringResource(current.titleRes)) }
        when (current) {
            ProgramTab.RELATED -> relatedGrid(state.related, columns, onOpenRelated)
            else -> activeSection?.let { section ->
                if (section.tab == ProgramTab.EPISODES && detail?.seasons?.isNotEmpty() == true) {
                    item(key = "seasons") { SeasonChips(state, onSelect = viewModel::selectSeason) }
                }
                fichaContentSection(
                    section, columns,
                    onSelect = { item -> if (section.opensDetails(item, state.structure)) onOpenRelated(item) else onOpenItem(item) },
                    onLoadMore = { viewModel.loadMore(section) },
                    onRetry = { viewModel.retry(section) },
                    entriesById = screen.entriesById,
                    suggestion = suggestion,
                )
            }
        }
    }
}

/** Botón grande de la ficha de programa, con "desde el inicio" si se está a medias. */
@Composable
internal fun PrimaryPlayButton(
    suggestion: PlaySuggestion,
    onPlay: (CatalogItem, restart: Boolean) -> Unit,
    canRestart: Boolean = true,
) {
    val label = suggestion.label(LocalContext.current)
    val restart = stringResource(R.string.action_restart)
    PlayActions(
        label = label,
        onPlay = { onPlay(suggestion.item, false) },
        secondary = listOfNotNull(
            SecondaryAction(Icons.Filled.Refresh, restart) { onPlay(suggestion.item, true) }
                .takeIf { canRestart && suggestion is PlaySuggestion.Continue },
        ),
    )
}

@Composable
internal fun LoadingMoreRow() {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun SeasonChips(state: ProgramUiState, onSelect: (String?) -> Unit) {
    val seasons = state.detail?.seasons.orEmpty()
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = ScreenPadding, vertical = 8.dp),
    ) {
        FilterChip(
            selected = state.selectedSeasonId == null,
            onClick = { onSelect(null) },
            label = { Text(stringResource(R.string.program_all_seasons)) },
        )
        seasons.forEach { season ->
            FilterChip(
                selected = state.selectedSeasonId == season.id,
                onClick = { onSelect(season.id) },
                label = { Text(season.title) },
            )
        }
    }
}
