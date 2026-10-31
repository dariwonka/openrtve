package es.openrtve.ui.mobile

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import es.openrtve.R
import es.openrtve.domain.CatalogItem
import es.openrtve.domain.CatalogModule
import es.openrtve.domain.HomeFeed
import es.openrtve.domain.HomeRow
import es.openrtve.testing.FakeCatalogRepository
import es.openrtve.testing.catalogItem
import es.openrtve.testing.loaded
import es.openrtve.ui.theme.OpenRtveTheme
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PortadaScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository = FakeCatalogRepository()
    private val opened = mutableListOf<CatalogItem>()

    @Test
    fun rowsRenderTheirItemsAndEmptyRowsDisappear() {
        repository.portada = { _, _, cachedOnly ->
            if (cachedOnly) throw es.openrtve.data.NotCachedException()
            loaded(HomeFeed(null, "Portada", listOf(row(0, "Cine"), row(1, "Vacía"), row(2, ""))))
        }
        repository.module = { row, _, _ ->
            when (row.id) {
                0 -> loaded(CatalogModule("", listOf(catalogItem("jojo", title = "Jojo Rabbit"), catalogItem("par", title = "Parásitos"))))
                1 -> loaded(CatalogModule("", emptyList()))
                else -> loaded(CatalogModule("Título de la colección", listOf(catalogItem("x", title = "Equis"))))
            }
        }
        setContent()

        compose.onNodeWithText("Cine").assertIsDisplayed()
        compose.onNodeWithText("Jojo Rabbit").assertIsDisplayed()
        compose.onNodeWithText("Parásitos").assertIsDisplayed()
        compose.onNodeWithText("Título de la colección").assertIsDisplayed()
        compose.onAllNodes(hasText("Vacía")).assertCountEquals(0)

        compose.onNodeWithText("Jojo Rabbit").performClick()
        assertEquals(listOf("jojo"), opened.map { it.id })
    }

    @Test
    fun aFailedRowOffersRetryAndRecovers() {
        repository.portada = { _, _, cachedOnly ->
            if (cachedOnly) throw es.openrtve.data.NotCachedException()
            loaded(HomeFeed(null, "Portada", listOf(row(0, "Cine"))))
        }
        var attempts = 0
        repository.module = { _, _, _ ->
            if (attempts++ == 0) throw IOException("boom") else loaded(CatalogModule("", listOf(catalogItem("ok", title = "Recuperado"))))
        }
        setContent()

        val retry = context.getString(R.string.action_retry)
        compose.onNodeWithText(retry).assertIsDisplayed().performClick()
        compose.onNodeWithText("Recuperado").assertIsDisplayed()
    }

    @Test
    fun aFailedFeedExplainsTheErrorInPlaceInsteadOfAnEmptyHome() {
        repository.portada = { _, _, cachedOnly ->
            if (cachedOnly) throw es.openrtve.data.NotCachedException()
            throw IOException("offline")
        }
        setContent()

        val error = context.getString(R.string.error_unknown)
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(error)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(error).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.action_retry)).assertIsDisplayed()
        compose.onAllNodes(hasText(context.getString(R.string.state_empty_home))).assertCountEquals(0)
    }

    private fun setContent() {
        compose.setContent {
            OpenRtveTheme {
                PortadaScreen(
                    repository = repository,
                    url = "https://www.rtve.es/play/index_apps.json",
                    title = "",
                    onBack = null,
                    onOpenItem = { opened += it },
                    onOpenRow = { _, _ -> },
                )
            }
        }
    }

    private fun row(id: Int, title: String) = HomeRow(
        id = id, title = title, order = id, moduleType = null, presentation = "ColeccionPoster",
        contentUrl = "https://api.rtve.es/api/collection/$id.json",
    )
}
