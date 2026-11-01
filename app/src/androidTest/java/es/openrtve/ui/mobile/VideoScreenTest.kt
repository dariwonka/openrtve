package es.openrtve.ui.mobile

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import es.openrtve.ui.theme.OpenRtveTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun aFilmInTheBroadcastsProgramOpensItsSheetButItsAdvancePlays() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val broadcast = catalogItem("broadcast", title = "Un coloquio", programId = "program").copy(programType = "Entrevistas", programShowsFragments = true)
        val film = catalogItem("film", title = "Una película del programa", programId = "program").copy(mediaTypeId = "39816", mediaSubtypeId = "130734")
        val trailer = film.copy(id = "trailer", title = "Un avance del programa", mediaTypeId = "39980")
        val repository = FakeCatalogRepository().apply {
            video = { _, _ -> loaded(VideoDetail(
                item = broadcast, backdropUrl = null, description = "Un programa de cine", programTitle = "Historia de nuestro cine",
                year = "2026", ageRating = null, genres = emptyList(), director = null, cast = emptyList(),
                originalLanguage = null, expirationDate = null, webUrl = null,
            )) }
            programVideos = { _, _, _, _, _ -> page(listOf(broadcast, film)) }
            programExtras = { _, _, _ -> page(listOf(trailer)) }
        }
        val opened = mutableListOf<CatalogItem>()
        val played = mutableListOf<CatalogItem>()
        compose.setContent {
            OpenRtveTheme {
                VideoScreen(
                    repository, WatchHistory(File(context.cacheDir, "video-test-history-${System.nanoTime()}.json")), broadcast,
                    onBack = {}, onPlay = { item, _ -> played += item }, onOpenItem = { opened += it }, onOpenProgram = { _, _ -> },
                )
            }
        }
        compose.onAllNodes(hasText(context.getString(R.string.tab_details))).assertCountEquals(0)
        compose.onNodeWithText(context.getString(R.string.tab_movies)).performScrollTo().performClick()
        compose.onNodeWithText("Una película del programa").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.tab_more_videos)).performScrollTo().performClick()
        compose.onNodeWithText("Un avance del programa").performScrollTo().performClick()
        assertEquals(listOf("film"), opened.map { it.id })
        assertEquals(listOf("trailer"), played.map { it.id })
    }
}
