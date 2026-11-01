package es.openrtve.ui.mobile

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import es.openrtve.R
import es.openrtve.data.CatalogRepository
import es.openrtve.data.WatchHistory
import es.openrtve.domain.CatalogItem
import es.openrtve.ui.collectionLinkText
import es.openrtve.ui.LoadMoreOnScrollEnd
import es.openrtve.ui.ProgramTab
import es.openrtve.ui.facts
import es.openrtve.ui.contentSections
import es.openrtve.ui.structure
import es.openrtve.ui.metaLine
import es.openrtve.ui.rememberVideoScreen
import es.openrtve.ui.shareLink
import es.openrtve.ui.text
import es.openrtve.ui.primaryTab
import es.openrtve.ui.titleRes
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
        enabled = activeSection?.hasMore == true && activeSection.error == null,
        contentKey = activeSection?.progressKey,
        loadMore = { activeSection?.let(screen.viewModel::loadMore) },
    )

    val restart = stringResource(R.string.action_restart)
    val programTitle = detail?.programTitle ?: state.item.subtitle
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
                subtitle = programTitle,
                onSubtitleClick = state.item.programId?.let { programId -> { onOpenProgram(programId, programTitle.orEmpty()) } },
                subtitleLinkText = context.collectionLinkText(state.structure, programTitle),
                meta = detail?.metaLine(context),
                synopsis = detail?.description?.let { html -> remember(html) { AnnotatedString.fromHtml(html) } },
                facts = detail?.facts,
                progress = resume?.progress,
                actions = {
                    PlayActions(
                        label = stringResource(if (resume != null) R.string.action_continue else R.string.action_play),
                        onPlay = { onPlay(state.item, false) },
                        secondary = listOfNotNull(
                            SecondaryAction(Icons.Filled.Refresh, restart) { onPlay(state.item, true) }.takeIf { resume != null },
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
        if (tabs.size == 1 && current != null) item(key = "section-title") { FichaSectionTitle(stringResource(current.titleRes)) }
        when (current) {
            ProgramTab.RELATED -> relatedGrid(state.related, columns, onOpenItem)
            else -> activeSection?.let { section ->
                fichaContentSection(
                    section, columns,
                    onSelect = { video -> if (section.opensDetails(video, state.structure)) onOpenItem(video) else onPlay(video, false) },
                    onLoadMore = { screen.viewModel.loadMore(section) },
                    onRetry = { screen.viewModel.retry(section) },
                    currentItemId = state.item.id,
                )
            }
        }
    }
}
