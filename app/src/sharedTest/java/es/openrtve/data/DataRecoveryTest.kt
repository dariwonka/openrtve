package es.openrtve.data

import es.openrtve.domain.SearchResults
import es.openrtve.testing.FakeCatalogRepository
import es.openrtve.testing.MainDispatcherRule
import es.openrtve.testing.catalogItem
import es.openrtve.ui.SearchViewModel
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import okio.source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Contratos de recuperación que se ejecutan tanto en JVM como en Android. */
@OptIn(ExperimentalCoroutinesApi::class)
class DataRecoveryTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun cancellingDuringTheResponseBodyCancelsTheCallAndReleasesTheCaller() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val socket = Socket(InetAddress.getLoopbackAddress(), server.localPort)
        val peer = server.accept()
        val reading = CountDownLatch(1)
        val call = AtomicReference<Call>()
        val source = object : ForwardingSource(socket.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                reading.countDown()
                return super.read(sink, byteCount)
            }
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = 1
            override fun source() = source
        }
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            call.set(chain.call())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body).build()
        }.build()
        val client = SafeHttpsClient(http)
        val worker = Thread {
            try { client.fetch("https://www.rtve.es/test.json") } catch (_: Exception) { }
        }.apply { isDaemon = true }
        try {
            worker.start()
            assertTrue(reading.await(2, TimeUnit.SECONDS))
            worker.interrupt()
            worker.join(2_000)
            assertFalse("El lector cancelado no debe seguir esperando", worker.isAlive)
            assertTrue(call.get().isCanceled())
        } finally {
            peer.close()
            socket.close()
            server.close()
            worker.join(2000)
            http.dispatcher.executorService.shutdownNow()
            http.connectionPool.evictAll()
        }
    }

    @Test fun cacheEnforcesItsLimitThroughoutTheSession() {
        val cache = RawDocumentCache(temporaryDirectory("review-cache"), maxBytes = 400)
        repeat(12) { cache.write("key-$it", "x".repeat(100), it * 1000L) }
        assertTrue(cache.sizeBytes() <= 400)
        assertNotNull(cache.read("key-11"))
    }

    @Test fun successfulNetworkResponseSurvivesAnUnwritableCache() = runBlocking {
        val directory = temporaryDirectory("review-cache-failure")
        val blocker = directory.resolve("not-a-directory").apply { writeText("file") }
        val client = object : TextHttpClient {
            override fun fetch(url: String, etag: String?) = TextResponse("""{"title":"Valid response","rows":[]}""", null)
        }
        val repo = DefaultCatalogRepository(client, RtveJsonParser(), RawDocumentCache(blocker))
        assertEquals("Valid response", repo.loadPortada("https://www.rtve.es/play/index_apps.json").value.title)
    }

    @Test fun aChangedQueryInvalidatesOldResultsBeforeTheDebounceFinishes() = runTest(main.dispatcher) {
        val oldResponse = CompletableDeferred<SearchResults>()
        val newResponse = CompletableDeferred<SearchResults>()
        val repo = FakeCatalogRepository().apply {
            search = { query -> if (query == "primera") oldResponse.await() else newResponse.await() }
        }
        val vm = SearchViewModel(repo)
        runCurrent()
        vm.setQuery("primera")
        advanceTimeBy(401)
        runCurrent()
        assertTrue(repo.calls.contains("search(primera)"))
        vm.setQuery("segunda")
        runCurrent()
        oldResponse.complete(SearchResults(emptyList(), listOf(catalogItem("old-result"))))
        runCurrent()
        assertEquals("segunda", vm.uiState.value.query)
        assertNull(vm.uiState.value.results)
        advanceTimeBy(401)
        runCurrent()
        assertTrue(vm.uiState.value.isSearching)
        assertNull(vm.uiState.value.results)
        newResponse.complete(SearchResults(emptyList(), emptyList()))
        advanceUntilIdle()
    }

    private fun temporaryDirectory(prefix: String): File = File.createTempFile(prefix, "").apply {
        check(delete() && mkdirs())
    }
}
