package es.openrtve.ui.tv

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import es.openrtve.R
import es.openrtve.data.WatchEntry
import es.openrtve.domain.CatalogItem
import es.openrtve.ui.ContentSection
import es.openrtve.ui.LoadMoreOnScrollEnd
import es.openrtve.ui.PlaySuggestion
import es.openrtve.ui.ProgramTab
import es.openrtve.ui.badge
import es.openrtve.ui.showsPosters
import es.openrtve.ui.text

@Composable
internal fun TvContentSection(
    section: ContentSection,
    onSelect: (CatalogItem) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    entriesById: Map<String, WatchEntry> = emptyMap(),
    suggestion: PlaySuggestion? = null,
    currentItemId: String? = null,
    takesInitialFocus: Boolean = false,
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val episodic = section.tab == ProgramTab.EPISODES
    LoadMoreOnScrollEnd(listState, threshold = 3, enabled = section.hasMore && section.error == null, contentKey = section.progressKey, loadMore = onLoadMore)
    if (section.error != null || (section.items.isEmpty() && !section.hasMore)) {
        TvInlineMessage(
            message = section.error?.text(context) ?: stringResource(
                when {
                    section.isLoading -> R.string.state_loading
                    episodic -> R.string.program_empty
                    else -> R.string.state_empty_module
                },
            ),
            onRetry = onRetry.takeUnless { section.isLoading },
            modifier = Modifier.padding(TvRowPadding),
            focusRetry = section.items.isEmpty(),
        )
    }
    if (section.items.isEmpty() && !section.hasMore) return
    if (section.tab.showsPosters) {
        TvRelatedRow(section.items, onSelect, section.hasMore, section.isLoadingMore, onLoadMore, listState, takesInitialFocus)
    } else {
        TvEpisodesRow(
            items = section.items,
            onPlay = onSelect,
            progressOf = { entriesById[it.id]?.takeIf { entry -> entry.inProgress }?.progress },
            badgeOf = { item -> section.badge(context, item, entriesById[item.id], suggestion, currentItemId) },
            hasMore = section.hasMore,
            isLoadingMore = section.isLoadingMore,
            onLoadMore = onLoadMore,
            listState = listState,
            episodic = episodic,
            takesInitialFocus = takesInitialFocus,
        )
    }
}
