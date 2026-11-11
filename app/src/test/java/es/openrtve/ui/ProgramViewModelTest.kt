package es.openrtve.ui

import es.openrtve.data.HttpStatusException
import es.openrtve.domain.EpisodeOrder
import es.openrtve.domain.ProgramDetail
import es.openrtve.testing.FakeCatalogRepository
import es.openrtve.testing.MainDispatcherRule
import es.openrtve.testing.catalogItem
import es.openrtve.testing.loaded
import es.openrtve.testing.page
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProgramViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val repository = FakeCatalogRepository().apply {
        program = { id, _ -> loaded(detail(id)) }
        programVideos = { _, _, pageNumber, _, _ -> page(listOf(catalogItem("e$pageNumber")), page = pageNumber, totalPages = 2) }
    }

    @Test
    fun `detail arrives first and the first page of complete episodes follows`() = runTest(mainDispatcher.dispatcher) {
        val viewModel = ProgramViewModel(repository, "135930", "Telediario (inicial)")
        assertTrue(viewModel.uiState.value.episodes.isLoading)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Telediario", state.title)
        assertEquals(listOf("e1"), state.episodes.items.map { it.id })
        assertEquals(1, state.episodes.page)
        assertTrue(state.episodes.hasMore)
        assertFalse(state.episodes.isLoading)
        assertEquals(listOf("program(135930, force=false)", "programVideos(135930, season=null, page=1, complete=true, order=NEWEST_FIRST)"), repository.calls)
    }

    @Test
    fun `load more appends the next page and ignores duplicates and double taps`() = runTest(mainDispatcher.dispatcher) {
        val secondPage = CompletableDeferred<Unit>()
        repository.programVideos = { _, _, pageNumber, _, _ ->
            if (pageNumber == 2) secondPage.await()
            page(listOf(catalogItem("e$pageNumber"), catalogItem("shared")), page = pageNumber, totalPages = 2)
        }
        val viewModel = ProgramViewModel(repository, "1", "")
        advanceUntilIdle()

        viewModel.loadMore()
        runCurrent()
        assertTrue(viewModel.uiState.value.episodes.isLoadingMore)
        viewModel.loadMore()
        runCurrent()
        secondPage.complete(Unit)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("e1", "shared", "e2"), state.episodes.items.map { it.id })
        assertFalse(state.episodes.hasMore)
        assertEquals(1, repository.calls.count { "page=2" in it })

        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals("sin más páginas no se pide nada", 1, repository.calls.count { "page=2" in it })
    }

    @Test
    fun `a missing sheet does not prevent the episodes from loading`() = runTest(mainDispatcher.dispatcher) {
        repository.program = { _, _ -> throw IOException("404") }
        val viewModel = ProgramViewModel(repository, "1", "Título de la fila")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Título de la fila", state.title)
        assertEquals(listOf("e1"), state.episodes.items.map { it.id })
        assertNull(state.episodes.error)
    }

    @Test
    fun `episode errors are reported and retry reloads the right page`() = runTest(mainDispatcher.dispatcher) {
        repository.programVideos = { _, _, _, _, _ -> throw SocketTimeoutException() }
        val viewModel = ProgramViewModel(repository, "1", "")
        advanceUntilIdle()
        assertEquals(LoadError.Timeout, viewModel.uiState.value.episodes.error)
        assertFalse(viewModel.uiState.value.episodes.isLoading)

        repository.programVideos = { _, _, pageNumber, _, _ -> page(listOf(catalogItem("e$pageNumber")), page = pageNumber, totalPages = 2) }
        viewModel.retry()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.episodes.error)
        assertEquals(listOf("e1"), viewModel.uiState.value.episodes.items.map { it.id })

        repository.programVideos = { _, _, _, _, _ -> throw IOException() }
        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals(LoadError.Unknown, viewModel.uiState.value.episodes.error)
        assertFalse(viewModel.uiState.value.episodes.isLoadingMore)

        repository.programVideos = { _, _, pageNumber, _, _ -> page(listOf(catalogItem("e$pageNumber")), page = pageNumber, totalPages = 2) }
        viewModel.retry()
        advanceUntilIdle()
        assertEquals("con episodios, reintentar pide la siguiente página", listOf("e1", "e2"), viewModel.uiState.value.episodes.items.map { it.id })
    }

    @Test
    fun `a complete series is read from the beginning and one without detail from the end`() = runTest(mainDispatcher.dispatcher) {
        repository.program = { id, _ -> loaded(detail(id).copy(isComplete = true)) }
        val complete = ProgramViewModel(repository, "1", "")
        advanceUntilIdle()
        assertEquals(EpisodeOrder.OLDEST_FIRST, complete.uiState.value.order)

        repository.program = { _, _ -> throw HttpStatusException(500) }
        val unknown = ProgramViewModel(repository, "2", "")
        advanceUntilIdle()
        assertEquals(EpisodeOrder.NEWEST_FIRST, unknown.uiState.value.order)
    }

    private fun detail(id: String) = ProgramDetail(
        id = id,
        title = "Telediario",
    )
}
