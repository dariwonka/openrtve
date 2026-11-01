package es.openrtve.ui.mobile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import es.openrtve.R
import es.openrtve.data.WatchHistory
import es.openrtve.domain.CatalogItem
import es.openrtve.domain.ContentKind
import es.openrtve.domain.ProgramDetail
import es.openrtve.domain.RtveTypes
import es.openrtve.domain.VideoDetail
import es.openrtve.testing.FakeCatalogRepository
import es.openrtve.testing.catalogItem
import es.openrtve.testing.loaded
import es.openrtve.testing.page
import es.openrtve.ui.theme.OpenRtveTheme
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DetailRecoveryTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val history = WatchHistory(File(context.cacheDir, "detail-test-${System.nanoTime()}.json"))

    @Test fun theMobileFilmSheetLinksToItsCycleInsteadOfRepeatingIt() {
        val film = catalogItem("film", programId = "cycle").copy(mediaSubtypeId = RtveTypes.MOVIE, programTypeId = RtveTypes.MOVIE_CONTAINER)
        val other = film.copy(id = "other", title = "Otra película del ciclo")
        val repo = FakeCatalogRepository().apply {
            video = { _, _ -> loaded(VideoDetail(film, null, "Sinopsis", programTitle = "Ciclo de cine", year = null,
                ageRating = null, genres = emptyList(), director = null, cast = emptyList(), originalLanguage = null, expirationDate = null, webUrl = null)) }
            programVideos = { _, _, _, _, _ -> page(listOf(film, other)) }
        }
        val openedPrograms = mutableListOf<Pair<String, String>>()
        compose.setContent {
            OpenRtveTheme { VideoScreen(repo, history, film, {}, { _, _ -> }, {}, { id, title -> openedPrograms += id to title }) }
        }
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.collection_more_in, "Ciclo de cine")).performClick()
        assertEquals(listOf("cycle" to "Ciclo de cine"), openedPrograms)
        compose.onNodeWithText(other.title).assertDoesNotExist()
    }

    @Test fun theProgramSheetCanRecoverMetadataWhileKeepingItsEpisodes() {
        var fail = true
        val repo = FakeCatalogRepository().apply {
            program = { id, _ -> if (fail) throw IOException("Sin ficha") else loaded(ProgramDetail(id, "Programa recuperado", "Sinopsis recuperada", null, null, emptyList())) }
            programVideos = { _, _, _, _, _ -> page(listOf(catalogItem("episode", title = "Capítulo disponible"))) }
        }
        compose.setContent {
            OpenRtveTheme { ProgramScreen(repo, history, catalogItem("program", kind = ContentKind.PROGRAM), {}, {}) }
        }
        compose.onNodeWithText("Capítulo disponible").performScrollTo().assertIsDisplayed()
        fail = false
        compose.onNodeWithText(context.getString(R.string.action_retry)).performScrollTo().performClick()
        compose.onNodeWithText("Sinopsis recuperada").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Capítulo disponible").performScrollTo().assertIsDisplayed()
    }
}
