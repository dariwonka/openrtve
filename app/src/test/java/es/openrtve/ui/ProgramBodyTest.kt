package es.openrtve.ui

import es.openrtve.domain.CatalogItem
import es.openrtve.domain.referenceItem
import es.openrtve.domain.ContentKind
import es.openrtve.domain.VideoDetail
import es.openrtve.domain.ProgramDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Qué enseña el cuerpo de la ficha y qué pestañas lleva: decisiones puras, sin Compose. */
class ProgramBodyTest {
    private fun item(id: String) = referenceItem(id, ContentKind.VIDEO, "Vídeo $id")

    private fun state(episodes: List<CatalogItem> = emptyList(), isLoading: Boolean = false, error: LoadError? = null) =
        ProgramUiState(programId = "p1", title = "Programa", episodes = episodes, isLoading = isLoading, error = error)

    @Test
    fun `mientras no se saben los capitulos el cuerpo carga, con la cabecera ya pintada`() {
        assertEquals(ProgramBody.Loading, programBody(state(isLoading = true)))
    }

    @Test
    fun `con capitulos se enseña la lista`() {
        assertEquals(ProgramBody.Episodes, programBody(state(episodes = listOf(item("v1")))))
    }

    @Test
    fun `sin capitulos se explica el hueco, con el error si lo hubo`() {
        assertEquals(ProgramBody.NoEpisodes(null), programBody(state()))
        assertEquals(ProgramBody.NoEpisodes(LoadError.Offline), programBody(state(error = LoadError.Offline)))
    }

    @Test
    fun `la ficha de programa solo tiene capitulos, asi que no enseña fila de pestañas`() {
        val state = state(episodes = listOf(item("v1")))
        assertEquals(listOf(ProgramTab.EPISODES), programTabs(ProgramBody.Episodes, state))
    }

    @Test
    fun `la ficha de video enseña detalles solo si hay ficha tecnica`() {
        val video = item("v1")
        fun detail(director: String?) = VideoDetail(
            item = video, backdropUrl = null, description = "Sinopsis", promo = null, subtypeName = null,
            programTitle = null, year = null, ageRating = null, genres = emptyList(), director = director,
            cast = emptyList(), originalLanguage = null, expirationDate = null, webUrl = null,
        )
        // La sinopsis va en la cabecera: sola no justifica la pestaña.
        assertTrue(videoTabs(VideoUiState(item = video, detail = detail(null))).isEmpty())
        assertEquals(listOf(ProgramTab.DETAILS), videoTabs(VideoUiState(item = video, detail = detail("Alguien"))))
    }

    @Test
    fun `las pestañas aparecen solo cuando su contenido existe`() {
        val extras = PagedItemsState(items = listOf(item("clip")), page = 1, totalPages = 1)
        val detail = ProgramDetail("p1", "Programa", null, null, null, emptyList(), director = "Directora")
        val programState = state(episodes = listOf(item("v1"))).copy(extras = extras, detail = detail)
        assertEquals(
            listOf(ProgramTab.EPISODES, ProgramTab.MORE_VIDEOS, ProgramTab.DETAILS),
            programTabs(ProgramBody.Episodes, programState),
        )

        val videoState = VideoUiState(
            item = item("v1"),
            chapters = PagedItemsState(items = listOf(item("v2"))),
            extras = extras,
        )
        assertEquals(listOf(ProgramTab.EPISODES, ProgramTab.MORE_VIDEOS), videoTabs(videoState))
        assertEquals(
            listOf(ProgramTab.DETAILS, ProgramTab.EPISODES, ProgramTab.MORE_VIDEOS),
            videoTabs(videoState.copy(detail = VideoDetail(
                item = item("v1"), backdropUrl = null, description = null, promo = null, subtypeName = null,
                programTitle = null, year = "2024", ageRating = null, genres = emptyList(), director = null,
                cast = emptyList(), originalLanguage = null, expirationDate = null, webUrl = null,
            ))),
        )
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
        assertEquals(listOf(ProgramTab.MOVIES, ProgramTab.MORE_VIDEOS), programTabs(ProgramBody.Episodes, state))
    }
}
