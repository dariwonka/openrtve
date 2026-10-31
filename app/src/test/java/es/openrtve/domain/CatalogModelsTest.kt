package es.openrtve.domain

import es.openrtve.testing.catalogItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogModelsTest {
    @Test
    fun `live progress follows the clock when start and duration are known`() {
        val live = LiveInfo(isOnAir = true, startsAtMillis = 1_000_000, durationMinutes = 60, progressPercent = 10, channelLogoUrl = null, category = null)

        assertEquals(0f, live.progressAt(1_000_000)!!, 0.001f)
        assertEquals(0.5f, live.progressAt(1_000_000 + 30 * 60_000L)!!, 0.001f)
        assertEquals("nunca pasa de 1", 1f, live.progressAt(1_000_000 + 90 * 60_000L)!!, 0.001f)
        assertEquals("ni baja de 0 antes de empezar", 0f, live.progressAt(0)!!, 0.001f)
    }

    @Test
    fun `live progress falls back to the feed percentage and is absent otherwise`() {
        val fromFeed = LiveInfo(isOnAir = true, startsAtMillis = null, durationMinutes = null, progressPercent = 250, channelLogoUrl = null, category = null)
        assertEquals(1f, fromFeed.progressAt(0)!!, 0.001f)

        val zeroDuration = fromFeed.copy(startsAtMillis = 1, durationMinutes = 0, progressPercent = 40)
        assertEquals("duración 0 no divide: se usa el porcentaje", 0.4f, zeroDuration.progressAt(5)!!, 0.001f)

        assertNull(fromFeed.copy(progressPercent = null).progressAt(0))
    }

    @Test
    fun `upcoming means scheduled in the future and not yet on air`() {
        val scheduled = LiveInfo(isOnAir = false, startsAtMillis = 2_000, durationMinutes = 30, progressPercent = null, channelLogoUrl = null, category = null)
        assertTrue(scheduled.isUpcomingAt(1_999))
        assertFalse(scheduled.isUpcomingAt(2_000))
        assertFalse("en el aire aunque el feed lleve una hora futura", scheduled.copy(isOnAir = true).isUpcomingAt(0))
        assertFalse("sin hora no se puede bloquear", scheduled.copy(startsAtMillis = null).isUpcomingAt(0))
    }

    @Test
    fun `sprite cues are looked up by position with the last one covering the tail`() {
        val sprite = PreviewSprite(
            "https://img.rtve.es/sprite.jpg",
            listOf(SpriteCue(0, 10_000, 0, 0, 160, 90), SpriteCue(10_000, 20_000, 160, 0, 160, 90)),
        )

        assertEquals(0, sprite.cueAt(0)!!.x)
        assertEquals(0, sprite.cueAt(9_999)!!.x)
        assertEquals(160, sprite.cueAt(10_000)!!.x)
        assertEquals("más allá del último tramo vale el último", 160, sprite.cueAt(25_000)!!.x)
        assertNull(PreviewSprite("https://img.rtve.es/s.jpg", emptyList()).cueAt(5))
    }

    @Test
    fun `pagination and row flags derive from the feed values`() {
        assertTrue(CatalogPage(emptyList(), page = 1, totalPages = 3).hasMore)
        assertFalse(CatalogPage(emptyList(), page = 3, totalPages = 3).hasMore)
        assertFalse(CatalogPage(emptyList(), page = 1, totalPages = 0).hasMore)

        val row = HomeRow(id = 0, title = "", order = 0, moduleType = null, presentation = null, contentUrl = null)
        assertFalse(row.isLive)
        assertTrue(row.copy(presentation = "DirectosTV").isLive)
        assertTrue(row.copy(moduleType = "LivesCollection").isLive)
        assertFalse(row.copy(presentation = "ColeccionPoster").isLive)
    }

    @Test
    fun `an account is needed for login, paid or drm content`() {
        val free = catalogItem("1")
        assertFalse(free.needsAccount)
        assertTrue(free.copy(loginRequired = true).needsAccount)
        assertTrue(free.copy(paid = true).needsAccount)
        assertTrue(free.copy(drm = true).needsAccount)
        assertTrue(SearchResults(emptyList(), emptyList()).isEmpty)
        assertFalse(SearchResults(listOf(free), emptyList()).isEmpty)
    }
}
