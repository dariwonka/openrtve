package es.openrtve.ui

import es.openrtve.domain.CatalogModule
import es.openrtve.domain.ContentKind
import es.openrtve.domain.HomeFeed
import es.openrtve.domain.HomeRow
import es.openrtve.domain.RtveTypes
import es.openrtve.domain.RtveUrls
import es.openrtve.testing.FakeCatalogRepository
import es.openrtve.testing.MainDispatcherRule
import es.openrtve.testing.catalogItem
import es.openrtve.testing.loaded
import java.net.UnknownHostException
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
class ClanCatalogTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private fun program(id: String, title: String, image: String? = "https://img.rtve.es/p/$id") =
        catalogItem(id, ContentKind.PROGRAM, title).copy(imageUrl = image, subtitle = "Último capítulo")

    private fun episode(id: String, programId: String, series: String) =
        catalogItem(id, title = "Capítulo $id", programId = programId).copy(subtitle = series, imageUrl = "https://img.rtve.es/v/$id")

    private fun movie(id: String, title: String) = catalogItem(id, title = title).copy(mediaTypeId = RtveTypes.COMPLETE, mediaSubtypeId = RtveTypes.MOVIE)

    @Test
    fun `editorial rows become one alphabetical list of unique series`() {
        val catalog = buildClanCatalog(
            listOf(
                LoadedRow("Princesas", listOf(program("3", "Peppa Pig"), program("1", "Érase una vez"))),
                LoadedRow("Para descubrir dinosaurios", listOf(program("2", "Bluey"), program("3", "Peppa Pig"))),
                LoadedRow("Lo último", listOf(program("4", "¡Zas! El show"), program("5", "ana y los robots"))),
            ),
        )

        // Sin tildes, mayúsculas ni signos de apertura en el orden; cada título una vez.
        assertEquals(listOf("ana y los robots", "Bluey", "Érase una vez", "Peppa Pig", "¡Zas! El show"), catalog.series.map { it.title })
        // La tarjeta solo lleva el título de la serie.
        assertTrue(catalog.series.all { it.subtitle == null })
        assertTrue(catalog.movies.isEmpty())
    }

    @Test
    fun `an episode contributes its series, not itself`() {
        val catalog = buildClanCatalog(
            listOf(LoadedRow("Nuevos capítulos", listOf(episode("100", "7", "Pocoyó"), episode("101", "7", "Pocoyó"), episode("102", "8", "Momonsters")))),
        )

        assertEquals(listOf("Momonsters" to "8", "Pocoyó" to "7"), catalog.series.map { it.title to it.id })
        assertTrue(catalog.series.all { it.kind == ContentKind.PROGRAM })
    }

    @Test
    fun `the programme image wins over an episode still, whatever the row order`() {
        val catalog = buildClanCatalog(
            listOf(
                LoadedRow("Nuevos capítulos", listOf(episode("100", "7", "Pocoyó"))),
                LoadedRow("Series", listOf(program("7", "Pocoyó"))),
            ),
        )

        assertEquals("https://img.rtve.es/p/7", catalog.series.single().imageUrl)
    }

    @Test
    fun `films go to their own list and never among the series`() {
        val catalog = buildClanCatalog(
            listOf(
                LoadedRow("Destacados", listOf(movie("50", "Tadeo Jones"), program("2", "Bluey"))),
                // Una fila de películas manda aunque el feed no clasifique sus piezas.
                LoadedRow("Películas para toda la familia", listOf(catalogItem("51", title = "Atrapa la bandera"), program("9", "El Cid, la leyenda"))),
                LoadedRow("Series", listOf(program("9", "El Cid, la leyenda"))),
            ),
        )

        assertEquals(listOf("Atrapa la bandera", "El Cid, la leyenda", "Tadeo Jones"), catalog.movies.map { it.title })
        assertEquals(listOf("Bluey"), catalog.series.map { it.title })
    }

    @Test
    fun `a trailer is not a film`() {
        val trailer = movie("60", "Tráiler").copy(mediaTypeId = RtveTypes.PREVIEW)

        assertTrue(buildClanCatalog(listOf(LoadedRow("Destacados", listOf(trailer)))).movies.isEmpty())
    }

    @Test
    fun `the home catalogue merges every row and survives one that fails`() = runTest(mainDispatcher.dispatcher) {
        val repository = FakeCatalogRepository()
        fun row(id: Int, title: String, url: String?) = HomeRow(id = id, title = title, order = id, moduleType = "Collection", presentation = "ColeccionPoster", contentUrl = url)
        repository.portada = { url, _, _ ->
            assertEquals(RtveUrls.CLAN_HOME, url)
            loaded(HomeFeed(null, "Clan", listOf(row(0, "Princesas", "https://www.rtve.es/a.json"), row(1, "Rota", "https://www.rtve.es/b.json"), row(2, "Cine", "https://www.rtve.es/c.json"))))
        }
        repository.module = { row, _, _ ->
            when (row.id) {
                0 -> loaded(CatalogModule(row.title, listOf(program("3", "Peppa Pig"), program("2", "Bluey"))))
                2 -> loaded(CatalogModule(row.title, listOf(movie("50", "Tadeo Jones"))))
                else -> throw UnknownHostException()
            }
        }

        val viewModel = ClanCatalogViewModel(repository)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.error)
        assertEquals(listOf("Bluey", "Peppa Pig"), state.catalog.titles(ClanSection.SERIES).map { it.title })
        assertEquals(listOf("Tadeo Jones"), state.catalog.titles(ClanSection.MOVIES).map { it.title })
    }

    @Test
    fun `with nothing loaded the failure is shown and can be retried`() = runTest(mainDispatcher.dispatcher) {
        val repository = FakeCatalogRepository()
        repository.portada = { _, _, _ -> throw UnknownHostException() }
        val viewModel = ClanCatalogViewModel(repository)
        advanceUntilIdle()
        assertEquals(LoadError.Offline, viewModel.uiState.value.error)

        repository.portada = { _, _, _ -> loaded(HomeFeed(null, "Clan", emptyList())) }
        viewModel.retry()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.isLoading)
    }
}
