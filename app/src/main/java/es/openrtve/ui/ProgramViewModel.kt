package es.openrtve.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import es.openrtve.data.CatalogRepository
import es.openrtve.domain.CatalogItem
import es.openrtve.domain.EpisodeOrder
import es.openrtve.domain.episodeOrder
import es.openrtve.domain.isFirstSeason
import es.openrtve.domain.ProgramDetail
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
    val selectedSeasonId: String? = null,
    val episodes: List<CatalogItem> = emptyList(),
    val page: Int = 0,
    val totalPages: Int = 0,
    /** Fragmentos, avances y tráilers; nunca se mezclan con los episodios completos. */
    val extras: PagedItemsState = PagedItemsState(),
    /** "Similares" de RTVE (`recomsys`); vacío si no hay o falló: es contenido secundario. */
    val related: List<CatalogItem> = emptyList(),
    /** Sentido de [episodes]: el de RTVE Play para este programa y temporada. */
    val order: EpisodeOrder = EpisodeOrder.NEWEST_FIRST,
    /**
     * Primera página de la lista completa ("Todos") y su orden: de ella sale el botón
     * grande, que no debe cambiar al mirar otra temporada.
     */
    val headerEpisodes: List<CatalogItem> = emptyList(),
    val headerOrder: EpisodeOrder = EpisodeOrder.NEWEST_FIRST,
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val isStale: Boolean = false,
    val error: LoadError? = null,
) {
    val hasMore: Boolean get() = page < totalPages
}

/** Ficha de programa: detalle + episodios paginados, al estilo de la pantalla de serie de Findroid. */
class ProgramViewModel(
    private val repository: CatalogRepository,
    programId: String,
    initialTitle: String,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(ProgramUiState(programId = programId, title = initialTitle))
    val uiState: StateFlow<ProgramUiState> = mutableUiState.asStateFlow()
    private var episodesJob: Job? = null
    private var extrasJob: Job? = null
    /** La ficha vino de la copia local por un fallo de red; se recuerda al recalcular el aviso. */
    private var detailIsStale = false

    init {
        loadDetailThenEpisodes()
        viewModelScope.launch {
            val related = loadRelatedOrEmpty(repository, RelatedKind.PROGRAM, programId)
            mutableUiState.update { it.copy(related = related) }
        }
    }

    /** La ficha decide de dónde salen los episodios (radio -> audios.json), así que va primero. */
    private fun loadDetailThenEpisodes() {
        episodesJob?.cancel()
        episodesJob = viewModelScope.launch {
            mutableUiState.update { it.copy(isLoading = true, error = null) }
            val programId = mutableUiState.value.programId
            try {
                val detail = repository.loadProgram(programId)
                detailIsStale = detail.isStale
                mutableUiState.update {
                    it.copy(
                        detail = detail.value,
                        title = detail.value.title.ifBlank { it.title },
                        isStale = it.isStale || detail.isStale,
                    )
                }
                if (detail.value.showFragments) loadFirstExtras()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Sin ficha se intenta igualmente con los vídeos; el reintento vuelve a pedirla.
            }
            loadFirstPage()
        }
    }


    /** Sin ficha no hay regla: del más nuevo al más antiguo, como los feeds. */
    private fun orderFor(seasonId: String?): EpisodeOrder =
        mutableUiState.value.detail?.let { it.episodeOrder(isFirstSeason = it.isFirstSeason(seasonId)) } ?: EpisodeOrder.NEWEST_FIRST

    fun selectSeason(seasonId: String?) {
        if (seasonId == mutableUiState.value.selectedSeasonId) return
        mutableUiState.update { it.copy(selectedSeasonId = seasonId, episodes = emptyList(), page = 0, totalPages = 0) }
        loadFirstPage()
    }

    fun loadMore() {
        val state = mutableUiState.value
        if (!state.hasMore || state.isLoadingMore || state.isLoading) return
        episodesJob = viewModelScope.launch {
            mutableUiState.update { it.copy(isLoadingMore = true) }
            try {
                val result = repository.loadProgramVideos(state.programId, state.selectedSeasonId, state.page + 1, completeOnly = true, order = state.order)
                mutableUiState.update {
                    it.copy(
                        episodes = (it.episodes + result.value.items).distinctBy(CatalogItem::id),
                        page = result.value.page,
                        totalPages = result.value.totalPages,
                        isLoadingMore = false,
                        isStale = it.isStale || result.isStale,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableUiState.update { it.copy(isLoadingMore = false, error = error.toLoadError()) }
            }
        }
    }

    fun retry() {
        mutableUiState.update { it.copy(error = null) }
        val state = mutableUiState.value
        when {
            // Sin ficha no hay temporadas, sinopsis ni extras: el reintento empieza por ella.
            state.detail == null -> loadDetailThenEpisodes()
            state.episodes.isEmpty() -> loadFirstPage()
            else -> loadMore()
        }
    }

    fun dismissError() {
        mutableUiState.update { it.copy(error = null) }
    }

    fun loadMoreExtras() {
        val state = mutableUiState.value
        if (!state.extras.hasMore || state.extras.isLoadingMore || state.extras.isLoading) return
        extrasJob = viewModelScope.launch {
            mutableUiState.update { it.copy(extras = it.extras.copy(isLoadingMore = true, error = null)) }
            try {
                val result = repository.loadProgramExtras(state.programId, state.extras.page + 1, EpisodeOrder.NEWEST_FIRST)
                mutableUiState.update {
                    it.copy(
                        extras = it.extras.copy(
                            items = (it.extras.items + result.value.items).distinctBy(CatalogItem::id),
                            page = result.value.page,
                            totalPages = result.value.totalPages,
                            isLoadingMore = false,
                            isStale = it.extras.isStale || result.isStale,
                        ),
                        isStale = it.isStale || result.isStale,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableUiState.update { it.copy(extras = it.extras.copy(isLoadingMore = false, error = error.toLoadError())) }
            }
        }
    }

    fun retryExtras() {
        if (mutableUiState.value.extras.items.isEmpty()) loadFirstExtras() else loadMoreExtras()
    }

    private fun loadFirstPage() {
        episodesJob?.cancel()
        episodesJob = viewModelScope.launch {
            mutableUiState.update { it.copy(isLoading = true, error = null) }
            val programId = mutableUiState.value.programId
            val seasonId = mutableUiState.value.selectedSeasonId
            val order = orderFor(seasonId)
            try {
                val result = repository.loadProgramVideos(programId, seasonId, page = 1, completeOnly = true, order = order)
                mutableUiState.update {
                    it.copy(
                        episodes = result.value.items,
                        page = result.value.page,
                        totalPages = result.value.totalPages,
                        order = order,
                        headerEpisodes = if (seasonId == null) result.value.items else it.headerEpisodes,
                        headerOrder = if (seasonId == null) order else it.headerOrder,
                        isLoading = false,
                        // Una lista nueva (otra temporada) no hereda el aviso de la anterior.
                        isStale = detailIsStale || it.extras.isStale || result.isStale,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableUiState.update { it.copy(isLoading = false, error = error.toLoadError()) }
            }
        }
    }

    private fun loadFirstExtras() {
        extrasJob?.cancel()
        extrasJob = viewModelScope.launch {
            mutableUiState.update { it.copy(extras = it.extras.copy(isLoading = true, error = null)) }
            val state = mutableUiState.value
            try {
                val result = repository.loadProgramExtras(state.programId, page = 1, order = EpisodeOrder.NEWEST_FIRST)
                mutableUiState.update {
                    it.copy(
                        extras = PagedItemsState(
                            items = result.value.items,
                            page = result.value.page,
                            totalPages = result.value.totalPages,
                            isStale = result.isStale,
                        ),
                        isStale = it.isStale || result.isStale,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableUiState.update { it.copy(extras = it.extras.copy(isLoading = false, error = error.toLoadError())) }
            }
        }
    }
}
