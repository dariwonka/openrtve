package es.openrtve.svt

import es.openrtve.domain.CatalogItem
import es.openrtve.domain.ContentKind
import es.openrtve.domain.PlaybackDecision
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Un episodio de SVT Barn (en sueco). [videoId] es el id que entiende `api.svt.se/video`. */
data class SvtEpisode(
    val videoId: String,
    val title: String,
    val seconds: Int,
    val season: Int,
    val number: Int,
    val image: String,
)

/** Un programa. Si [episodes] está vacío aún no se han pedido (o no tiene ninguno visible). */
data class SvtProgram(
    val id: String,
    val title: String,
    val slug: String,
    val image: String,
    val episodes: List<SvtEpisode> = emptyList(),
)

/**
 * Catálogo infantil de SVT Play (Suecia): solo programas en sueco, sin adolescentes, y solo
 * episodios que se ven desde fuera de Suecia y sin DRM. La lista y los episodios se piden a SVT
 * al abrir la sección; `assets/svt_barn.json` es una copia del 13-11-2026 para cuando no hay red.
 */
object SvtBarn {
    /** Programas que no entran: no están en sueco o son para adolescentes. Lo nuevo entra salvo que se añada aquí. */
    val EXCLUDED_SLUGS: Set<String> = setOf(
        // No están en sueco (finés, sami, meänkieli, lengua de signos, yiddish)
        "ainos-escape-room-finska", "ainos-escape-room-teckensprak", "binnabannas-1",
        "forsta-gangen-meankieli", "gaminghornan-live-finska", "gaminghornan-live-teckensprak",
        "gaminghornan-testar-spel--meankieli", "goavkut-en-dorr-pa-glant", "hjalp-auta-minua",
        "hjalp-veahket", "hjalp-teckensprak", "kompisar-finska",
        "kompisar-samiska", "lilla-aktuellt-teckensprak", "min-dag-muro-djes-1",
        "pikkuklapit", "taaperot", "utan-filter-meankieli-1",
        "utan-filter-teckensprak", "rys-i-sapmi", "kinderlech",
        "vi-gar-pa-samiska-forskolan", "vi-gar-pa-sverigefinska-forskolan",
        // Para adolescentes o mayores
        "klassen", "klassen-klassresan", "jobbigt",
        "tjejer", "killar", "strula",
        "strula-kortdrama", "strula2", "drama-forever",
        "puberteten-mens-svett-malbrott-brost-snoppen-och-snippan", "snart-13", "skills",
        "okej-nice", "fritids", "zombie",
        "hemsokt", "fejd", "skyldig",
        "spookys", "vi-forever", "du-a-jag",
        "kar", "min-adhd-1", "min-autism",
        "adhd-och-livet-1", "ett-ar-i-usa", "level-up",
        "komiker-utan-granser", "hjarnkoll", "akta-dig",
        "tsm-1", "trean", "stopp",
        "otajmat", "varsta-sommaren", "best-surprise-ever",
        "bossfajten", "livs-levande", "sommarskuggan",
        "min-fantastiska-historia", "lizettes-vlogg-och-lista", "malins-fejstajm",
        "alva", "basta-platsen", "mote-med-barn-1",
        "unnit-1", "maxat",
    )

    private val NOT_SWEDISH = Regex("(finska|samiska|meankieli|teckensprak|sapmi)", RegexOption.IGNORE_CASE)

    /** Los programas nuevos de otro idioma se reconocen por su dirección (`...-finska`, `...-teckensprak`). */
    fun isIncluded(slug: String): Boolean = slug.isNotBlank() && slug !in EXCLUDED_SLUGS && !NOT_SWEDISH.containsMatchIn(slug)

    private val KINDS = setOf("TvSeries", "KidsTvShow", "TvShow")

    /** La copia guardada en la app. */
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
            }.ordered()
            if (episodes.isEmpty()) return@mapNotNull null
            SvtProgram(
                id = obj["i"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                title = obj["t"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                slug = obj["s"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                image = obj["q"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                episodes = episodes,
            )
        }
    }.getOrDefault(emptyList())

    /** La lista A-Ö de la sección Barn tal como la devuelve `categoryPage`; sin episodios todavía. */
    fun parsePrograms(body: String): List<SvtProgram> = runCatching {
        val modules = Json.parseToJsonElement(body).obj()?.get("data").obj()?.get("categoryPage").obj()?.get("modules").arr().orEmpty()
        val items = modules.firstOrNull { it.obj()?.get("id").text() == "all-barn" }
            ?.obj()?.get("selection").obj()?.get("items").arr().orEmpty()
        items.mapNotNull { element ->
            val o = element.obj() ?: return@mapNotNull null
            val item = o["item"].obj() ?: return@mapNotNull null
            if (item["__typename"].text() !in KINDS) return@mapNotNull null
            val slug = item["urls"].obj()?.get("svtplay").text()?.slugOfPath().orEmpty()
            if (!isIncluded(slug)) return@mapNotNull null
            val image = o["adaptiveImage"].obj()?.let { img ->
                val id = img["id"]?.jsonPrimitive?.contentOrNull
                val changed = img["changed"]?.jsonPrimitive?.contentOrNull
                if (id != null && changed != null) "$id/$changed" else null
            }.orEmpty()
            SvtProgram(
                id = item["svtId"].text() ?: return@mapNotNull null,
                title = o["heading"].text() ?: return@mapNotNull null,
                slug = slug,
                image = image,
            )
        }.distinctBy { it.id }.sortedBy { it.title.lowercase() }
    }.getOrDefault(emptyList())

    /** Los episodios de un programa (respuesta de `listablesBySlug`), solo los que se pueden ver desde fuera de Suecia. */
    fun parseEpisodes(body: String): List<SvtEpisode> = runCatching {
        val program = Json.parseToJsonElement(body).obj()?.get("data").obj()?.get("listablesBySlug").arr()?.firstOrNull().obj()
        program?.get("seasons").arr().orEmpty().flatMap { s ->
            val season = (s.obj()?.get("number") as? JsonPrimitive)?.intOrNull ?: 0
            s.obj()?.get("episodes").arr().orEmpty().mapNotNull { e ->
                val o = e.obj() ?: return@mapNotNull null
                val rights = o["restrictions"].obj()
                if ((rights?.get("onlyAvailableInSweden") as? JsonPrimitive)?.booleanOrNull == true) return@mapNotNull null
                if ((rights?.get("drmCopyProtection") as? JsonPrimitive)?.booleanOrNull == true) return@mapNotNull null
                val wide = o["images"].obj()?.get("wide").obj()
                val image = if (wide != null && wide["id"].text() != null && wide["changed"] is JsonPrimitive) "${wide["id"].text()}/${(wide["changed"] as JsonPrimitive).content}" else ""
                SvtEpisode(
                    videoId = o["videoSvtId"].text()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null,
                    title = o["name"].text() ?: return@mapNotNull null,
                    seconds = (o["duration"] as? JsonPrimitive)?.intOrNull ?: 0,
                    season = season,
                    number = (o["number"] as? JsonPrimitive)?.intOrNull ?: 0,
                    image = image,
                )
            }
        }.ordered()
    }.getOrDefault(emptyList())

    private fun List<SvtEpisode>.ordered() = sortedWith(compareBy({ it.season }, { it.number == 0 }, { it.number }))

    private fun String.slugOfPath(): String =
        substringBefore('?').substringAfter("svtplay.se").trim('/').takeIf { !it.contains('/') }.orEmpty()

    /** `id/changed` -> URL 16:9 de svtstatic.se. */
    fun imageUrl(ref: String, width: Int = 600): String? =
        ref.takeIf { it.isNotBlank() && !it.contains("null") }?.let { "https://www.svtstatic.se/image/wide/$width/$it" }

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

/** Habla con la API pública de SVT. Todo es bloqueante: llamar fuera del hilo principal. */
class SvtApi(private val client: OkHttpClient) {
    fun programs(): List<SvtProgram> = SvtBarn.parsePrograms(graphql(PROGRAMS_QUERY, buildJsonObject {
        put("id", "barn")
        put("filter", buildJsonObject { put("kidsFilter", true); put("userIsAbroad", true); put("includeFullOppetArkiv", false) })
    }))

    /** `null` si SVT no responde; lista vacía si el programa no tiene nada visible. */
    fun episodes(slug: String): List<SvtEpisode>? = runCatching {
        SvtBarn.parseEpisodes(graphql(EPISODES_QUERY, buildJsonObject { put("s", slug) }))
    }.getOrNull()

    private fun graphql(query: String, variables: JsonObject): String {
        val body = buildJsonObject { put("query", query); put("variables", variables) }.toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(URL).post(body).build()
        return client.newCall(request).execute().use { check(it.isSuccessful) { "SVT ${it.code}" }; it.body?.string().orEmpty() }
    }

    private companion object {
        const val URL = "https://contento.svt.se/graphql"
        const val PROGRAMS_QUERY = "query(\$id: String!, \$filter: Filter!) { categoryPage(id: \$id, abTestVariants: []) { modules { id selection { id " +
            "items(filter: \$filter) { heading adaptiveImage { id changed } item { __typename svtId urls { svtplay } } } } } } }"
        private const val EP = "videoSvtId name duration number restrictions { onlyAvailableInSweden drmCopyProtection } images { wide { id changed } }"
        private const val BODY = "svtId name seasons { number episodes { $EP } }"
        const val EPISODES_QUERY = "query(\$s: [String!]!) { listablesBySlug(slugs: \$s) { __typename " +
            "... on TvSeries { $BODY } ... on KidsTvShow { $BODY } ... on TvShow { $BODY } } }"
    }
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
