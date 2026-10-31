package es.openrtve.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import es.openrtve.R
import es.openrtve.data.WatchEntry
import es.openrtve.domain.CatalogItem
import es.openrtve.ui.ContentSection
import es.openrtve.ui.LoadMoreOnScrollEnd
import es.openrtve.ui.PlaySuggestion
import es.openrtve.ui.ProgramTab
import es.openrtve.ui.text

@Composable
internal fun TvContentSection(
    section: ContentSection,
    onSelect: (CatalogItem) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    firstItemFocus: FocusRequester? = null,
    entriesById: Map<String, WatchEntry> = emptyMap(),
    suggestion: PlaySuggestion? = null,
    currentItemId: String? = null,
    listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
) {
    val page = section.page
    val episodic = section.tab == ProgramTab.EPISODES
    LoadMoreOnScrollEnd(listState, threshold = 3, enabled = page.hasMore && page.error == null, contentKey = page.page, loadMore = onLoadMore)
    if (page.error != null || (page.items.isEmpty() && !page.hasMore)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(TvRowPadding)) {
            Text(
                page.error?.text(LocalContext.current) ?: stringResource(
                    when {
                        page.isLoading -> R.string.state_loading
                        episodic -> R.string.program_empty
                        else -> R.string.state_empty_module
                    },
                ),
            )
            if (!page.isLoading) Button(onClick = onRetry, modifier = Modifier.initialFocus(enabled = page.items.isEmpty())) {
                Text(stringResource(R.string.action_retry))
            }
        }
    }
    if (page.items.isEmpty() && !page.hasMore) return
    if (section.tab == ProgramTab.MOVIES || section.tab == ProgramTab.DOCUMENTARIES) {
        TvRelatedRow(page.items, onSelect, page.hasMore, page.isLoadingMore, onLoadMore, listState, firstItemFocus)
    } else {
        TvEpisodesRow(
            items = page.items,
            onPlay = onSelect,
            progressOf = { entriesById[it.id]?.takeIf { entry -> entry.inProgress }?.progress },
            badgeOf = { item ->
                when {
                    episodic && item.id == currentItemId -> stringResource(R.string.episode_current)
                    episodic && entriesById[item.id]?.finished == true -> stringResource(R.string.episode_watched)
                    episodic && suggestion is PlaySuggestion.Next && suggestion.item.id == item.id -> stringResource(R.string.episode_next)
                    section.tab == ProgramTab.MORE_VIDEOS -> item.mediaTypeName
                    !episodic -> item.mediaSubtypeName
                    else -> null
                }
            },
            hasMore = page.hasMore,
            isLoadingMore = page.isLoadingMore,
            onLoadMore = onLoadMore,
            listState = listState,
            firstItemFocus = firstItemFocus,
            episodic = episodic,
        )
    }
}

@Composable
internal fun ProgramTab.sectionTitle(): String = stringResource(
    when (this) {
        ProgramTab.EPISODES -> R.string.tab_episodes
        ProgramTab.MOVIES -> R.string.tab_movies
        ProgramTab.DOCUMENTARIES -> R.string.tab_documentaries
        ProgramTab.VIDEOS -> R.string.tab_videos
        ProgramTab.MORE_VIDEOS -> R.string.tab_more_videos
        ProgramTab.RELATED -> R.string.tab_related
        ProgramTab.DETAILS -> R.string.tab_details
    },
)
