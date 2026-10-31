package es.openrtve.ui.mobile

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import es.openrtve.R
import es.openrtve.data.WatchHistory
import es.openrtve.domain.CatalogItem
import es.openrtve.domain.ContentKind
import es.openrtve.domain.ProgramDetail
import es.openrtve.domain.ProgramSeason
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
class ProgramScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val historyFile = File(context.cacheDir, "test-history-${System.nanoTime()}.json")
    private val history = WatchHistory(historyFile)
    private val program = catalogItem("p1", kind = ContentKind.PROGRAM, title = "Telediario")
    private val episodes = listOf(
        catalogItem("e3", title = "Episodio 3", programId = "p1"),
        catalogItem("e2", title = "Episodio 2", programId = "p1"),
        catalogItem("e1", title = "Episodio 1", programId = "p1"),
    )
    private val repository = FakeCatalogRepository().apply {
        this.program = { id, _ -> loaded(ProgramDetail(id, "Telediario", "<b>Noticias</b>", null, null, listOf(ProgramSeason("t1", "Temporada 1", 3)))) }
        programVideos = { _, _, pageNumber, _, _ -> if (pageNumber == 1) page(episodes, 1, 2) else page(listOf(catalogItem("e0", title = "Episodio 0")), 2, 2) }
    }
    private val opened = mutableListOf<CatalogItem>()
    private val detailsOpened = mutableListOf<CatalogItem>()

    @Test
    fun filmCyclesShowTheirCatalogAndOpenTheSelectedFilmsSheet() {
        val film = catalogItem("film", title = "Akelarre").copy(mediaSubtypeName = "Película")
        repository.program = { id, _ ->
            loaded(ProgramDetail(
                id, "Somos cine", "Un ciclo de cine", null, null, emptyList(),
                programType = "Contenedor Películas", director = "Directora del ciclo",
            ))
        }
        repository.programVideos = { _, _, _, _, _ -> page(listOf(film)) }
        setContent()

        compose.onAllNodes(hasText(context.getString(R.string.tab_details))).assertCountEquals(0)
        compose.onAllNodes(hasText(context.getString(R.string.tab_related))).assertCountEquals(0)
        compose.onAllNodes(hasText(context.getString(R.string.tab_episodes))).assertCountEquals(0)
        compose.onAllNodes(hasText(context.getString(R.string.program_play_episode, "Akelarre"))).assertCountEquals(0)
        compose.onNodeWithText(context.getString(R.string.action_browse_movies)).performScrollTo().performClick()
        compose.onNodeWithText("Akelarre").performScrollTo().performClick()

        assertEquals(listOf("film"), detailsOpened.map { it.id })
        assertEquals(emptyList<CatalogItem>(), opened)
    }

    @Test
    fun episodicProgramsStartOnChaptersEvenWhenTheyHaveTechnicalDetails() {
        repository.program = { id, _ ->
            loaded(ProgramDetail(
                id, "Historia de nuestro cine", "Programa de cine", null, null, emptyList(),
                programTypeId = "136525", programType = "Entrevistas", director = "Directora del programa",
            ))
        }
        setContent()

        compose.onNodeWithText(context.getString(R.string.tab_episodes)).assertIsSelected()
        compose.onAllNodes(hasText(context.getString(R.string.tab_movies))).assertCountEquals(0)
        compose.onNodeWithText("Episodio 3").performScrollTo().performClick()
        assertEquals(listOf("e3"), opened.map { it.id })
    }

    @Test
    fun aMixedContainerSeparatesItsFourKindsAndOnlyExtrasPlayDirectly() {
        val film = catalogItem("film", title = "Una película").copy(mediaTypeId = "39816", mediaSubtypeId = "130734")
        val documentary = catalogItem("doc", title = "Un documental").copy(mediaTypeId = "39816", mediaSubtypeId = "130735")
        val video = catalogItem("video", title = "Vídeo sin subtipo").copy(mediaTypeId = "39816")
        val trailer = catalogItem("trailer", title = "Avance de cine").copy(mediaTypeId = "39980", mediaSubtypeId = "130734")
        repository.program = { id, _ -> loaded(ProgramDetail(id, "Somos cine", null, null, null, emptyList(), programTypeId = "137310", showFragments = true)) }
        repository.programVideos = { _, _, _, _, _ -> page(listOf(film, documentary, video)) }
        repository.programExtras = { _, _, _ -> page(listOf(trailer)) }
        setContent()

        compose.onNodeWithText(context.getString(R.string.tab_movies)).assertIsSelected()
        compose.onNodeWithText("Una película").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.tab_documentaries)).performScrollTo().performClick()
        compose.onNodeWithText("Un documental").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.tab_videos)).performScrollTo().performClick()
        compose.onNodeWithText("Vídeo sin subtipo").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.tab_more_videos)).performScrollTo().performClick()
        compose.onNodeWithText("Avance de cine").performScrollTo().performClick()

        assertEquals(listOf("film", "doc", "video"), detailsOpened.map { it.id })
        assertEquals(listOf("trailer"), opened.map { it.id })
    }

    @Test
    fun pagesWithoutFilmsDoNotStopTheFilmCatalogPagination() {
        repository.program = { id, _ -> loaded(ProgramDetail(id, "Somos cine", null, null, null, emptyList(), programTypeId = "137310")) }
        repository.programVideos = { _, _, pageNumber, _, _ ->
            page(listOf(when (pageNumber) {
                1 -> catalogItem("video", title = "Otro vídeo").copy(mediaTypeId = "39816")
                2 -> catalogItem("doc", title = "Otro documental").copy(mediaTypeId = "39816", mediaSubtypeId = "130735")
                else -> catalogItem("film", title = "Película de la tercera página").copy(mediaTypeId = "39816", mediaSubtypeId = "130734")
            }), pageNumber, 3)
        }
        setContent()

        compose.waitUntil(timeoutMillis = 5_000) { repository.calls.count { it.startsWith("programVideos(") } == 3 }
        compose.onNodeWithText("Película de la tercera página").performScrollTo().assertIsDisplayed()
        assertEquals(3, repository.calls.count { it.startsWith("programVideos(") })
    }

    @Test
    fun withoutHistoryTheNewestEpisodeIsOfferedAndTheListPaginates() {
        setContent()

        compose.onNodeWithText(context.getString(R.string.program_play_episode, "Episodio 3")).assertIsDisplayed()
        compose.onNodeWithText("Episodio 1").assertIsDisplayed()
        compose.onAllNodes(hasText(context.getString(R.string.action_more))).assertCountEquals(0)

        // Al verse el final de la lista, la siguiente página se pide sola y el botón manual no hace falta.
        compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("Episodio 3"))).performScrollToNode(hasText("Episodio 0"))
        compose.onNodeWithText("Episodio 0").assertIsDisplayed()
        compose.onAllNodes(hasText(context.getString(R.string.program_load_more))).assertCountEquals(0)
        assertEquals(2, repository.calls.count { it.startsWith("programVideos") })
    }

    @Test
    fun anEpisodeInProgressIsContinuedAndAFinishedOneMarksTheNext() {
        history.register(episodes[2])
        history.updateProgress("e1", 120_000, 600_000)
        setContent()

        compose.onNodeWithText(context.getString(R.string.program_continue_episode, "Episodio 1")).assertIsDisplayed().performClick()
        assertEquals(listOf("e1"), opened.map { it.id })

        history.updateProgress("e1", 599_000, 600_000)
        compose.onNodeWithText(context.getString(R.string.program_next_episode, "Episodio 2")).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.episode_watched)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.episode_next)).assertIsDisplayed()
    }

    @Test
    fun anEmptyProgramExplainsItselfAndRetries() {
        var attempts = 0
        repository.programVideos = { _, _, _, _, _ -> if (attempts++ < 1) page(emptyList()) else page(episodes) }
        setContent()

        compose.onNodeWithText(context.getString(R.string.program_empty)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.action_retry)).performClick()
        compose.onNodeWithText("Episodio 3").assertIsDisplayed()
    }

    @Test
    fun shortSynopsisDoesNotOfferMore() {
        compose.setContent {
            OpenRtveTheme {
                ExpandableText("Una sinopsis breve")
            }
        }
        compose.onAllNodes(hasText(context.getString(R.string.action_more))).assertCountEquals(0)
    }

    @Test
    fun longSynopsisCanExpandAndCollapse() {
        compose.setContent {
            OpenRtveTheme {
                ExpandableText(List(30) { "Una sinopsis suficientemente larga para ocupar varias líneas." }.joinToString(" "))
            }
        }
        compose.onNodeWithText(context.getString(R.string.action_more)).assertIsDisplayed().performClick()
        compose.onNodeWithText(context.getString(R.string.action_less)).assertIsDisplayed()
    }

    private fun setContent() {
        compose.setContent {
            OpenRtveTheme {
                ProgramScreen(
                    repository = repository,
                    history = history,
                    program = program,
                    onBack = {},
                    onOpenItem = { opened += it },
                    onOpenRelated = { detailsOpened += it },
                    onError = {},
                )
            }
        }
    }
}
