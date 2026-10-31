package es.openrtve.ui.mobile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import es.openrtve.R
import es.openrtve.data.CatalogRepository
import es.openrtve.data.WatchHistory
import es.openrtve.domain.CatalogItem
import es.openrtve.ui.FichaFacts
import es.openrtve.ui.LoadMoreOnScrollEnd
import es.openrtve.ui.ProgramTab
import es.openrtve.ui.facts
import es.openrtve.ui.contentSections
import es.openrtve.ui.contents
import es.openrtve.ui.opensDetailsIn
import es.openrtve.ui.metaLine
import es.openrtve.ui.rememberVideoScreen
import es.openrtve.ui.shareLink
import es.openrtve.ui.text
import es.openrtve.ui.videoTabs

/**
 * Ficha de vídeo o película: cabecera con imagen, título, datos, botón de
 * reproducir y sinopsis; debajo, la ficha técnica. Sigue la plantilla de
 * [FichaLayout], la misma que la ficha de programa y que LibreTres.
 */
@Composable
fun VideoScreen(
    repository: CatalogRepository,
    history: WatchHistory,
    item: CatalogItem,
    onBack: () -> Unit,
    onPlay: (CatalogItem, Boolean) -> Unit,
    onOpenItem: (CatalogItem) -> Unit,
    onOpenProgram: (programId: String, title: String) -> Unit,
) {
    val screen = rememberVideoScreen(repository, history, item)
    val state = screen.state
    val resume = screen.resume
    val context = LocalContext.current
    val detail = state.detail
    val sections = state.contentSections
    val tabs = videoTabs(state)
    val columns = relatedColumns()
    val (current, selectTab) = rememberSelectedTab(tabs)
    val listState = rememberLazyListState()
    val activeSection = sections.firstOrNull { it.tab == current }
    LoadMoreOnScrollEnd(
        listState,
        threshold = 4,
        enabled = activeSection?.page?.hasMore == true && activeSection.page.error == null,
        contentKey = activeSection?.page?.page,
        loadMore = { activeSection?.let(screen.viewModel::loadMore) },
    )

    val restart = stringResource(R.string.action_restart)
    val programTitle = detail?.programTitle ?: state.item.subtitle
    val openProgram = programTitle?.let { stringResource(R.string.action_more_from, it) } ?: stringResource(R.string.action_open_program)
    FichaLayout(
        title = state.item.title,
        listState = listState,
        onBack = onBack,
        onShare = detail?.webUrl?.let { url -> { context.shareLink(state.item.title, url) } },
        header = { scrollOffset ->
            FichaHeader(
                backdropUrl = detail?.backdropUrl ?: state.item.imageUrl,
                title = state.item.title,
                scrollOffset = scrollOffset,
                subtitle = detail?.programTitle ?: state.item.subtitle,
                meta = detail?.metaLine(context),
                synopsis = detail?.description?.let { html -> remember(html) { AnnotatedString.fromHtml(html) } },
                progress = resume?.progress,
                actions = {
                    PlayActions(
                        label = stringResource(if (resume != null) R.string.action_continue else R.string.action_play),
                        onPlay = { onPlay(state.item, false) },
                        secondary = listOfNotNull(
                            SecondaryAction(Icons.Filled.Refresh, restart) { onPlay(state.item, true) }.takeIf { resume != null },
                            state.item.programId?.let { programId ->
                                SecondaryAction(Icons.AutoMirrored.Filled.List, openProgram) {
                                    onOpenProgram(programId, programTitle.orEmpty())
                                }
                            },
                        ),
                    )
                },
            )
        },
        tabs = if (tabs.size > 1) {
            { FichaTabs(tabs, current, selectTab) }
        } else {
            null
        },
    ) {
        if (detail == null && state.isLoading) {
            item(key = "loading") { LoadingMoreRow() }
            return@FichaLayout
        }
        if (detail == null && state.error != null) {
            item(key = "detail-error") { EmptyPanel(state.error.text(context), screen.viewModel::retry) }
            return@FichaLayout
        }
        when (current) {
            ProgramTab.DETAILS -> item(key = "details") { FichaFactsPanel(detail?.facts ?: FichaFacts()) }
            ProgramTab.RELATED -> relatedGrid(state.related, columns, onOpenItem)
            else -> activeSection?.let { section ->
                fichaContentSection(
                    section, columns,
                    onSelect = { video ->
                        if (section.tab != ProgramTab.MORE_VIDEOS && video.opensDetailsIn(state.contents)) onOpenItem(video) else onPlay(video, false)
                    },
                    onLoadMore = { screen.viewModel.loadMore(section) },
                    onRetry = { screen.viewModel.retry(section) },
                    currentItemId = state.item.id,
                )
            }
        }
    }
}

/** Ficha técnica: filas etiqueta/valor, al mismo margen que el resto de la ficha. */
@Composable
internal fun FichaFactsPanel(facts: FichaFacts) {
    Column(Modifier.padding(horizontal = ScreenPadding)) {
        Spacer(Modifier.height(8.dp))
        facts.rows.forEach { (label, value) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(
                    text = stringResource(label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(LabelColumn),
                )
                Spacer(Modifier.width(12.dp))
                Text(text = value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** Ancho de la columna de etiquetas de la ficha técnica. */
internal val LabelColumn = 104.dp
