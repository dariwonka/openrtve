package es.openrtve.ui

import es.openrtve.data.WatchHistory
import es.openrtve.domain.CatalogItem
import es.openrtve.domain.ContentKind
import es.openrtve.domain.EpisodeOrder
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.Dispatchers
import org.junit.Test

class PlaySuggestionTest {
    // Reloj propio: con el real, dos episodios vistos en el mismo milisegundo empatan como "último visto".
    private var now = 1_000L
    private val history = WatchHistory(Files.createTempDirectory("openrtve").resolve("h.json").toFile(), nowMillis = { now++ }, ioDispatcher = Dispatchers.Unconfined)
    // Del más nuevo al más antiguo, como los feeds de RTVE.
    private val episodes = listOf(episode("904"), episode("903"), episode("902"))

    @Test
    fun `without history the newest episode plays`() {
        assertEquals(PlaySuggestion.Play(episodes[0]), suggestPlay(history, "p", episodes))
        assertNull(suggestPlay(history, "p", emptyList()))
    }

    @Test
    fun `an episode in progress is continued`() {
        history.register(episodes[2])
        history.updateProgress("902", 600_000, 3_000_000)

        val suggestion = suggestPlay(history, "p", episodes)
        assertTrue(suggestion is PlaySuggestion.Continue)
        assertEquals("902", suggestion!!.item.id)
    }

    @Test
    fun `a finished episode suggests the following one, or the newest when it was the last`() {
        history.register(episodes[2])
        history.updateProgress("902", 3_000_000, 3_000_000)
        assertEquals(PlaySuggestion.Next(episodes[1]), suggestPlay(history, "p", episodes))

        history.register(episodes[0])
        history.updateProgress("904", 3_000_000, 3_000_000)
        assertEquals(PlaySuggestion.Play(episodes[0]), suggestPlay(history, "p", episodes))
    }

    private fun episode(id: String) = CatalogItem(
        id = id,
        playbackId = id,
        assetId = null,
        title = "Episodio $id",
        subtitle = null,
        imageUrl = null,
        kind = ContentKind.VIDEO,
        allowedInCountry = null,
        loginRequired = false,
        paid = false,
        drm = false,
        programId = "p",
    )

    @Test
    fun `with the oldest first the series starts at the first chapter and the following is the next in the list`() {
        val oldestFirst = episodes.reversed()
        assertEquals(PlaySuggestion.Play(episodes[2]), suggestPlay(history, "p", oldestFirst, EpisodeOrder.OLDEST_FIRST))

        history.register(episodes[2])
        history.updateProgress("902", 3_000_000, 3_000_000)
        assertEquals(PlaySuggestion.Next(episodes[1]), suggestPlay(history, "p", oldestFirst, EpisodeOrder.OLDEST_FIRST))

        history.register(episodes[0])
        history.updateProgress("904", 3_000_000, 3_000_000)
        assertEquals("terminado el último, se vuelve a empezar", PlaySuggestion.Play(episodes[2]), suggestPlay(history, "p", oldestFirst, EpisodeOrder.OLDEST_FIRST))
    }
}
