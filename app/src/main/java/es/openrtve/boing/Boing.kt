package es.openrtve.boing

import es.openrtve.domain.CatalogItem
import es.openrtve.domain.ContentKind
import es.openrtve.domain.PlaybackDecision
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Un capítulo de Boing. [seconds] es 0 mientras no se conozca su duración. */
data class BoingEpisode(
    val guid: String,
    val slug: String,
    val title: String,
    val seconds: Int,
    val number: Int,
    val image: String,
    /** 0 si la serie tiene una sola lista de capítulos. */
    val season: Int = 0,
)

data class BoingSeries(
    val guid: String,
    val slug: String,
    val title: String,
    val image: String,
    val episodes: List<BoingEpisode> = emptyList(),
)

/**
 * Series de Boing (Warner Bros. Discovery, España): solo capítulos, sin cortos, extras ni juegos.
 * `assets/boing_catalogo.json` trae las series y las duraciones ya conocidas; la app solo pide a
 * Boing los capítulos que aparezcan nuevos.
 */
object Boing {
    const val PREFIX = "boing:"

    /** Menús de una ficha que no son capítulos. */
    private val NOT_CHAPTERS = Regex("extra|corto|juego|quiz|karaoke|únete|unete|conoce|postres", RegexOption.IGNORE_CASE)

    fun isBoing(id: String?): Boolean = id?.startsWith(PREFIX) == true

    /** La copia guardada en la app. */
    fun parseCatalog(raw: String): List<BoingSeries> = runCatching {
        (Json.parseToJsonElement(raw) as JsonArray).mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val episodes = (o["e"] as? JsonArray).orEmpty().mapNotNull { e ->
                val a = e as? JsonArray ?: return@mapNotNull null
                BoingEpisode(
                    guid = a[0].text() ?: return@mapNotNull null,
                    slug = a[1].text() ?: return@mapNotNull null,
                    title = a[2].text() ?: return@mapNotNull null,
                    seconds = (a[3] as? JsonPrimitive)?.intOrNull ?: 0,
                    number = (a[4] as? JsonPrimitive)?.intOrNull ?: 0,
                    image = a[5].text().orEmpty(),
                    season = (a[6] as? JsonPrimitive)?.intOrNull ?: 0,
                )
            }.ordered()
            if (episodes.isEmpty()) return@mapNotNull null
            BoingSeries(
                guid = o["g"].text() ?: return@mapNotNull null,
                slug = o["s"].text().orEmpty(),
                title = o["t"].text() ?: return@mapNotNull null,
                image = o["i"].text().orEmpty(),
                episodes = episodes,
            )
        }
    }.getOrDefault(emptyList())

    /** `Programs/getPrograms`: las series, sin capítulos todavía. */
    fun parsePrograms(body: String): List<BoingSeries> = runCatching {
        ((Json.parseToJsonElement(body) as? JsonObject)?.get("Programs") as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            BoingSeries(
                guid = o["Guid"].text() ?: return@mapNotNull null,
                slug = o["Slug"].text().orEmpty(),
                title = o["Title"].text()?.trim() ?: return@mapNotNull null,
                image = o["Thumbnail"].text().orEmpty(),
            )
        }
    }.getOrDefault(emptyList())

    /** `Program/getProgram`: los capítulos de la ficha (sin duración), en el orden de sus menús. */
    fun parseChapters(body: String): List<BoingEpisode> = runCatching {
        val menus = ((Json.parseToJsonElement(body) as? JsonObject)?.get("Menu") as? JsonArray).orEmpty().mapNotNull { m ->
            val menu = m as? JsonObject ?: return@mapNotNull null
            val name = (menu["Category"] as? JsonObject)?.get("Title").text().orEmpty().trim()
            if (NOT_CHAPTERS.containsMatchIn(name)) return@mapNotNull null
            val videos = (menu["Items"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                .filter { it["ObjectStringType"].text() == "Video" }
            if (videos.isEmpty()) null else name to videos
        }
        menus.flatMapIndexed { index, (name, videos) ->
            videos.mapNotNull { v ->
                val title = v["Title"].text()?.trim() ?: return@mapNotNull null
                BoingEpisode(
                    guid = v["Guid"].text() ?: return@mapNotNull null,
                    slug = v["Slug"].text() ?: return@mapNotNull null,
                    title = if (menus.size > 1) "$name · $title" else title,
                    seconds = 0,
                    number = (v["Order"] as? JsonPrimitive)?.intOrNull ?: 0,
                    image = v["poster"].text() ?: v["Thumbnail"].text().orEmpty(),
                    season = if (menus.size > 1) index + 1 else 0,
                )
            }
        }.ordered()
    }.getOrDefault(emptyList())

    /** `Video/getVideo`: duración y número de capítulo, o `null` si no se entiende. */
    fun parseVideoInfo(body: String): Pair<Int, Int>? = runCatching {
        val v = (Json.parseToJsonElement(body) as JsonObject)["Video"] as JsonObject
        val seconds = (v["DurationSeconds"] as? JsonPrimitive)?.intOrNull ?: return null
        seconds to ((v["episode_number"] as? JsonPrimitive)?.intOrNull ?: 0)
    }.getOrNull()

    /** La dirección del vídeo (HLS, si no DASH). `null` si no hay o está protegido. */
    fun pickStream(body: String, title: String): PlaybackDecision.Ready? = runCatching {
        val v = (Json.parseToJsonElement(body) as JsonObject)["Video"] as JsonObject
        val protection = v["ProtectionType"].text().orEmpty()
        if (protection.isNotBlank() && !protection.equals("none", ignoreCase = true)) return null
        val formats = (v["Formats"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        fun url(type: String) = formats.firstNotNullOfOrNull { f ->
            f["Url"].text()?.takeIf { f["Type"].text().equals(type, ignoreCase = true) && it.startsWith("https://") }
        }
        url("hls")?.let { return PlaybackDecision.Ready(uri = it, mimeType = MIME_HLS, title = title) }
        url("mpegdash")?.let { return PlaybackDecision.Ready(uri = it, mimeType = MIME_DASH, title = title) }
        null
    }.getOrNull()

    private const val MIME_HLS = "application/x-mpegURL"
    private const val MIME_DASH = "application/dash+xml"

    private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun List<BoingEpisode>.ordered() = sortedWith(compareBy({ it.season }, { it.number == 0 }, { it.number }))

    fun BoingSeries.asItem() = CatalogItem(
        id = PREFIX + guid, playbackId = null, assetId = null, title = title, subtitle = null,
        imageUrl = image.ifBlank { null }, kind = ContentKind.PROGRAM, allowedInCountry = null,
        loginRequired = false, paid = false, drm = false, programId = PREFIX + guid,
    )

    fun BoingEpisode.asItem(series: String, seriesTitle: String?) = CatalogItem(
        id = PREFIX + guid, playbackId = null, assetId = null, title = title, subtitle = seriesTitle,
        imageUrl = image.ifBlank { null }, kind = ContentKind.VIDEO, allowedInCountry = null,
        loginRequired = false, paid = false, drm = false, programId = series,
        durationMs = seconds.takeIf { it > 0 }?.let { it * 1000L }, episode = number.takeIf { it > 0 },
    )
}

/** Habla con el servidor de la app de Boing. Todo es bloqueante: llamar fuera del hilo principal. */
class BoingApi(private val client: OkHttpClient) {
    fun programs(): List<BoingSeries>? = get("Programs/getPrograms")?.let { Boing.parsePrograms(it) }?.takeIf { it.isNotEmpty() }

    /** `null` si Boing no responde. */
    fun chapters(guid: String): List<BoingEpisode>? = get("Program/getProgram", "programId" to guid)?.let { Boing.parseChapters(it) }

    /** Ficha de un capítulo (duración, direcciones de vídeo); la dirección caduca, se pide al reproducir. */
    fun video(slug: String): String? = get("Video/getVideo", "slug" to slug)

    private fun get(path: String, vararg params: Pair<String, String>): String? = runCatching {
        val url = (BASE + path).toHttpUrl().newBuilder().addQueryParameter("platformId", PLATFORM)
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val request = Request.Builder().url(url).header("x-functions-key", KEY).header("Accept", "application/json").build()
        client.newCall(request).execute().use { if (it.isSuccessful) it.body?.string() else null }
    }.getOrNull()

    private companion object {
        const val BASE = "https://feapi.turner.streamity.com/api/v1/"
        const val PLATFORM = "96a881d2-b1bc-4bd0-8c3c-4c69c5d41480"
        const val KEY = "pc__I4Ronpm-n4la827RuyTSaCKlFQ4a_2ssh4GEfY3EAzFu1gpYAA=="
    }
}

/** Duraciones de los capítulos que no venían en la copia de la app: se piden una vez y se guardan. */
class BoingDurations(private val file: File) {
    @Volatile private var cache: Map<String, Pair<Int, Int>>? = null

    @Synchronized
    fun read(): Map<String, Pair<Int, Int>> = cache ?: runCatching {
        (Json.parseToJsonElement(file.readText()) as JsonObject).mapNotNull { (k, v) ->
            val a = v as? JsonArray ?: return@mapNotNull null
            val s = (a[0] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            k to (s to ((a[1] as? JsonPrimitive)?.intOrNull ?: 0))
        }.toMap()
    }.getOrDefault(emptyMap()).also { cache = it }

    @Synchronized
    fun put(slug: String, seconds: Int, number: Int) {
        val next = read() + (slug to (seconds to number))
        cache = next
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(buildJsonObject { next.forEach { (k, v) -> put(k, buildJsonArray { add(JsonPrimitive(v.first)); add(JsonPrimitive(v.second)) }) } }.toString())
            tmp.renameTo(file)
        }
    }
}
