package es.openrtve.ui

import es.openrtve.domain.ProgramStructure

import es.openrtve.domain.ProgramDetail
import es.openrtve.testing.catalogItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgramStructureTest {
    private val film = catalogItem("film").copy(mediaSubtypeName = "Película")
    private val episode = catalogItem("episode").copy(episode = 1)
    private val base = ProgramUiState("program", "Colección")

    private fun state(type: String) = base.copy(
        detail = ProgramDetail("program", "Colección", null, null, null, emptyList(), programType = type),
    )

    @Test
    fun `a film cycle is a collection before its videos arrive`() {
        val state = state("Contenedor Películas")
        assertEquals(ProgramStructure.MOVIES, state.structure)
        assertTrue(film.opensDetailsIn(state.structure))
        // Entrevistas y otras piezas del ciclo también se eligen en su propia ficha.
        assertTrue(episode.opensDetailsIn(state.structure))
    }

    @Test
    fun `other containers are video collections rather than episodes`() {
        assertEquals(ProgramStructure.DOCUMENTARIES, state("Contenedor Documentales").structure)
        assertTrue(episode.opensDetailsIn(state("Contenedor Documentales").structure))
    }

    @Test
    fun `the page of videos never reclassifies its parent program`() {
        assertEquals(ProgramStructure.EPISODIC, base.copy(episodes = PagedItemsState(listOf(film))).structure)
        assertEquals(ProgramStructure.EPISODIC, base.structure)
        assertEquals(
            ProgramStructure.EPISODIC,
            state("Entrevistas").copy(title = "Cine de siempre", episodes = PagedItemsState(listOf(film))).structure,
        )
    }

    @Test
    fun `API type IDs identify a film container and a standalone film`() {
        val state = base.copy(detail = ProgramDetail(
            "program", "Programa", null, null, null, emptyList(), programTypeId = "137310",
        ))
        assertEquals(ProgramStructure.MOVIES, state.structure)
        assertTrue(episode.copy(mediaSubtypeId = "130734").opensDetailsIn(base.structure))
    }

    @Test
    fun `series keep direct playback but standalone films open their sheet`() {
        val state = state("Serie Documental").copy(episodes = PagedItemsState(listOf(episode, film)))
        assertEquals(ProgramStructure.EPISODIC, state.structure)
        assertFalse(episode.opensDetailsIn(state.structure))
        assertTrue(film.opensDetailsIn(state.structure))
    }
}
