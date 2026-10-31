package es.openrtve.ui

import es.openrtve.domain.ProgramDetail
import es.openrtve.testing.catalogItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgramContentsTest {
    private val film = catalogItem("film").copy(mediaSubtypeName = "Película")
    private val episode = catalogItem("episode").copy(episode = 1)
    private val base = ProgramUiState("program", "Colección")

    private fun state(type: String) = base.copy(
        detail = ProgramDetail("program", "Colección", null, null, null, emptyList(), programType = type),
    )

    @Test
    fun `a film cycle is a collection before its videos arrive`() {
        val state = state("Contenedor Películas")
        assertEquals(ProgramContents.MOVIES, state.contents)
        assertTrue(state.opensItemDetails(film))
        // Entrevistas y otras piezas del ciclo también se eligen en su propia ficha.
        assertTrue(state.opensItemDetails(episode))
    }

    @Test
    fun `other containers are video collections rather than episodes`() {
        assertEquals(ProgramContents.VIDEOS, state("Contenedor Documentales").contents)
        assertTrue(state("Contenedor Documentales").opensItemDetails(episode))
    }

    @Test
    fun `the page of videos never reclassifies its parent program`() {
        assertEquals(ProgramContents.EPISODES, base.copy(episodes = listOf(film)).contents)
        assertEquals(ProgramContents.EPISODES, base.contents)
        assertEquals(
            ProgramContents.EPISODES,
            state("Entrevistas").copy(title = "Cine de siempre", episodes = listOf(film)).contents,
        )
    }

    @Test
    fun `API type IDs identify a film container and a standalone film`() {
        val state = base.copy(detail = ProgramDetail(
            "program", "Programa", null, null, null, emptyList(), programTypeId = "137310",
        ))
        assertEquals(ProgramContents.MOVIES, state.contents)
        assertTrue(base.opensItemDetails(episode.copy(mediaSubtypeId = "130734")))
    }

    @Test
    fun `series keep direct playback but standalone films open their sheet`() {
        val state = state("Serie Documental").copy(episodes = listOf(episode, film))
        assertEquals(ProgramContents.EPISODES, state.contents)
        assertFalse(state.opensItemDetails(episode))
        assertTrue(state.opensItemDetails(film))
    }
}
