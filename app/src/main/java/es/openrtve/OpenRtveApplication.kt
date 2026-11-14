package es.openrtve

import android.app.Application
import android.os.Build
import android.os.storage.StorageManager
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import okio.Path.Companion.toOkioPath
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import es.openrtve.data.RawDocumentCache
import es.openrtve.data.RtveImageSizeInterceptor

class OpenRtveApplication : Application(), SingletonImageLoader.Factory {
    val container: AppContainer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppContainer(applicationContext)
    }

    // Comparte el cliente OkHttp (conexión HTTP/2, allowlist), ajusta el tamaño pedido y fija una caché de disco propia.
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                // Cada imagen del servicio de RTVE se pide al tamaño en que se pinta.
                add(RtveImageSizeInterceptor())
                // Las carátulas de SVT Barn salen de svtstatic.se, fuera de la allowlist de rtve.es.
                add(OkHttpNetworkFetcherFactory(callFactory = {
                    okhttp3.Call.Factory { request ->
                        val host = request.url.host
                        val media = host == "svtstatic.se" || host.endsWith(".svtstatic.se") || BOING_HOSTS.any { host == it || host.endsWith(".$it") }
                        val client = if (media) container.mediaHttpClient else container.okHttpClient
                        client.newCall(request)
                    }
                }))
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("images").toOkioPath())
                    .maxSizeBytes(imageCacheBytes())
                    .build()
            }
            .crossfade(true)
            .build()

    /**
     * Tope de la caché de imágenes, el que recomienda Android: la cuota de caché de la
     * app (`getCacheQuotaBytes`). El sistema reserva para cachés una parte del almacenamiento
     * (el 15 % del espacio libre hasta Android 12) y la reparte según el tiempo de uso de
     * cada app; por debajo de su cuota, los ficheros de una app son los últimos que borra.
     * Así, más almacenamiento y más uso dan más caché. Se descuenta la de documentos y se
     * acota entre [MIN_IMAGE_CACHE_BYTES] y [MAX_IMAGE_CACHE_BYTES], el techo por defecto de
     * Coil. Coil lo crea al primer uso, fuera del hilo principal.
     */
    private fun imageCacheBytes(): Long {
        val quota = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                val storage = getSystemService(StorageManager::class.java)
                storage.getCacheQuotaBytes(storage.getUuidForPath(cacheDir))
            }.getOrNull()
        } else {
            null
        }
        return ((quota ?: Long.MAX_VALUE) - RawDocumentCache.DEFAULT_MAX_BYTES).coerceIn(MIN_IMAGE_CACHE_BYTES, MAX_IMAGE_CACHE_BYTES)
    }

    private companion object {
        /** Carátulas de Boing: Brightcove, Azure y Akamai. */
        val BOING_HOSTS = listOf("boltdns.net", "azurefd.net", "akamaized.net", "windows.net")
        /** Con imágenes al tamaño en que se pintan (~30-60 KB), varios miles: más ya no se nota. */
        const val MAX_IMAGE_CACHE_BYTES = 250L * 1024 * 1024
        /** Una app recién instalada recibe una cuota pequeña: lo justo para la portada y las fichas recientes. */
        const val MIN_IMAGE_CACHE_BYTES = 32L * 1024 * 1024
    }
}
