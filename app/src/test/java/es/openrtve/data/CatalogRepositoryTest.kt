package es.openrtve.data

import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CatalogRepositoryTest {
    private var now = 1_000_000L
    private val client = ScriptedClient()
    private val repository = DefaultCatalogRepository(
        httpClient = client,
        parser = RtveJsonParser(),
        cache = RawDocumentCache(Files.createTempDirectory("openrtve-cache").toFile()),
        nowMillis = { now },
    )

    @Test
    fun `fresh cache is served without touching the network`() = runBlocking {
        client.response = { home("Primera") }
        repository.loadPortada("https://www.rtve.es/play/index_apps.json")

        client.response = { home("Segunda") }
        now += 60_000
        val result = repository.loadPortada("https://www.rtve.es/play/index_apps.json")

        assertEquals("Primera", result.value.title)
        assertEquals(1, client.calls)
        assertFalse(result.isStale)
    }

    @Test
    fun `expired cache goes to the network and force refresh ignores freshness`() = runBlocking {
        client.response = { home("Primera") }
        repository.loadPortada("https://www.rtve.es/play/index_apps.json")

        client.response = { home("Segunda") }
        now += 3 * 60_000
        assertEquals("Segunda", repository.loadPortada("https://www.rtve.es/play/index_apps.json").value.title)

        client.response = { home("Tercera") }
        assertEquals("Tercera", repository.loadPortada("https://www.rtve.es/play/index_apps.json", forceRefresh = true).value.title)
        assertEquals(3, client.calls)
    }

    @Test
    fun `network failure falls back to a stale copy and is reported as such`() = runBlocking {
        client.response = { home("Primera") }
        repository.loadPortada("https://www.rtve.es/play/index_apps.json")

        client.response = { throw IOException("offline") }
        now += 6 * 60 * 60_000
        val result = repository.loadPortada("https://www.rtve.es/play/index_apps.json")

        assertEquals("Primera", result.value.title)
        assertTrue(result.isStale)
    }

    @Test
    fun `an unchanged etag revalidates without downloading and keeps the copy fresh`() = runBlocking {
        client.etag = "W/\"abc\""
        client.response = { home("Primera") }
        repository.loadPortada("https://www.rtve.es/play/index_apps.json")

        now += 3 * 60_000
        client.response = { error("no debería descargar") }
        val result = repository.loadPortada("https://www.rtve.es/play/index_apps.json")

        assertEquals("Primera", result.value.title)
        assertEquals("W/\"abc\"", client.lastRequestedEtag)
        assertFalse(result.isStale)

        // Tras el 304 la copia vuelve a contar como fresca: sin red hasta que caduque.
        now += 60_000
        repository.loadPortada("https://www.rtve.es/play/index_apps.json")
        assertEquals(2, client.calls)
    }

    @Test
    fun `cached only returns any local copy without network and fails when there is none`() = runBlocking {
        try {
            repository.loadPortada("https://www.rtve.es/play/index_apps.json", cachedOnly = true)
            fail("sin copia local")
        } catch (_: NotCachedException) {
        }
        client.response = { home("Primera") }
        repository.loadPortada("https://www.rtve.es/play/index_apps.json")

        now += 3 * 60 * 60_000
        val copy = repository.loadPortada("https://www.rtve.es/play/index_apps.json", cachedOnly = true)
        assertEquals("Primera", copy.value.title)
        assertTrue("caducada pero disponible", copy.isStale)
        assertEquals(1, client.calls)
    }

    @Test
    fun `too old cache is not used and the network error surfaces`() = runBlocking {
        client.response = { home("Primera") }
        repository.loadPortada("https://www.rtve.es/play/index_apps.json")

        client.response = { throw IOException("offline") }
        now += 48 * 60 * 60_000
        try {
            repository.loadPortada("https://www.rtve.es/play/index_apps.json")
            fail("expected the network error")
        } catch (error: IOException) {
            assertEquals("offline", error.message)
        }
    }

    private fun home(title: String) = """{"title":"$title","rows":[]}"""

    private class ScriptedClient : TextHttpClient {
        var response: () -> String = { "{}" }
        var calls = 0
        var etag: String? = null
        var lastRequestedEtag: String? = null
        var lastUrl: String? = null

        override fun fetch(url: String, etag: String?): TextResponse {
            calls++
            lastUrl = url
            lastRequestedEtag = etag
            if (etag != null && etag == this.etag) return TextResponse(body = null, etag = etag)
            return TextResponse(body = response(), etag = this.etag)
        }
    }
}
