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
    fun `static files and other hosts are left alone`() {
        assertNull(RtveImageSizing.sized("https://img.rtve.es/imagenes/x/1.jpg", 300, 200))
        assertNull(RtveImageSizing.sized("https://img2.rtve.es/p/1?w=1", 300, 200))
        assertNull(RtveImageSizing.sized("https://img.rtve.es/p/1?w=1", 0, 0))
    }
}
