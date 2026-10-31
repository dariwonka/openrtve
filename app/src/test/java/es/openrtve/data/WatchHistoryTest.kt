package es.openrtve.data

import es.openrtve.domain.CatalogItem
import es.openrtve.domain.ContentKind
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.Dispatchers
import org.junit.Test

class WatchHistoryTest {
    private val file = Files.createTempDirectory("openrtve-history").resolve("history.json").toFile()
    private var now = 1_000_000L
    private val history = WatchHistory(file, nowMillis = { now }, ioDispatcher = Dispatchers.Unconfined)

    @Test
    fun `progress is kept per item, finished items drop out and short positions do not count`() {
        history.register(item("v1"))
        history.updateProgress("v1", positionMs = 600_000, durationMs = 3_000_000)
        assertEquals(0.2f, history.entryFor("v1")!!.progress, 0.001f)
        assertEquals(listOf("v1"), history.resumable.map { it.item.id })

        history.register(item("v2"))
        history.updateProgress("v2", positionMs = 5_000, durationMs = 3_000_000)
        assertTrue("menos de 30 s no aparece en Seguir viendo", history.resumable.none { it.item.id == "v2" })

        history.updateProgress("v1", positionMs = 2_900_000, durationMs = 3_000_000)
        val finished = history.entryFor("v1")!!
        assertTrue("al 95 % se considera visto", finished.finished)
        assertTrue(history.resumable.none { it.item.id == "v1" })
        assertEquals("v1", history.lastWatchedOf("p1")!!.item.id)
    }

    @Test
    fun `lives are never recorded and entries survive a restart`() {
        history.register(item("live").copy(kind = ContentKind.LIVE))
        assertNull(history.entryFor("live"))

        history.register(item("v1"))
        now += 10
        history.updateProgress("v1", positionMs = 120_000, durationMs = 600_000)

        val reloaded = WatchHistory(file, nowMillis = { now }, ioDispatcher = Dispatchers.Unconfined)
        val entry = reloaded.entryFor("v1")!!
        assertEquals(120_000L, entry.positionMs)
        assertEquals("Vídeo v1", entry.item.title)
        assertEquals("p1", entry.item.programId)
    }

    private fun item(id: String) = CatalogItem(
        id = id,
        playbackId = id,
        assetId = null,
        title = "Vídeo $id",
        subtitle = null,
        imageUrl = "https://img.rtve.es/v/$id/horizontal2",
        kind = ContentKind.VIDEO,
        allowedInCountry = null,
        loginRequired = false,
        paid = false,
        drm = false,
        programId = "p1",
    )
}
