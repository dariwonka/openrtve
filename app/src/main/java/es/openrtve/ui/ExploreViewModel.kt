package es.openrtve.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import es.openrtve.data.CatalogRepository
import es.openrtve.domain.CatalogItem
import es.openrtve.domain.ContentKind
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
    private var liveJob: Job? = null

    private fun load() {
        job?.cancel()
        liveJob?.cancel()
        job = viewModelScope.launch {
            mutableUiState.update { it.copy(isLoading = true, error = null) }
            try {
                val groups = repository.loadExplore(tv = tv).value
                mutableUiState.update { it.copy(groups = groups, isLoading = false) }
                // Fuera de `update`: la red no puede ir dentro de un bucle compare-and-set.
                val enriched = withNowPlaying(groups, forceRefresh = false)
                mutableUiState.update { it.copy(groups = enriched) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableUiState.update { it.copy(isLoading = false, error = error.toLoadError()) }
            }
        }
    }

    /** Lo que emiten los canales temáticos cambia: se renueva mientras se ve Explorar. */
    fun refreshLive() {
        if (job?.isActive == true || liveJob?.isActive == true) return
        liveJob = viewModelScope.launch {
            val refreshed = withNowPlaying(mutableUiState.value.groups, forceRefresh = true)
            mutableUiState.update { it.copy(groups = refreshed) }
        }
    }

    /**
     * Cada canal temático con lo que emite ahora, de su feed de directo (que también
     * trae la imagen que el menú no tiene). Se piden en paralelo tras pintar el menú;
     * un fallo conserva lo que ya había y, sin nada, queda la tarjeta de categoría.
     */
    private suspend fun withNowPlaying(groups: List<ExploreGroup>, forceRefresh: Boolean): List<ExploreGroup> {
        val channels = groups.flatMap { it.categories }.filter { it.isLive }
        if (channels.isEmpty()) return groups
        val playing = coroutineScope { channels.map { async { it.contentUrl to it.currentBroadcast(forceRefresh) } }.awaitAll() }
            .mapNotNull { (url, item) -> item?.let { url to it } }
            .toMap()
        return groups.map { group ->
            group.copy(
                categories = group.categories.map { category ->
                    val now = playing[category.contentUrl] ?: category.nowPlaying
                    category.copy(nowPlaying = now, imageUrl = category.imageUrl ?: now?.imageUrl)
                },
            )
        }
    }

    private suspend fun ExploreCategory.currentBroadcast(forceRefresh: Boolean): CatalogItem? = try {
        repository.loadModule(liveRow(contentUrl, title), forceRefresh).value.items.firstOrNull { it.kind == ContentKind.LIVE }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        null
    }
}
