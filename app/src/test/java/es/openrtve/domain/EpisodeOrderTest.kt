package es.openrtve.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class EpisodeOrderTest {
    private val base = ProgramDetail(id = "1", title = "P")

    @Test
    fun `episode order follows the official rules`() {
        // Serie diaria en emisión: del final.
        val onAir = base.copy(programTypeId = "132870", inEmission = true)
        assertEquals(EpisodeOrder.NEWEST_FIRST, onAir.episodeOrder())

        // Serie terminada sin completar (Isabel): del principio.
        assertEquals(EpisodeOrder.OLDEST_FIRST, base.copy(programTypeId = "136519", inEmission = false).episodeOrder())

        // Completa: siempre del principio, aunque emita.
        assertEquals(EpisodeOrder.OLDEST_FIRST, base.copy(programTypeId = "136519", inEmission = true, isComplete = true).episodeOrder())

        // Documentales, conciertos, entrevistas y reportajes: del final aunque hayan terminado y sea una temporada pasada.
        assertEquals(EpisodeOrder.NEWEST_FIRST, base.copy(programTypeId = "137650", inEmission = false).episodeOrder())

        // Noticias: del final mientras emiten; del principio si terminaron.
        assertEquals(EpisodeOrder.NEWEST_FIRST, base.copy(programTypeId = "132534", inEmission = true).episodeOrder())
        assertEquals(EpisodeOrder.OLDEST_FIRST, base.copy(programTypeId = "132534", inEmission = false).episodeOrder())

        // Sin tipo (programas antiguos): solo cuenta si emite.
        assertEquals(EpisodeOrder.NEWEST_FIRST, base.copy(inEmission = true).episodeOrder())
        assertEquals(EpisodeOrder.OLDEST_FIRST, base.copy(inEmission = false).episodeOrder())
    }

    @Test
    fun `api values match the official client`() {
        assertEquals("multimedia_date_emission,desc", EpisodeOrder.NEWEST_FIRST.apiValue)
        assertEquals("multimedia_date_emission,asc", EpisodeOrder.OLDEST_FIRST.apiValue)
    }
}
