package es.openrtve.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import es.openrtve.R
import es.openrtve.data.CatalogRepository
import es.openrtve.data.WatchHistory
import es.openrtve.domain.CatalogItem
import es.openrtve.domain.ProgramStructure
import es.openrtve.ui.facts
import es.openrtve.ui.contentSections
import es.openrtve.ui.structure
import es.openrtve.ui.metaLine
import es.openrtve.ui.rememberVideoScreen
import es.openrtve.ui.titleRes
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
    val programTitle = detail?.programTitle ?: state.item.subtitle

    TvDetailsScaffold(
        backdropUrl = detail?.backdropUrl ?: state.item.imageUrl,
        header = {
            TvDetailsHeaderText(
                title = state.item.title,
                subtitle = programTitle,
                meta = detail?.metaLine(context),
                synopsis = detail?.description,
            )
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
                // Ir al programa es navegación secundaria: verbo y destino, que ya se lee
                // justo encima. Una pieza de un contenedor no lo lleva: su ciclo se ve como
                // fila de contenido ("Más de Cine de siempre"), más abajo.
                state.item.programId?.takeUnless { state.structure.isCollection }?.let { programId ->
                    Button(onClick = { onOpenProgram(programId, programTitle.orEmpty()) }) {
                        Text(stringResource(R.string.action_open_program))
                    }
                }
            }
        },
    ) {
        state.contentSections.forEach { section ->
            tvDetailsSection(section.tab.name, { stringResource(section.tab.titleRes) }) {
                TvContentSection(
                    section,
                    onSelect = { video ->
                        if (section.opensDetails(video, state.structure)) onOpenItem(video) else onPlay(video, false)
                    },
                    onLoadMore = { viewModel.loadMore(section) },
                    onRetry = { viewModel.retry(section) },
                    currentItemId = state.item.id,
                )
            }
        }
        if (state.moreFromProgram.isNotEmpty() && programTitle != null) {
            tvDetailsSection("more-from-program", { stringResource(R.string.action_more_from, programTitle) }) {
                // Cada pieza de un contenedor tiene su ficha: se abre, no se reproduce.
                when (state.structure) {
                    ProgramStructure.MOVIES, ProgramStructure.DOCUMENTARIES -> TvRelatedRow(state.moreFromProgram, onOpenItem)
                    else -> TvEpisodesRow(state.moreFromProgram, onPlay = onOpenItem, episodic = false)
                }
            }
        }
        if (state.related.isNotEmpty()) {
            tvDetailsSection("related", { stringResource(R.string.tab_related) }) {
                TvRelatedRow(state.related, onOpenItem)
            }
        }
        // La ficha técnica cierra la página, como "Acerca de" en Google TV.
        detail?.facts?.takeIf { it.hasDetails }?.let { facts ->
            tvDetailsSection("details", { stringResource(R.string.tab_details) }) { TvFactsPanel(facts) }
        }
    }
}
