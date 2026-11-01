package es.openrtve.data

import es.openrtve.domain.ContentKind
import es.openrtve.domain.ProgramDetail
import es.openrtve.domain.SearchResults
import es.openrtve.domain.VideoDetail
import es.openrtve.testing.FakeCatalogRepository
import es.openrtve.testing.catalogItem
import es.openrtve.testing.loaded
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepLinkResolverTest {
    private val repository = FakeCatalogRepository()
    private val resolver = DeepLinkResolver(repository)

    @Test
    fun `video ids fetch their sheet and audios are not ours`() = runBlocking {
        repository.video = { id, _ -> loaded(detail(catalogItem(id))) }

        assertEquals("17222216", resolver.resolve("https://play.rtve.es/v/17222216")!!.id)
        assertNull("los audios son de RTVE Audio", resolver.resolve("https://www.rtve.es/play/audios/el-buscon/17150412/"))
        assertEquals(listOf("video(17222216, force=false)"), repository.calls)
    }

    @Test
    fun `a program link becomes a program item from its sheet`() = runBlocking {
        repository.program = { id, _ ->
            loaded(ProgramDetail(id, "Telediario", null, "https://img.rtve.es/p/$id", null, emptyList()))
        }

        val item = resolver.resolve("https://play.rtve.es/pr/135930")!!

        assertEquals(ContentKind.PROGRAM, item.kind)
        assertEquals("135930", item.programId)
        assertEquals("Telediario", item.title)
        assertNull("un programa no se reproduce por su id", item.playbackId)
    }

    @Test
    fun `a live link reads the channel feed and keeps only the live item`() = runBlocking {
        repository.module = { row, _, _ ->
            assertEquals("https://api.rtve.es/api/lives/1688877.json", row.contentUrl)
            loaded(es.openrtve.domain.CatalogModule("", listOf(catalogItem("x", kind = ContentKind.VIDEO), catalogItem("1688877", kind = ContentKind.LIVE))))
        }

        assertEquals("1688877", resolver.resolve("https://play.rtve.es/d/1688877")!!.id)
    }

    @Test
    fun `program permalinks are searched and matched by web url`() = runBlocking {
        repository.search = { query ->
            assertEquals("telediario 2", query)
            SearchResults(
                programs = listOf(
                    catalogItem("1", kind = ContentKind.PROGRAM).copy(webUrl = "https://www.rtve.es/play/videos/telediario-21-horas/"),
                    catalogItem("2", kind = ContentKind.PROGRAM).copy(webUrl = "https://www.rtve.es/play/videos/telediario-2/"),
                ),
                videos = emptyList(),
            )
        }

        assertEquals("2", resolver.resolve("https://www.rtve.es/play/videos/telediario-2/")!!.id)
        assertNull("un audio no es un enlace de OpenRTVE", resolver.resolve("https://www.rtve.es/play/audios/telediario-2/"))
    }

    @Test
    fun `live permalinks are looked up in the lives feed and unknown links resolve to nothing`() = runBlocking {
        repository.module = { _, _, _ ->
            loaded(
                es.openrtve.domain.CatalogModule(
                    "",
                    listOf(
                        catalogItem("la1", kind = ContentKind.LIVE).copy(webUrl = "https://www.rtve.es/play/videos/directo/la-1/"),
                        catalogItem("la2", kind = ContentKind.LIVE).copy(webUrl = "https://www.rtve.es/play/videos/directo/la-2"),
                    ),
                ),
            )
        }

        assertEquals("la2", resolver.resolve("https://www.rtve.es/play/videos/directo/la-2/")!!.id)
        assertNull(resolver.resolve("https://www.rtve.es/play/videos/directo/canal-24h/"))
        assertNull(resolver.resolve("https://example.org/play/videos/directo/la-1/"))
    }

    private fun detail(item: es.openrtve.domain.CatalogItem) = VideoDetail(
        item = item, backdropUrl = null, description = null, programTitle = null,
        year = null, ageRating = null, genres = emptyList(), director = null, cast = emptyList(), originalLanguage = null,
        expirationDate = null, webUrl = null,
    )
}
