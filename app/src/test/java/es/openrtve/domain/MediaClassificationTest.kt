package es.openrtve.domain

import es.openrtve.testing.catalogItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaClassificationTest {
    @Test
    fun `form and subject remain independent and subtype IDs work without names`() {
        val trailer = catalogItem("trailer").copy(mediaTypeId = "39980", mediaSubtypeId = "130734")
        assertEquals(MediaForm.EXTRA, trailer.mediaForm)
        assertEquals(MediaSubject.MOVIE, trailer.mediaSubject)
        assertFalse(trailer.isFullMovie)
        assertFalse(trailer.canHaveNextEpisode)
        assertEquals(MediaSubject.DOCUMENTARY, trailer.copy(mediaSubtypeId = "130735").mediaSubject)
    }

    @Test
    fun `autoplay excludes films extras and the pieces of a collection`() {
        val episode = catalogItem("episode").copy(mediaTypeId = "39816", programType = "Serie Documental")
        assertTrue(episode.canHaveNextEpisode)
        assertFalse(episode.copy(mediaSubtypeId = "130734").canHaveNextEpisode)
        assertFalse(episode.copy(mediaTypeId = "39978").canHaveNextEpisode)
        assertFalse(episode.copy(programType = "Contenedor Documentales").canHaveNextEpisode)
        assertFalse(episode.copy(programTypeId = "137310").canHaveNextEpisode)
    }
}
