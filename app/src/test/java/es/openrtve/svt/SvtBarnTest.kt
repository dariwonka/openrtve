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
        assert(programs.all { it.slug.isNotBlank() && SvtBarn.isIncluded(it.slug) })
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

    @Test
    fun `reads the A-Z list and drops teen, other-language and single titles`() {
        val body = """{"data":{"categoryPage":{"modules":[
          {"id":"otra","selection":{"items":[{"heading":"No","item":{"__typename":"TvSeries","svtId":"x","urls":{"svtplay":"/no"}}}]}},
          {"id":"all-barn","selection":{"items":[
            {"heading":"Pippi","adaptiveImage":{"id":"5","changed":9},"item":{"__typename":"TvSeries","svtId":"a","urls":{"svtplay":"/pippi-langstrump"}}},
            {"heading":"Klassen","item":{"__typename":"TvSeries","svtId":"b","urls":{"svtplay":"/klassen"}}},
            {"heading":"Nuevo - finska","item":{"__typename":"TvSeries","svtId":"c","urls":{"svtplay":"/nuevo-finska"}}},
            {"heading":"Peli","item":{"__typename":"Single","svtId":"d","urls":{"svtplay":"/video/d/peli"}}},
            {"heading":"Bolibompa nuevo","item":{"__typename":"KidsTvShow","svtId":"e","urls":{"svtplay":"/bolibompa-nuevo"}}}
          ]}}]}}}"""
        val programs = SvtBarn.parsePrograms(body)
        assertEquals(listOf("Bolibompa nuevo", "Pippi"), programs.map { it.title })
        assertEquals("bolibompa-nuevo", programs[0].slug)
        assertEquals("5/9", programs[1].image)
        assertEquals("", programs[0].image)
    }

    @Test
    fun `episodes keep only what plays from abroad and without DRM, in order`() {
        val body = """{"data":{"listablesBySlug":[{"seasons":[{"number":1,"episodes":[
          {"videoSvtId":"v2","name":"Dos","duration":60,"number":2,"restrictions":{"onlyAvailableInSweden":false,"drmCopyProtection":false},"images":{"wide":{"id":"1","changed":2}}},
          {"videoSvtId":"v1","name":"Uno","duration":30,"number":1,"restrictions":{"onlyAvailableInSweden":false,"drmCopyProtection":false},"images":{"wide":null}},
          {"videoSvtId":"v3","name":"Solo Suecia","duration":30,"number":3,"restrictions":{"onlyAvailableInSweden":true,"drmCopyProtection":false}},
          {"videoSvtId":"v4","name":"DRM","duration":30,"number":4,"restrictions":{"onlyAvailableInSweden":false,"drmCopyProtection":true}}
        ]}]}]}}"""
        val episodes = SvtBarn.parseEpisodes(body)
        assertEquals(listOf("v1", "v2"), episodes.map { it.videoId })
        assertEquals("1/2", episodes[1].image)
        assertNull(SvtBarn.imageUrl(episodes[0].image))
        assertEquals(emptyList<SvtEpisode>(), SvtBarn.parseEpisodes("""{"data":{"listablesBySlug":[]}}"""))
    }

    @Test
    fun `filter excludes known teen titles and new titles in another language`() {
        assert(SvtBarn.isIncluded("pippi-langstrump"))
        assert(!SvtBarn.isIncluded("klassen"))
        assert(!SvtBarn.isIncluded("lo-que-sea-teckensprak"))
        assert(!SvtBarn.isIncluded(""))
    }
}
