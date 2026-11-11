package es.openrtve.data

import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Copia de seguridad en un solo fichero JSON: el catálogo (listas, anclados, eliminados)
 * y lo visto (posiciones y episodios terminados). Sirve para cambiar de tele o reinstalar.
 */
class CatalogBackup(
    private val catalogFile: File,
    private val historyFile: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun export(): String = withContext(io) {
        buildJsonObject {
            put("version", VERSION)
            put("catalog", catalogFile.takeIf { it.isFile }?.readText().orEmpty())
            put("history", historyFile.takeIf { it.isFile }?.readText().orEmpty())
        }.toString()
    }

    /** Sustituye catálogo e historial por los de la copia; `false` si el fichero no es una copia válida. */
    suspend fun import(raw: String): Boolean = withContext(io) {
        runCatching {
            val root = json.parseToJsonElement(raw).jsonObject
            if ((root["version"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() != VERSION) return@runCatching false
            val catalog = root.text("catalog")
            val history = root.text("history")
            // Se comprueba que ambos son JSON antes de tocar ningún fichero.
            if (catalog.isNotEmpty()) json.parseToJsonElement(catalog).jsonObject
            if (history.isNotEmpty()) json.parseToJsonElement(history)
            if (catalog.isEmpty() && history.isEmpty()) return@runCatching false
            if (catalog.isNotEmpty()) replace(catalogFile, catalog)
            if (history.isNotEmpty()) replace(historyFile, history)
            true
        }.getOrDefault(false)
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun replace(target: File, content: String) {
        val pending = File(target.parentFile, target.name + ".restore")
        pending.writeText(content)
        if (!pending.renameTo(target)) {
            target.delete()
            pending.renameTo(target)
        }
    }

    private companion object {
        const val VERSION = 1
    }
}
