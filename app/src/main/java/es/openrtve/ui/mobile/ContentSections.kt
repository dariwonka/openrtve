package es.openrtve.ui.mobile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import es.openrtve.R
import es.openrtve.data.WatchEntry
import es.openrtve.domain.CatalogItem
import es.openrtve.ui.ContentSection
import es.openrtve.ui.PlaySuggestion
import es.openrtve.ui.ProgramTab
import es.openrtve.ui.text
import androidx.compose.ui.platform.LocalContext

/** La ficha de programa y la de vídeo muestran y paginan las mismas secciones. */
internal fun LazyListScope.fichaContentSection(
    section: ContentSection,
    columns: Int,
    onSelect: (CatalogItem) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    entriesById: Map<String, WatchEntry> = emptyMap(),
    suggestion: PlaySuggestion? = null,
    currentItemId: String? = null,
) {
    val page = section.page
    val episodic = section.tab == ProgramTab.EPISODES
    if (section.tab == ProgramTab.MOVIES || section.tab == ProgramTab.DOCUMENTARIES) {
        relatedGrid(page.items, columns, onSelect)
    } else {
        items(page.items, key = { "${section.tab}-${it.id}" }) { item ->
            val entry = entriesById[item.id]
            EpisodeRow(
                item,
                onClick = { onSelect(item) },
                progress = entry?.takeIf { it.inProgress }?.progress,
                badge = when {
                    episodic && item.id == currentItemId -> stringResource(R.string.episode_current)
                    episodic && entry?.finished == true -> stringResource(R.string.episode_watched)
                    episodic && suggestion is PlaySuggestion.Next && suggestion.item.id == item.id -> stringResource(R.string.episode_next)
                    section.tab == ProgramTab.MORE_VIDEOS -> item.mediaTypeName
                    !episodic -> item.mediaSubtypeName
                    else -> null
                },
                highlighted = episodic && (item.id == currentItemId || suggestion?.item?.id == item.id),
                episodic = episodic,
            )
        }
    }
    if (page.error != null) {
        item(key = "content-error") { EmptyPanel(page.error.text(LocalContext.current), onRetry) }
    } else if (page.items.isEmpty() && !page.isLoading && !page.hasMore) {
        item(key = "content-empty") {
            EmptyPanel(stringResource(if (episodic) R.string.program_empty else R.string.state_empty_module), onRetry)
        }
    }
    if (page.isLoading || page.isLoadingMore) {
        item(key = "content-loading") { LoadingMoreRow() }
    } else if (page.hasMore && page.error == null) {
        item(key = "content-more") {
            Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                TextButton(onClick = onLoadMore) { Text(stringResource(R.string.program_load_more)) }
            }
        }
    }
}
