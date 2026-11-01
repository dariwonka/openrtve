package es.openrtve.ui

import es.openrtve.data.HttpStatusException
import es.openrtve.domain.EpisodeOrder
import es.openrtve.domain.ProgramDetail
import es.openrtve.domain.ProgramSeason
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
    fun `API film containers do not request program recommendations`() = runTest(mainDispatcher.dispatcher) {
        var recommendations = 0
        repository.related = { _, _ ->
            recommendations++
            loaded(listOf(catalogItem("related")))
        }
        repository.program = { id, _ -> loaded(detail(id).copy(programTypeId = "137310")) }
        val container = ProgramViewModel(repository, "container", "")
        advanceUntilIdle()
        assertEquals(0, recommendations)
        assertTrue(container.uiState.value.related.isEmpty())

        repository.program = { id, _ -> loaded(detail(id).copy(programTypeId = "136525", programType = "Entrevistas")) }
        val program = ProgramViewModel(repository, "program", "")
        advanceUntilIdle()
        assertEquals(1, recommendations)
        assertEquals(listOf("related"), program.uiState.value.related.map { it.id })
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
    fun `clips and trailers load separately from complete episodes`() = runTest(mainDispatcher.dispatcher) {
        repository.program = { id, _ -> loaded(detail(id).copy(showFragments = true)) }
        repository.programVideos = { _, _, _, _, _ -> page(emptyList()) }
        repository.programExtras = { _, _, _ -> page(listOf(catalogItem("clip"), catalogItem("trailer"))) }
        val viewModel = ProgramViewModel(repository, "1", "")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.episodes.items.isEmpty())
        assertEquals(listOf("clip", "trailer"), viewModel.uiState.value.extras.items.map { it.id })
        assertTrue(repository.calls.any { it.startsWith("programExtras(1") })
    }

    @Test
    fun `selecting a season restarts the list and the same season is a no-op`() = runTest(mainDispatcher.dispatcher) {
        val viewModel = ProgramViewModel(repository, "1", "")
        advanceUntilIdle()
        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.episodes.items.size)

        viewModel.selectSeason("t2")
        assertTrue(viewModel.uiState.value.episodes.items.isEmpty())
        advanceUntilIdle()
        assertEquals(listOf("e1"), viewModel.uiState.value.episodes.items.map { it.id })
        assertTrue(repository.calls.last().contains("season=t2, page=1"))

        val before = repository.calls.size
        viewModel.selectSeason("t2")
        advanceUntilIdle()
        assertEquals(before, repository.calls.size)
    }

    @Test
    fun `a missing sheet does not prevent the episodes from loading`() = runTest(mainDispatcher.dispatcher) {
        repository.program = { _, _ -> throw IOException("404") }
        val viewModel = ProgramViewModel(repository, "1", "Título de la fila")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.detail)
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
    fun `the order follows the RTVE Play rule for the program and the selected season`() = runTest(mainDispatcher.dispatcher) {
        // Serie en emisión con dos temporadas (la más reciente primero): la actual del final, la pasada del principio.
        repository.program = { id, _ ->
            loaded(detail(id).copy(seasons = listOf(ProgramSeason("t2", "Temporada 2", 3, order = 2), ProgramSeason("t1", "Temporada 1", 10, order = 1))))
        }
        val viewModel = ProgramViewModel(repository, "1", "")
        advanceUntilIdle()
        assertEquals(EpisodeOrder.NEWEST_FIRST, viewModel.uiState.value.order)

        viewModel.selectSeason("t2")
        advanceUntilIdle()
        assertEquals(EpisodeOrder.NEWEST_FIRST, viewModel.uiState.value.order)

        viewModel.selectSeason("t1")
        advanceUntilIdle()
        assertEquals(EpisodeOrder.OLDEST_FIRST, viewModel.uiState.value.order)
        assertTrue(repository.calls.last().endsWith("order=OLDEST_FIRST)"))

        viewModel.loadMore()
        advanceUntilIdle()
        assertTrue("las páginas siguientes mantienen el sentido", repository.calls.last().contains("page=2, complete=true, order=OLDEST_FIRST"))
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

    @Test
    fun `chapters beyond the first mixed page become available to the main action and survive season selection`() = runTest(mainDispatcher.dispatcher) {
        val film = catalogItem("film").copy(mediaTypeId = "39816", mediaSubtypeId = "130734")
        val episode = catalogItem("episode").copy(mediaTypeId = "39816")
        repository.program = { id, _ -> loaded(detail(id).copy(programType = "Entrevistas")) }
        repository.programVideos = { _, season, number, _, _ ->
            if (season != null) page(listOf(catalogItem("season-episode"))) else page(listOf(if (number == 1) film else episode), number, 2)
        }
        val viewModel = ProgramViewModel(repository, "program", "")
        advanceUntilIdle()
        val chapters = viewModel.uiState.value.contentSections.first { it.tab == ProgramTab.EPISODES }
        assertTrue(chapters.items.isEmpty())
        viewModel.loadMore(chapters)
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertEquals(listOf("episode"), state.headerEpisodes.filter { it.contentTab(state.structure) == ProgramTab.EPISODES }.map { it.id })
        viewModel.selectSeason("t1")
        advanceUntilIdle()
        assertEquals(listOf("film", "episode"), viewModel.uiState.value.headerEpisodes.map { it.id })
    }

    private fun detail(id: String) = ProgramDetail(
        id = id,
        title = "Telediario",
        description = null,
        imageUrl = null,
        emission = null,
        seasons = listOf(ProgramSeason("t1", "Temporada 1", 10)),
    )
}
