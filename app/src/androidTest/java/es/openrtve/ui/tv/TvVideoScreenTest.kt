package es.openrtve.ui.tv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import es.openrtve.R
import es.openrtve.data.WatchHistory
import es.openrtve.domain.CatalogItem
import es.openrtve.domain.VideoDetail
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
class TvVideoScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun theRemoteOpensTheFilmSheetFromTheBroadcastSheet() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val broadcast = catalogItem("broadcast", title = "Un coloquio", programId = "program").copy(programType = "Entrevistas")
        val film = catalogItem("film", title = "Una película del programa").copy(mediaTypeId = "39816", mediaSubtypeId = "130734")
        val repository = FakeCatalogRepository().apply {
            video = { _, _ -> loaded(VideoDetail(
                item = broadcast, backdropUrl = null, description = null, promo = null, subtypeName = null,
                programTitle = "Historia de nuestro cine", programType = "Entrevistas", year = "2026",
                ageRating = null, genres = emptyList(), director = null, cast = emptyList(),
                originalLanguage = null, expirationDate = null, webUrl = null,
            )) }
            programVideos = { _, _, _, _, _ -> page(listOf(broadcast, film)) }
        }
        val opened = mutableListOf<CatalogItem>()
        val played = mutableListOf<CatalogItem>()
        val history = WatchHistory(File(context.cacheDir, "tv-video-test-history-${System.nanoTime()}.json"))
        compose.setContent {
            OpenRtveTvTheme {
                TvVideoScreen(repository, history, broadcast, onPlay = { item, _ -> played += item }, onOpenProgram = { _, _ -> }, onOpenItem = { opened += it })
            }
        }
        compose.onNode(hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText(context.getString(R.string.tab_movies)))
        val poster = compose.onNode(hasClickAction() and hasAnySibling(hasText(film.title)))
        poster.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        poster.performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(listOf("film"), opened.map { it.id })
        assertEquals(emptyList<CatalogItem>(), played)
    }
}
