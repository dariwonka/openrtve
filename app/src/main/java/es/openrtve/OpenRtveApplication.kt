package es.openrtve

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import okio.Path.Companion.toOkioPath
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
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
                add(OkHttpNetworkFetcherFactory(callFactory = { container.okHttpClient }))
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("images").toOkioPath())
                    .maxSizeBytes(IMAGE_DISK_CACHE_BYTES)
                    .build()
            }
            .crossfade(true)
            .build()

    private companion object {
        const val IMAGE_DISK_CACHE_BYTES = 256L * 1024 * 1024
    }
}
