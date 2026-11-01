package es.openrtve.data

import es.openrtve.domain.CatalogItem
import es.openrtve.domain.CatalogLoad
import es.openrtve.domain.CatalogModule
import es.openrtve.domain.CatalogPage
import es.openrtve.domain.EpisodeOrder
import es.openrtve.domain.ExploreGroup
import es.openrtve.domain.HomeFeed
import es.openrtve.domain.HomeRow
import es.openrtve.domain.PreviewSprite
import es.openrtve.domain.ProgramDetail
import es.openrtve.domain.QuickFilter
import es.openrtve.domain.RelatedKind
import es.openrtve.domain.RtveTypes
import es.openrtve.domain.RtveUrls
import es.openrtve.domain.SearchResults
import es.openrtve.domain.VideoDetail
import java.io.IOException
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/** Ausencia de copia local cuando se pidió solo caché. */
class NotCachedException : NoSuchElementException("Sin copia local")

interface CatalogRepository {
    /** Con [cachedOnly], devuelve la copia local de cualquier antigüedad sin tocar la red, o lanza [NotCachedException]. */
    suspend fun loadPortada(url: String, forceRefresh: Boolean = false, cachedOnly: Boolean = false): CatalogLoad<HomeFeed>
    suspend fun loadModule(row: HomeRow, forceRefresh: Boolean = false, cachedOnly: Boolean = false): CatalogLoad<CatalogModule>
    /** Menú de Explorar de la configuración de móvil o, con [tv], de la de Android TV. */
    suspend fun loadExplore(forceRefresh: Boolean = false, tv: Boolean = false): CatalogLoad<List<ExploreGroup>>
    suspend fun loadQuickFilters(): CatalogLoad<List<QuickFilter>>
    suspend fun loadQuickFilterItems(filter: QuickFilter): CatalogLoad<CatalogModule>
    suspend fun search(query: String): SearchResults

    /** Página [page] de un bloque de búsqueda, a partir de su `query` relativo (ver [SearchResults.videosQuery]). */
    suspend fun searchPage(relativeQuery: String, page: Int): CatalogPage

    /** "Similares" según RTVE (`recomsys`), sin el propio item ni contenido de pago. */
    suspend fun loadRelated(kind: RelatedKind, id: String): CatalogLoad<List<CatalogItem>>
    suspend fun loadProgram(programId: String, forceRefresh: Boolean = false): CatalogLoad<ProgramDetail>
    suspend fun loadVideo(videoId: String, forceRefresh: Boolean = false): CatalogLoad<VideoDetail>

    /** Siguiente episodio según RTVE (`videos/{id}/next.json`), o `null` si no hay. */
    suspend fun loadNextVideo(videoId: String): CatalogItem?

    /** Miniaturas para la barra de progreso, o `null` si el vídeo no las tiene. */
    suspend fun loadPreviewSprite(videoId: String): PreviewSprite?

    /**
     * Episodios de un programa o de una temporada. `completeOnly` pide solo
     * contenidos completos (`type=39816`); sin él llegan también los fragmentos.
     * [order] es el de RTVE Play para ese programa (ver `ProgramDetail.episodeOrder`).
     */
    suspend fun loadProgramVideos(
        programId: String,
        seasonId: String? = null,
        page: Int = 1,
        completeOnly: Boolean = true,
        order: EpisodeOrder = EpisodeOrder.NEWEST_FIRST,
        forceRefresh: Boolean = false,
    ): CatalogLoad<CatalogPage>

    /** Fragmentos y avances/tráilers de un programa, separados de sus episodios completos. */
    suspend fun loadProgramExtras(
        programId: String,
        page: Int = 1,
        order: EpisodeOrder = EpisodeOrder.NEWEST_FIRST,
        forceRefresh: Boolean = false,
    ): CatalogLoad<CatalogPage>
}

class DefaultCatalogRepository(
    private val httpClient: TextHttpClient,
    private val parser: RtveJsonParser,
    private val cache: RawDocumentCache,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : CatalogRepository {
    override suspend fun loadPortada(
        url: String,
        forceRefresh: Boolean,
        cachedOnly: Boolean,
    ): CatalogLoad<HomeFeed> = loadDocument(
        url = url,
        forceRefresh = forceRefresh,
        freshForMillis = HOME_FRESH_MS,
        staleForMillis = HOME_STALE_MS,
        cachedOnly = cachedOnly,
        parse = parser::parseHome,
    )

    override suspend fun loadExplore(forceRefresh: Boolean, tv: Boolean): CatalogLoad<List<ExploreGroup>> {
        val (primary, secondary) = if (tv) RtveUrls.REMOTE_CONFIG_TV to RtveUrls.REMOTE_CONFIG else RtveUrls.REMOTE_CONFIG to RtveUrls.REMOTE_CONFIG_TV
        val groups = loadDocument(
            url = primary,
            forceRefresh = forceRefresh,
            freshForMillis = CONFIG_FRESH_MS,
            staleForMillis = CONFIG_STALE_MS,
            parse = parser::parseExplore,
        )
        // Imágenes de reserva de la otra configuración; si no llega, el menú sigue igual.
        val fallbacks = try {
            loadDocument(
                url = secondary,
                forceRefresh = false,
                freshForMillis = CONFIG_FRESH_MS,
                staleForMillis = CONFIG_STALE_MS,
                parse = parser::parseExploreImages,
            ).value
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyMap()
        }
        return groups.copy(
            value = groups.value.map { group ->
                group.copy(
                    categories = group.categories.map { category ->
                        category.copy(fallbackImageUrl = fallbacks[category.contentUrl]?.takeIf { it != category.imageUrl })
                    },
                )
            },
        )
    }

    override suspend fun loadQuickFilters(): CatalogLoad<List<QuickFilter>> = loadDocument(
        url = RtveUrls.QUICK_SEARCHES,
        forceRefresh = false,
        freshForMillis = CONFIG_FRESH_MS,
        staleForMillis = CONFIG_STALE_MS,
        parse = parser::parseQuickFilters,
    )

    override suspend fun loadQuickFilterItems(filter: QuickFilter): CatalogLoad<CatalogModule> = loadDocument(
        url = filter.contentUrl,
        forceRefresh = false,
        freshForMillis = MODULE_FRESH_MS,
        staleForMillis = MODULE_STALE_MS,
        parse = { parser.parseModule(it, filter.title) },
    )

    // Las búsquedas no se persisten: solo interesa el resultado del momento.
    override suspend fun search(query: String): SearchResults {
        val url = "${RtveUrls.SEARCH}?search=${encode(query)}&context=tve&tipology=video&type=completo"
        return fetchParsed(url, parser::parseSearch)
    }

    override suspend fun searchPage(relativeQuery: String, page: Int): CatalogPage =
        fetchParsed("${RtveUrls.SEARCH_PAGES}$relativeQuery&page=$page", parser::parseVideoPage)

    override suspend fun loadRelated(kind: RelatedKind, id: String): CatalogLoad<List<CatalogItem>> = loadDocument(
        url = "${RtveUrls.RECOMMENDATIONS}/${kind.path}?source=apps&item=/${kind.itemPrefix}/${encode(id)}",
        forceRefresh = false,
        freshForMillis = PROGRAM_FRESH_MS,
        staleForMillis = PROGRAM_STALE_MS,
        parse = { raw ->
            parser.parseVideoPage(raw).items
                .filter { it.id != id && !it.paid }
                .take(MAX_RELATED)
        },
    )

    override suspend fun loadModule(
        row: HomeRow,
        forceRefresh: Boolean,
        cachedOnly: Boolean,
    ): CatalogLoad<CatalogModule> {
        val url = requireNotNull(row.contentUrl) { "Este módulo no tiene una fuente remota" }
        return loadDocument(
            url = url,
            forceRefresh = forceRefresh,
            freshForMillis = MODULE_FRESH_MS,
            staleForMillis = MODULE_STALE_MS,
            cachedOnly = cachedOnly,
            parse = { parser.parseModule(it, row.title) },
        )
    }

    override suspend fun loadProgram(
        programId: String,
        forceRefresh: Boolean,
    ): CatalogLoad<ProgramDetail> = loadDocument(
        url = "$PROGRAMS_BASE/${encode(programId)}.json",
        forceRefresh = forceRefresh,
        freshForMillis = PROGRAM_FRESH_MS,
        staleForMillis = PROGRAM_STALE_MS,
        parse = parser::parseProgram,
    )

    override suspend fun loadVideo(
        videoId: String,
        forceRefresh: Boolean,
    ): CatalogLoad<VideoDetail> = loadDocument(
        url = "$VIDEOS_BASE/${encode(videoId)}.json",
        forceRefresh = forceRefresh,
        freshForMillis = PROGRAM_FRESH_MS,
        staleForMillis = PROGRAM_STALE_MS,
        parse = parser::parseVideo,
    )

    override suspend fun loadNextVideo(videoId: String): CatalogItem? =
        fetchParsed("$VIDEOS_BASE/${encode(videoId)}/next.json") { parser.parseVideoPage(it).items.firstOrNull() }

    override suspend fun loadPreviewSprite(videoId: String): PreviewSprite? {
        val (spriteUrl, vttUrl) = fetchParsed("$PREVIEWS_BASE/sprite?idasset=${encode(videoId)}", parser::parseSpriteInfo)
            ?: return null
        return fetchParsed(vttUrl) { parser.parseSpriteVtt(it, spriteUrl) }
    }

    override suspend fun loadProgramVideos(
        programId: String,
        seasonId: String?,
        page: Int,
        completeOnly: Boolean,
        order: EpisodeOrder,
        forceRefresh: Boolean,
    ): CatalogLoad<CatalogPage> {
        val path = buildString {
            append(PROGRAMS_BASE).append('/').append(encode(programId))
            if (seasonId != null) append("/temporadas/").append(encode(seasonId))
            append("/videos.json?page=").append(page)
            if (completeOnly) append("&type=").append(RtveTypes.COMPLETE)
            append("&order=").append(encode(order.apiValue))
        }
        return loadDocument(
            url = path,
            forceRefresh = forceRefresh,
            freshForMillis = PROGRAM_FRESH_MS,
            staleForMillis = PROGRAM_STALE_MS,
            parse = parser::parseVideoPage,
        )
    }

    override suspend fun loadProgramExtras(
        programId: String,
        page: Int,
        order: EpisodeOrder,
        forceRefresh: Boolean,
    ): CatalogLoad<CatalogPage> = loadDocument(
        url = "$PROGRAMS_BASE/${encode(programId)}/videos.json?page=$page&type=${RtveTypes.FRAGMENT},${RtveTypes.PREVIEW}&order=${encode(order.apiValue)}",
        forceRefresh = forceRefresh,
        freshForMillis = PROGRAM_FRESH_MS,
        staleForMillis = PROGRAM_STALE_MS,
        parse = parser::parseVideoPage,
    )

    private fun encode(segment: String): String = URLEncoder.encode(segment, "UTF-8")

    /** Descarga en IO y parseo en Default: ningún endpoint devuelve el trabajo de JSON/VTT a Main. */
    private suspend fun <T> fetchParsed(url: String, parse: (String) -> T): T =
        withContext(Dispatchers.Default) { parse(fetchRaw(url)) }

    /**
     * Petición bloqueante de OkHttp en IO e interrumpible: al cancelar la corrutina
     * (un refresh, salir de la pantalla) la conexión se corta en vez de seguir
     * descargando y ocupando un hueco del pool.
     */
    private suspend fun fetchRaw(url: String): String = runInterruptible(Dispatchers.IO) { httpClient.get(url) }

    private suspend fun fetchRaw(url: String, etag: String?): TextResponse = runInterruptible(Dispatchers.IO) { httpClient.fetch(url, etag) }

    private suspend fun <T> loadDocument(
        url: String,
        forceRefresh: Boolean,
        freshForMillis: Long,
        staleForMillis: Long,
        parse: (String) -> T,
        cachedOnly: Boolean = false,
    ): CatalogLoad<T> = withContext(Dispatchers.IO) {
        val now = nowMillis()
        val cached = cache.read(url)
        val age = cached?.let { now - it.savedAtMillis } ?: Long.MAX_VALUE
        // Solo se parsea la copia local cuando hace falta: está fresca o la red falla.
        val cachedValue: T? by lazy { cached?.let { runCatching { parse(it.raw) }.getOrNull() } }

        if (cachedOnly) {
            return@withContext cachedValue?.let { CatalogLoad(it, isStale = age > freshForMillis) } ?: throw NotCachedException()
        }
        if (!forceRefresh && age <= freshForMillis) {
            cachedValue?.let { return@withContext CatalogLoad(it, isStale = false) }
        }

        try {
            // Con copia local se revalida por ETag: un 304 no descarga nada.
            val response = fetchRaw(url, cached?.etag)
            if (response.notModified && cached != null) {
                cache.touch(url, now)
                CatalogLoad(cachedValue ?: parse(cached.raw), isStale = false)
            } else {
                val raw = response.body ?: throw IOException("Respuesta vacía")
                val value = parse(raw)
                try {
                    cache.write(url, raw, now, response.etag)
                } catch (_: IOException) {
                    // La persistencia es opcional: una descarga válida se muestra aunque no haya espacio.
                }
                CatalogLoad(value, isStale = false)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (networkError: Exception) {
            val fallback = if (age <= staleForMillis) cachedValue else null
            fallback?.let { CatalogLoad(it, isStale = true) } ?: throw networkError
        }
    }

    private companion object {
        const val HOME_FRESH_MS = 2 * 60 * 1_000L
        const val HOME_STALE_MS = 24 * 60 * 60 * 1_000L
        const val MODULE_FRESH_MS = 5 * 60 * 1_000L
        const val MODULE_STALE_MS = 24 * 60 * 60 * 1_000L
        const val PROGRAM_FRESH_MS = 15 * 60 * 1_000L
        const val PROGRAM_STALE_MS = 7 * 24 * 60 * 60 * 1_000L
        const val CONFIG_FRESH_MS = 60 * 60 * 1_000L
        const val CONFIG_STALE_MS = 7 * 24 * 60 * 60 * 1_000L
        const val PROGRAMS_BASE = "https://www.rtve.es/api/programas"
        const val VIDEOS_BASE = "https://api.rtve.es/api/videos"
        const val PREVIEWS_BASE = "https://videopreviews.rtve.es/tiivii-previews/api"
        const val MAX_RELATED = 24
    }
}
