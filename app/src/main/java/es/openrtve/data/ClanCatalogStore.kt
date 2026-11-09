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

/** Las dos listas del inicio tal como se guardan: títulos ya únicos y ordenados. */
data class StoredCatalog(val series: List<CatalogItem>, val movies: List<CatalogItem>)

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
            StoredCatalog(root.items("series"), root.items("movies")).takeIf { it.series.isNotEmpty() || it.movies.isNotEmpty() }
        }.getOrNull()
    }

    /** Escritura atómica: un corte a medias deja la copia anterior. */
    suspend fun write(catalog: StoredCatalog) = withContext(io) {
        runCatching {
            val raw = buildJsonObject {
                put("series", catalog.series.toJson())
                put("movies", catalog.movies.toJson())
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
