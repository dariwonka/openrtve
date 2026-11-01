package es.openrtve.data

import es.openrtve.testing.catalogItem
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchHistoryWriterTest {
    @Test fun burstsKeepOnlyOnePendingWriteAndPersistTheLatestProgress() {
        val folder = File.createTempFile("history-writer", "").apply { check(delete() && mkdirs()) }
        val file = File(folder, "history.json")
        val dispatcher = QueuedDispatcher()
        val history = WatchHistory(file, ioDispatcher = dispatcher)
        repeat(100) { index ->
            history.register(catalogItem("video"))
            history.updateProgress("video", 30_000L + index * 1_000, 600_000)
        }
        val queued = dispatcher.pending
        val start = System.nanoTime()
        dispatcher.drain()
        println("PERF history queuedWrites=$queued drainMs=${(System.nanoTime() - start) / 1_000_000.0}")
        val restored = WatchHistory(file, ioDispatcher = QueuedDispatcher())
        assertEquals(129_000L, restored.entryFor("video")!!.positionMs)
        assertTrue("Una ráfaga no debe acumular una escritura por cada cambio", queued <= 1)
        folder.deleteRecursively()
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        val pending get() = tasks.size
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
}
