package es.openrtve.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import es.openrtve.data.CatalogRepository
import es.openrtve.domain.CatalogItem
import es.openrtve.domain.QuickFilter
import es.openrtve.domain.SearchResults
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Contenido del filtro rápido seleccionado: cargando, cargado (quizá vacío) o fallido. */
sealed interface FilterState {
    data object Loading : FilterState
    data class Loaded(val items: List<CatalogItem>) : FilterState
    data class Failed(val error: LoadError) : FilterState
}

data class SearchUiState(
    val query: String = "",
    val filters: List<QuickFilter> = emptyList(),
    val selectedFilter: QuickFilter? = null,
    /** Items del filtro rápido seleccionado (cuando no hay texto). */
    val filter: FilterState = FilterState.Loading,
    /** Resultados del texto escrito (a partir de [MIN_QUERY_LENGTH] caracteres). */
    val results: SearchResults? = null,
    val isSearching: Boolean = false,
    val isLoadingMoreVideos: Boolean = false,
    /** Fallo de la búsqueda: se pinta en el sitio de los resultados, con su reintento. */
    val searchError: LoadError? = null,
    /** Sube con cada reintento para repetir la misma consulta. */
    internal val attempt: Int = 0,
) {
    val isTextSearch: Boolean get() = query.trim().length >= MIN_QUERY_LENGTH

    companion object {
        const val MIN_QUERY_LENGTH = 3
    }
}

/**
 * Buscador al estilo de la app oficial: campo de texto y, debajo, pestañas de
 * búsquedas predefinidas mientras no se escribe nada.
 */
class SearchViewModel(
    private val repository: CatalogRepository,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = mutableUiState.asStateFlow()
    private var filterJob: Job? = null
    private var moreVideosJob: Job? = null

    init {
        loadFilters()
        observeQuery()
    }

    private fun loadFilters() {
        filterJob?.cancel()
        mutableUiState.update { it.copy(filter = FilterState.Loading) }
        filterJob = viewModelScope.launch {
            try {
                val filters = repository.loadQuickFilters().value
                mutableUiState.update { it.copy(filters = filters) }
                val first = filters.firstOrNull()
                if (first != null) selectFilter(first) else mutableUiState.update { it.copy(filter = FilterState.Loaded(emptyList())) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // En el sitio de los filtros, con su reintento: si no, la pantalla se queda en blanco.
                mutableUiState.update { it.copy(filter = FilterState.Failed(error.toLoadError())) }
            }
        }
    }

    fun setQuery(query: String) {
        mutableUiState.update { it.copy(query = query) }
    }

    fun selectFilter(filter: QuickFilter) {
        val current = mutableUiState.value
        if (filter == current.selectedFilter && current.filter is FilterState.Loaded) return
        filterJob?.cancel()
        mutableUiState.update { it.copy(selectedFilter = filter, filter = FilterState.Loading) }
        filterJob = viewModelScope.launch {
            val loaded = try {
                FilterState.Loaded(repository.loadQuickFilterItems(filter).value.items)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // El fallo se pinta en el sitio del filtro, con su reintento; no como aviso suelto.
                FilterState.Failed(error.toLoadError())
            }
            mutableUiState.update { it.copy(filter = loaded) }
        }
    }

    /** Repite la búsqueda actual tras un fallo. */
    fun retrySearch() {
        mutableUiState.update { it.copy(attempt = it.attempt + 1) }
    }

    /** Siguiente página de vídeos, con el `query` relativo que da el propio buscador. */
    fun loadMoreVideos() {
        val state = mutableUiState.value
        val results = state.results ?: return
        val query = results.videosQuery ?: return
        if (!results.hasMoreVideos || state.isLoadingMoreVideos || state.isSearching) return
        moreVideosJob = viewModelScope.launch {
            mutableUiState.update { it.copy(isLoadingMoreVideos = true) }
            try {
                val page = repository.searchPage(query, results.videosPage + 1)
                mutableUiState.update { ui ->
                    // Solo si la consulta no cambió mientras tanto.
                    val current = ui.results?.takeIf { it.videosQuery == query } ?: return@update ui.copy(isLoadingMoreVideos = false)
                    ui.copy(
                        results = current.copy(
                            videos = (current.videos + page.items).distinctBy(CatalogItem::id),
                            videosPage = page.page,
                            videosTotalPages = page.totalPages,
                        ),
                        isLoadingMoreVideos = false,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // La lista ya pintada sigue ahí; el pie vuelve a ofrecer "Cargar más".
                mutableUiState.update { it.copy(isLoadingMoreVideos = false) }
            }
        }
    }

    private fun observeQuery() {
        viewModelScope.launch {
            mutableUiState
                .map { it.query.trim() to it.attempt }
                .distinctUntilChanged()
                // Cancela antes de esperar: durante el debounce tampoco puede publicarse la consulta anterior.
                .collectLatest { (query, attempt) ->
                    moreVideosJob?.cancel()
                    if (query.length < SearchUiState.MIN_QUERY_LENGTH) {
                        mutableUiState.update { it.copy(results = null, isSearching = false, isLoadingMoreVideos = false, searchError = null) }
                        return@collectLatest
                    }
                    mutableUiState.update { it.copy(results = null, isSearching = true, isLoadingMoreVideos = false, searchError = null) }
                    delay(QUERY_DEBOUNCE_MS)
                    try {
                        val results = repository.search(query)
                        mutableUiState.update {
                            if (it.query.trim() == query && it.attempt == attempt) it.copy(results = results, isSearching = false) else it
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        // Sin resultados viejos de otra consulta: el fallo ocupa su sitio.
                        mutableUiState.update { it.copy(results = null, isSearching = false, searchError = error.toLoadError()) }
                    }
                }
        }
    }

    private companion object {
        const val QUERY_DEBOUNCE_MS = 400L
    }
}
