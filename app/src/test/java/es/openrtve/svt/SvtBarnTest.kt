package es.openrtve.svt

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class SvtBarnTest {
    @Test
    fun `parses programs and orders episodes by season and number`() {
        val raw = """[{"i":"p1","t":"Serie","q":"10/20","e":[["v2","Dos",120,1,2,"1/2"],["v1","Uno",60,1,1,"3/4"],["v0","Sin numero",30,1,0,""]]},{"i":"p2","t":"Vacia","q":"","e":[]}]"""
        val programs = SvtBarn.parse(raw)
        assertEquals(1, programs.size)
        assertEquals(listOf("v1", "v2", "v0"), programs[0].episodes.map { it.videoId })
        assertEquals("https://www.svtstatic.se/image/wide/600/10/20", SvtBarn.imageUrl(programs[0].image))
        assertNull(SvtBarn.imageUrl(""))
    }

    @Test
    fun `bundled catalogue parses and has only playable episodes`() {
        val file = listOf("src/main/assets/svt_barn.json", "app/src/main/assets/svt_barn.json").map(::File).first { it.exists() }
        val programs = SvtBarn.parse(file.readText())
        assert(programs.size > 100)
        assert(programs.all { p -> p.episodes.all { it.videoId.isNotBlank() } })
    }

    @Test
    fun `prefers plain hls and ignores non https sources`() {
        val resolver = SvtStreamResolver(OkHttpClient())
        val body = """{"videoReferences":[{"format":"dash","url":"https://x/d.mpd"},{"format":"hls","url":"https://x/h.m3u8"}]}"""
        val ready = resolver.pick(body, "T")
        assertNotNull(ready)
        assertEquals("https://x/h.m3u8", ready!!.uri)
        assertEquals("application/x-mpegURL", ready.mimeType)
        assertNull(resolver.pick("""{"videoReferences":[{"format":"hls","url":"http://x/h.m3u8"}]}""", "T"))
        assertNull(resolver.pick("no json", "T"))
    }
}
