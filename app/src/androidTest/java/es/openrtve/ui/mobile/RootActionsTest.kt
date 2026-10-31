package es.openrtve.ui.mobile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.openrtve.data.NotCachedException
import es.openrtve.domain.CatalogModule
import es.openrtve.domain.ExploreCategory
import es.openrtve.domain.ExploreGroup
import es.openrtve.domain.HomeFeed
import es.openrtve.domain.HomeRow
import es.openrtve.domain.QuickFilter
import es.openrtve.domain.SearchResults
import es.openrtve.ui.DestinationHost
import es.openrtve.ui.NavigationViewModel
import es.openrtve.ui.Tab
import es.openrtve.testing.FakeCatalogRepository
import es.openrtve.testing.catalogItem
import es.openrtve.testing.loaded
import es.openrtve.ui.theme.OpenRtveTheme
import es.openrtve.ui.theme.OpenRtveTvTheme
import es.openrtve.ui.tv.TvSearchScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RootActionsTest {
    @get:Rule val compose = createComposeRule()
    private val navigation = NavigationViewModel(SavedStateHandle())
    private lateinit var focusManager: FocusManager
    private val keyboard = RecordingKeyboard()
    private val repository = FakeCatalogRepository().apply {
        portada = { _, _, cachedOnly ->
            if (cachedOnly) throw NotCachedException()
            loaded(HomeFeed(null, "Inicio", (0..29).map { HomeRow(it, "Fila $it", it, null, "ColeccionPoster", contentUrl = "https://api.rtve.es/api/collection/$it.json") }))
        }
        module = { row, _, _ -> loaded(CatalogModule(row.title, listOf(catalogItem("item-${row.id}", title = "Contenido ${row.id}")))) }
        explore = { loaded(listOf(ExploreGroup(null, (0..99).map { ExploreCategory("Categoría $it", null, "https://www.rtve.es/play/$it/index_apps.json") }))) }
        quickFilters = { loaded(listOf(QuickFilter("Más buscados", "https://recomsys.rtve.es/tops"))) }
        quickFilterItems = { loaded(CatalogModule("Más buscados", emptyList())) }
        search = { SearchResults(emptyList(), emptyList()) }
    }

    @Test
    fun selectingHomeAgainScrollsToTheBeginningWithoutReloading() {
        setContent()
        verticalList().performScrollToNode(hasText("Fila 25"))
        compose.onNodeWithText("Fila 25").assertIsDisplayed()
        val loads = repository.calls.count { it.startsWith("portada(") }
        compose.onNodeWithText("nav-HOME").performClick()
        compose.onNodeWithText("Fila 0").assertIsDisplayed()
        assertEquals(loads, repository.calls.count { it.startsWith("portada(") })
    }

    @Test
    fun selectingExploreAgainScrollsToTheBeginningButSwitchingSectionsKeepsThePosition() {
        setContent()
        compose.onNodeWithText("nav-EXPLORE").performClick()
        verticalList().performScrollToNode(hasText("Categoría 90"))
        compose.onNodeWithText("nav-HOME").performClick()
        compose.onNodeWithText("nav-EXPLORE").performClick()
        compose.onNodeWithText("Categoría 90").assertIsDisplayed()
        compose.onNodeWithText("nav-EXPLORE").performClick()
        compose.onNodeWithText("Categoría 0").assertIsDisplayed()
        verticalList().performScrollToNode(hasText("Categoría 90"))
        compose.onNodeWithText("nav-HOME").performClick()
        compose.onNodeWithText("nav-EXPLORE").performClick()
        compose.onNodeWithText("Categoría 90").assertIsDisplayed()
    }

    @Test
    fun selectingSearchAgainFocusesTheFieldAndShowsTheKeyboardWithoutClearingTheQuery() {
        setContent()
        compose.onNodeWithText("nav-SEARCH").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("cine")
        compose.runOnIdle { focusManager.clearFocus() }
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
        val shows = keyboard.shows
        compose.onNodeWithText("nav-SEARCH").performClick()
        compose.onNode(hasSetTextAction()).assertIsFocused().assertTextContains("cine")
        assertTrue(keyboard.shows > shows)
    }

    @Test
    fun selectingSearchAgainAlsoOpensTheFieldAndKeyboardOnTv() {
        navigation.selectTab(Tab.SEARCH)
        compose.setContent {
            val nav by navigation.state.collectAsStateWithLifecycle()
            OpenRtveTvTheme {
                CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                    TvSearchScreen(repository, {}, reselectRequest = nav.rootRequests.getValue(Tab.SEARCH))
                }
            }
        }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        assertEquals(0, keyboard.shows)
        compose.runOnIdle { navigation.selectTab(Tab.SEARCH) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).assertIsFocused()
        assertTrue(keyboard.shows > 0)
    }

    private fun verticalList() = compose.onNode(hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))

    private fun setContent() {
        compose.setContent {
            val nav by navigation.state.collectAsStateWithLifecycle()
            focusManager = LocalFocusManager.current
            OpenRtveTheme {
                CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                    Column(Modifier.fillMaxSize()) {
                        Row {
                            Tab.entries.forEach { tab -> TextButton(onClick = { navigation.selectTab(tab) }) { Text("nav-${tab.name}") } }
                        }
                        Box(Modifier.weight(1f)) {
                            DestinationHost(nav, navigation) {
                                when (nav.tab) {
                                    Tab.HOME -> PortadaScreen(repository, "https://www.rtve.es/play/index_apps.json", "", null, {}, { _, _ -> }, reselectRequest = nav.rootRequests.getValue(Tab.HOME))
                                    Tab.SEARCH -> SearchScreen(repository, {}, reselectRequest = nav.rootRequests.getValue(Tab.SEARCH))
                                    Tab.EXPLORE -> ExploreScreen(repository, {}, {}, reselectRequest = nav.rootRequests.getValue(Tab.EXPLORE))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private class RecordingKeyboard : SoftwareKeyboardController {
        var shows = 0
        override fun show() { shows++ }
        override fun hide() = Unit
    }
}
