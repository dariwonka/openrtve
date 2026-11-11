package es.openrtve.ui

import es.openrtve.domain.ProgramStructure

import es.openrtve.domain.ProgramDetail
import es.openrtve.testing.catalogItem
import org.junit.Assert.assertEquals
import org.junit.Test

class ProgramStructureTest {
    private val film = catalogItem("film").copy(mediaSubtypeName = "Película")
    private val episode = catalogItem("episode").copy(episode = 1)
    private val base = ProgramUiState("program", "Colección")

    private fun state(type: String) = base.copy(
        detail = ProgramDetail("program", "Colección", null, null, null, emptyList(), programType = type),
    )

    @Test
    fun `the page of videos never reclassifies its parent program`() {
        assertEquals(ProgramStructure.EPISODIC, base.copy(episodes = PagedItemsState(listOf(film))).structure)
        assertEquals(ProgramStructure.EPISODIC, base.structure)
        assertEquals(
            ProgramStructure.EPISODIC,
            state("Entrevistas").copy(title = "Cine de siempre", episodes = PagedItemsState(listOf(film))).structure,
        )
    }

}
