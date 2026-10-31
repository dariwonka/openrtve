package es.openrtve.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
import androidx.tv.material3.Text
import es.openrtve.R
import es.openrtve.data.CatalogRepository
import es.openrtve.data.WatchHistory
import es.openrtve.domain.CatalogItem
import es.openrtve.ui.LoadMoreOnScrollEnd
import es.openrtve.ui.PlaySuggestion
import es.openrtve.ui.ProgramUiState
import es.openrtve.ui.ProgramViewModel
import es.openrtve.ui.facts
import es.openrtve.ui.contents
import es.openrtve.ui.opensItemDetails
import es.openrtve.ui.metaLine
import es.openrtve.ui.rememberProgramScreen
import kotlinx.coroutines.launch

/**
 * Ficha de programa en TV (ver [TvDetailsScaffold]): cabecera con el botón de
 * reproducir y, debajo, Capítulos (con las temporadas como pestañas), Extras,
 * Similares y Detalles, una fila por sección, como la página de detalles de Google TV.
 */
@Composable
fun TvProgramScreen(
    repository: CatalogRepository,
    history: WatchHistory,
    program: CatalogItem,
    /** Capítulos y extras se reproducen al pulsarlos. */
    onOpenItem: (CatalogItem) -> Unit,
    onError: (String) -> Unit,
    /** Los similares y las piezas de una colección abren su ficha. */
    onOpenRelated: (CatalogItem) -> Unit = onOpenItem,
) {
    val screen = rememberProgramScreen(repository, history, program, onError)
    val viewModel = screen.viewModel
    val state = screen.state
    val suggestion = screen.suggestion
    val contents = state.contents
    val firstContent = remember { FocusRequester() }
    val detailsState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val episodesState = rememberLazyListState()
    LoadMoreOnScrollEnd(episodesState, threshold = 3, loadMore = viewModel::loadMore)

    TvDetailsScaffold(
        backdropUrl = state.detail?.imageUrl ?: program.imageUrl,
        listState = detailsState,
        header = {
            Text(
                text = state.title,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            state.detail?.metaLine()?.let {
                Spacer(Modifier.height(8.dp))
                Text(text = it, style = MaterialTheme.typography.bodyLarge)
            }
            state.detail?.emission?.let {
                Spacer(Modifier.height(4.dp))
                Text(text = it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            state.detail?.description?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = remember(it) { AnnotatedString.fromHtml(it) },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(20.dp))
            if (contents.isCollection) {
                Button(
                    onClick = {
                        // La cabecera enfocada intenta volver arriba; se libera antes de ir a la fila.
                        focusManager.clearFocus()
                        scope.launch {
                            detailsState.scrollToItem(1)
                            episodesState.scrollToItem(0)
                            withFrameNanos { }
                            firstContent.requestFocus()
                        }
                    },
                    enabled = state.episodes.isNotEmpty(),
                    modifier = Modifier.initialFocus(enabled = state.episodes.isNotEmpty()),
                ) {
                    Text(stringResource(contents.browseLabel))
                }
            } else Button(
                onClick = { suggestion?.let { onOpenItem(it.item) } },
                enabled = suggestion != null,
                // Deshabilitado no admite el foco: se concede en cuanto hay qué reproducir.
                modifier = Modifier.initialFocus(enabled = suggestion != null),
            ) {
                Text(
                    text = when (suggestion) {
                        null -> stringResource(R.string.action_play)
                        is PlaySuggestion.Continue -> stringResource(R.string.program_continue_episode, suggestion.item.title)
                        is PlaySuggestion.Next -> stringResource(R.string.program_next_episode, suggestion.item.title)
                        is PlaySuggestion.Play -> stringResource(R.string.program_play_episode, suggestion.item.title)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
    ) {
        tvDetailsSection("episodes", { stringResource(contents.label) }) {
            if (contents.isCollection && state.episodes.isNotEmpty()) {
                TvRelatedRow(
                    items = state.episodes,
                    onOpen = onOpenRelated,
                    hasMore = state.hasMore,
                    isLoadingMore = state.isLoadingMore,
                    onLoadMore = viewModel::loadMore,
                    listState = episodesState,
                    firstItemFocus = firstContent,
                )
            } else {
                if (!contents.isCollection) SeasonTabs(state, viewModel)
                EpisodesBody(state, viewModel, screen.entriesById, suggestion, episodesState) { item ->
                    if (state.opensItemDetails(item)) onOpenRelated(item) else onOpenItem(item)
                }
            }
        }
        if (state.extras.items.isNotEmpty()) {
            tvDetailsSection("extras", { stringResource(R.string.tab_more_videos) }) {
                TvEpisodesRow(
                    items = state.extras.items,
                    onPlay = onOpenItem,
                    hasMore = state.extras.hasMore,
                    isLoadingMore = state.extras.isLoadingMore,
                    onLoadMore = viewModel::loadMoreExtras,
                )
            }
        }
        if (!contents.isCollection && state.related.isNotEmpty()) {
            tvDetailsSection("related", { stringResource(R.string.tab_related) }) {
                TvRelatedRow(state.related, onOpenRelated)
            }
        }
        if (!contents.isCollection) {
            state.detail?.facts?.takeIf { it.hasDetails }?.let { facts ->
                tvDetailsSection("details", { stringResource(R.string.tab_details) }) { TvFactsPanel(facts) }
            }
        }
    }
}

/**
 * Temporadas como pestañas de TV (indicador de píldora), que se activan al
 * enfocarlas, como pide la guía de pestañas de Google TV. Al entrar en la fila,
 * el foco vuelve a la temporada elegida y no cambia nada.
 */
@Composable
private fun SeasonTabs(state: ProgramUiState, viewModel: ProgramViewModel) {
    val seasons = state.detail?.seasons.orEmpty()
    if (seasons.isEmpty()) return
    val options = listOf<Pair<String?, String>>(null to stringResource(R.string.program_all_seasons)) + seasons.map { it.id to it.title }
    val selected = options.indexOfFirst { it.first == state.selectedSeasonId }.coerceAtLeast(0)
    // Al entrar en la fila (desde el botón o desde los capítulos) el foco va a la
    // temporada elegida: si cayera en la más cercana, la activaría sin querer.
    val selectedTab = remember { FocusRequester() }
    WithTvRowScroll {
    TabRow(
        selectedTabIndex = selected,
        modifier = Modifier
            .padding(TvRowPadding)
            .padding(bottom = 16.dp)
            .focusProperties { onEnter = { selectedTab.requestFocus() } }
            .focusGroup(),
    ) {
        options.forEachIndexed { index, (seasonId, title) ->
            Tab(
                selected = index == selected,
                onFocus = { viewModel.selectSeason(seasonId) },
                onClick = { viewModel.selectSeason(seasonId) },
                modifier = if (index == selected) Modifier.focusRequester(selectedTab) else Modifier,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
    }
    }
}

@Composable
private fun EpisodesBody(
    state: ProgramUiState,
    viewModel: ProgramViewModel,
    entriesById: Map<String, es.openrtve.data.WatchEntry>,
    suggestion: PlaySuggestion?,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onPlay: (CatalogItem) -> Unit,
) {
    when {
        // Mismo alto que la fila: cambiar de temporada no hace saltar la página.
        state.isLoading && state.episodes.isEmpty() -> Box(
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier.height(EpisodesRowHeight).padding(TvRowPadding),
        ) {
            Text(stringResource(R.string.state_loading), style = MaterialTheme.typography.titleMedium)
        }
        state.episodes.isEmpty() -> Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(TvRowPadding),
        ) {
            Text(stringResource(R.string.program_empty), style = MaterialTheme.typography.titleMedium)
            Button(onClick = viewModel::retry, modifier = Modifier.initialFocus()) { Text(stringResource(R.string.action_retry)) }
        }
        else -> {
            val watched = stringResource(R.string.episode_watched)
            val next = stringResource(R.string.episode_next)
            TvEpisodesRow(
                items = state.episodes,
                onPlay = onPlay,
                progressOf = { episode -> entriesById[episode.id]?.takeIf { it.inProgress }?.progress },
                badgeOf = { episode ->
                    when {
                        entriesById[episode.id]?.finished == true -> watched
                        suggestion is PlaySuggestion.Next && suggestion.item.id == episode.id -> next
                        else -> null
                    }
                },
                hasMore = state.hasMore,
                isLoadingMore = state.isLoadingMore,
                onLoadMore = viewModel::loadMore,
                listState = listState,
            )
        }
    }
}

/** Alto de una fila de capítulos (imagen 16:9 de [TvEpisodeWidth] y dos líneas de texto). */
private val EpisodesRowHeight = 240.dp
