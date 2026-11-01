package es.openrtve.ui

import es.openrtve.domain.CatalogItem
import es.openrtve.domain.referenceItem
import es.openrtve.domain.ContentKind
import es.openrtve.domain.VideoDetail
import es.openrtve.domain.ProgramDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Navegación de las fichas según el contenido disponible, sin Compose. */
class ProgramTabsTest {
    private fun item(id: String) = referenceItem(id, ContentKind.VIDEO, "Vídeo $id")

    private fun state(episodes: List<CatalogItem> = emptyList()) =
        ProgramUiState(programId = "p1", title = "Programa", episodes = PagedItemsState(episodes))

    @Test
    fun `la ficha de programa solo tiene capitulos, asi que no enseña fila de pestañas`() {
        val state = state(episodes = listOf(item("v1")))
        assertEquals(listOf(ProgramTab.EPISODES), programTabs(state))
    }

    @Test
    fun `la ficha tecnica va en la cabecera, nunca en una pestaña`() {
        val video = item("v1")
        val detail = VideoDetail(
            item = video, backdropUrl = null, description = "Sinopsis", programTitle = null, year = "2024", ageRating = null, genres = emptyList(), director = "Alguien",
            cast = listOf("Actriz"), originalLanguage = null, expirationDate = null, webUrl = null,
        )
        assertTrue(videoTabs(VideoUiState(item = video, detail = detail)).isEmpty())
        // Lo que ya dice la línea de datos de la cabecera no se repite en la ficha técnica.
        assertEquals(listOf(es.openrtve.R.string.video_cast, es.openrtve.R.string.video_director), detail.facts.rows.map { it.first })
        assertEquals(2, detail.facts.compactRowCount)
    }

    @Test
    fun `las pestañas aparecen solo cuando su contenido existe`() {
        val extras = PagedItemsState(items = listOf(item("clip")), page = 1, totalPages = 1)
        val detail = ProgramDetail("p1", "Programa", null, null, null, emptyList(), director = "Directora")
        val programState = state(episodes = listOf(item("v1"))).copy(extras = extras, detail = detail)
        assertEquals(listOf(ProgramTab.EPISODES, ProgramTab.MORE_VIDEOS), programTabs(programState))

        val videoState = VideoUiState(
            item = item("v1"),
            chapters = PagedItemsState(items = listOf(item("v2"))),
            extras = extras,
        )
        assertEquals(listOf(ProgramTab.EPISODES, ProgramTab.MORE_VIDEOS), videoTabs(videoState))
    }

    @Test
    fun `containers show their catalog and extras without technical details or similar programs`() {
        val state = state(episodes = listOf(item("film").copy(mediaSubtypeId = "130734"))).copy(
            detail = ProgramDetail(
                "cycle", "Ciclo", null, null, null, emptyList(),
                programTypeId = "137310", genres = listOf("Cine"), director = "No es una obra",
            ),
            related = listOf(item("other-program")),
            extras = PagedItemsState(items = listOf(item("clip"))),
        )
        assertEquals(listOf(ProgramTab.MOVIES, ProgramTab.MORE_VIDEOS), programTabs(state))
    }
}
