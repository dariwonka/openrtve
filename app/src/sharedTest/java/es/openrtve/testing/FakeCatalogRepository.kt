package es.openrtve.testing

import es.openrtve.data.CatalogRepository
import es.openrtve.domain.CatalogItem
import es.openrtve.domain.CatalogLoad
import es.openrtve.domain.CatalogModule
import es.openrtve.domain.CatalogPage
import es.openrtve.domain.EpisodeOrder
import es.openrtve.domain.ContentKind
import es.openrtve.domain.ExploreGroup
import es.openrtve.domain.HomeFeed
import es.openrtve.domain.HomeRow
import es.openrtve.domain.PreviewSprite
import es.openrtve.domain.ProgramDetail
import es.openrtve.domain.QuickFilter
import es.openrtve.domain.RelatedKind
import es.openrtve.domain.SearchResults
import es.openrtve.domain.VideoDetail

/**
 * Repositorio de pruebas: cada método delega en una lambda sustituible y
 * registra las llamadas. Lo que no se configura falla con
 * [UnsupportedOperationException] para que un test no dependa de una fuente
 * que no ha declarado.
 */
open class FakeCatalogRepository : CatalogRepository {
    val calls = mutableListOf<String>()

    var portada: suspend (url: String, forceRefresh: Boolean, cachedOnly: Boolean) -> CatalogLoad<HomeFeed> = { _, _, _ -> unsupported() }
    var module: suspend (row: HomeRow, forceRefresh: Boolean, cachedOnly: Boolean) -> CatalogLoad<CatalogModule> = { _, _, _ -> unsupported() }
    var explore: suspend (forceRefresh: Boolean) -> CatalogLoad<List<ExploreGroup>> = { unsupported() }
    var quickFilters: suspend () -> CatalogLoad<List<QuickFilter>> = { unsupported() }
    var quickFilterItems: suspend (filter: QuickFilter) -> CatalogLoad<CatalogModule> = { unsupported() }
    var search: suspend (query: String) -> SearchResults = { unsupported() }
    var searchPage: suspend (relativeQuery: String, page: Int) -> CatalogPage = { _, _ -> unsupported() }
    /** Sin configurar no hay similares: es contenido secundario y no debe romper otros tests. */
    var related: suspend (kind: RelatedKind, id: String) -> CatalogLoad<List<CatalogItem>> = { _, _ -> CatalogLoad(emptyList(), false) }
    var program: suspend (programId: String, forceRefresh: Boolean) -> CatalogLoad<ProgramDetail> = { _, _ -> unsupported() }
    var video: suspend (videoId: String, forceRefresh: Boolean) -> CatalogLoad<VideoDetail> = { _, _ -> unsupported() }
    var nextVideo: suspend (videoId: String) -> CatalogItem? = { null }
    /** Sin configurar, la cadena no tiene listado A-Z. */
    var channelPrograms: suspend (channelId: String, cachedOnly: Boolean) -> CatalogLoad<List<CatalogItem>> = { _, _ -> CatalogLoad(emptyList(), false) }
    var previewSprite: suspend (videoId: String) -> PreviewSprite? = { null }
    var programVideos: suspend (programId: String, seasonId: String?, page: Int, completeOnly: Boolean, forceRefresh: Boolean) -> CatalogLoad<CatalogPage> =
        { _, _, _, _, _ -> unsupported() }
    var programExtras: suspend (programId: String, page: Int, forceRefresh: Boolean) -> CatalogLoad<CatalogPage> =
        { _, page, _ -> CatalogLoad(CatalogPage(emptyList(), page, page), false) }

    override suspend fun loadPortada(url: String, forceRefresh: Boolean, cachedOnly: Boolean): CatalogLoad<HomeFeed> {
        calls += "portada($url, force=$forceRefresh, cached=$cachedOnly)"
        return portada(url, forceRefresh, cachedOnly)
    }

    override suspend fun loadModule(row: HomeRow, forceRefresh: Boolean, cachedOnly: Boolean): CatalogLoad<CatalogModule> {
        calls += "module(${row.contentUrl}, force=$forceRefresh, cached=$cachedOnly)"
        return module(row, forceRefresh, cachedOnly)
    }

    override suspend fun loadExplore(forceRefresh: Boolean, tv: Boolean): CatalogLoad<List<ExploreGroup>> {
        calls += "explore(force=$forceRefresh)"
        return explore(forceRefresh)
    }

    override suspend fun loadChannelPrograms(channelId: String, cachedOnly: Boolean): CatalogLoad<List<CatalogItem>> {
        calls += "channelPrograms($channelId, cached=$cachedOnly)"
        return channelPrograms(channelId, cachedOnly)
    }

    override suspend fun loadQuickFilters(): CatalogLoad<List<QuickFilter>> {
        calls += "quickFilters()"
        return quickFilters()
    }

    override suspend fun loadQuickFilterItems(filter: QuickFilter): CatalogLoad<CatalogModule> {
        calls += "quickFilterItems(${filter.title})"
        return quickFilterItems(filter)
    }

    override suspend fun search(query: String): SearchResults {
        calls += "search($query)"
        return search.invoke(query)
    }

    override suspend fun searchPage(relativeQuery: String, page: Int): CatalogPage {
        calls += "searchPage($relativeQuery, page=$page)"
        return searchPage.invoke(relativeQuery, page)
    }

    override suspend fun loadRelated(kind: RelatedKind, id: String): CatalogLoad<List<CatalogItem>> = related(kind, id)

    override suspend fun loadProgram(programId: String, forceRefresh: Boolean): CatalogLoad<ProgramDetail> {
        calls += "program($programId, force=$forceRefresh)"
        return program(programId, forceRefresh)
    }

    override suspend fun loadVideo(videoId: String, forceRefresh: Boolean): CatalogLoad<VideoDetail> {
        calls += "video($videoId, force=$forceRefresh)"
        return video(videoId, forceRefresh)
    }

    override suspend fun loadNextVideo(videoId: String): CatalogItem? {
        calls += "nextVideo($videoId)"
        return nextVideo(videoId)
    }

    override suspend fun loadPreviewSprite(videoId: String): PreviewSprite? {
        calls += "previewSprite($videoId)"
        return previewSprite(videoId)
    }

    override suspend fun loadProgramVideos(
        programId: String,
        seasonId: String?,
        page: Int,
        completeOnly: Boolean,
        order: EpisodeOrder,
        forceRefresh: Boolean,
    ): CatalogLoad<CatalogPage> {
        calls += "programVideos($programId, season=$seasonId, page=$page, complete=$completeOnly, order=${order.name})"
        return programVideos(programId, seasonId, page, completeOnly, forceRefresh)
    }

    override suspend fun loadProgramExtras(
        programId: String,
        page: Int,
        order: EpisodeOrder,
        forceRefresh: Boolean,
    ): CatalogLoad<CatalogPage> {
        calls += "programExtras($programId, page=$page, order=${order.name})"
        return programExtras(programId, page, forceRefresh)
    }

    private fun unsupported(): Nothing = throw UnsupportedOperationException("No configurado en el test")
}

/** Item mínimo válido; los tests sobreescriben lo que les importa con `copy`. */
fun catalogItem(
    id: String,
    kind: ContentKind = ContentKind.VIDEO,
    title: String = "Título $id",
    programId: String? = null,
    playbackId: String? = if (kind == ContentKind.PROGRAM) null else id,
): CatalogItem = CatalogItem(
    id = id,
    playbackId = playbackId,
    assetId = null,
    title = title,
    subtitle = null,
    imageUrl = null,
    kind = kind,
    allowedInCountry = null,
    loginRequired = false,
    paid = false,
    drm = false,
    programId = programId,
)

fun page(items: List<CatalogItem>, page: Int = 1, totalPages: Int = 1, isStale: Boolean = false) =
    CatalogLoad(CatalogPage(items, page, totalPages), isStale)

fun <T> loaded(value: T, isStale: Boolean = false) = CatalogLoad(value, isStale)
