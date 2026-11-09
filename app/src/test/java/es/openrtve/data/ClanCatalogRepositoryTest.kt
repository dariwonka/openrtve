package es.openrtve.data

import es.openrtve.domain.ExploreCategory
import es.openrtve.domain.ExploreGroup
import es.openrtve.domain.RelatedKind
import es.openrtve.testing.FakeCatalogRepository
import es.openrtve.testing.catalogItem
import es.openrtve.testing.loaded
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClanCatalogRepositoryTest {
    private val general = FakeCatalogRepository()
    private val clan = ClanCatalogRepository(general)

    private val junior = ExploreCategory("JUNIOR", null, "https://www.rtve.es/play/clan/edad/junior/index_apps.json")
    private val cine = ExploreCategory("CINE", null, "https://www.rtve.es/play/cine/index_apps.json")
    private val cocina = ExploreCategory("RTVE COCINA", null, "https://api.rtve.es/api/lives/broadcasts/1.json", isLive = true)

    @Test
    fun `the menu keeps only the kids block`() = runTest {
        general.explore = {
            loaded(
                listOf(
                    ExploreGroup(null, listOf(cine)),
                    ExploreGroup("Canales temáticos", listOf(cocina)),
                    ExploreGroup(null, listOf(junior), isKids = true),
                ),
            )
        }

        val groups = clan.loadExplore().value

        assertEquals(listOf(junior), groups.flatMap { it.categories })
        assertTrue(groups.all { it.isKids })
    }

    @Test
    fun `a kids block never offers a live channel`() = runTest {
        general.explore = { loaded(listOf(ExploreGroup(null, listOf(junior, cocina), isKids = true))) }

        assertEquals(listOf(junior), clan.loadExplore().value.single().categories)
    }

    @Test
    fun `without a kids block there are no age ranges, not the general menu`() = runTest {
        general.explore = { loaded(listOf(ExploreGroup(null, listOf(cine)))) }

        assertTrue(clan.loadExplore().value.isEmpty())
    }

    @Test
    fun `recommendations from the general catalogue are never shown`() = runTest {
        general.related = { _, _ -> loaded(listOf(catalogItem("adulto"))) }

        assertTrue(clan.loadRelated(RelatedKind.PROGRAM, "1").value.isEmpty())
        assertTrue(general.calls.isEmpty())
    }

    @Test
    fun `text search goes through, the general quick searches do not`() = runTest {
        general.search = { query -> es.openrtve.domain.SearchResults(listOf(catalogItem(query)), emptyList()) }
        general.quickFilters = { error("no deben consultarse las búsquedas predefinidas") }

        assertEquals(listOf("corona"), clan.search("corona").programs.map { it.id })
        assertTrue(clan.loadQuickFilters().value.isEmpty())
        assertEquals(listOf("search(corona)"), general.calls)
    }

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
    fun `portadas and details pass through untouched`() = runTest {
        general.nextVideo = { catalogItem("siguiente") }

        assertEquals("siguiente", clan.loadNextVideo("1")?.id)
        assertEquals(listOf("nextVideo(1)"), general.calls)
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
