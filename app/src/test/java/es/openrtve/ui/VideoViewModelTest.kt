package es.openrtve.ui


import es.openrtve.data.HttpStatusException
import es.openrtve.domain.ContentKind
import es.openrtve.domain.VideoDetail
import es.openrtve.testing.FakeCatalogRepository
import es.openrtve.testing.MainDispatcherRule
import es.openrtve.testing.catalogItem
import es.openrtve.testing.loaded
import es.openrtve.testing.page
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VideoViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val repository = FakeCatalogRepository().apply {
        video = { id, _ -> loaded(detail(catalogItem(id).copy(drm = true, title = "Ficha $id"))) }
    }

    @Test
    fun `the sheet replaces the row item because it carries the real rights`() = runTest(mainDispatcher.dispatcher) {
        val fromRow = catalogItem("1").copy(title = "De la fila")
        val viewModel = VideoViewModel(repository, fromRow)
        assertEquals("se pinta lo que ya se sabía", "De la fila", viewModel.uiState.value.item.title)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Ficha 1", state.item.title)
        assertTrue(state.item.drm)
        assertFalse(state.isLoading)
    }

    @Test
    fun `programs open their last episode`() = runTest(mainDispatcher.dispatcher) {
        val program = catalogItem("p1", kind = ContentKind.PROGRAM, playbackId = "ep9")
        VideoViewModel(repository, program)
        advanceUntilIdle()

        assertEquals(listOf("video(ep9, force=false)"), repository.calls)
    }

    @Test
    fun `errors keep the known item and retry reloads`() = runTest(mainDispatcher.dispatcher) {
        repository.video = { _, _ -> throw HttpStatusException(404) }
        val viewModel = VideoViewModel(repository, catalogItem("1"))
        advanceUntilIdle()
        assertEquals(LoadError.Http(404), viewModel.uiState.value.error)
        assertEquals("1", viewModel.uiState.value.item.id)
        assertNull(viewModel.uiState.value.detail)

        repository.video = { id, _ -> loaded(detail(catalogItem(id))) }
        viewModel.retry()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.error)
        assertEquals("1", viewModel.uiState.value.detail!!.item.id)
    }

    @Test
    fun `an episode loads its season chapters and program extras independently`() = runTest(mainDispatcher.dispatcher) {
        val episode = catalogItem("e9", programId = "p1").copy(
            seasonId = "s5",
            episode = 9,
            programShowsFragments = true,
        )
        repository.video = { _, _ -> loaded(detail(episode)) }
        repository.programVideos = { _, _, _, _, _ -> page(listOf(catalogItem("e8"))) }
        repository.programExtras = { _, _, _ -> page(listOf(catalogItem("clip"))) }

        val viewModel = VideoViewModel(repository, catalogItem("e9"))
        advanceUntilIdle()

        assertEquals(listOf("e8"), viewModel.uiState.value.chapters.items.map { it.id })
        assertEquals(listOf("clip"), viewModel.uiState.value.extras.items.map { it.id })
        assertTrue(repository.calls.any { "programVideos(p1, season=s5" in it })
        assertTrue(repository.calls.any { it.startsWith("programExtras(p1") })
    }

    @Test
    fun `a film never loads the other films or the extras of its cycle as chapters`() = runTest(mainDispatcher.dispatcher) {
        val film = catalogItem("film", programId = "cine").copy(
            mediaSubtypeName = "Película", seasonId = "editorial-group", programShowsFragments = true,
        )
        repository.video = { _, _ -> loaded(detail(film.copy(programType = "Contenedor Películas"))) }
        repository.programVideos = { _, _, _, _, _ -> page(listOf(film, catalogItem("other-film").copy(mediaSubtypeName = "Película"))) }

        val viewModel = VideoViewModel(repository, film)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("las películas del ciclo no son capítulos", state.chapters.items.isEmpty())
        assertTrue(state.contentSections.isEmpty())
        assertFalse(repository.calls.any { it.startsWith("programExtras(") })
        // Su ciclo se ofrece como "Más de...", sin la propia película.
        assertEquals(listOf("other-film"), state.moreFromProgram.map { it.id })
    }

    @Test
    fun `a film mixed into a broadcast feed has its own section in the video sheet`() = runTest(mainDispatcher.dispatcher) {
        val broadcast = catalogItem("broadcast", programId = "p1").copy(programType = "Entrevistas")
        val film = catalogItem("film", programId = "p1").copy(mediaTypeId = "39816", mediaSubtypeId = "130734")
        repository.video = { _, _ -> loaded(detail(broadcast)) }
        repository.programVideos = { _, _, _, _, _ -> page(listOf(broadcast, film)) }

        val viewModel = VideoViewModel(repository, broadcast)
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertEquals(listOf(ProgramTab.EPISODES, ProgramTab.MOVIES), state.contentSections.map { it.tab })
        assertEquals(listOf("film"), state.contentSections.last().items.map { it.id })
        assertTrue(film.opensDetailsIn(state.structure))
    }

    @Test
    fun `a container ID prevents chapter loading even when its name is missing and a season is present`() = runTest(mainDispatcher.dispatcher) {
        val video = catalogItem("video", programId = "cycle").copy(programTypeId = "137310", seasonId = "editorial-season")
        repository.video = { _, _ -> loaded(detail(video)) }
        val viewModel = VideoViewModel(repository, video)
        advanceUntilIdle()
        // Ni capítulos de su "temporada": como pieza de un contenedor, solo su catálogo para "Más de...".
        assertTrue(viewModel.uiState.value.chapters.items.isEmpty())
        assertFalse(repository.calls.any { it.startsWith("programVideos(cycle, season=editorial-season") })
    }

    @Test
    fun `recommendations use subtype IDs and an advance never gets film recommendations`() = runTest(mainDispatcher.dispatcher) {
        val kinds = mutableListOf<es.openrtve.domain.RelatedKind>()
        repository.related = { kind, _ -> kinds += kind; loaded(emptyList()) }
        val film = catalogItem("film").copy(mediaTypeId = "39816", mediaSubtypeId = "130734")
        repository.video = { _, _ -> loaded(detail(film)) }
        VideoViewModel(repository, film)
        advanceUntilIdle()
        assertEquals(listOf(es.openrtve.domain.RelatedKind.FILM), kinds)

        repository.video = { _, _ -> loaded(detail(film.copy(id = "trailer", mediaTypeId = "39980"))) }
        VideoViewModel(repository, film.copy(id = "trailer"))
        advanceUntilIdle()
        assertEquals(1, kinds.size)
    }

    private fun detail(item: es.openrtve.domain.CatalogItem) = VideoDetail(
        item = item, backdropUrl = null, description = null, programTitle = null,
        year = null, ageRating = null, genres = emptyList(), director = null, cast = emptyList(), originalLanguage = null,
        expirationDate = null, webUrl = null,
    )
}
