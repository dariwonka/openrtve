package es.openrtve.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PortadaTitleTest {
    @Test
    fun `menu titles in capitals take the spelling of the feed`() {
        assertEquals("La Revuelta", portadaTitle("LA REVUELTA", "Portada RTVE Play La Revuelta"))
        assertEquals("24 horas", portadaTitle("24 HORAS", "RTVE 24 horas"))
        // Las siglas siguen siéndolo.
        assertEquals("NO-DO", portadaTitle("NO-DO", "RTVE Play NO-DO"))
        assertEquals("TDP", portadaTitle("TDP", "Home TDP Play"))
    }

    @Test
    fun `without a match it reads as a sentence and written titles are kept`() {
        assertEquals("70 años", portadaTitle("70 AÑOS", "Portada RTVE Play Archivo"))
        assertEquals("Series para maratón", portadaTitle("Series para maratón", "Portada colección"))
        assertEquals("Portada RTVE Play Cine", portadaTitle("", "Portada RTVE Play Cine"))
    }
}
