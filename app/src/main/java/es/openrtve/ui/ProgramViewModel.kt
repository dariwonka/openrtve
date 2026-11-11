package es.openrtve.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import es.openrtve.data.CatalogRepository
import es.openrtve.domain.EpisodeOrder
import es.openrtve.domain.episodeOrder
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
    /** Vídeos completos del programa, página a página; cargan ya mientras llega la ficha. */
    val episodes: PagedItemsState = PagedItemsState(isLoading = true),
    /** Sentido de [episodes]: el de RTVE Play para este programa. */
    val order: EpisodeOrder = EpisodeOrder.NEWEST_FIRST,
)

/** Página de una serie: la ficha decide el orden de los vídeos y después se cargan paginados. */
class ProgramViewModel(
    private val repository: CatalogRepository,
    programId: String,
    initialTitle: String,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(ProgramUiState(programId = programId, title = initialTitle))
    val uiState: StateFlow<ProgramUiState> = mutableUiState.asStateFlow()
    private var startJob: Job? = null
    private var detailLoaded = false

    private val episodeLoader = PagedItemsLoader(viewModelScope, mutableUiState, ProgramUiState::episodes, { copy(episodes = it) }) { page ->
        val state = mutableUiState.value
        repository.loadProgramVideos(state.programId, page = page, completeOnly = true, order = state.order).value
    }

    init {
        start()
    }

    private fun start() {
        startJob?.cancel()
        episodeLoader.cancel()
        startJob = viewModelScope.launch {
            mutableUiState.update { it.copy(episodes = it.episodes.copy(isLoading = true, error = null)) }
            try {
                val detail = repository.loadProgram(mutableUiState.value.programId).value
                detailLoaded = true
                mutableUiState.update {
                    it.copy(title = detail.title.ifBlank { it.title }, order = detail.episodeOrder())
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Sin ficha, los vídeos van del más nuevo al más antiguo, como los feeds.
            }
            episodeLoader.load(firstPage = true)
        }
    }

    fun loadMore() = episodeLoader.loadMore()

    /** Sin ficha el reintento empieza por ella; si no, por la página que falló. */
    fun retry() {
        if (detailLoaded) episodeLoader.retry() else start()
    }
}
