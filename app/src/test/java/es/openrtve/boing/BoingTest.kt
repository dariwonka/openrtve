package es.openrtve.boing

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoingTest {
    @Test
    fun `bundled catalogue parses`() {
        val file = listOf("src/main/assets/boing_catalogo.json", "app/src/main/assets/boing_catalogo.json").map(::File).first { it.exists() }
        val series = Boing.parseCatalog(file.readText())
        assertTrue(series.size >= 30)
        assertTrue(series.all { s -> s.episodes.isNotEmpty() && s.episodes.all { it.guid.isNotBlank() && it.slug.isNotBlank() } })
    }

    @Test
    fun `chapters skip shorts extras and games and tolerate nulls`() {
        val body = """{"Menu":[
          {"Category":{"Title":"Episodios"},"Items":[{"ObjectStringType":"Image","Guid":"i"},{"ObjectStringType":"Video","Guid":"b","Slug":"b","Title":"Dos","Order":2,"poster":null,"Thumbnail":"t"},{"ObjectStringType":"Video","Guid":"a","Slug":"a","Title":"Uno","Order":1,"poster":"p"}]},
          {"Category":{"Title":"Cortos"},"Items":[{"ObjectStringType":"Video","Guid":"c","Slug":"c","Title":"Corto","Order":1}]},
          {"Category":{"Title":"Juegos"},"Items":[{"ObjectStringType":"Game","Guid":"g"}]}]}"""
        val chapters = Boing.parseChapters(body)
        assertEquals(listOf("a", "b"), chapters.map { it.guid })
        assertEquals("t", chapters[1].image)
    }

    @Test
    fun `several chapter menus are prefixed and numbered as seasons`() {
        val body = """{"Menu":[{"Category":{"Title":"Agallas"},"Items":[{"ObjectStringType":"Video","Guid":"a","Slug":"a","Title":"X","Order":1}]},
          {"Category":{"Title":" Dexter"},"Items":[{"ObjectStringType":"Video","Guid":"d","Slug":"d","Title":"Y","Order":1}]}]}"""
        val chapters = Boing.parseChapters(body)
        assertEquals(listOf("Agallas · X", "Dexter · Y"), chapters.map { it.title })
        assertEquals(listOf(1, 2), chapters.map { it.season })
    }

    @Test
    fun `stream prefers https hls and refuses protected video`() {
        val ok = """{"Video":{"ProtectionType":"none","Formats":[{"Type":"mpegdash","Url":"https://x/d.mpd"},{"Type":"hls","Url":"http://x/insecure.m3u8"},{"Type":"hls","Url":"https://x/m.m3u8"}]}}"""
        assertEquals("https://x/m.m3u8", Boing.pickStream(ok, "T")?.uri)
        val dash = """{"Video":{"ProtectionType":"none","Formats":[{"Type":"mpegdash","Url":"https://x/d.mpd"}]}}"""
        assertEquals("https://x/d.mpd", Boing.pickStream(dash, "T")?.uri)
        val drm = """{"Video":{"ProtectionType":"widevine","Formats":[{"Type":"hls","Url":"https://x/m.m3u8"}]}}"""
        assertNull(Boing.pickStream(drm, "T"))
        assertNull(Boing.pickStream("no json", "T"))
    }

    @Test
    fun `video info and programs`() {
        assertEquals(1768 to 13, Boing.parseVideoInfo("""{"Video":{"DurationSeconds":1768,"episode_number":13}}"""))
        assertNull(Boing.parseVideoInfo("{}"))
        val programs = Boing.parsePrograms("""{"Programs":[{"Guid":"g","Slug":"s","Title":" Serie ","Thumbnail":null},{"Slug":"x"}]}""")
        assertEquals(1, programs.size)
        assertEquals("Serie", programs[0].title)
        assertNotNull(with(Boing) { programs[0].asItem() })
    }
}
