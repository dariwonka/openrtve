package es.openrtve.svt

import es.openrtve.domain.CatalogItem
import es.openrtve.domain.ContentKind
import es.openrtve.domain.PlaybackDecision
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/** Un episodio de SVT Barn (en sueco). [videoId] es el id que entiende `api.svt.se/video`. */
data class SvtEpisode(
    val videoId: String,
    val title: String,
    val seconds: Int,
    val season: Int,
    val number: Int,
    val image: String,
)

data class SvtProgram(val id: String, val title: String, val image: String, val episodes: List<SvtEpisode>)

/**
 * Catálogo infantil de SVT Play (Suecia) recogido de su API pública: solo programas en sueco,
 * sin adolescentes y solo episodios que se ven desde fuera de Suecia y sin DRM.
 * Vive en `assets/svt_barn.json`; las miniaturas y el vídeo se piden a SVT al usarlos.
 */
object SvtBarn {
    fun parse(raw: String): List<SvtProgram> = runCatching {
        Json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val episodes = obj["e"]?.jsonArray.orEmpty().mapNotNull { e ->
                val a = (e as? JsonArray) ?: return@mapNotNull null
                SvtEpisode(
                    videoId = a[0].jsonPrimitive.content,
                    title = a[1].jsonPrimitive.content,
                    seconds = a[2].jsonPrimitive.intOrNull ?: 0,
                    season = a[3].jsonPrimitive.intOrNull ?: 0,
                    number = a[4].jsonPrimitive.intOrNull ?: 0,
                    image = a[5].jsonPrimitive.content,
                )
            }.sortedWith(compareBy({ it.season }, { it.number == 0 }, { it.number }))
            if (episodes.isEmpty()) return@mapNotNull null
            SvtProgram(
                id = obj["i"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                title = obj["t"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                image = obj["q"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                episodes = episodes,
            )
        }
    }.getOrDefault(emptyList())

    /** `id/changed` -> URL 16:9 de svtstatic.se. */
    fun imageUrl(ref: String, width: Int = 600): String? =
        ref.takeIf { it.isNotBlank() }?.let { "https://www.svtstatic.se/image/wide/$width/$it" }

    fun SvtProgram.asItem() = CatalogItem(
        id = "svt:$id", playbackId = null, assetId = null, title = title, subtitle = null,
        imageUrl = imageUrl(image), kind = ContentKind.PROGRAM, allowedInCountry = null,
        loginRequired = false, paid = false, drm = false,
    )

    fun SvtEpisode.asItem() = CatalogItem(
        id = "svt:$videoId", playbackId = null, assetId = null, title = title, subtitle = null,
        imageUrl = imageUrl(image), kind = ContentKind.VIDEO, allowedInCountry = null,
        loginRequired = false, paid = false, drm = false,
        durationMs = seconds * 1000L, episode = number.takeIf { it > 0 },
    )
}

/** Pide a SVT la dirección del vídeo en el momento de verlo (las URL caducan). */
class SvtStreamResolver(private val client: OkHttpClient) {
    /** Bloqueante: llamar fuera del hilo principal. `null` si no hay fuente reproducible. */
    fun resolve(videoId: String, title: String): PlaybackDecision.Ready? {
        val request = Request.Builder().url("https://api.svt.se/video/$videoId").build()
        val body = client.newCall(request).execute().use { if (it.isSuccessful) it.body?.string() else null } ?: return null
        return pick(body, title)
    }

    internal fun pick(body: String, title: String): PlaybackDecision.Ready? {
        val refs = runCatching { Json.parseToJsonElement(body).jsonObject["videoReferences"]?.jsonArray }.getOrNull() ?: return null
        fun url(format: String) = refs.firstNotNullOfOrNull { r ->
            val o = r.jsonObject
            o["url"]?.jsonPrimitive?.contentOrNull?.takeIf { o["format"]?.jsonPrimitive?.contentOrNull == format && it.startsWith("https://") }
        }
        url("hls")?.let { return PlaybackDecision.Ready(uri = it, mimeType = MIME_HLS, title = title) }
        url("hls-ts-avc")?.let { return PlaybackDecision.Ready(uri = it, mimeType = MIME_HLS, title = title) }
        url("dash")?.let { return PlaybackDecision.Ready(uri = it, mimeType = MIME_DASH, title = title) }
        return null
    }

    private companion object {
        const val MIME_HLS = "application/x-mpegURL"
        const val MIME_DASH = "application/dash+xml"
    }
}
