package es.openrtve.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import es.openrtve.data.CatalogRepository
import es.openrtve.domain.CatalogItem
import es.openrtve.domain.EpisodeOrder
import es.openrtve.domain.episodeOrder
import es.openrtve.domain.isFirstSeason
import es.openrtve.domain.ProgramDetail
import es.openrtve.domain.structure
import es.openrtve.domain.RelatedKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProgramUiState(
    val programId: String,
    val title: String,
    val detail: ProgramDetail? = null,
    /** Los metadatos pueden fallar aunque la lista de vídeos esté disponible. */
    val detailError: LoadError? = null,
    val selectedSeasonId: String? = null,
    /** Emisiones completas de la temporada elegida; carga ya mientras llega la ficha. */
    val episodes: PagedItemsState = PagedItemsState(isLoading = true),
    /** Sentido de [episodes]: el de RTVE Play para este programa y temporada. */
    val order: EpisodeOrder = EpisodeOrder.NEWEST_FIRST,
    /** Fragmentos, avances y tráilers; nunca se mezclan con los episodios completos. */
    val extras: PagedItemsState = PagedItemsState(),
    /** "Similares" de RTVE (`recomsys`); vacío si no hay o falló: es contenido secundario. */
    val related: List<CatalogItem> = emptyList(),
    /**
     * Páginas cargadas de la lista completa ("Todos") y su orden: de ellas sale el
     * botón grande, que no debe cambiar al mirar otra temporada.
     */
    val headerEpisodes: List<CatalogItem> = emptyList(),
    val headerOrder: EpisodeOrder = EpisodeOrder.NEWEST_FIRST,
)

/** Ficha de programa: la ficha primero (decide temporadas, orden y extras) y después sus listas paginadas. */
class ProgramViewModel(
    private val repository: CatalogRepository,
    programId: String,
    initialTitle: String,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(ProgramUiState(programId = programId, title = initialTitle))
    val uiState: StateFlow<ProgramUiState> = mutableUiState.asStateFlow()
    private var detailJob: Job? = null

    private val episodeLoader = PagedItemsLoader(
        viewModelScope, mutableUiState, ProgramUiState::episodes, { copy(episodes = it) },
        onLoaded = { if (selectedSeasonId == null) copy(headerEpisodes = episodes.items, headerOrder = order) else this },
    ) { page ->
        val state = mutableUiState.value
        repository.loadProgramVideos(state.programId, state.selectedSeasonId, page, completeOnly = true, order = state.order).value
    }

    private val extrasLoader = PagedItemsLoader(viewModelScope, mutableUiState, ProgramUiState::extras, { copy(extras = it) }) { page ->
        repository.loadProgramExtras(mutableUiState.value.programId, page, EpisodeOrder.NEWEST_FIRST).value
    }

    init {
        loadDetailThenEpisodes()
    }

    private fun loadDetailThenEpisodes() {
        detailJob?.cancel()
        episodeLoader.cancel()
        detailJob = viewModelScope.launch {
            mutableUiState.update { it.copy(detailError = null, episodes = it.episodes.copy(isLoading = true, error = null)) }
            val programId = mutableUiState.value.programId
            try {
                val detail = repository.loadProgram(programId).value
                mutableUiState.update { it.copy(detail = detail, title = detail.title.ifBlank { it.title }) }
                if (detail.showFragments) extrasLoader.load(firstPage = true)
                if (!detail.structure.isCollection) launch {
                    val related = loadRelatedOrEmpty(repository, RelatedKind.PROGRAM, programId)
                    mutableUiState.update { it.copy(related = related) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableUiState.update { it.copy(detailError = error.toLoadError()) }
            }
            mutableUiState.update { it.copy(order = orderFor(it.selectedSeasonId)) }
            episodeLoader.load(firstPage = true)
        }
    }

    /** Sin ficha no hay regla: del más nuevo al más antiguo, como los feeds. */
    private fun orderFor(seasonId: String?): EpisodeOrder =
        mutableUiState.value.detail?.let { it.episodeOrder(isFirstSeason = it.isFirstSeason(seasonId)) } ?: EpisodeOrder.NEWEST_FIRST

    fun loadMore() = episodeLoader.loadMore()

    /** Sin ficha no hay temporadas, sinopsis ni extras: el reintento empieza por ella. */
    fun retry() {
        if (mutableUiState.value.detail == null) loadDetailThenEpisodes() else episodeLoader.retry()
    }

    fun loadMore(section: ContentSection) {
        if (section.loadsComplete) episodeLoader.loadMore()
        if (section.loadsExtras) extrasLoader.loadMore()
    }

    fun retry(section: ContentSection) {
        if (section.loadsComplete) retry()
        if (section.loadsExtras) extrasLoader.retry()
    }
}
