package es.openrtve

import android.content.Context
import es.openrtve.data.AppSettings
import es.openrtve.data.CatalogBackup
import es.openrtve.data.CatalogRepository
import es.openrtve.data.ClanCatalogRepository
import es.openrtve.data.ClanCatalogStore
import es.openrtve.data.DefaultCatalogRepository
import es.openrtve.data.DrmTokenClient
import es.openrtve.data.RawDocumentCache
import es.openrtve.data.RtveJsonParser
import es.openrtve.data.SafeHttpsClient
import es.openrtve.data.WatchHistory
import es.openrtve.domain.PlaybackResolver
import es.openrtve.domain.RtveHostPolicy
import okhttp3.OkHttpClient

class AppContainer(private val context: Context) {
    private val hostPolicy = RtveHostPolicy()
    /** Único cliente de red de la app: lo comparten catálogo, licencias DRM e imágenes. */
    val okHttpClient: OkHttpClient = SafeHttpsClient.buildOkHttpClient(hostPolicy)
    /**
     * Streams y licencias DRM: comparten conexiones, dispatcher y User-Agent con el
     * catálogo, pero sin la allowlist, porque `ztnr` redirige a un CDN y la licencia
     * Widevine la sirve Axinom, fuera de rtve.es. Solo HTTPS: lo impone
     * `network_security_config`.
     */
    val mediaHttpClient: OkHttpClient = okHttpClient.newBuilder().apply { networkInterceptors().clear() }.build()
    private val httpClient = SafeHttpsClient(okHttpClient, hostPolicy)
    val documentCache = RawDocumentCache(context.cacheDir.resolve("catalog"))
    /** Todo el catálogo pasa por el filtro infantil: la UI no tiene otra entrada. */
    val catalogRepository: CatalogRepository = ClanCatalogRepository(
        DefaultCatalogRepository(
            httpClient = httpClient,
            parser = RtveJsonParser(),
            cache = documentCache,
            childProfile = true,
        ),
    )
    val settings = AppSettings(context)
    /** Copia del catálogo del inicio: con ella la app abre sin esperar a la red. */
    /** Programas con género Infantil en RTVE (recogidos de su catálogo), que se suman a Clan. */
    val infantilSeed: List<es.openrtve.domain.CatalogItem> by lazy { loadSeed(context, "infantil.json", es.openrtve.domain.ContentKind.PROGRAM) }
    val movieSeed: List<es.openrtve.domain.CatalogItem> by lazy { loadSeed(context, "peliculas.json", es.openrtve.domain.ContentKind.VIDEO) }
    /** El PIN de borrado se pide una vez por sesión de la app. */
    @Volatile var removalUnlocked: Boolean = false
    val clanCatalogStore = ClanCatalogStore(context.filesDir.resolve("clan-catalog.json"))
    val watchHistory = WatchHistory(context.filesDir.resolve("watch-history.json"))
    val backup = CatalogBackup(context.filesDir.resolve("clan-catalog.json"), context.filesDir.resolve("watch-history.json"))
    val playbackResolver = PlaybackResolver()
    val drmTokenClient = DrmTokenClient(httpClient)
}

private fun loadSeed(context: Context, asset: String, kind: es.openrtve.domain.ContentKind): List<es.openrtve.domain.CatalogItem> = try {
    val raw = context.assets.open(asset).bufferedReader().use { it.readText() }
    kotlinx.serialization.json.Json.parseToJsonElement(raw).let { it as kotlinx.serialization.json.JsonArray }.mapNotNull { element ->
        val obj = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
        fun field(name: String) = (obj[name] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
        val id = field("i") ?: return@mapNotNull null
        val title = field("t") ?: return@mapNotNull null
        es.openrtve.domain.referenceItem(id, kind, title, field("p"))
            .copy(posterUrl = field("q"))
    }
} catch (error: Exception) {
    emptyList()
}
