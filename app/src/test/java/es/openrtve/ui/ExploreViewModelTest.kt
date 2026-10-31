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
    fun `thematic channels take their image from the live feed and survive its failure`() = runTest(mainDispatcher.dispatcher) {
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
        assertEquals("https://img.rtve.es/live1.jpg", groups[0].categories[0].imageUrl)
        assertNull(groups[0].categories[1].imageUrl)
        assertEquals(listOf(cine), groups[1].categories)
        // Las portadas con imagen no generan peticiones.
        assertEquals(2, repository.calls.count { it.startsWith("module(") })
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
