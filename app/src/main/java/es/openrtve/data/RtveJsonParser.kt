package es.openrtve.data

import es.openrtve.domain.CatalogItem
import es.openrtve.domain.CatalogModule
import es.openrtve.domain.CatalogPage
import es.openrtve.domain.RowLayout
import es.openrtve.domain.SearchResults
import es.openrtve.domain.ContentKind
import es.openrtve.domain.HomeFeed
import es.openrtve.domain.HomeLink
import es.openrtve.domain.HomeRow
import es.openrtve.domain.LinkKind
import es.openrtve.domain.LiveInfo
import es.openrtve.domain.ProgramDetail
import es.openrtve.domain.ProgramSeason
import es.openrtve.domain.RtveHostPolicy
import es.openrtve.domain.programStructure
import es.openrtve.domain.referenceItem
import es.openrtve.domain.seasonsInDisplayOrder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class RtveJsonParser(
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val hostPolicy: RtveHostPolicy = RtveHostPolicy(),
) {
    fun parseHome(raw: String): HomeFeed {
        val root = json.parseToJsonElement(raw).jsonObject
        val rows = root.array("rows")
            .orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { row ->
                val presentation = row.text("tipo")
                val isLinks = presentation.equals("links", ignoreCase = true)
                val links = if (isLinks) homeLinks(row) else emptyList()
                // Una fila `links` sin enlaces no aporta nada.
                if (isLinks && links.isEmpty()) return@mapNotNull null
                HomeRow(
                    id = -1,
                    title = row.text("title", "name")?.let(::editorialTitle).orEmpty(),
                    order = row.number("orden", "order") ?: Int.MAX_VALUE,
                    moduleType = row.text("moduleType"),
                    presentation = presentation,
                    // El `urlContent` de una fila `links` es otro documento (enlaces web); la app oficial usa los inline.
                    contentUrl = row.text("urlContent")?.takeIf { !isLinks && hostPolicy.isAllowed(it) },
                    layout = rowLayout(presentation),
                    links = links,
                )
            }
            // Noticias no son reproducibles; la parrilla tiene otra estructura.
            .filterNot { it.presentation?.lowercase() in NON_CATALOG_ROWS }
            .sortedBy(HomeRow::order)
            .mapIndexed { index, row -> row.copy(id = index) }

        return HomeFeed(
            id = root.text("id"),
            title = root.text("title") ?: "RTVE Play",
            rows = rows,
        )
    }

    /**
     * Muchas filas de portada llegan sin título; la propia colección sí lo trae
     * (`title`, no `name`, que es la ruta editorial interna).
     */
    fun parseModule(raw: String, fallbackTitle: String): CatalogModule {
        val root = json.parseToJsonElement(raw).jsonObject
        val pageItems = root.obj("page")?.array("items")
            ?: root.array("items")
            ?: JsonArray(emptyList())
        val collection = (pageItems.singleOrNull() as? JsonObject)
            ?.takeIf { it.array("collectionItems") != null }
        val items = toCatalogItems((collection?.array("collectionItems") ?: pageItems).mapNotNull { it as? JsonObject })

        return CatalogModule(
            title = fallbackTitle.ifBlank { collection?.text("title")?.let(::editorialTitle) ?: fallbackTitle },
            items = items,
        )
    }

    fun parseSearch(raw: String): SearchResults {
        val root = json.parseToJsonElement(raw).jsonObject
        fun block(key: String) = toCatalogItems(
            root.obj(key)?.obj("page")?.array("items").orEmpty().mapNotNull { it as? JsonObject },
        )
        val videos = root.obj("videos")
        return SearchResults(
            programs = block("programs"),
            videos = block("videos"),
            videosPage = videos?.obj("page")?.number("number") ?: 1,
            videosTotalPages = videos?.obj("page")?.number("totalPages") ?: 1,
            // Ruta relativa al buscador; si cambiara de host o de forma, no se pagina.
            videosQuery = videos?.text("query")?.takeIf { it.startsWith("/") && !it.startsWith("//") },
        )
    }

    /** Accesos inline de una fila `links`; `enlaceExterno` (web) se omite. */
    private fun homeLinks(row: JsonObject): List<HomeLink> = row.array("links")
        .orEmpty()
        .mapNotNull { it as? JsonObject }
        .mapNotNull { link ->
            val kind = when (link.text("tipo")) {
                "collection" -> LinkKind.COLLECTION
                "portadaPlay" -> LinkKind.PORTADA
                "programaPlay" -> LinkKind.PROGRAM
                "videoPlay" -> LinkKind.VIDEO
                else -> return@mapNotNull null
            }
            HomeLink(
                title = link.text("title") ?: return@mapNotNull null,
                imageUrl = firstAllowed(link.text("imgHorizontal", "image", "imgSquare")),
                url = link.text("url")?.let(hostPolicy::sanitize) ?: return@mapNotNull null,
                kind = kind,
            )
        }
        // La URL es la clave de la tarjeta en la fila; un enlace repetido no aporta y rompería la lista.
        .distinctBy(HomeLink::url)

    /** Un valor por presentación de RTVE Play; `directosTV16` y `ColeccionCuadradoPeq` comparten holder con su base. */
    private fun rowLayout(presentation: String?): RowLayout = when (presentation?.lowercase()) {
        "colecciondestacado" -> RowLayout.HERO
        "coleccionsuperdestacado" -> RowLayout.FEATURED
        "coleccionposter", "videoposter", "storiesposter", "programas" -> RowLayout.POSTER
        "coleccionsuper" -> RowLayout.POSTER_TALL
        "coleccioncuadrado", "coleccioncuadradopeq" -> RowLayout.SQUARE
        "collecciontops", "colecciontops", "tops" -> RowLayout.RANKED
        else -> RowLayout.LANDSCAPE
    }

    fun parseProgram(raw: String): ProgramDetail {
        val root = json.parseToJsonElement(raw).jsonObject
        val program = root.obj("page")?.array("items")?.firstOrNull() as? JsonObject
            ?: throw IllegalArgumentException("El programa no tiene ficha")
        val seasons = program.array("seasons")
            .orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { season ->
                val id = season.text("id") ?: return@mapNotNull null
                ProgramSeason(
                    id = id,
                    title = season.text("longTitle", "shorttitle", "shortTitle", "title") ?: id,
                    episodeCount = season.number("numEpisodes"),
                    order = season.number("orden"),
                )
            }
        val detail = ProgramDetail(
            id = program.text("id") ?: throw IllegalArgumentException("Programa sin id"),
            title = program.text("name", "title", "longTitle") ?: "",
            description = program.text("description", "longDescription", "promoDesc"),
            imageUrl = firstAllowed(program.text("imgPortada", "imgPortada2"))
                ?: derivedImage("p", program.text("id").orEmpty(), "imgPortada", BACKDROP_WIDTH),
            emission = program.text("emission"),
            seasons = seasons,
            webUrl = program.text("htmlUrl")?.let(hostPolicy::sanitize),
            programTypeId = program.text("programTypeId", "programTypeID"),
            programType = program.text("programType"),
            inEmission = program.flag("outOfEmission") != true,
            isComplete = program.flag("isComplete") == true,
            showFragments = program.flag("showFragments") == true,
            year = program.text("productionDate")?.let(::productionYear),
            ageRating = program.text("ageRange"),
            genres = program.genres(),
            director = program.people("director"),
            cast = program.peopleList("casting"),
            originalLanguage = program.text("languageOriginal"),
            country = program.text("country"),
        )
        return detail.copy(seasons = detail.seasonsInDisplayOrder())
    }

    fun parseVideoPage(raw: String): CatalogPage {
        val root = json.parseToJsonElement(raw).jsonObject
        val page = root.obj("page")
        val items = toCatalogItems(page?.array("items").orEmpty().mapNotNull { it as? JsonObject })
        return CatalogPage(
            items = items,
            page = page?.number("number") ?: 1,
            totalPages = page?.number("totalPages") ?: 1,
        )
    }

    /**
     * Listado A-Z de una cadena (`cadenas/{id}/programas.json`). Todo lo que trae son
     * programas, aunque `small=true` omita su `contentType`; las imágenes salen del
     * servicio por id, como en el resto de programas.
     */
    fun parsePrograms(raw: String): CatalogPage {
        val page = json.parseToJsonElement(raw).jsonObject.obj("page")
        val items = page?.array("items").orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { item ->
                val id = item.text("id", "uid") ?: return@mapNotNull null
                val title = item.text("name", "title", "longTitle", "shortTitle") ?: return@mapNotNull null
                referenceItem(id, ContentKind.PROGRAM, title, derivedImage("p", id, "imgPortada", LANDSCAPE_WIDTH))
                    .copy(posterUrl = derivedImage("p", id, "imgPoster", POSTER_WIDTH))
            }
            .distinctBy(CatalogItem::id)
        return CatalogPage(items, page?.number("number") ?: 1, page?.number("totalPages") ?: 1)
    }

    /**
     * Una lista de "programa + lastMultimedia" puede ser una lista de programas
     * (cada uno con su último episodio) o una lista de medios que repite el
     * programa en cada item (etapas, resúmenes, invitados...). Se deduce de los
     * datos: si el programa se repite, cada item es su medio.
     */
    private fun toCatalogItems(objects: List<JsonObject>): List<CatalogItem> {
        val programOccurrences = objects
            .filter { it.obj("lastMultimedia") != null }
            .groupingBy { it.text("id", "uid") }
            .eachCount()
        return objects
            .mapNotNull { item ->
                val repeated = (programOccurrences[item.text("id", "uid")] ?: 0) > 1
                toCatalogItem(item, mediaFirst = repeated)
            }
            // Noticias, enlaces o tipos que no conocemos no se pueden abrir ni reproducir.
            .filter { it.kind != ContentKind.UNKNOWN }
            // Los feeds repiten items; Compose exige claves únicas en las listas.
            .distinctBy(CatalogItem::id)
    }

    private fun toCatalogItem(item: JsonObject, mediaFirst: Boolean = false): CatalogItem? {
        val nestedMedia = item.obj("lastMultimedia")
        // Sin id propio el item no es un programa: es una envoltura del medio que trae dentro.
        val bareMedia = nestedMedia != null && item.text("id", "uid") == null
        if (nestedMedia != null && (mediaFirst || bareMedia || isContainer(item))) {
            val mediaItem = toCatalogItem(nestedMedia) ?: return null
            // Las previews del medio anidado repiten la imagen del programa; el
            // servicio de imágenes por id sí devuelve la propia del medio.
            return mediaItem.copy(
                subtitle = mediaItem.subtitle ?: item.text("name", "title"),
                programId = mediaItem.programId ?: item.text("id"),
            )
        }
        val media = nestedMedia ?: item
        val id = item.text("id", "uid") ?: media.text("id", "uid") ?: return null
        val title = item.text("title", "name", "titulo", "longTitle", "shortTitle", "metaTitle")
            ?: media.text("title", "name", "longTitle")
            ?: fallbackTitle(item)
            ?: return null
        val mediaTitle = media.text("title", "longTitle")
            ?.takeUnless { it == title }
        val contentType = media.text("contentType")
            ?: item.text("contentType")
        val editorialType = media.text("tipo") ?: item.text("tipo")
        val type = media.obj("type") ?: item.obj("type")
        val subtype = media.obj("subType") ?: item.obj("subType")
        val typeValue = media.text("type") ?: item.text("type")
        val subtypeValue = media.text("subType") ?: item.text("subType")
        val programInfo = media.obj("programInfo") ?: item.obj("programInfo")
        val assetId = media.text("idAsset") ?: item.text("idAsset")
        val kind = contentKind(contentType, editorialType, nestedMedia != null, assetId)
        val live = if (kind == ContentKind.LIVE) liveInfo(item) else null
        val subtitle = when {
            mediaTitle != null -> mediaTitle
            kind == ContentKind.VIDEO -> item.obj("programInfo")?.text("title")
            kind == ContentKind.LIVE -> live?.category
            else -> null
        }

        return CatalogItem(
            id = id,
            playbackId = playbackId(kind, nestedMedia, id),
            assetId = assetId,
            title = title,
            subtitle = subtitle,
            imageUrl = landscapeImage(kind, id, media, item),
            kind = kind,
            posterUrl = posterImage(kind, id, media, item),
            squareUrl = squareImage(kind, id, media, item),
            tallPosterUrl = if (kind == ContentKind.PROGRAM) derivedImage("p", id, "imgCol", POSTER_WIDTH) else null,
            programId = if (kind == ContentKind.PROGRAM) id else programInfo?.text("id"),
            seasonId = media.text("temporadaId") ?: item.text("temporadaId"),
            mediaTypeId = type?.text("id") ?: typeValue?.takeIf { it.all(Char::isDigit) },
            mediaTypeName = type?.text("name") ?: typeValue?.takeUnless { it.all(Char::isDigit) },
            mediaSubtypeName = subtype?.text("name") ?: subtypeValue?.takeUnless { it.all(Char::isDigit) },
            mediaSubtypeId = subtype?.text("id") ?: subtypeValue?.takeIf { it.all(Char::isDigit) },
            programShowsFragments = programInfo?.flag("showFragments"),
            programTypeId = programInfo?.text("programTypeId", "programTypeID"),
            programType = programInfo?.text("programType"),
            allowedInCountry = media.flag("allowedInCountry")
                ?: item.flag("allowedInCountry"),
            loginRequired = media.flag("requireLogged")
                ?: item.flag("requireLogged")
                ?: false,
            paid = media.flag("paidContent")
                ?: item.flag("paidContent")
                ?: false,
            drm = media.flag("hasDRM")
                ?: item.flag("hasDRM")
                ?: false,
            durationMs = media.long("duration"),
            publicationDate = media.text("publicationDate", "dateOfEmission"),
            episode = media.number("episode")?.takeIf { it > 0 },
            seasonTitle = media.text("temporada", "temporadaShortTitle"),
            live = live,
            webUrl = (item.text("htmlUrl") ?: media.text("htmlUrl"))?.let(hostPolicy::sanitize),
        )
    }

    private fun liveInfo(item: JsonObject): LiveInfo = LiveInfo(
        isOnAir = item.flag("live") ?: true,
        startsAtMillis = item.text("inicio")?.let(::parseMadridTime),
        durationMinutes = item.number("duracion")?.takeIf { it > 0 },
        progressPercent = item.number("porcentaje"),
        channelLogoUrl = item.text("logo")?.let(hostPolicy::sanitize),
        category = item.text("antetitulo")?.let(::titleCase),
    )

    /** El feed escribe las horas en local de Madrid, sin zona. */
    private fun parseMadridTime(value: String): Long? = runCatching {
        SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.ROOT)
            .apply { timeZone = TimeZone.getTimeZone("Europe/Madrid") }
            .parse(value)?.time
    }.getOrNull()

    /**
     * Algunas colecciones llegan con su ruta editorial como título ("Portada En
     * Play > Destacados"): se muestra solo el último tramo.
     */
    private fun editorialTitle(value: String): String =
        value.substringAfterLast(" > ").trim().ifEmpty { value }

    /** "LA VUELTA 2026" -> "La Vuelta 2026"; los antetítulos llegan en mayúsculas. */
    private fun titleCase(value: String): String =
        if (value != value.uppercase()) value
        else value.lowercase().split(' ').joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }

    private fun isContainer(program: JsonObject): Boolean =
        programStructure(program.text("programTypeId", "programTypeID"), program.text("programType")).isCollection

    /**
     * Un programa solo es reproducible a través de su último medio; su propio ID
     * no identifica ningún stream.
     */
    private fun playbackId(kind: ContentKind, nestedMedia: JsonObject?, itemId: String): String? =
        when {
            nestedMedia != null -> nestedMedia.text("id")
            kind == ContentKind.PROGRAM || kind == ContentKind.UNKNOWN -> null
            else -> itemId
        }

    private fun contentKind(
        contentType: String?,
        editorialType: String?,
        isProgramWithMedia: Boolean,
        assetId: String?,
    ): ContentKind {
        if (editorialType?.lowercase() in LIVE_EDITORIAL_TYPES) {
            return ContentKind.LIVE
        }
        return when (contentType?.lowercase()) {
            "video" -> if (isProgramWithMedia) ContentKind.PROGRAM else ContentKind.VIDEO
            "program", "programa" -> ContentKind.PROGRAM
            "directo", "live", "broadcast" -> ContentKind.LIVE
            // Sin tipo declarado, un idAsset es la única pista de que se trata de un directo.
            null -> if (assetId != null) ContentKind.LIVE else ContentKind.UNKNOWN
            else -> ContentKind.UNKNOWN
        }
    }

    /*
     * Imágenes por proporción, medidas el 12-09-2026: `imgPortada`, `thumb` y
     * `previews.horizontal*` son 16:9; `imgPoster`, `thumb_vertical` y
     * `previews.vertical*` 2:3; `imgBackground`, `thumb_square` y `previews.square*`
     * 1:1; `imgCol` 1:2 e `imgBanner` 4:1. Para vídeos y programas el
     * servicio por id devuelve siempre la variante pedida y admite `w=`.
     */
    private fun landscapeImage(kind: ContentKind, id: String, media: JsonObject, item: JsonObject): String? = when (kind) {
        ContentKind.VIDEO -> derivedImage("v", id, "horizontal2", LANDSCAPE_WIDTH)
        ContentKind.PROGRAM -> derivedImage("p", id, "imgPortada", LANDSCAPE_WIDTH)
        else -> firstAllowed(
            item.text("thumb", "imagen", "imgPortada", "imgPortada2", "thumbnail"),
            media.obj("previews")?.text("horizontal2", "horizontal"),
            item.text("imageSEO", "imgBackground"),
        )
    }

    private fun posterImage(kind: ContentKind, id: String, media: JsonObject, item: JsonObject): String? = when (kind) {
        ContentKind.VIDEO -> derivedImage("v", id, "vertical", POSTER_WIDTH)
        ContentKind.PROGRAM -> derivedImage("p", id, "imgPoster", POSTER_WIDTH)
        else -> firstAllowed(
            item.text("thumb_vertical", "imgPoster", "imgPoster2"),
            media.obj("previews")?.text("vertical2", "vertical"),
        )
    }

    private fun squareImage(kind: ContentKind, id: String, media: JsonObject, item: JsonObject): String? = when (kind) {
        ContentKind.PROGRAM -> derivedImage("p", id, "imgBackground", SQUARE_WIDTH)
        ContentKind.VIDEO -> null
        else -> firstAllowed(item.text("thumb_square", "imgBackground"), media.obj("previews")?.text("square", "square2"))
    }

    private fun firstAllowed(vararg candidates: String?): String? =
        candidates.firstNotNullOfOrNull { it?.let(hostPolicy::sanitize) }

    private fun derivedImage(type: String, id: String, variant: String?, width: Int): String = when (type) {
        "p" -> "$IMAGE_SERVICE/p/$id?imgProgApi=$variant&w=$width"
        "v" -> "$IMAGE_SERVICE/v/$id/$variant?w=$width"
        else -> "$IMAGE_SERVICE/$type/$id?w=$width"
    }

    /**
     * Los canales FAST llegan sin `titulo`; su descripción empieza por el nombre
     * ("Canal RTVE La Promesa: ...") y, si no, queda el permalink ("play-promesa").
     */
    private fun fallbackTitle(item: JsonObject): String? {
        val description = item.text("descripcion", "description")
            ?.substringBefore(':')
            ?.substringBefore('.')
            ?.trim()
            ?.takeIf { it.length in 3..60 }
        return description ?: item.text("permalink")
            ?.split('-')
            ?.joinToString(" ") { part -> part.replaceFirstChar(Char::uppercase) }
    }

    private fun JsonObject.text(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        (get(key) as? JsonPrimitive)
            ?.contentOrNull
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    }

    private fun JsonObject.number(vararg keys: String): Int? = keys.firstNotNullOfOrNull { key ->
        (get(key) as? JsonPrimitive)?.intOrNull
    }

    private fun JsonObject.long(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull

    private fun JsonObject.flag(key: String): Boolean? =
        (get(key) as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject

    private fun JsonObject.array(key: String): JsonArray? = get(key) as? JsonArray

    private fun JsonObject.genres(): List<String> = array("generos")
        .orEmpty()
        .mapNotNull { (it as? JsonObject)?.text("subGeneroInf", "generoInf") }
        .distinct()

    private fun JsonObject.people(key: String): String? = peopleList(key).joinToString(", ").ifBlank { null }

    private fun JsonObject.peopleList(key: String): List<String> = text(key)
        ?.split('|')
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        .orEmpty()

    private fun productionYear(value: String): String? = YEAR.find(value)?.value

    private companion object {
        val LIVE_EDITORIAL_TYPES = setOf("broadcast", "peticion", "directo")
        val NON_CATALOG_ROWS = setOf("noticias", "parrilla")
        val YEAR = Regex("\\b(?:19|20)\\d{2}\\b")
        const val IMAGE_SERVICE = "https://img.rtve.es"
        const val LANDSCAPE_WIDTH = 960
        const val POSTER_WIDTH = 480
        const val SQUARE_WIDTH = 480
        const val BACKDROP_WIDTH = 1600
    }
}
