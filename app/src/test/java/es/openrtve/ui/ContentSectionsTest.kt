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

}
