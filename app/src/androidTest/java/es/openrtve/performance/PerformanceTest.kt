package es.openrtve.performance

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.runtime.State
import androidx.media3.session.MediaController
import androidx.test.core.app.ActivityScenario
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import es.openrtve.OpenRtveApplication
import es.openrtve.data.DefaultCatalogRepository
import es.openrtve.data.NotCachedException
import es.openrtve.data.RawDocumentCache
import es.openrtve.data.RtveJsonParser
import es.openrtve.data.TextHttpClient
import es.openrtve.data.TextResponse
import es.openrtve.domain.CatalogModule
import es.openrtve.domain.DrmSpec
import es.openrtve.domain.HomeFeed
import es.openrtve.domain.HomeRow
import es.openrtve.domain.PlaybackDecision
import es.openrtve.domain.RowLayout
import es.openrtve.playback.PlayerActivity
import es.openrtve.testing.FakeCatalogRepository
import es.openrtve.testing.catalogItem
import es.openrtve.testing.loaded
import es.openrtve.ui.mobile.PortadaScreen
import es.openrtve.ui.theme.OpenRtveTheme
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Diagnóstico repetible sin depender del catálogo ni de las imágenes de la red. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 26)
class PerformanceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun parsingLargeSearchResponsesKeepsTheUiThreadResponsive() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val count = 5_000
        val raw = buildString {
            append("{\"videos\":{\"page\":{\"number\":1,\"totalPages\":1,\"items\":[")
            repeat(count) { index ->
                if (index > 0) append(',')
                append("{\"id\":\"").append(index).append("\",\"title\":\"Vídeo ").append(index)
                    .append("\",\"contentType\":\"video\",\"type\":{\"id\":\"39816\"}}")
            }
            append("]}}}")
        }
        val delays = mutableListOf<Double>()
        val violations = mutableListOf<Boolean>()
        repeat(3) {
            val heartbeat = CompletableFuture<Long>()
            val fetchedAt = AtomicLong()
            val client = object : TextHttpClient {
                override fun fetch(url: String, etag: String?): TextResponse {
                    fetchedAt.set(SystemClock.elapsedRealtimeNanos())
                    Handler(Looper.getMainLooper()).postDelayed({ heartbeat.complete(SystemClock.elapsedRealtimeNanos()) }, 15)
                    return TextResponse(raw, null)
                }
            }
            val repo = DefaultCatalogRepository(client, RtveJsonParser(), RawDocumentCache(File(context.cacheDir, "perf-search")))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            try {
                val result = runBlocking {
                    scope.async { repo.search("prueba").also { assertEquals(count, it.videos.size) } to SystemClock.elapsedRealtimeNanos() }.await()
                }
                val tick = heartbeat.get()
                val lateness = (tick - fetchedAt.get()) / 1_000_000.0 - 15
                delays += lateness.coerceAtLeast(0.0)
                violations += tick >= result.second
                println("PERF search items=$count uiDelayMs=$lateness totalMs=${(result.second - fetchedAt.get()) / 1_000_000.0}")
            } finally { scope.cancel() }
        }
        println("PERF search uiDelayMedianMs=${delays.sorted()[1]}")
        assertTrue("El hilo de interfaz debe atender mensajes antes de terminar de procesar el JSON", violations.none { it })
    }

    @Test fun scrollThroughMixedRowsRecordsFrameTimes() {
        val layouts = listOf(RowLayout.HERO, RowLayout.POSTER, RowLayout.LANDSCAPE, RowLayout.SQUARE, RowLayout.RANKED)
        val rows = List(40) { index -> HomeRow(index, "Fila $index", index, "Collection", null, "https://www.rtve.es/perf/$index", layouts[index % layouts.size]) }
        val repo = FakeCatalogRepository().apply {
            portada = { _, _, cachedOnly -> if (cachedOnly) throw NotCachedException() else loaded(HomeFeed(null, "Rendimiento", rows)) }
            module = { row, _, _ -> loaded(CatalogModule(row.title, List(16) { catalogItem("${row.id}-$it", title = "Contenido $it de ${row.id}") })) }
        }
        compose.setContent { OpenRtveTheme { PortadaScreen(repo, "https://www.rtve.es/perf", "Rendimiento", {}, {}, { _, _ -> }) } }
        compose.waitForIdle()
        val durations = Collections.synchronizedList(mutableListOf<Long>())
        val thread = HandlerThread("frame-metrics").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ -> durations += metrics.getMetric(FrameMetrics.TOTAL_DURATION) }
        compose.activity.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
        try {
            repeat(8) {
                compose.onAllNodes(hasScrollAction()).onFirst().performTouchInput { swipeUp(durationMillis = 300) }
                compose.waitForIdle()
            }
            repeat(4) {
                compose.onAllNodes(hasScrollAction()).onFirst().performTouchInput { swipeDown(durationMillis = 300) }
                compose.waitForIdle()
            }
        } finally {
            compose.activity.window.removeOnFrameMetricsAvailableListener(listener)
            thread.quitSafely()
            thread.join()
        }
        val values = durations.toList().sorted()
        assertTrue(values.isNotEmpty())
        println("PERF scroll frames=${values.size} p50Ms=${values[values.size / 2] / 1_000_000.0} p95Ms=${values[(values.size * 0.95).toInt().coerceAtMost(values.lastIndex)] / 1_000_000.0} over16ms=${values.count { it > 16_666_667 }} pssKb=${android.os.Debug.getPss()}")
    }

    /**
     * Desde abrir el reproductor hasta entregar el item a la sesión, con un token de
     * licencia que tarda [TOKEN_LATENCY_MS]. Se ejecuta solo (proceso nuevo) para que
     * la sesión arranque en frío como al abrir el primer vídeo.
     */
    @Test fun playerStartOverlapsLicenseTokenWithSessionConnection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val container = (context.applicationContext as OpenRtveApplication).container
        val launchedAt = AtomicLong()
        val tokenAt = AtomicLong()
        val tokenClient = container.drmTokenClient
        val field = tokenClient.javaClass.getDeclaredField("httpClient").apply { isAccessible = true }
        val original = field.get(tokenClient)
        field.set(tokenClient, object : TextHttpClient {
            override fun fetch(url: String, etag: String?): TextResponse {
                tokenAt.set(SystemClock.elapsedRealtime())
                SystemClock.sleep(TOKEN_LATENCY_MS)
                return TextResponse("""{"widevineURL":"https://license.example.test/widevine"}""", null)
            }
        })
        val file = File(context.cacheDir, "perf-player.wav")
        val samples = 8_000 * 10
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8_000); putInt(16_000)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(samples * 2)
        }.array()
        file.outputStream().use { it.write(header); it.write(ByteArray(samples * 2)) }
        val decision = PlaybackDecision.Ready(file.toURI().toString(), "audio/wav", "Rendimiento", drm = DrmSpec("https://api.rtve.es/perf-token"))
        try {
            val delegate = PlayerActivity::class.java.getDeclaredField("controller\$delegate").apply { isAccessible = true }
            // Sonda en paralelo al arranque: ActivityScenario.launch no vuelve hasta que la actividad se ha pintado.
            val connectedAt = AtomicLong()
            val itemAt = AtomicLong()
            val probe = Thread {
                val until = SystemClock.elapsedRealtime() + 10_000
                while (itemAt.get() == 0L && SystemClock.elapsedRealtime() < until) {
                    instrumentation.runOnMainSync {
                        val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                            .plus(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.STARTED))
                            .filterIsInstance<PlayerActivity>().firstOrNull() ?: return@runOnMainSync
                        @Suppress("UNCHECKED_CAST")
                        val controller = (delegate.get(activity) as State<MediaController?>).value ?: return@runOnMainSync
                        val now = SystemClock.elapsedRealtime()
                        connectedAt.compareAndSet(0L, now)
                        if (controller.currentMediaItem != null) itemAt.set(now)
                    }
                    SystemClock.sleep(2)
                }
            }
            launchedAt.set(SystemClock.elapsedRealtime())
            probe.start()
            ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, decision)).use { scenario ->
                probe.join()
                assertTrue("El reproductor no recibió el item", itemAt.get() > 0)
                val start = launchedAt.get()
                println("PERF player tokenRequestedMs=${tokenAt.get() - start} connectedMs=${connectedAt.get() - start} itemMs=${itemAt.get() - start} tokenLatencyMs=$TOKEN_LATENCY_MS")
                scenario.onActivity { activity ->
                    @Suppress("UNCHECKED_CAST")
                    (delegate.get(activity) as State<MediaController?>).value?.run { stop(); clearMediaItems() }
                }
            }
        } finally {
            field.set(tokenClient, original)
            file.delete()
        }
    }

    private companion object { const val TOKEN_LATENCY_MS = 250L }
}
