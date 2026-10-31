package es.openrtve.ui.tv

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import es.openrtve.R
import es.openrtve.data.WatchHistory
import es.openrtve.domain.CatalogItem
import es.openrtve.domain.ContentKind
import es.openrtve.domain.ProgramDetail
import es.openrtve.testing.FakeCatalogRepository
import es.openrtve.testing.catalogItem
import es.openrtve.testing.loaded
import es.openrtve.testing.page
import es.openrtve.ui.theme.OpenRtveTvTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvProgramScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aFilmCycleHasNoBrowseButtonAndTheRemoteReachesItsPoster() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val film = catalogItem("film", title = "Akelarre").copy(mediaSubtypeName = "Película")
        val repository = FakeCatalogRepository().apply {
            program = { id, _ ->
                loaded(ProgramDetail(
                    id, "Somos cine", "Un ciclo de cine", null, null, emptyList(),
                    programType = "Contenedor Películas", genres = listOf("Cine"),
                ))
            }
            programVideos = { _, _, _, _, _ -> page(listOf(film)) }
        }
        val opened = mutableListOf<CatalogItem>()
        val played = mutableListOf<CatalogItem>()
        val history = WatchHistory(File(context.cacheDir, "tv-test-history-${System.nanoTime()}.json"))
        compose.setContent {
            OpenRtveTvTheme {
                TvProgramScreen(
                    repository, history, catalogItem("cycle", kind = ContentKind.PROGRAM),
                    onOpenItem = { played += it }, onError = {}, onOpenRelated = { opened += it },
                )
            }
        }

        compose.onAllNodes(hasText("Ver películas")).assertCountEquals(0)
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasText("Somos cine") and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Somos cine").performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasClickAction() and hasAnySibling(hasText("Akelarre")) and isFocused())
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasClickAction() and hasAnySibling(hasText("Akelarre")))
            .assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }

        assertEquals(listOf("film"), opened.map { it.id })
        assertEquals(emptyList<CatalogItem>(), played)
    }
}
