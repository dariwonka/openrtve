package es.openrtve.data

import es.openrtve.domain.CatalogItem
import es.openrtve.domain.CatalogLoad
import es.openrtve.domain.CatalogModule
import es.openrtve.domain.CatalogPage
import es.openrtve.domain.EpisodeOrder
import es.openrtve.domain.HomeFeed
import es.openrtve.domain.HomeRow
import es.openrtve.domain.ProgramDetail
import es.openrtve.domain.RtveTypes
import es.openrtve.domain.RtveUrls
import es.openrtve.domain.SearchResults
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
    suspend fun search(query: String): SearchResults

    /** Página [page] de un bloque de búsqueda, a partir de su `query` relativo (ver [SearchResults.videosQuery]). */
    suspend fun searchPage(relativeQuery: String, page: Int): CatalogPage

    /**
     * Todos los programas de una cadena con contenido en RTVE Play, de la A a la Z
     * (el listado del buscador A-Z de la app oficial). Con [cachedOnly] no toca la red.
     */
    suspend fun loadChannelPrograms(channelId: String, cachedOnly: Boolean = false): CatalogLoad<List<CatalogItem>>

    suspend fun loadProgram(programId: String, forceRefresh: Boolean = false): CatalogLoad<ProgramDetail>

    /** Siguiente episodio según RTVE (`videos/{id}/next.json`), o `null` si no hay. */
    suspend fun loadNextVideo(videoId: String): CatalogItem?

    /** Miniaturas para la barra de progreso, o `null` si el vídeo no las tiene. */

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
    /**
     * Perfil infantil del buscador (`isChild=true`), el que usa la app oficial de Clan:
     * RTVE devuelve solo contenido infantil, de Clan o del resto del catálogo.
     */
    private val childProfile: Boolean = false,
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

    // Las búsquedas no se persisten: solo interesa el resultado del momento.
    override suspend fun search(query: String): SearchResults {
        val url = "${RtveUrls.SEARCH}?search=${encode(query)}&context=tve&tipology=video&type=completo" +
            if (childProfile) "&isChild=true" else ""
        return fetchParsed(url, parser::parseSearch)
    }

    override suspend fun searchPage(relativeQuery: String, page: Int): CatalogPage =
        fetchParsed("${RtveUrls.SEARCH_PAGES}$relativeQuery&page=$page", parser::parseVideoPage)

    override suspend fun loadChannelPrograms(channelId: String, cachedOnly: Boolean): CatalogLoad<List<CatalogItem>> {
        val programs = mutableListOf<CatalogItem>()
        var stale = false
        var page = 1
        do {
            val load = loadDocument(
                url = "$CHANNELS_BASE/${encode(channelId)}/programas.json?inazlist=true&play=true&size=$AZ_PAGE_SIZE&order=name,ASC&small=true&page=$page",
                forceRefresh = false,
                freshForMillis = CONFIG_FRESH_MS,
                staleForMillis = CONFIG_STALE_MS,
                cachedOnly = cachedOnly,
                parse = parser::parsePrograms,
            )
            programs += load.value.items
            stale = stale || load.isStale
            val more = load.value.hasMore && load.value.items.isNotEmpty()
            page++
        } while (more && page <= AZ_MAX_PAGES)
        return CatalogLoad(programs.distinctBy(CatalogItem::id), stale)
    }

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

    override suspend fun loadNextVideo(videoId: String): CatalogItem? =
        fetchParsed("$VIDEOS_BASE/${encode(videoId)}/next.json") { parser.parseVideoPage(it).items.firstOrNull() }

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
        const val CHANNELS_BASE = "https://api2.rtve.es/api/cadenas"
        const val AZ_PAGE_SIZE = 500
        const val AZ_MAX_PAGES = 6
    }
}
