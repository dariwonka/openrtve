package es.openrtve.data

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawDocumentCacheTest {
    private val directory: File = Files.createTempDirectory("openrtve-raw").toFile()
    private val cache = RawDocumentCache(File(directory, "docs"))

    @Test
    fun `documents round trip with their timestamp and etag`() {
        cache.write(KEY, "{\"a\":1}", savedAtMillis = 1_700_000_000_000, etag = "W/\"x\"")

        val document = cache.read(KEY)!!
        assertEquals("{\"a\":1}", document.raw)
        assertEquals(1_700_000_000_000, document.savedAtMillis)
        assertEquals("W/\"x\"", document.etag)
    }

    @Test
    fun `missing keys read as null and keys do not leak into file names`() {
        assertNull(cache.read("https://www.rtve.es/nada.json"))
        cache.write("https://www.rtve.es/a/b?c=d&e=../../x", "raw", 1)
        assertTrue(File(directory, "docs").listFiles()!!.all { it.name.matches(Regex("[0-9a-f]{64}\\.(json|etag)")) })
    }

    @Test
    fun `pruning drops the oldest documents and their etags first`() {
        val small = RawDocumentCache(File(directory, "small"), maxBytes = 400)
        (1..5).forEach { small.write("k$it", "x".repeat(100), savedAtMillis = it * 1_000L, etag = "e$it") }
        small.prune()

        assertNull("el más antiguo se va", small.read("k1"))
        assertEquals("el más reciente se queda con su ETag", "e5", small.read("k5")!!.etag)
        assertTrue(small.sizeBytes() <= 300)
        assertTrue(File(directory, "small").listFiles()!!.none { it.name.endsWith(".etag") && !File(it.path.removeSuffix(".etag") + ".json").exists() })
    }

    @Test
    fun `rewriting without etag forgets the old one`() {
        cache.write(KEY, "v1", 1, etag = "W/\"1\"")
        cache.write(KEY, "v2", 2, etag = null)

        val document = cache.read(KEY)!!
        assertEquals("v2", document.raw)
        assertNull(document.etag)
    }

    @Test
    fun `touch renews the date but not the content`() {
        cache.write(KEY, "v1", 1_700_000_000_000, etag = "W/\"1\"")
        cache.touch(KEY, 1_700_000_060_000)

        val document = cache.read(KEY)!!
        assertEquals("v1", document.raw)
        assertEquals("W/\"1\"", document.etag)
        assertEquals(1_700_000_060_000, document.savedAtMillis)
    }

    @Test
    fun `size counts every file and clear empties the directory`() {
        cache.write(KEY, "12345", 1, etag = "abc")
        cache.write("$KEY?2", "678", 1)
        assertEquals(5 + 3 + 3L, cache.sizeBytes())

        cache.clear()
        assertEquals(0L, cache.sizeBytes())
        assertNull(cache.read(KEY))
    }

    @Test
    fun `unicode content survives the round trip`() {
        cache.write(KEY, "{\"t\":\"Año — 📺\"}", 1)
        assertEquals("{\"t\":\"Año — 📺\"}", cache.read(KEY)!!.raw)
    }

    private companion object {
        const val KEY = "https://www.rtve.es/play/index_apps.json"
    }
}
