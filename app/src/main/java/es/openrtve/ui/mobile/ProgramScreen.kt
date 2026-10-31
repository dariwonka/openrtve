package es.openrtve.ui.mobile

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import es.openrtve.ui.FichaFacts
import es.openrtve.ui.PlaySuggestion
import es.openrtve.ui.ProgramBody
import es.openrtve.ui.ProgramTab
import es.openrtve.ui.ProgramUiState
import es.openrtve.ui.facts
import es.openrtve.ui.contents
import es.openrtve.ui.opensItemDetails
import es.openrtve.ui.metaLine
import es.openrtve.ui.programBody
import es.openrtve.ui.programTabs
import es.openrtve.ui.rememberProgramScreen
import es.openrtve.ui.shareLink
import es.openrtve.ui.text

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
    onError: (String) -> Unit,
    /** Reproducir desde el principio lo que se estaba viendo; sin él, el botón grande sólo continúa. */
    onPlay: ((CatalogItem, restart: Boolean) -> Unit)? = null,
    /** Abrir la ficha de un similar o una pieza de una colección. */
    onOpenRelated: (CatalogItem) -> Unit = onOpenItem,
) {
    val screen = rememberProgramScreen(repository, history, program, onError)
    val viewModel = screen.viewModel
    val state = screen.state
    val suggestion = screen.suggestion
    val context = LocalContext.current
    val detail = state.detail
    val contents = state.contents
    val body = programBody(state)
    val tabs = remember(body, state.extras, state.related, detail) { programTabs(body, state) }
    val columns = relatedColumns()
    val (current, selectTab) = rememberSelectedTab(tabs)
    val listState = rememberLazyListState()
    // Paginación infinita: pide la siguiente página al acercarse al final.
    LoadMoreOnScrollEnd(listState, threshold = 4, enabled = current == ProgramTab.EPISODES && state.hasMore, loadMore = viewModel::loadMore)
    LoadMoreOnScrollEnd(
        listState,
        threshold = 4,
        enabled = current == ProgramTab.MORE_VIDEOS && state.extras.hasMore,
        loadMore = viewModel::loadMoreExtras,
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
                progress = (suggestion as? PlaySuggestion.Continue)?.entry?.progress,
                actions = if (contents.isCollection) {
                    {
                        Button(onClick = { selectTab(ProgramTab.EPISODES) }) {
                            Text(stringResource(contents.browseLabel))
                        }
                    }
                } else suggestion?.let { target ->
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
            { FichaTabs(tabs, current, selectTab, contents.label) }
        } else {
            null
        },
    ) {
        when (current) {
            ProgramTab.EPISODES -> {
                // Las temporadas se quedan mientras carga la elegida: si desaparecieran,
                // la lista encogería y la fila de chips perdería su desplazamiento.
                if (!contents.isCollection && detail?.seasons?.isNotEmpty() == true) {
                    item(key = "seasons") { SeasonChips(state, onSelect = viewModel::selectSeason) }
                }
                if (body == ProgramBody.Loading) {
                    item(key = "loading") { LoadingMoreRow() }
                    return@FichaLayout
                }
                if (contents.isCollection) {
                    relatedGrid(state.episodes, columns, onOpenRelated)
                } else items(state.episodes, key = { it.id }) { episode ->
                    val entry = screen.entriesById[episode.id]
                    EpisodeRow(
                        item = episode,
                        onClick = { if (state.opensItemDetails(episode)) onOpenRelated(episode) else onOpenItem(episode) },
                        progress = entry?.takeIf { it.inProgress }?.progress,
                        badge = when {
                            entry?.finished == true -> stringResource(R.string.episode_watched)
                            suggestion is PlaySuggestion.Next && suggestion.item.id == episode.id -> stringResource(R.string.episode_next)
                            else -> null
                        },
                        highlighted = suggestion?.item?.id == episode.id,
                    )
                }
                // El hueco se explica: o falló la petición, o el programa no tiene
                // vídeos. Nunca una pantalla vacía sin decir por qué.
                if (body is ProgramBody.NoEpisodes) {
                    item(key = "no-episodes") {
                        EmptyPanel(
                            message = body.error?.text(context) ?: stringResource(R.string.program_empty),
                            onRetry = viewModel::retry,
                        )
                    }
                }
                if (state.isLoadingMore) {
                    item(key = "loading-more") { LoadingMoreRow() }
                } else if (state.hasMore && body is ProgramBody.Episodes) {
                    item(key = "load-more") {
                        Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                            TextButton(onClick = viewModel::loadMore) { Text(stringResource(R.string.program_load_more)) }
                        }
                    }
                }
            }

            ProgramTab.MORE_VIDEOS -> {
                items(state.extras.items, key = { "extra-${it.id}" }) { extra ->
                    EpisodeRow(item = extra, onClick = { onOpenItem(extra) })
                }
                if (state.extras.items.isEmpty() && state.extras.error != null) {
                    item(key = "extras-error") {
                        EmptyPanel(message = state.extras.error.text(context), onRetry = viewModel::retryExtras)
                    }
                }
                if (state.extras.isLoading || state.extras.isLoadingMore) {
                    item(key = "extras-loading") { LoadingMoreRow() }
                } else if (state.extras.hasMore) {
                    item(key = "extras-more") {
                        Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                            TextButton(onClick = viewModel::loadMoreExtras) { Text(stringResource(R.string.program_load_more)) }
                        }
                    }
                }
            }

            ProgramTab.RELATED -> relatedGrid(state.related, columns, onOpenRelated)
            ProgramTab.DETAILS -> item(key = "details") { FichaFactsPanel(detail?.facts ?: FichaFacts()) }
            null -> Unit
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
    val label = when (suggestion) {
        is PlaySuggestion.Continue -> stringResource(R.string.program_continue_episode, suggestion.item.title)
        is PlaySuggestion.Next -> stringResource(R.string.program_next_episode, suggestion.item.title)
        is PlaySuggestion.Play -> stringResource(R.string.program_play_episode, suggestion.item.title)
    }
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
