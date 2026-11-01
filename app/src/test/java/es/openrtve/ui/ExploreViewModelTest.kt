package es.openrtve.ui

import es.openrtve.domain.CatalogItem
import es.openrtve.domain.CatalogModule
import es.openrtve.domain.ContentKind
import es.openrtve.domain.ExploreCategory
import es.openrtve.domain.ExploreGroup
import es.openrtve.testing.FakeCatalogRepository
import es.openrtve.testing.MainDispatcherRule
import es.openrtve.testing.loaded
import java.net.UnknownHostException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExploreViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val repository = FakeCatalogRepository()

    @Test
    fun `groups load and a failure can be retried`() = runTest(mainDispatcher.dispatcher) {
        repository.explore = { throw UnknownHostException() }
        val viewModel = ExploreViewModel(repository)
        advanceUntilIdle()
        assertEquals(LoadError.Offline, viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.isLoading)

        val groups = listOf(ExploreGroup("Series", listOf(ExploreCategory("Drama", null, "https://www.rtve.es/play/drama.json"))))
        repository.explore = { loaded(groups) }
        viewModel.retry()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.error)
        assertEquals(groups, viewModel.uiState.value.groups)
    }

    @Test
    fun `thematic channels show what they broadcast now, survive its failure and refresh`() = runTest(mainDispatcher.dispatcher) {
        val cocina = ExploreCategory("RTVE COCINA", null, "https://api.rtve.es/api/lives/broadcasts/1.json", isLive = true)
        val moda = ExploreCategory("RTVE MODA", null, "https://api.rtve.es/api/lives/broadcasts/2.json", isLive = true)
        val cine = ExploreCategory("CINE", "https://img.rtve.es/cine.jpg", "https://www.rtve.es/play/cine/index_apps.json")
        repository.explore = { loaded(listOf(ExploreGroup("Canales temáticos", listOf(cocina, moda)), ExploreGroup("Bloque", listOf(cine)))) }
        repository.module = { row, _, _ ->
            if (row.contentUrl == cocina.contentUrl) loaded(CatalogModule("", listOf(liveItem("1", "https://img.rtve.es/live1.jpg"))))
            else throw UnknownHostException()
        }
        val viewModel = ExploreViewModel(repository)
        advanceUntilIdle()

        val groups = viewModel.uiState.value.groups
        // Su tarjeta es la del directo: se reproduce al tocarla, sin pasar por una página con una sola tarjeta.
        assertEquals("1", groups[0].categories[0].nowPlaying?.id)
        assertEquals("https://img.rtve.es/live1.jpg", groups[0].categories[0].imageUrl)
        // Sin directo queda la tarjeta de categoría, que abre su página.
        assertNull(groups[0].categories[1].nowPlaying)
        assertNull(groups[0].categories[1].imageUrl)
        assertEquals(listOf(cine), groups[1].categories)
        // Las portadas no generan peticiones.
        assertEquals(2, repository.calls.count { it.startsWith("module(") })

        // Cada minuto en pantalla se renueva lo que emiten; un fallo conserva lo que había.
        repository.module = { row, force, _ ->
            check(force) { "el refresco no puede servir la copia de caché" }
            if (row.contentUrl == moda.contentUrl) loaded(CatalogModule("", listOf(liveItem("2", "https://img.rtve.es/live2.jpg"))))
            else throw UnknownHostException()
        }
        viewModel.refreshLive()
        advanceUntilIdle()
        val refreshed = viewModel.uiState.value.groups[0].categories
        assertEquals(listOf("1", "2"), refreshed.map { it.nowPlaying?.id })
    }

    private fun liveItem(id: String, imageUrl: String) = CatalogItem(
        id = id,
        playbackId = id,
        assetId = id,
        title = "Directo",
        subtitle = null,
        imageUrl = imageUrl,
        kind = ContentKind.LIVE,
        allowedInCountry = null,
        loginRequired = false,
        paid = false,
        drm = false,
        programId = null,
    )
}
