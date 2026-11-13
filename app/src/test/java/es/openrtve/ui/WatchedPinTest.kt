package es.openrtve.ui

import es.openrtve.data.StoredCatalog
import es.openrtve.domain.ContentKind
import es.openrtve.domain.referenceItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchedPinTest {
    private val serie = referenceItem("p1", ContentKind.PROGRAM, "Bluey")
    private val otra = referenceItem("p2", ContentKind.PROGRAM, "Peppa")
    private val peli = referenceItem("m1", ContentKind.VIDEO, "Los Cinco")

    @Test
    fun `watching an episode pins its series and puts it first`() {
        val stored = StoredCatalog(listOf(serie, otra), listOf(peli), pinned = setOf("p2"), pinnedFresh = mapOf("p2" to 5L))
        val episode = referenceItem("v9", ContentKind.VIDEO, "Capítulo").copy(programId = "p1")
        val next = stored.withWatched(episode, 100L)
        assertEquals(setOf("p1", "p2"), next.pinned)
        assertEquals(100L, next.pinnedFresh["p1"])
        // Ya anclada: sigue anclada y vuelve a ser la primera.
        val again = next.withWatched(referenceItem("v10", ContentKind.VIDEO, "Otro").copy(programId = "p2"), 200L)
        assertEquals(setOf("p1", "p2"), again.pinned)
        assertEquals(200L, again.pinnedFresh["p2"])
    }

    @Test
    fun `watching a film pins it and undoes a manual unpin`() {
        val stored = StoredCatalog(listOf(serie), listOf(peli), unpinned = setOf("m1"))
        val next = stored.withWatched(peli, 50L)
        assertTrue("m1" in next.pinned)
        assertTrue("m1" !in next.unpinned)
        assertEquals(50L, next.pinnedFresh["m1"])
    }

    @Test
    fun `removed titles are not pinned again`() {
        val stored = StoredCatalog(emptyList(), emptyList(), removed = mapOf("m1" to false))
        assertEquals(stored, stored.withWatched(peli, 1L))
    }
}
