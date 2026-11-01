package es.openrtve.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import es.openrtve.data.CatalogRepository
import es.openrtve.domain.CatalogItem
import es.openrtve.domain.HomeLink
import es.openrtve.domain.HomeRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

sealed interface SectionState {
    data object Loading : SectionState
    data class Loaded(val items: List<CatalogItem>, val isStale: Boolean) : SectionState
    /** Fila `links`: sus accesos llegan con la portada, sin carga propia. */
    data class Links(val links: List<HomeLink>) : SectionState
    data class Failed(val error: LoadError) : SectionState
}

/** Estado inicial de una fila: los enlaces ya están; el resto se carga. */
private fun HomeRow.initialSection() = HomeSection(this, if (isInline) SectionState.Links(links) else SectionState.Loading)

data class HomeSection(
    val row: HomeRow,
    val state: SectionState,
    /** Título de la fila o, si la portada no lo trae, el de la propia colección. */
    val title: String = row.title,
)

data class PortadaUiState(
    val title: String = "",
    val sections: List<HomeSection> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isFeedStale: Boolean = false,
    /** Fallo que deja la portada sin nada que pintar: se explica en el sitio, con su reintento. */
    val error: LoadError? = null,
) {
    val isStale: Boolean
        get() = isFeedStale || sections.any { (it.state as? SectionState.Loaded)?.isStale == true }
}

/**
 * Una portada (`index_apps.json`): la principal o una temática.
 * Cada fila se carga por separado para que una lenta no bloquee al resto.
 */
class PortadaViewModel(
    private val repository: CatalogRepository,
    private val url: String,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(PortadaUiState())
    val uiState: StateFlow<PortadaUiState> = mutableUiState.asStateFlow()

    private var feedJob: Job? = null
    /** Ámbito de las cargas de sección del feed actual; un refresh las cancela en bloque. */
    private var sectionsScope: CoroutineScope? = null
    private val sectionLoads = Semaphore(MAX_PARALLEL_SECTION_LOADS)

    init {
        load(forceRefresh = false)
    }

    fun refresh() = load(forceRefresh = true)

    private var lastLoadedAtMillis = 0L

    /** Al volver a primer plano: si la portada tiene más de [REFRESH_AFTER_MS] (el `refresh` de la configuración oficial), se recarga. */
    fun onResumed() {
        if (System.currentTimeMillis() - lastLoadedAtMillis > REFRESH_AFTER_MS && !mutableUiState.value.isLoading) {
            load(forceRefresh = false)
        }
    }

    /** Las filas de directos cambian cada pocos minutos: se recargan solas mientras la pantalla está visible. */
    fun refreshLiveSections() {
        val scope = sectionsScope ?: return
        mutableUiState.value.sections
            .filter { it.row.isLive && it.state !is SectionState.Loading }
            .forEach { section -> scope.launch { loadSection(section.row, forceRefresh = true) } }
    }

    fun retrySection(row: HomeRow) {
        val alreadyLoading = mutableUiState.value.sections
            .any { it.row.id == row.id && it.state is SectionState.Loading }
        if (alreadyLoading) return
        val scope = sectionsScope ?: return
        updateSection(row.id, SectionState.Loading)
        scope.launch { loadSection(row, forceRefresh = true) }
    }

    private fun load(forceRefresh: Boolean) {
        feedJob?.cancel()
        sectionsScope?.cancel()
        val scope = CoroutineScope(
            viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]),
        ).also { sectionsScope = it }
        feedJob = viewModelScope.launch {
            // Primero la copia local, aunque esté caducada: la portada aparece al
            // instante y la red solo la revalida (con ETag, un 304 si no cambió).
            val cachedModules = if (mutableUiState.value.sections.isEmpty()) showCachedCopy(scope) else emptyList()
            val hadSections = mutableUiState.value.sections.isNotEmpty()
            mutableUiState.update { it.copy(isLoading = !hadSections, isRefreshing = hadSections, error = null) }
            val feed = try {
                repository.loadPortada(url, forceRefresh)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableUiState.update { ui ->
                    ui.copy(
                        isLoading = false,
                        isRefreshing = false,
                        // Con copia local mostrada, un fallo de red solo la marca como antigua.
                        isFeedStale = hadSections,
                        error = if (hadSections) null else error.toLoadError(),
                    )
                }
                // Las filas que no tenían copia local se intentan por su cuenta: sin esto
                // se quedarían para siempre con su hueco de carga. Se espera antes a la
                // lectura de la caché para no tapar con un error una copia que ya llega.
                cachedModules.joinAll()
                mutableUiState.value.sections
                    .filter { it.state is SectionState.Loading }
                    .forEach { section -> scope.launch { loadSection(section.row, forceRefresh = false) } }
                return@launch
            }

            val rows = feed.value.rows.filter { it.isRenderable }
            lastLoadedAtMillis = System.currentTimeMillis()
            mutableUiState.update { ui ->
                // Las filas remotas que ya estaban conservan su contenido mientras se revalidan;
                // las inline vienen completas en el feed nuevo.
                val previous = ui.sections.filter { !it.row.isInline }.associateBy { it.row.contentUrl }
                ui.copy(
                    title = feed.value.title.trim(),
                    sections = rows.map { row -> previous[row.contentUrl]?.copy(row = row) ?: row.initialSection() },
                    isLoading = false,
                    isRefreshing = false,
                    isFeedStale = feed.isStale,
                )
            }
            rows.filter { !it.isInline }.forEach { row -> scope.launch { loadSection(row, forceRefresh) } }
        }
    }

    /** Pinta la copia local y devuelve las lecturas de módulos que lanza, para poder esperarlas. */
    private suspend fun showCachedCopy(scope: CoroutineScope): List<Job> {
        val cached = try {
            repository.loadPortada(url, cachedOnly = true)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return emptyList()
        }
        val rows = cached.value.rows.filter { it.isRenderable }
        mutableUiState.update {
            it.copy(
                title = cached.value.title.trim(),
                sections = rows.map(HomeRow::initialSection),
                isLoading = false,
            )
        }
        return rows.filter { !it.isInline }.map { row ->
            scope.launch {
                val module = try {
                    repository.loadModule(row, cachedOnly = true)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    return@launch
                }
                // Solo si la fila sigue pendiente: la revalidación puede haber llegado antes.
                mutableUiState.update { ui ->
                    ui.copy(
                        sections = ui.sections.map { section ->
                            if (section.row.id == row.id && section.state is SectionState.Loading) {
                                // Sin aviso de "antiguo": la red la revalida enseguida y solo si falla se marca.
                                section.copy(state = SectionState.Loaded(module.value.items, isStale = false), title = module.value.title.ifBlank { section.title })
                            } else {
                                section
                            }
                        },
                    )
                }
            }
        }
    }

    private suspend fun loadSection(row: HomeRow, forceRefresh: Boolean) {
        var title: String? = null
        val state = sectionLoads.withPermit {
            try {
                val module = repository.loadModule(row, forceRefresh)
                title = module.value.title
                SectionState.Loaded(module.value.items, module.isStale)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                SectionState.Failed(error.toLoadError())
            }
        }
        // Como en Findroid, una sección vacía desaparece en vez de mostrar un hueco.
        if (state is SectionState.Loaded && state.items.isEmpty()) {
            mutableUiState.update { ui -> ui.copy(sections = ui.sections.filterNot { it.row.id == row.id }) }
        } else {
            updateSection(row.id, state, title)
        }
    }

    private fun updateSection(rowId: Int, state: SectionState, title: String? = null) {
        mutableUiState.update { ui ->
            ui.copy(
                sections = ui.sections.map { section ->
                    if (section.row.id != rowId) {
                        section
                    } else {
                        section.copy(state = state, title = title?.ifBlank { null } ?: section.title)
                    }
                },
            )
        }
    }

    private companion object {
        const val MAX_PARALLEL_SECTION_LOADS = 6
        const val REFRESH_AFTER_MS = 120_000L
    }
}
