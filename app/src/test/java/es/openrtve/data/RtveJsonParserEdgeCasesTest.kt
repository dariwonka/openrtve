package es.openrtve.data

import es.openrtve.domain.ContentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RtveJsonParserEdgeCasesTest {
    private val parser = RtveJsonParser()

    @Test
    fun `a portada without rows or title still parses`() {
        val feed = parser.parseHome("""{"id":"1"}""")
        assertEquals("RTVE Play", feed.title)
        assertTrue(feed.rows.isEmpty())
    }

    @Test
    fun `rows without order go last and keep their relative position`() {
        val feed = parser.parseHome(
            """{"rows":[
              {"title":"sin orden A","urlContent":"https://api.rtve.es/a.json"},
              {"title":"dos","orden":2,"urlContent":"https://api.rtve.es/b.json"},
              {"title":"sin orden B","urlContent":"https://api.rtve.es/c.json"},
              {"title":"uno","orden":1,"urlContent":"https://api.rtve.es/d.json"}
            ]}""",
        )
        assertEquals(listOf("uno", "dos", "sin orden A", "sin orden B"), feed.rows.map { it.title })
    }

    @Test
    fun `modules accept items at the root, under page, or nested in a single collection`() {
        val root = parser.parseModule("""{"items":[{"id":"1","contentType":"video","title":"a"}]}""", "Fila")
        val paged = parser.parseModule("""{"page":{"items":[{"id":"2","contentType":"video","title":"b"}]}}""", "Fila")
        val empty = parser.parseModule("""{"page":{}}""", "Fila")

        assertEquals(listOf("1"), root.items.map { it.id })
        assertEquals(listOf("2"), paged.items.map { it.id })
        assertTrue(empty.items.isEmpty())
        assertEquals("Fila", empty.title)
    }

    @Test
    fun `unusable documents raise instead of producing half a sheet`() {
        for (raw in listOf("""{"page":{"items":[]}}""", """{}""")) {
            try {
                parser.parseProgram(raw)
                fail("programa sin ficha")
            } catch (_: IllegalArgumentException) {
            }
            try {
                parser.parseVideo(raw)
                fail("vídeo sin ficha")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun `live category is title cased only when it arrives shouting`() {
        val module = parser.parseModule(
            """{"items":[
              {"id":"1","idAsset":"a1","tipo":"broadcast","titulo":"Uno","antetitulo":"LA VUELTA 2026"},
              {"id":"2","idAsset":"a2","tipo":"broadcast","titulo":"Dos","antetitulo":"Telediario 2"}
            ]}""",
            "",
        )
        assertEquals(ContentKind.LIVE, module.items[0].kind)
        assertEquals("La Vuelta 2026", module.items[0].live!!.category)
        assertEquals("Telediario 2", module.items[1].live!!.category)
    }

    @Test
    fun `items without any usable id or title are skipped rather than crashing the row`() {
        val module = parser.parseModule(
            """{"items":[
              {"contentType":"video","title":"sin id"},
              {"id":"3","contentType":"video"},
              {"id":"4","contentType":"video","title":"ok"},
              "no es un objeto"
            ]}""",
            "",
        )
        assertEquals(listOf("4"), module.items.map { it.id })
    }

    @Test
    fun `search tolerates missing blocks`() {
        val results = parser.parseSearch("""{"programs":{}}""")
        assertTrue(results.isEmpty)
    }
}
