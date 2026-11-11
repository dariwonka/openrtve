package es.openrtve.data

import es.openrtve.domain.CatalogItem
import es.openrtve.domain.ContentKind
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import kotlinx.coroutines.Dispatchers
import org.junit.Test

class WatchHistoryTest {
    private val file = Files.createTempDirectory("openrtve-history").resolve("history.json").toFile()
    private var now = 1_000_000L
    private val history = WatchHistory(file, nowMillis = { now }, ioDispatcher = Dispatchers.Unconfined)

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

    @Test
    fun `API classification survives a restart so a film or extra is not resumed as a chapter`() {
        val trailer = item("trailer").copy(
            mediaTypeId = "39980", mediaTypeName = "Avance", mediaSubtypeId = "130734", mediaSubtypeName = "Película",
            programTypeId = "137310", programType = "Contenedor Películas", seasonId = "season", seasonTitle = "Temporada", episode = 3,
        )
        history.register(trailer)
        history.updateProgress(trailer.id, 40_000, 180_000)
        val restored = WatchHistory(file, nowMillis = { now }, ioDispatcher = Dispatchers.Unconfined)
        assertEquals(trailer, restored.entryFor(trailer.id)!!.item)
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
