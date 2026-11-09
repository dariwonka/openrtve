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
    fun `the general search is closed`() = runTest {
        general.search = { error("no debe consultarse el buscador general") }
        general.searchPage = { _, _ -> error("no debe consultarse el buscador general") }
        general.quickFilters = { error("no debe consultarse el buscador general") }

        assertTrue(clan.search("telediario").isEmpty)
        assertTrue(clan.searchPage("/videos?search=telediario", 2).items.isEmpty())
        assertTrue(clan.loadQuickFilters().value.isEmpty())
        assertTrue(general.calls.isEmpty())
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
