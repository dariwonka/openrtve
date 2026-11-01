package es.openrtve.ui

import es.openrtve.domain.CatalogItem
import es.openrtve.domain.CatalogPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Una lista paginada de una ficha (capítulos, extras): sus piezas, por dónde va y cómo está su carga. */
data class PagedItemsState(
    val items: List<CatalogItem> = emptyList(),
    val page: Int = 0,
    val totalPages: Int = 0,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    /** Se explica en la propia sección, con su reintento. */
    val error: LoadError? = null,
) {
    val hasMore: Boolean get() = page < totalPages
}

/**
 * Carga una [PagedItemsState] que vive dentro del estado [S] de una pantalla:
 * la primera página o la siguiente, acumulando sin duplicados y dejando el fallo
 * en la propia lista. [onLoaded] deriva del resultado lo que haga falta.
 */
internal class PagedItemsLoader<S>(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<S>,
    private val get: (S) -> PagedItemsState,
    private val set: S.(PagedItemsState) -> S,
    private val onLoaded: S.() -> S = { this },
    private val fetch: suspend (page: Int) -> CatalogPage,
) {
    private var job: Job? = null

    /** La siguiente página se ignora si no la hay o ya se está pidiendo algo; la primera reemplaza lo anterior. */
    fun load(firstPage: Boolean) {
        val current = get(state.value)
        if (!firstPage && (!current.hasMore || current.isLoading || current.isLoadingMore)) return
        job?.cancel()
        job = scope.launch {
            state.update { it.set(get(it).copy(isLoading = firstPage, isLoadingMore = !firstPage, error = null)) }
            try {
                val result = fetch(if (firstPage) 1 else get(state.value).page + 1)
                state.update {
                    val previous = if (firstPage) emptyList() else get(it).items
                    it.set(PagedItemsState((previous + result.items).distinctBy(CatalogItem::id), result.page, result.totalPages)).onLoaded()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.set(get(it).copy(isLoading = false, isLoadingMore = false, error = error.toLoadError())) }
            }
        }
    }

    fun loadMore() = load(firstPage = false)

    /** Sin nada cargado se vuelve a pedir la primera página; si no, la que falló. */
    fun retry() = load(firstPage = get(state.value).items.isEmpty())

    fun cancel() {
        job?.cancel()
    }
}
