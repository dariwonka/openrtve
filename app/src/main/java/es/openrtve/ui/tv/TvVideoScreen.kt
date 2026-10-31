package es.openrtve.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import es.openrtve.R
import es.openrtve.data.CatalogRepository
import es.openrtve.data.WatchHistory
import es.openrtve.domain.CatalogItem
import es.openrtve.ui.facts
import es.openrtve.ui.metaLine
import es.openrtve.ui.rememberVideoScreen
import es.openrtve.ui.text

/**
 * Ficha de vídeo o película en TV (ver [TvDetailsScaffold]): cabecera con
 * reproducir, empezar de nuevo y el programa; debajo, Capítulos, Extras,
 * Similares y Detalles, como en la ficha de programa y en el móvil.
 */
@Composable
fun TvVideoScreen(
    repository: CatalogRepository,
    history: WatchHistory,
    item: CatalogItem,
    onPlay: (CatalogItem, Boolean) -> Unit,
    onOpenProgram: (programId: String, title: String) -> Unit,
    /** Un similar abre su ficha; capítulos y extras se reproducen con [onPlay]. */
    onOpenItem: (CatalogItem) -> Unit,
) {
    val screen = rememberVideoScreen(repository, history, item)
    val state = screen.state
    val resume = screen.resume
    val context = LocalContext.current
    val detail = state.detail
    val viewModel = screen.viewModel

    TvDetailsScaffold(
        backdropUrl = detail?.backdropUrl ?: state.item.imageUrl,
        header = {
            Text(
                text = state.item.title,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            (detail?.programTitle ?: state.item.subtitle)?.let {
                Text(text = it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
            }
            detail?.metaLine(context)?.let {
                Spacer(Modifier.height(8.dp))
                Text(text = it, style = MaterialTheme.typography.bodyLarge)
            }
            detail?.description?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = remember(it) { AnnotatedString.fromHtml(it) },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Sin ficha se puede reproducir igual; se dice qué falló y se ofrece reintentar.
            if (detail == null && state.error != null) {
                Spacer(Modifier.height(12.dp))
                Text(state.error.text(context), style = MaterialTheme.typography.bodyLarge)
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { onPlay(state.item, false) }, modifier = Modifier.initialFocus()) {
                    Text(stringResource(if (resume != null) R.string.action_continue else R.string.action_play))
                }
                if (resume != null) {
                    Button(onClick = { onPlay(state.item, true) }) { Text(stringResource(R.string.action_restart)) }
                }
                if (detail == null && state.error != null) {
                    Button(onClick = viewModel::retry) { Text(stringResource(R.string.action_retry)) }
                }
                state.item.programId?.let { programId ->
                    val programTitle = detail?.programTitle ?: state.item.subtitle
                    Button(onClick = { onOpenProgram(programId, programTitle.orEmpty()) }) {
                        // "Más de Cine de siempre" dice adónde lleva; "Programa" a secas, no.
                        Text(programTitle?.let { stringResource(R.string.action_more_from, it) } ?: stringResource(R.string.action_open_program))
                    }
                }
            }
        },
    ) {
        // Con un solo capítulo (el propio vídeo) la fila no aporta nada.
        if (state.chapters.items.size > 1) {
            tvDetailsSection("chapters", { stringResource(R.string.tab_episodes) }) {
                TvEpisodesRow(
                    items = state.chapters.items,
                    onPlay = { onPlay(it, false) },
                    badgeOf = { chapter -> stringResource(R.string.episode_current).takeIf { chapter.id == state.item.id } },
                    hasMore = state.chapters.hasMore,
                    isLoadingMore = state.chapters.isLoadingMore,
                    onLoadMore = viewModel::loadMoreChapters,
                )
            }
        }
        if (state.extras.items.isNotEmpty()) {
            tvDetailsSection("extras", { stringResource(R.string.tab_more_videos) }) {
                TvEpisodesRow(
                    items = state.extras.items,
                    onPlay = { onPlay(it, false) },
                    hasMore = state.extras.hasMore,
                    isLoadingMore = state.extras.isLoadingMore,
                    onLoadMore = viewModel::loadMoreExtras,
                )
            }
        }
        if (state.related.isNotEmpty()) {
            tvDetailsSection("related", { stringResource(R.string.tab_related) }) {
                TvRelatedRow(state.related, onOpenItem)
            }
        }
        detail?.facts?.takeIf { it.hasDetails }?.let { facts ->
            tvDetailsSection("details", { stringResource(R.string.tab_details) }) { TvFactsPanel(facts) }
        }
    }
}
