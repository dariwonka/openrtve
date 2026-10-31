package es.openrtve.ui

import es.openrtve.domain.ProgramDetail
import es.openrtve.testing.catalogItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentSectionsTest {
    private val film = catalogItem("film").copy(mediaTypeId = "39816", mediaSubtypeId = "130734")
    private val documentary = catalogItem("documentary").copy(mediaTypeId = "39816", mediaSubtypeId = "130735")
    private val video = catalogItem("video").copy(mediaTypeId = "39816")
    private val trailer = film.copy(id = "trailer", mediaTypeId = "39978")

    @Test
    fun `Somos cine separates complete films documentaries and untyped videos from extras`() {
        val sections = contentSections(
            ProgramContents.MOVIES,
            PagedItemsState(listOf(film, documentary, video, trailer), page = 1, totalPages = 3),
            PagedItemsState(listOf(trailer, catalogItem("clip")), page = 1, totalPages = 2),
            includePrimary = true,
        )
        assertEquals(
            listOf(ProgramTab.MOVIES, ProgramTab.DOCUMENTARIES, ProgramTab.VIDEOS, ProgramTab.MORE_VIDEOS),
            sections.map { it.tab },
        )
        assertEquals(listOf(listOf("film"), listOf("documentary"), listOf("video"), listOf("trailer", "clip")), sections.map { it.page.items.map { item -> item.id } })
        assertTrue(sections.all { it.page.hasMore })
        assertTrue(sections.last().loadsComplete)
        assertTrue(sections.last().loadsExtras)
    }

    @Test
    fun `Historia de nuestro cine keeps broadcasts and films in different sections`() {
        val sections = contentSections(
            ProgramContents.EPISODES,
            PagedItemsState(listOf(video, film)),
            PagedItemsState(),
            includePrimary = true,
        )
        assertEquals(listOf(ProgramTab.EPISODES, ProgramTab.MOVIES), sections.map { it.tab })
        assertEquals(listOf("video"), sections[0].page.items.map { it.id })
        assertEquals(listOf("film"), sections[1].page.items.map { it.id })
        assertFalse(video.opensDetailsIn(ProgramContents.EPISODES))
        assertTrue(film.opensDetailsIn(ProgramContents.EPISODES))
    }

    @Test
    fun `a documentary series keeps its documentary episodes together`() {
        val chapter = documentary.copy(seasonId = "season", episode = 2)
        val sections = contentSections(ProgramContents.EPISODES, PagedItemsState(listOf(chapter)), PagedItemsState(), true)
        assertEquals(listOf(ProgramTab.EPISODES), sections.map { it.tab })
        assertFalse(chapter.opensDetailsIn(ProgramContents.EPISODES))
        assertTrue(chapter.opensDetailsIn(ProgramContents.DOCUMENTARIES))
    }

    @Test
    fun `an extra with film subtype remains an extra with direct playback`() {
        assertEquals(ProgramTab.MORE_VIDEOS, trailer.contentTab(ProgramContents.MOVIES))
        assertFalse(trailer.opensDetailsIn(ProgramContents.MOVIES))
        assertFalse(trailer.opensDetailsIn(ProgramContents.EPISODES))
    }

    @Test
    fun `titles never decide whether a piece is a trailer or a film`() {
        assertEquals(ProgramTab.VIDEOS, video.copy(title = "Tráiler de una película").contentTab(ProgramContents.MOVIES))
        assertEquals(ProgramTab.MORE_VIDEOS, trailer.copy(title = "Película completa").contentTab(ProgramContents.MOVIES))
    }

    @Test
    fun `an empty filtered page retains pagination until the next film arrives`() {
        fun sections(page: Int, items: List<es.openrtve.domain.CatalogItem>) = contentSections(
            ProgramContents.MOVIES, PagedItemsState(items, page, 3), PagedItemsState(), true,
        )
        val first = sections(1, listOf(video)).first()
        val second = sections(2, listOf(video, documentary)).first()
        val third = sections(3, listOf(video, documentary, film)).first()
        assertTrue(first.page.items.isEmpty() && second.page.items.isEmpty())
        assertTrue(first.page.hasMore && second.page.hasMore)
        assertEquals(1, first.page.page)
        assertEquals(2, second.page.page)
        assertEquals(listOf("film"), third.page.items.map { it.id })
        assertFalse(third.page.hasMore)
    }

    @Test
    fun `the video sheet uses the same sections as the program sheet`() {
        val state = VideoUiState(item = video, chapters = PagedItemsState(listOf(video, film, trailer)))
        assertEquals(listOf(ProgramTab.EPISODES, ProgramTab.MOVIES, ProgramTab.MORE_VIDEOS), state.contentSections.map { it.tab })
        assertTrue(film.opensDetailsIn(state.contents))
    }

    @Test
    fun `additional content sections do not hide details or recommendations`() {
        val state = ProgramUiState(
            "program", "Programa",
            detail = ProgramDetail("program", "Programa", null, null, null, emptyList(), director = "Directora"),
            episodes = listOf(video, film),
            extras = PagedItemsState(listOf(trailer)),
            related = listOf(catalogItem("related")),
            isLoading = false,
        )
        assertEquals(
            listOf(ProgramTab.EPISODES, ProgramTab.MOVIES, ProgramTab.MORE_VIDEOS, ProgramTab.RELATED, ProgramTab.DETAILS),
            programTabs(state),
        )
    }
}
