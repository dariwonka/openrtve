package es.openrtve.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChildProfileTest {
    @Test
    fun `the child profile is what the official Clan app sends`() = runTest {
        val requested = mutableListOf<String>()
        val client = object : TextHttpClient {
            override fun get(url: String): String {
                requested += url
                return """{"programs":{"page":{"items":[]}},"videos":{"page":{"items":[]}}}"""
            }
            override fun fetch(url: String, etag: String?): TextResponse = error("la búsqueda no se cachea")
        }
        val repository = DefaultCatalogRepository(client, RtveJsonParser(), RawDocumentCache(java.nio.file.Files.createTempDirectory("clan-search").toFile()), childProfile = true)

        repository.search("corona mágica")

        assertEquals("https://api.rtve.es/api/search/results?search=corona+m%C3%A1gica&context=tve&tipology=video&type=completo&isChild=true", requested.single())
    }

    @Test
    fun `the A-Z listing of a channel is a list of programmes even without a declared type`() {
        val page = RtveJsonParser().parsePrograms(
            """{"page":{"items":[{"id":"123","name":"Bluey"},{"id":456,"name":"Pocoyó"},{"name":"Sin id"},{"id":"123","name":"Bluey"}],"number":1,"totalPages":2}}""",
        )

        assertEquals(listOf("123" to "Bluey", "456" to "Pocoyó"), page.items.map { it.id to it.title })
        assertTrue(page.items.all { it.kind == es.openrtve.domain.ContentKind.PROGRAM && it.imageUrl?.contains("/p/${it.id}") == true })
        assertTrue(page.hasMore)
    }
}
