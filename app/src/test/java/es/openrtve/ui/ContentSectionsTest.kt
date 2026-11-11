package es.openrtve.ui

import es.openrtve.domain.ProgramStructure

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
            ProgramStructure.MOVIES,
            PagedItemsState(listOf(film, documentary, video, trailer), page = 1, totalPages = 3),
            PagedItemsState(listOf(trailer, catalogItem("clip")), page = 1, totalPages = 2),
            includePrimary = true,
        )
        assertEquals(
            listOf(ProgramTab.MOVIES, ProgramTab.DOCUMENTARIES, ProgramTab.VIDEOS, ProgramTab.MORE_VIDEOS),
            sections.map { it.tab },
        )
        assertEquals(listOf(listOf("film"), listOf("documentary"), listOf("video"), listOf("trailer", "clip")), sections.map { it.items.map { item -> item.id } })
        assertTrue(sections.all { it.hasMore })
        assertTrue(sections.last().loadsComplete)
        assertTrue(sections.last().loadsExtras)
    }

    @Test
    fun `Historia de nuestro cine keeps broadcasts and films in different sections`() {
        val sections = contentSections(
            ProgramStructure.EPISODIC,
            PagedItemsState(listOf(video, film)),
            PagedItemsState(),
            includePrimary = true,
        )
        assertEquals(listOf(ProgramTab.EPISODES, ProgramTab.MOVIES), sections.map { it.tab })
        assertEquals(listOf("video"), sections[0].items.map { it.id })
        assertEquals(listOf("film"), sections[1].items.map { it.id })
        assertFalse(video.opensDetailsIn(ProgramStructure.EPISODIC))
        assertTrue(film.opensDetailsIn(ProgramStructure.EPISODIC))
    }

    @Test
    fun `a documentary series keeps its documentary episodes together`() {
        val chapter = documentary.copy(seasonId = "season", episode = 2)
        val sections = contentSections(ProgramStructure.EPISODIC, PagedItemsState(listOf(chapter)), PagedItemsState(), true)
        assertEquals(listOf(ProgramTab.EPISODES), sections.map { it.tab })
        assertFalse(chapter.opensDetailsIn(ProgramStructure.EPISODIC))
        assertTrue(chapter.opensDetailsIn(ProgramStructure.DOCUMENTARIES))
    }

    @Test
    fun `an extra with film subtype remains an extra with direct playback`() {
        assertEquals(ProgramTab.MORE_VIDEOS, trailer.contentTab(ProgramStructure.MOVIES))
        assertFalse(trailer.opensDetailsIn(ProgramStructure.MOVIES))
        assertFalse(trailer.opensDetailsIn(ProgramStructure.EPISODIC))
    }

    @Test
    fun `titles never decide whether a piece is a trailer or a film`() {
        assertEquals(ProgramTab.VIDEOS, video.copy(title = "Tráiler de una película").contentTab(ProgramStructure.MOVIES))
        assertEquals(ProgramTab.MORE_VIDEOS, trailer.copy(title = "Película completa").contentTab(ProgramStructure.MOVIES))
    }

    @Test
    fun `an empty filtered page retains pagination until the next film arrives`() {
        fun sections(page: Int, items: List<es.openrtve.domain.CatalogItem>) = contentSections(
            ProgramStructure.MOVIES, PagedItemsState(items, page, 3), PagedItemsState(), true,
        )
        val first = sections(1, listOf(video)).first()
        val second = sections(2, listOf(video, documentary)).first()
        val third = sections(3, listOf(video, documentary, film)).first()
        assertTrue(first.items.isEmpty() && second.items.isEmpty())
        assertTrue(first.hasMore && second.hasMore)
        assertEquals(1, first.progressKey)
        assertEquals(2, second.progressKey)
        assertEquals(listOf("film"), third.items.map { it.id })
        assertFalse(third.hasMore)
    }

    @Test
    fun `the video sheet uses the same sections as the program sheet`() {
        val state = VideoUiState(item = video, chapters = PagedItemsState(listOf(video, film, trailer)))
        assertEquals(listOf(ProgramTab.EPISODES, ProgramTab.MOVIES, ProgramTab.MORE_VIDEOS), state.contentSections.map { it.tab })
        assertTrue(film.opensDetailsIn(state.structure))
    }

}
