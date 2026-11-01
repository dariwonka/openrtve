package es.openrtve.playback

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import es.openrtve.OpenRtveApplication
import es.openrtve.data.TextHttpClient
import es.openrtve.data.TextResponse
import es.openrtve.domain.DrmSpec
import es.openrtve.domain.PlaybackDecision
import es.openrtve.testing.catalogItem
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Sesión y ExoPlayer reales, audio local y tokens controlados: no depende de RTVE. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerActivityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container get() = (context.applicationContext as OpenRtveApplication).container

    @Test fun aFinishedVideoStartsAtTheBeginning() = withPlayer { decision, controller ->
        container.watchHistory.updateProgress(decision.historyKey!!, 59_000, 60_000)
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, decision)).use {
            await { controller.duration > 0 && controller.playbackState != Player.STATE_BUFFERING }
            assertTrue("Reproducir un vídeo visto debe empezar al principio", onMain { controller.currentPosition } < 5_000)
        }
    }

    @Test fun anExplicitRestartSeeksAndPlaysEvenWhenTheSameVideoIsLoaded() = withPlayer { decision, controller ->
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, decision)).use { scenario ->
            await { controller.playbackState == Player.STATE_READY }
            onMain { controller.seekTo(20_000); controller.pause() }
            scenario.onActivity { it.startActivity(PlayerActivity.intent(it, decision, restart = true)) }
            instrumentation.waitForIdleSync()
            await { controller.playWhenReady && controller.currentPosition < 5_000 }
            onMain { controller.seekTo(20_000); controller.pause() }
            scenario.onActivity { it.startActivity(PlayerActivity.intent(it, decision)) }
            await { controller.playWhenReady && controller.currentPosition >= 20_000 }
            onMain { controller.pause() }
            scenario.recreate()
            assertFalse("Reconectar no equivale a una nueva orden de reproducir", onMain { controller.playWhenReady })
        }
    }

    @Test fun returningFromTheNotificationKeepsTheHistoryIdentity() = withPlayer { decision, controller ->
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, decision)).use { scenario ->
            await { controller.playbackState == Player.STATE_READY }
            onMain { controller.seekTo(35_000); controller.pause() }
            scenario.onActivity { it.startActivity(Intent(it, PlayerActivity::class.java)) }
            instrumentation.waitForIdleSync()
            scenario.close()
            assertTrue("La reapertura desde notificación debe seguir guardando progreso", container.watchHistory.entryFor(decision.historyKey!!)!!.positionMs >= 30_000)
        }
    }

    @Test fun openingAnExistingSessionWithoutExtrasRestoresItsHistoryIdentity() = withPlayer { decision, controller ->
        var item: androidx.media3.common.MediaItem? = null
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, decision)).use {
            await { controller.playbackState == Player.STATE_READY }
            item = onMain { controller.currentMediaItem!!.buildUpon().setUri(decision.uri).setMimeType(decision.mimeType).build() }
        }
        onMain { controller.setMediaItem(item!!); controller.prepare(); controller.play() }
        ActivityScenario.launch<PlayerActivity>(Intent(context, PlayerActivity::class.java)).use { scenario ->
            var activity: PlayerActivity? = null
            scenario.onActivity { activity = it }
            await { controller.playbackState == Player.STATE_READY && activity?.playerView?.player != null }
            onMain { controller.seekTo(35_000); controller.pause() }
            scenario.close()
            assertTrue(container.watchHistory.entryFor(decision.historyKey!!)!!.positionMs >= 30_000)
        }
    }

    @Test fun aTokenThatArrivesAfterLeavingDoesNotStartPlayback() = withPlayer { decision, controller ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val tokenClient = container.drmTokenClient
        val field = tokenClient.javaClass.getDeclaredField("httpClient").apply { isAccessible = true }
        val original = field.get(tokenClient)
        field.set(tokenClient, object : TextHttpClient {
            override fun fetch(url: String, etag: String?): TextResponse {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
                return TextResponse("""{"widevineURL":"https://license.example.test/widevine"}""", null)
            }
        })
        try {
            val pending = decision.copy(drm = DrmSpec("https://api.rtve.es/test-token"))
            ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, pending)).use { scenario ->
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                var activity: PlayerActivity? = null
                scenario.onActivity { activity = it }
                assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
                await { activity!!.lifecycle.currentState == Lifecycle.State.CREATED }
                release.countDown()
                SystemClock.sleep(1_000)
                assertNull("Salir sin PiP invalida el arranque pendiente", onMain { controller.currentMediaItem })
            }
        } finally {
            release.countDown()
            field.set(tokenClient, original)
        }
    }

    private fun withPlayer(test: (PlaybackDecision.Ready, MediaController) -> Unit) {
        val settings = container.settings.current
        container.settings.update { it.copy(pictureInPictureOnLeave = false, autoplayNext = false) }
        val file = File(context.cacheDir, "player-test-${System.nanoTime()}.wav")
        val samples = 8_000 * 60
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8_000); putInt(16_000)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(samples * 2)
        }.array()
        file.outputStream().use { it.write(header); it.write(ByteArray(samples * 2)) }
        val id = "player-test-${System.nanoTime()}"
        container.watchHistory.register(catalogItem(id))
        val decision = PlaybackDecision.Ready(file.toURI().toString(), "audio/wav", "Prueba local", historyKey = id)
        val future = onMain { MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }
        val controller = future.get(10, TimeUnit.SECONDS)
        onMain { controller.stop(); controller.clearMediaItems() }
        try { test(decision, controller) } finally {
            onMain { controller.stop(); controller.clearMediaItems(); controller.release() }
            container.watchHistory.remove(id)
            container.settings.update { settings }
            file.delete()
        }
    }

    private fun await(condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 10_000
        while (!onMain(condition) && SystemClock.elapsedRealtime() < until) SystemClock.sleep(50)
        assertTrue("La sesión no alcanzó el estado esperado", onMain(condition))
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
