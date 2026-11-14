package es.openrtve.svt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SvtStateTest {
    private fun p(id: String, title: String) = SvtProgram(id, title, id, "")
    private val all = listOf(p("a", "Alfa"), p("b", "Beta"), p("c", "Gamma"), p("d", "Delta"))

    @Test
    fun `first download is the baseline, later new programs are pinned first`() {
        val first = SvtState().discover(listOf("a", "b", "c"), 10L)
        assertTrue(first.initialized)
        assertTrue(first.pinned.isEmpty())
        val next = first.discover(listOf("a", "b", "c", "d"), 20L)
        assertEquals(setOf("d"), next.pinned)
        assertEquals(20L, next.pinnedFresh["d"])
        assertEquals(listOf("d", "a", "b", "c"), next.order(all).map { it.id })
        // Sin novedades no cambia nada.
        assertEquals(next, next.discover(listOf("a", "d"), 30L))
    }

    @Test
    fun `a new program the user already unpinned is not pinned again`() {
        val s = SvtState(initialized = true, known = setOf("a"), unpinned = setOf("d")).discover(listOf("a", "d"), 5L)
        assertTrue("d" !in s.pinned)
    }

    @Test
    fun `watching pins and puts the program first, most recent first`() {
        val s = SvtState(initialized = true, known = setOf("a", "b", "c", "d"), pinned = setOf("b"))
            .watched("c", 100L).watched("a", 200L)
        assertEquals(listOf("a", "c", "b", "d"), s.order(all).map { it.id })
        // Ya anclado: sigue anclado y vuelve a ser el primero.
        val again = s.watched("c", 300L)
        assertEquals(listOf("c", "a", "b", "d"), again.order(all).map { it.id })
        // Ver algo desanclado a mano lo vuelve a anclar.
        assertTrue("b" in SvtState(unpinned = setOf("b")).watched("b", 1L).pinned)
    }

    @Test
    fun `toggle pins and unpins by hand`() {
        val pinned = SvtState().toggled("b")
        assertEquals(listOf("b", "a", "d", "c"), pinned.order(all).map { it.id })
        val unpinned = pinned.toggled("b")
        assertTrue("b" in unpinned.unpinned && "b" !in unpinned.pinned)
    }

    @Test
    fun `state survives a round trip through json`() {
        val s = SvtState(true, setOf("a", "b"), setOf("a"), mapOf("a" to 7L), setOf("b"))
        assertEquals(s, SvtState.fromJson(s.toJson()))
        assertEquals(SvtState(), SvtState.fromJson("basura"))
    }
}
