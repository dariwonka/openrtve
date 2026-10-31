package es.openrtve.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import es.openrtve.data.CatalogRepository
import es.openrtve.domain.ExploreCategory
import es.openrtve.domain.ExploreGroup
import es.openrtve.domain.liveRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ExploreUiState(
    val groups: List<ExploreGroup> = emptyList(),
    val isLoading: Boolean = true,
    val error: LoadError? = null,
)

/** Categorías del menú de la app oficial, leídas de la configuración remota. */
class ExploreViewModel(
    private val repository: CatalogRepository,
    /** La TV usa el menú de la app oficial de Android TV. */
    private val tv: Boolean = false,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(ExploreUiState())
    val uiState: StateFlow<ExploreUiState> = mutableUiState.asStateFlow()

    init {
        load()
    }

    fun retry() = load()

    private var job: Job? = null

    private fun load() {
        job?.cancel()
        job = viewModelScope.launch {
            mutableUiState.update { it.copy(isLoading = true, error = null) }
            try {
                val groups = repository.loadExplore(tv = tv).value
                mutableUiState.update { it.copy(groups = groups, isLoading = false) }
                // Fuera de `update`: la red no puede ir dentro de un bucle compare-and-set.
                val enriched = withLiveImages(groups)
                mutableUiState.update { it.copy(groups = enriched) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableUiState.update { it.copy(isLoading = false, error = error.toLoadError()) }
            }
        }
    }

    /**
     * Los canales temáticos llegan sin imagen en el menú; su feed de directo sí la
     * trae. Se piden en paralelo tras pintar el menú y un fallo deja la tarjeta sin imagen.
     */
    private suspend fun withLiveImages(groups: List<ExploreGroup>): List<ExploreGroup> {
        val pending = groups.flatMap { it.categories }.filter { it.isLive && it.imageUrl == null }
        if (pending.isEmpty()) return groups
        val images = coroutineScope { pending.map { async { it.contentUrl to it.liveImage() } }.awaitAll() }
            .mapNotNull { (url, image) -> image?.let { url to it } }
            .toMap()
        return groups.map { group ->
            group.copy(categories = group.categories.map { it.copy(imageUrl = it.imageUrl ?: images[it.contentUrl]) })
        }
    }

    private suspend fun ExploreCategory.liveImage(): String? = try {
        repository.loadModule(liveRow(contentUrl, title)).value.items.firstNotNullOfOrNull { it.imageUrl }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        null
    }
}
