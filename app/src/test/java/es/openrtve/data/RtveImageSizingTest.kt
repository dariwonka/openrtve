package es.openrtve.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RtveImageSizingTest {
    @Test
    fun `service images are requested at the drawn size in shared steps`() {
        assertEquals(
            "https://img.rtve.es/p/123?imgProgApi=imgPortada&w=640&h=360",
            RtveImageSizing.sized("https://img.rtve.es/p/123?imgProgApi=imgPortada&w=960", widthPx = 600, heightPx = 338),
        )
        assertEquals(
            "un hero 4:3 pide su recorte exacto, no un 16:9 ampliado",
            "https://img.rtve.es/v/9/horizontal2?w=1120&h=840",
            RtveImageSizing.sized("https://img.rtve.es/v/9/horizontal2?w=960", widthPx = 1080, heightPx = 810),
        )
    }

    @Test
    fun `width is capped and an unknown height keeps the aspect ratio`() {
        assertEquals("https://img.rtve.es/v/9/vertical?w=1920", RtveImageSizing.sized("https://img.rtve.es/v/9/vertical?w=480", 2560, -1))
    }

    @Test
    fun `originals without resizing go through the web resizer at the drawn size`() {
        assertEquals(
            "https://img.rtve.es/i/?w=320&h=180&i=https%3A%2F%2Fimg.rtve.es%2Fimagenes%2Fcine-de-siempre%2F1768405234614.jpg",
            RtveImageSizing.sized("https://img.rtve.es/imagenes/cine-de-siempre/1768405234614.jpg", widthPx = 300, heightPx = 169),
        )
        assertEquals(
            "sin alto conocido conserva la proporción del original",
            "https://img.rtve.es/i/?w=1120&i=https%3A%2F%2Fimg.rtve.es%2Fimagenes%2Fx%2F1.png",
            RtveImageSizing.sized("https://img.rtve.es/imagenes/x/1.png?v=2", widthPx = 1080, heightPx = -1),
        )
        assertEquals(
            "las categorías de Explorar",
            "https://img.rtve.es/i/?w=480&h=270&i=https%3A%2F%2Fimg.rtve.es%2Fcss%2Fmoviles%2Fi%2Frtveplay%2Fdispositivos%2Ftematica-cine.jpg",
            RtveImageSizing.sized("https://img.rtve.es/css/moviles/i/rtveplay/dispositivos/tematica-cine.jpg", widthPx = 480, heightPx = 270),
        )
        assertEquals(
            "una URL del redimensionador solo cambia de tamaño",
            "https://img.rtve.es/i/?i=1665049167787.jpg&w=480&h=270",
            RtveImageSizing.sized("https://img.rtve.es/i/?w=400&i=1665049167787.jpg", widthPx = 480, heightPx = 270),
        )
    }

    @Test
    fun `static files and other hosts are left alone`() {
        assertNull(RtveImageSizing.sized("https://img.rtve.es/resources/x/1.jpg", 300, 200))
        assertNull(RtveImageSizing.sized("https://img2.rtve.es/p/1?w=1", 300, 200))
        assertNull(RtveImageSizing.sized("https://img.rtve.es/p/1?w=1", 0, 0))
    }
}
