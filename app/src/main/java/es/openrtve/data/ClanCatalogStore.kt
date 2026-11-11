package es.openrtve.data

import es.openrtve.domain.CatalogItem
import es.openrtve.domain.ContentKind
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Lo que la app recuerda entre arranques: las dos listas (que solo crecen), los títulos
 * que se han quitado a mano, los favoritos y cuándo se vio por primera vez cada título.
 */
data class StoredCatalog(
    val series: List<CatalogItem>,
    val movies: List<CatalogItem>,
    /** Título quitado a mano → `true` si RTVE ya lo había dejado de ofrecer (si vuelve, reaparece). */
    val removed: Map<String, Boolean> = emptyMap(),
    /** Anclados: arriba de su lista. Los pone RTVE (sus novedades) o el usuario; solo el usuario los quita. */
    val pinned: Set<String> = emptySet(),
    /** Anclados que RTVE acaba de traer: van los primeros, los más nuevos antes. */
    val pinnedFresh: Map<String, Long> = emptyMap(),
    /** Desanclados a mano: RTVE no los vuelve a anclar. */
    val unpinned: Set<String> = emptySet(),
    /** Momento en que se descubrió un título nuevo de RTVE; 0 si ya estaba al instalar. */
    val firstSeen: Map<String, Long> = emptyMap(),
    /** Los títulos eliminados, enteros, para poder restaurarlos aunque RTVE ya no los ofrezca. */
    val removedItems: List<CatalogItem> = emptyList(),
    /** Cuándo se miró por última vez qué películas hay dentro de cada serie. */
    val scanned: Map<String, Long> = emptyMap(),
    /** Series que solo contienen películas: no salen entre las series, sus películas van sueltas. */
    val filmOnly: Set<String> = emptySet(),
)

/**
 * Última copia del catálogo del inicio, en un solo fichero pequeño. Al abrir la
 * app se pinta desde aquí, sin red y sin volver a fundir las filas de la portada;
 * la versión nueva llega después y lo sustituye.
 */
class ClanCatalogStore(
    private val file: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun read(): StoredCatalog? = withContext(io) {
        runCatching {
            if (!file.isFile) return@runCatching null
            val root = json.parseToJsonElement(file.readText()).jsonObject
            StoredCatalog(
                series = root.items("series"),
                movies = root.items("movies"),
                removed = (root["removed"] as? JsonObject).orEmpty().mapValues { (it.value as? JsonPrimitive)?.booleanOrNull ?: false },
                // "favorites" es el nombre antiguo de los anclados.
                pinned = ((root["pinned"] ?: root["favorites"]) as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet(),
                pinnedFresh = (root["pinnedFresh"] as? JsonObject).orEmpty().mapNotNull { (k, v) -> (v as? JsonPrimitive)?.longOrNull?.let { k to it } }.toMap(),
                unpinned = (root["unpinned"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet(),
                removedItems = root.items("removedItems"),
                firstSeen = (root["firstSeen"] as? JsonObject).orEmpty().mapNotNull { (k, v) -> (v as? JsonPrimitive)?.longOrNull?.let { k to it } }.toMap(),
                scanned = (root["scanned"] as? JsonObject).orEmpty().mapNotNull { (k, v) -> (v as? JsonPrimitive)?.longOrNull?.let { k to it } }.toMap(),
                filmOnly = (root["filmOnly"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet(),
            ).takeIf { it.series.isNotEmpty() || it.movies.isNotEmpty() || it.removed.isNotEmpty() || it.pinned.isNotEmpty() || it.unpinned.isNotEmpty() || it.removedItems.isNotEmpty() }
        }.getOrNull()
    }

    /** Quita títulos de las listas y de favoritos, y los apunta como eliminados a mano. */
    suspend fun remove(ids: Collection<String>) {
        val current = read() ?: StoredCatalog(emptyList(), emptyList())
        write(
            current.copy(
                series = current.series.filter { it.id !in ids },
                movies = current.movies.filter { it.id !in ids },
                removed = current.removed + ids.associateWith { false },
                removedItems = current.removedItems.filter { it.id !in ids } + (current.series + current.movies).filter { it.id in ids },
                pinned = current.pinned - ids.toSet(),
                pinnedFresh = current.pinnedFresh - ids.toSet(),
            ),
        )
    }

    /** Escritura atómica: un corte a medias deja la copia anterior. */
    suspend fun write(catalog: StoredCatalog) = withContext(io) {
        runCatching {
            val raw = buildJsonObject {
                put("removedItems", catalog.removedItems.toJson())
                put("series", catalog.series.toJson())
                put("movies", catalog.movies.toJson())
                put("removed", buildJsonObject { catalog.removed.forEach { (id, gone) -> put(id, gone) } })
                put("pinned", buildJsonArray { catalog.pinned.forEach { add(JsonPrimitive(it)) } })
                put("pinnedFresh", buildJsonObject { catalog.pinnedFresh.forEach { (id, at) -> put(id, at) } })
                put("unpinned", buildJsonArray { catalog.unpinned.forEach { add(JsonPrimitive(it)) } })
                put("firstSeen", buildJsonObject { catalog.firstSeen.forEach { (id, at) -> put(id, at) } })
                put("scanned", buildJsonObject { catalog.scanned.forEach { (id, at) -> put(id, at) } })
                put("filmOnly", buildJsonArray { catalog.filmOnly.forEach { add(JsonPrimitive(it)) } })
            }.toString()
            val pending = File(file.parentFile, file.name + ".tmp")
            pending.writeText(raw)
            if (!pending.renameTo(file)) {
                file.delete()
                pending.renameTo(file)
            }
        }
        Unit
    }

    private fun JsonObject.items(key: String): List<CatalogItem> =
        (get(key) as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.toItem() }

    private fun List<CatalogItem>.toJson(): JsonArray = buildJsonArray {
        forEach { item ->
            add(
                buildJsonObject {
                    put("id", item.id)
                    put("title", item.title)
                    put("kind", item.kind.name)
                    item.playbackId?.let { put("playbackId", it) }
                    item.assetId?.let { put("assetId", it) }
                    item.imageUrl?.let { put("imageUrl", it) }
                    item.posterUrl?.let { put("posterUrl", it) }
                    item.programId?.let { put("programId", it) }
                    item.allowedInCountry?.let { put("allowedInCountry", it) }
                    item.mediaTypeId?.let { put("mediaTypeId", it) }
                    item.mediaSubtypeId?.let { put("mediaSubtypeId", it) }
                    item.programTypeId?.let { put("programTypeId", it) }
                    item.programType?.let { put("programType", it) }
                    item.durationMs?.let { put("durationMs", it) }
                    put("loginRequired", item.loginRequired)
                    put("paid", item.paid)
                    put("drm", item.drm)
                },
            )
        }
    }

    private fun JsonObject.toItem(): CatalogItem? {
        fun text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
        fun flag(key: String) = (get(key) as? JsonPrimitive)?.booleanOrNull
        return CatalogItem(
            id = text("id") ?: return null,
            playbackId = text("playbackId"),
            assetId = text("assetId"),
            title = text("title") ?: return null,
            subtitle = null,
            imageUrl = text("imageUrl"),
            kind = runCatching { ContentKind.valueOf(text("kind").orEmpty()) }.getOrNull() ?: return null,
            posterUrl = text("posterUrl"),
            allowedInCountry = flag("allowedInCountry"),
            loginRequired = flag("loginRequired") ?: false,
            paid = flag("paid") ?: false,
            drm = flag("drm") ?: false,
            programId = text("programId"),
            mediaTypeId = text("mediaTypeId"),
            mediaSubtypeId = text("mediaSubtypeId"),
            programTypeId = text("programTypeId"),
            programType = text("programType"),
            durationMs = (get("durationMs") as? JsonPrimitive)?.longOrNull,
        )
    }
}
