package es.openrtve.playback

import es.openrtve.testing.catalogItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayQueueTest {
    @Test
    fun `next is the one after in the list order`() {
        val queue = PlayQueue()
        queue.set(listOf(catalogItem("c"), catalogItem("a"), catalogItem("b")))
        assertEquals("a", queue.after("c")?.id)
        assertEquals("b", queue.after("a")?.id)
        assertNull(queue.after("b"))
        assertNull(queue.after("zzz"))
        assertNull(queue.after(null))
    }
}
