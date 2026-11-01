package es.openrtve.ui.tv

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import es.openrtve.R
import es.openrtve.data.CatalogRepository
import es.openrtve.data.WatchHistory
import es.openrtve.domain.CatalogItem
import es.openrtve.ui.ProgramUiState
import es.openrtve.ui.ProgramViewModel
import es.openrtve.ui.facts
import es.openrtve.ui.structure
import es.openrtve.ui.contentSections
import es.openrtve.ui.ProgramTab
import es.openrtve.ui.label
import es.openrtve.ui.metaLine
import es.openrtve.ui.rememberProgramScreen
import es.openrtve.ui.text
import es.openrtve.ui.titleRes

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
    /** Los similares y las piezas de una colección abren su ficha. */
    onOpenRelated: (CatalogItem) -> Unit = onOpenItem,
) {
    val screen = rememberProgramScreen(repository, history, program)
    val viewModel = screen.viewModel
    val state = screen.state
    val suggestion = screen.suggestion
    val structure = state.structure
    val sections = state.contentSections

    TvDetailsScaffold(
        backdropUrl = state.detail?.imageUrl ?: program.imageUrl,
        header = {
            TvDetailsHeaderText(
                title = state.title,
                meta = state.detail?.metaLine(),
                claim = state.detail?.emission,
                synopsis = state.detail?.description,
            )
            state.detailError?.let { error ->
                Spacer(Modifier.height(12.dp))
                Text(error.text(LocalContext.current))
                Button(onClick = viewModel::retry) { Text(stringResource(R.string.action_retry)) }
            }
            if (!structure.isCollection) {
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = { suggestion?.let { onOpenItem(it.item) } },
                    enabled = suggestion != null,
                    // Deshabilitado no admite el foco: se concede en cuanto hay qué reproducir.
                    modifier = Modifier.initialFocus(enabled = suggestion != null),
                ) {
                    Text(
                        text = suggestion?.label(LocalContext.current) ?: stringResource(R.string.action_play),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
    ) {
        sections.forEachIndexed { index, section ->
            tvDetailsSection(section.tab.name, { stringResource(section.tab.titleRes) }) {
                if (section.tab == ProgramTab.EPISODES) SeasonChips(state, viewModel)
                TvContentSection(
                    section,
                    onSelect = { item ->
                        if (section.opensDetails(item, state.structure)) onOpenRelated(item) else onOpenItem(item)
                    },
                    onLoadMore = { viewModel.loadMore(section) },
                    onRetry = { viewModel.retry(section) },
                    entriesById = screen.entriesById,
                    suggestion = suggestion,
                    // Un contenedor no tiene botón de reproducir: el foco empieza en su catálogo.
                    takesInitialFocus = structure.isCollection && index == 0,
                )
            }
        }
        if (!structure.isCollection && state.related.isNotEmpty()) {
            tvDetailsSection("related", { stringResource(R.string.tab_related) }) {
                TvRelatedRow(state.related, onOpenRelated)
            }
        }
        if (!structure.isCollection) {
            state.detail?.facts?.takeIf { it.hasDetails }?.let { facts ->
                tvDetailsSection("details", { stringResource(R.string.tab_details) }) { TvFactsPanel(facts) }
            }
        }
    }
}

/**
 * Temporadas como chips de filtro de TV: se elige al pulsar, así recorrerlas con
 * el mando no lanza una carga por cada una. Al entrar en la fila el foco vuelve a
 * la temporada elegida.
 */
@Composable
private fun SeasonChips(state: ProgramUiState, viewModel: ProgramViewModel) {
    val seasons = state.detail?.seasons.orEmpty()
    if (seasons.isEmpty()) return
    val options = listOf<Pair<String?, String>>(null to stringResource(R.string.program_all_seasons)) + seasons.map { it.id to it.title }
    val selectedChip = remember { FocusRequester() }
    TvLazyRow(
        contentPadding = TvRowPadding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .padding(bottom = 16.dp)
            .focusProperties { onEnter = { selectedChip.requestFocus() } }
            .focusGroup(),
    ) {
        items(options, key = { it.first ?: "all" }) { (seasonId, title) ->
            val selected = seasonId == state.selectedSeasonId
            TvChoiceButton(
                label = title,
                selected = selected,
                onClick = { viewModel.selectSeason(seasonId) },
                modifier = if (selected) Modifier.focusRequester(selectedChip) else Modifier,
            )
        }
    }
}
