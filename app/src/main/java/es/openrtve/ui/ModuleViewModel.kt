package es.openrtve.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import es.openrtve.data.CatalogRepository
import es.openrtve.domain.CatalogItem
import es.openrtve.domain.HomeRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ModuleUiState(
    val title: String,
    val items: List<CatalogItem> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isStale: Boolean = false,
    /** Fallo de un refresco con contenido ya pintado: aviso de una vez. */
    val error: LoadError? = null,
    /** Fallo que deja la rejilla vacía: se explica en el sitio, con su reintento. */
    val failure: LoadError? = null,
)

/** Rejilla completa de una fila ("Ver todo"). La caché evita repetir la petición de la portada. */
class ModuleViewModel(
    private val repository: CatalogRepository,
    private val row: HomeRow,
    initialTitle: String,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(ModuleUiState(title = initialTitle))
    val uiState: StateFlow<ModuleUiState> = mutableUiState.asStateFlow()
    private var job: Job? = null

    init {
        load(forceRefresh = false)
    }

    fun refresh() = load(forceRefresh = true)

    fun dismissError() {
        mutableUiState.update { it.copy(error = null) }
    }

    private fun load(forceRefresh: Boolean) {
        job?.cancel()
        job = viewModelScope.launch {
            val hasItems = mutableUiState.value.items.isNotEmpty()
            mutableUiState.update { it.copy(isLoading = !hasItems, isRefreshing = hasItems, error = null, failure = null) }
            try {
                val module = repository.loadModule(row, forceRefresh)
                mutableUiState.update {
                    it.copy(
                        title = module.value.title.ifBlank { it.title },
                        items = module.value.items,
                        isLoading = false,
                        isRefreshing = false,
                        isStale = module.isStale,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableUiState.update {
                    val loadError = error.toLoadError()
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        error = loadError.takeIf { _ -> hasItems },
                        failure = loadError.takeUnless { _ -> hasItems },
                    )
                }
            }
        }
    }
}
