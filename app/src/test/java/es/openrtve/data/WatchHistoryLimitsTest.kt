package es.openrtve.data

import es.openrtve.domain.ContentKind
import es.openrtve.testing.catalogItem
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.Dispatchers
import org.junit.Test

class WatchHistoryLimitsTest {
    private val file = Files.createTempDirectory("openrtve-history").resolve("history.json").toFile()
    private var now = 1_000_000L
    private val history = WatchHistory(file, nowMillis = { now++ }, ioDispatcher = Dispatchers.Unconfined)

    @Test
    fun `only the most recent sixty entries survive`() {
        repeat(70) { index ->
            history.register(catalogItem("v$index"))
            history.updateProgress("v$index", 60_000, 600_000)
        }

        assertEquals(60, history.entries.value.size)
        assertNull("las más antiguas se descartan", history.entryFor("v0"))
        assertEquals("v69", history.resumable.first().item.id)
    }

    @Test
    fun `entries older than sixty days are dropped on the next write`() {
        history.register(catalogItem("old"))
        history.updateProgress("old", 60_000, 600_000)
        now += 61L * 24 * 60 * 60 * 1_000
        history.register(catalogItem("new"))

        assertNull(history.entryFor("old"))
        assertEquals(listOf("new"), history.entries.value.map { it.item.id })
    }

    @Test
    fun `re-registering keeps the saved position and progress needs a known duration`() {
        history.register(catalogItem("v1"))
        history.updateProgress("v1", 120_000, 600_000)
        history.register(catalogItem("v1").copy(title = "Nuevo título"))

        val entry = history.entryFor("v1")!!
        assertEquals(120_000L, entry.positionMs)
        assertEquals("Nuevo título", entry.item.title)

        history.updateProgress("v1", 300_000, durationMs = 0)
        assertEquals("sin duración no se guarda nada", 120_000L, history.entryFor("v1")!!.positionMs)
        history.updateProgress("desconocido", 300_000, 600_000)
        assertNull(history.entryFor("desconocido"))
    }

    @Test
    fun `finishing clamps the position to the end and watching again reopens it`() {
        history.register(catalogItem("v1", programId = "p"))
        history.updateProgress("v1", 599_000, 600_000)
        val finished = history.entryFor("v1")!!
        assertTrue(finished.finished)
        assertEquals(600_000L, finished.positionMs)
        assertEquals(1f, finished.progress, 0f)
        assertFalse(finished.inProgress)

        history.updateProgress("v1", 90_000, 600_000)
        val reopened = history.entryFor("v1")!!
        assertFalse(reopened.finished)
        assertTrue(reopened.inProgress)
    }

    @Test
    fun `remove forgets the item and a corrupt file starts empty`() {
        history.register(catalogItem("v1"))
        history.remove("v1")
        assertNull(history.entryFor("v1"))
        assertTrue(WatchHistory(file, ioDispatcher = Dispatchers.Unconfined).entries.value.isEmpty())

        file.writeText("{ esto no es json")
        assertTrue(WatchHistory(file, ioDispatcher = Dispatchers.Unconfined).entries.value.isEmpty())

        file.writeText("""[{"title":"sin id"},{"id":"ok","title":"Ok","kind":"INVENTADO","positionMs":5}]""")
        val recovered = WatchHistory(file, ioDispatcher = Dispatchers.Unconfined).entries.value
        assertEquals(listOf("ok"), recovered.map { it.item.id })
        assertEquals("un tipo desconocido se lee como vídeo", ContentKind.VIDEO, recovered.single().item.kind)
    }
}
