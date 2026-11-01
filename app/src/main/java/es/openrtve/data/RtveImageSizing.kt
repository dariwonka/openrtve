package es.openrtve.data

import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.size.pxOrElse
import java.net.URLEncoder

/**
 * El servicio de imágenes de RTVE (`img.rtve.es/p/...` y `/v/...`) escala con `w`
 * y, si además llega `h`, recorta al centro a ese tamaño exacto (verificado el
 * 31-10-2026). Así cada tarjeta pide los píxeles que pinta: ni una miniatura de
 * 960 px en una tarjeta pequeña ni un hero ampliado y borroso.
 *
 * Los originales de `/imagenes/` (cabeceras de las fichas, accesos de la portada)
 * y de `/css/` (las categorías de Explorar) no admiten `w`: llegan a 1280-1600 px y
 * 0,2-1,5 MB. Se piden al redimensionador del mismo host, `img.rtve.es/i/?w=&h=&i=`,
 * con las mismas reglas; las URLs que ya son suyas solo cambian de tamaño
 * (verificado el 01-11-2026: 1,5 MB pasan a 29 KB a 480 px).
 *
 * El ancho se redondea a escalones de [STEP_PX] para que tarjetas parecidas
 * compartan caché; el alto se escala en la misma proporción.
 */
object RtveImageSizing {
    private const val HOST = "https://img.rtve.es/"
    private const val STEP_PX = 160
    /** El servicio no entrega más de ~1900 px de ancho. */
    private const val MAX_WIDTH_PX = 1920

    /** URL pedida al tamaño [widthPx] x [heightPx], o `null` si no es del servicio redimensionable. */
    fun sized(url: String, widthPx: Int, heightPx: Int): String? {
        if (!url.startsWith(HOST) || widthPx <= 0) return null
        val path = url.removePrefix(HOST).substringBefore('?')
        val width = ((widthPx + STEP_PX - 1) / STEP_PX * STEP_PX).coerceAtMost(MAX_WIDTH_PX)
        val height = heightPx.takeIf { it > 0 }?.let { (it.toLong() * width / widthPx).toInt().coerceAtLeast(1) }
        val size = listOfNotNull("w=$width", height?.let { "h=$it" })
        return when {
            path.startsWith("p/") || path.startsWith("v/") || path.startsWith("i/") -> {
                val kept = url.substringAfter('?', "").split('&').filter { it.isNotEmpty() && !it.startsWith("w=") && !it.startsWith("h=") }
                HOST + path + "?" + (kept + size).joinToString("&")
            }
            path.startsWith("imagenes/") || path.startsWith("css/") ->
                HOST + "i/?" + (size + "i=${URLEncoder.encode(HOST + path, "UTF-8")}").joinToString("&")
            else -> null
        }
    }
}

/**
 * Aplica [RtveImageSizing] con el tamaño que Coil ha resuelto para cada petición. Si
 * la versión redimensionada fallase, se pide el original: el tamaño es una mejora,
 * nunca el motivo de una tarjeta vacía.
 */
class RtveImageSizeInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val url = chain.request.data as? String ?: return chain.proceed()
        val sized = RtveImageSizing.sized(
            url = url,
            widthPx = chain.size.width.pxOrElse { -1 },
            heightPx = chain.size.height.pxOrElse { -1 },
        ) ?: return chain.proceed()
        val result = chain.withRequest(chain.request.newBuilder().data(sized).build()).proceed()
        return if (result is ErrorResult) chain.proceed() else result
    }
}
