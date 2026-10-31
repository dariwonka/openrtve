package es.openrtve.ui.mobile

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import es.openrtve.R
import es.openrtve.domain.CatalogItem
import es.openrtve.domain.CatalogModule
import es.openrtve.domain.ContentKind
import es.openrtve.domain.QuickFilter
import es.openrtve.domain.SearchResults
import es.openrtve.testing.FakeCatalogRepository
import es.openrtve.testing.catalogItem
import es.openrtve.testing.loaded
import es.openrtve.ui.theme.OpenRtveTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository = FakeCatalogRepository().apply {
        quickFilters = { loaded(listOf(QuickFilter("Más buscados", "https://recomsys.rtve.es/tops"), QuickFilter("Series", "https://www.rtve.es/api/series.json"))) }
        quickFilterItems = { filter -> loaded(CatalogModule(filter.title, listOf(catalogItem("${filter.title}-1", title = "De ${filter.title}")))) }
        search = { query ->
            SearchResults(
                programs = listOf(catalogItem("p", kind = ContentKind.PROGRAM, title = "Programa $query")),
                videos = listOf(catalogItem("v", title = "Vídeo $query")),
            )
        }
    }
    private val opened = mutableListOf<CatalogItem>()

    @Test
    fun quickFiltersShowTheirItemsUntilTheUserTypes() {
        setContent()

        compose.onNodeWithText("De Más buscados").assertIsDisplayed()
        compose.onNodeWithText("Series").performClick()
        compose.onNodeWithText("De Series").assertIsDisplayed()

        compose.onNode(hasSetTextAction()).performTextInput("cine")
        // La búsqueda espera 400 ms a que se deje de escribir.
        compose.waitUntilText("Programa cine")
        compose.onNodeWithText("Programa cine").assertIsDisplayed()
        compose.onNodeWithText("Vídeo cine").assertIsDisplayed()
        compose.onAllNodes(hasText("De Series")).assertCountEquals(0)

        compose.onNodeWithText("Vídeo cine").performClick()
        assertEquals(listOf("v"), opened.map { it.id })

        compose.onNodeWithContentDescription(context.getString(R.string.search_clear)).performClick()
        compose.onNodeWithText("De Series").assertIsDisplayed()
    }

    @Test
    fun noResultsIsSaidExplicitly() {
        repository.search = { SearchResults(emptyList(), emptyList()) }
        setContent()

        compose.onNode(hasSetTextAction()).performTextInput("zzz")
        compose.waitUntilText(context.getString(R.string.search_empty))
        compose.onNodeWithText(context.getString(R.string.search_empty)).assertIsDisplayed()
    }

    private fun ComposeContentTestRule.waitUntilText(text: String) =
        waitUntil(5_000) { onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    private fun setContent() {
        compose.setContent {
            OpenRtveTheme {
                SearchScreen(repository = repository, onOpenItem = { opened += it })
            }
        }
    }
}
