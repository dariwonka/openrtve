package es.openrtve

import android.content.Context
import es.openrtve.data.AppSettings
import es.openrtve.data.DeepLinkResolver
import es.openrtve.data.DefaultCatalogRepository
import es.openrtve.data.DrmTokenClient
import es.openrtve.data.RawDocumentCache
import es.openrtve.data.RtveJsonParser
import es.openrtve.data.SafeHttpsClient
import es.openrtve.data.WatchHistory
import es.openrtve.domain.PlaybackResolver
import es.openrtve.domain.RtveHostPolicy
import okhttp3.OkHttpClient

class AppContainer(context: Context) {
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
    val catalogRepository = DefaultCatalogRepository(
        httpClient = httpClient,
        parser = RtveJsonParser(),
        cache = documentCache,
    )
    val settings = AppSettings(context)
    val deepLinkResolver = DeepLinkResolver(catalogRepository)
    val watchHistory = WatchHistory(context.filesDir.resolve("watch-history.json"))
    val playbackResolver = PlaybackResolver()
    val drmTokenClient = DrmTokenClient(httpClient)
}
