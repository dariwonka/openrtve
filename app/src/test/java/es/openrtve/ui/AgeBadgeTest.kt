package es.openrtve.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgeBadgeTest {
    @Test
    fun `age ratings get their short form`() {
        assertEquals("+7", ageBadge("No recomendable para menores de 7 años"))
        assertEquals("+18", ageBadge("No recomendable para menores de 18 años"))
        assertEquals("TP", ageBadge("Recomendable para todos los públicos"))
        assertEquals("Infantil", ageBadge("Especialmente recomendada para la infancia"))
        assertNull("lo que no se reconoce no va en la cabecera", ageBadge("Calificación pendiente"))
        assertNull(ageBadge(null))
    }
}
