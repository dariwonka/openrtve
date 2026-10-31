package es.openrtve.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepLinkTest {
    @Test
    fun `play rtve es short links map to typed destinations`() {
        assertEquals(DeepLink.Program("135930"), parseDeepLink("https://play.rtve.es/pr/135930"))
        assertEquals(DeepLink.Video("17222216"), parseDeepLink("https://play.rtve.es/v/17222216"))
        assertEquals(DeepLink.Live("1688877"), parseDeepLink("https://play.rtve.es/d/1688877"))
        assertEquals(
            DeepLink.Video("17222216"),
            parseDeepLink("https://play.rtve.es/content?uri=https%3A%2F%2Fwww.rtve.es%2Fplay%2Fvideos%2Ftelediario-2%2F21-horas%2F17222216%2F"),
        )
    }

    @Test
    fun `web urls resolve to video, audio, program and live`() {
        assertEquals(DeepLink.Video("16368062"), parseDeepLink("https://www.rtve.es/play/videos/cine-internacional/la-favorita/16368062/"))
        assertNull("los audios son de RTVE Audio", parseDeepLink("https://www.rtve.es/play/audios/ficcion-sonora/el-buscon/17150412/"))
        assertEquals(DeepLink.ProgramPermalink("telediario-2"), parseDeepLink("https://www.rtve.es/play/videos/telediario-2/"))
        assertNull(parseDeepLink("https://rtve.es/play/audios/ficcion-sonora"))
        assertEquals(DeepLink.LivePermalink("la-1"), parseDeepLink("https://www.rtve.es/play/videos/directo/la-1/"))
    }

    @Test
    fun `foreign or unknown urls are ignored`() {
        assertNull(parseDeepLink("https://example.org/play/videos/x/1/"))
        assertNull(parseDeepLink("https://www.rtve.es/noticias/20260913/algo.shtml"))
        assertNull(parseDeepLink("https://play.rtve.es/pr/abc"))
        assertNull(parseDeepLink("no es una url"))
    }
}
