package es.openrtve.ui

import es.openrtve.data.HttpStatusException
import es.openrtve.domain.CatalogModule
import es.openrtve.domain.CatalogPage
import es.openrtve.domain.QuickFilter
import es.openrtve.domain.SearchResults
import es.openrtve.testing.FakeCatalogRepository
import es.openrtve.testing.MainDispatcherRule
import es.openrtve.testing.catalogItem
import es.openrtve.testing.loaded
import java.net.UnknownHostException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Lo que enseña el filtro rápido elegido; vacío mientras carga o si falló. */
private val SearchUiState.filterItems get() = (filter as? FilterState.Loaded)?.items.orEmpty()

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val repository = FakeCatalogRepository().apply {
        quickFilters = { loaded(listOf(FILTER_TOP, FILTER_SERIES)) }
        quickFilterItems = { filter -> loaded(CatalogModule(filter.title, listOf(catalogItem("${filter.title}-1")))) }
        search = { query -> SearchResults(programs = emptyList(), videos = listOf(catalogItem("r-$query"))) }
    }

    @Test
    fun `quick filters load and the first one is selected with its items`() = runTest(mainDispatcher.dispatcher) {
        val viewModel = SearchViewModel(repository)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf(FILTER_TOP, FILTER_SERIES), state.filters)
        assertEquals(FILTER_TOP, state.selectedFilter)
        assertEquals(listOf("Más buscados-1"), state.filterItems.map { it.id })
        assertFalse(state.isTextSearch)
    }

    @Test
    fun `selecting a filter replaces the items and reselecting it does not refetch`() = runTest(mainDispatcher.dispatcher) {
        val viewModel = SearchViewModel(repository)
        advanceUntilIdle()

        viewModel.selectFilter(FILTER_SERIES)
        assertTrue("se vacía mientras carga", viewModel.uiState.value.filterItems.isEmpty())
        advanceUntilIdle()
        assertEquals(listOf("Series-1"), viewModel.uiState.value.filterItems.map { it.id })

        val before = repository.calls.size
        viewModel.selectFilter(FILTER_SERIES)
        advanceUntilIdle()
        assertEquals(before, repository.calls.size)
    }

    @Test
    fun `typing searches after a debounce and only from three characters`() = runTest(mainDispatcher.dispatcher) {
        val viewModel = SearchViewModel(repository)
        advanceUntilIdle()

        viewModel.setQuery("te")
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.results)
        assertTrue(repository.calls.none { it.startsWith("search") })

        viewModel.setQuery("tel")
        viewModel.setQuery("tele")
        advanceTimeBy(200)
        assertTrue("todavía dentro del debounce", repository.calls.none { it.startsWith("search") })
        advanceUntilIdle()

        assertEquals(listOf("search(tele)"), repository.calls.filter { it.startsWith("search") })
        assertEquals(listOf("r-tele"), viewModel.uiState.value.results!!.videos.map { it.id })
        assertFalse(viewModel.uiState.value.isSearching)
        assertTrue(viewModel.uiState.value.isTextSearch)
    }

    @Test
    fun `search failures are shown in place and retry repeats the same query`() = runTest(mainDispatcher.dispatcher) {
        var fail = true
        repository.search = { query -> if (fail) throw UnknownHostException() else SearchResults(emptyList(), listOf(catalogItem("r-$query"))) }
        val viewModel = SearchViewModel(repository)
        advanceUntilIdle()

        viewModel.setQuery("cine")
        advanceUntilIdle()
        assertEquals(LoadError.Offline, viewModel.uiState.value.searchError)
        assertNull("sin resultados de otra consulta", viewModel.uiState.value.results)
        assertFalse(viewModel.uiState.value.isSearching)

        fail = false
        viewModel.retrySearch()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.searchError)
        assertEquals(listOf("r-cine"), viewModel.uiState.value.results!!.videos.map { it.id })
    }

    @Test
    fun `more videos come from the relative query of the search block`() = runTest(mainDispatcher.dispatcher) {
        repository.search = { SearchResults(emptyList(), listOf(catalogItem("v1")), videosPage = 1, videosTotalPages = 2, videosQuery = "/contents?search=cine") }
        repository.searchPage = { _, page -> CatalogPage(listOf(catalogItem("v$page"), catalogItem("v1")), page = page, totalPages = 2) }
        val viewModel = SearchViewModel(repository)
        advanceUntilIdle()
        viewModel.setQuery("cine")
        advanceUntilIdle()

        viewModel.loadMoreVideos()
        advanceUntilIdle()
        val results = viewModel.uiState.value.results!!
        assertEquals(listOf("v1", "v2"), results.videos.map { it.id })
        assertFalse(results.hasMoreVideos)
        assertTrue(repository.calls.contains("searchPage(/contents?search=cine, page=2)"))
    }

    @Test
    fun `a failing filter feed leaves the screen usable for text search`() = runTest(mainDispatcher.dispatcher) {
        repository.quickFilters = { throw HttpStatusException(500) }
        val viewModel = SearchViewModel(repository)
        advanceUntilIdle()

        assertEquals("se pinta en el sitio, con reintento", FilterState.Failed(LoadError.Http(500)), viewModel.uiState.value.filter)
        assertNull(viewModel.uiState.value.searchError)
        assertTrue(viewModel.uiState.value.filters.isEmpty())

        viewModel.setQuery("cine")
        advanceUntilIdle()
        assertEquals(listOf("r-cine"), viewModel.uiState.value.results!!.videos.map { it.id })
        assertNull("una búsqueda con éxito no tiene error", viewModel.uiState.value.searchError)
    }

    private companion object {
        val FILTER_TOP = QuickFilter("Más buscados", "https://recomsys.rtve.es/tops")
        val FILTER_SERIES = QuickFilter("Series", "https://www.rtve.es/api/series.json")
    }
}
