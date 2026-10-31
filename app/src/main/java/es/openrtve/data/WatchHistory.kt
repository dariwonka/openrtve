package es.openrtve.data

import androidx.core.util.AtomicFile
import es.openrtve.domain.CatalogItem
import es.openrtve.domain.ContentKind
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class WatchEntry(
    val item: CatalogItem,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAtMillis: Long,
    /** Visto hasta el final: fuera de "Seguir viendo", pero sirve para saber cuál es el siguiente. */
    val finished: Boolean = false,
) {
    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val inProgress: Boolean get() = !finished && positionMs >= WatchHistory.MIN_POSITION_MS
}

/**
 * "Seguir viendo" local, sin cuenta: un fichero JSON con las últimas
 * reproducciones y su posición. Lo escribe el reproductor y lo lee la portada.
 *
 * El estado en memoria cambia al instante (hilo principal); el fichero se
 * escribe aparte, en [ioDispatcher] y de forma atómica, para que un guardado
 * cada pocos segundos durante la reproducción no toque el disco desde la UI.
 */
class WatchHistory(
    file: File,
    private val json: Json = Json,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val file = AtomicFile(file)
    private val mutableEntries = MutableStateFlow(load())
    val entries: StateFlow<List<WatchEntry>> = mutableEntries.asStateFlow()

    /** Vive lo que la app: el historial es un singleton del contenedor. */
    private val writer = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val writeLock = Mutex()

    fun entryFor(itemId: String): WatchEntry? = mutableEntries.value.firstOrNull { it.item.id == itemId }

    /** Registra el item antes de reproducirlo para que el progreso tenga a qué asociarse. */
    fun register(item: CatalogItem) {
        if (item.kind == ContentKind.LIVE) return
        val existing = entryFor(item.id)
        save(
            (mutableEntries.value.filterNot { it.item.id == item.id } +
                WatchEntry(item, existing?.positionMs ?: 0L, existing?.durationMs ?: 0L, nowMillis(), existing?.finished ?: false)),
        )
    }

    fun updateProgress(itemId: String, positionMs: Long, durationMs: Long) {
        val entry = entryFor(itemId) ?: return
        if (durationMs <= 0) return
        val finished = positionMs >= durationMs * FINISHED_FRACTION
        if (!finished && positionMs < MIN_POSITION_MS) return
        save(
            mutableEntries.value.filterNot { it.item.id == itemId } +
                entry.copy(
                    positionMs = if (finished) durationMs else positionMs,
                    durationMs = durationMs,
                    updatedAtMillis = nowMillis(),
                    finished = finished,
                ),
        )
    }

    /** Quita la entrada y la devuelve, para poder deshacerlo con [restore]. */
    fun remove(itemId: String): WatchEntry? {
        val removed = entryFor(itemId) ?: return null
        save(mutableEntries.value.filterNot { it.item.id == itemId })
        return removed
    }

    /** Vuelve a poner una entrada quitada, tal como estaba. */
    fun restore(entry: WatchEntry) = save(mutableEntries.value.filterNot { it.item.id == entry.item.id } + entry)

    /** Solo lo empezado y no terminado, lo más reciente primero. */
    val resumable: List<WatchEntry>
        get() = mutableEntries.value.filter { it.inProgress }.sortedByDescending { it.updatedAtMillis }

    /** Lo último visto (a medias o entero) de un programa. */
    fun lastWatchedOf(programId: String): WatchEntry? =
        mutableEntries.value.filter { it.item.programId == programId && (it.inProgress || it.finished) }
            .maxByOrNull { it.updatedAtMillis }

    private fun save(entries: List<WatchEntry>) {
        val trimmed = entries
            .filter { nowMillis() - it.updatedAtMillis < MAX_AGE_MS }
            .sortedByDescending { it.updatedAtMillis }
            .take(MAX_ENTRIES)
        mutableEntries.value = trimmed
        // Siempre se vuelca el estado más reciente: dos escrituras encoladas no pueden dejar una vieja.
        writer.launch { writeLock.withLock { persist(mutableEntries.value) } }
    }

    private fun persist(entries: List<WatchEntry>) {
        val bytes = buildJsonArray { entries.forEach { add(it.toJson()) } }.toString().toByteArray(Charsets.UTF_8)
        file.baseFile.parentFile?.mkdirs()
        val output = runCatching { file.startWrite() }.getOrNull() ?: return
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
        }
    }

    private fun load(): List<WatchEntry> = runCatching {
        if (!file.baseFile.isFile) return emptyList()
        json.parseToJsonElement(file.readFully().toString(Charsets.UTF_8)).jsonArray
            .mapNotNull { (it as? JsonObject)?.toEntry() }
            .sortedByDescending { it.updatedAtMillis }
    }.getOrDefault(emptyList())

    private fun WatchEntry.toJson(): JsonObject = buildJsonObject {
        put("id", item.id)
        item.playbackId?.let { put("playbackId", it) }
        item.assetId?.let { put("assetId", it) }
        put("title", item.title)
        item.subtitle?.let { put("subtitle", it) }
        item.imageUrl?.let { put("imageUrl", it) }
        item.posterUrl?.let { put("posterUrl", it) }
        put("kind", item.kind.name)
        item.programId?.let { put("programId", it) }
        put("drm", item.drm)
        put("paid", item.paid)
        put("positionMs", positionMs)
        put("durationMs", durationMs)
        put("updatedAt", updatedAtMillis)
        put("finished", finished)
    }

    private fun JsonObject.toEntry(): WatchEntry? {
        fun text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
        fun long(key: String) = (get(key) as? JsonPrimitive)?.longOrNull
        val id = text("id") ?: return null
        val item = CatalogItem(
            id = id,
            playbackId = text("playbackId"),
            assetId = text("assetId"),
            title = text("title") ?: return null,
            subtitle = text("subtitle"),
            imageUrl = text("imageUrl"),
            kind = runCatching { ContentKind.valueOf(text("kind").orEmpty()) }.getOrDefault(ContentKind.VIDEO),
            posterUrl = text("posterUrl"),
            allowedInCountry = null,
            loginRequired = false,
            paid = (get("paid") as? JsonPrimitive)?.booleanOrNull ?: false,
            drm = (get("drm") as? JsonPrimitive)?.booleanOrNull ?: false,
            programId = text("programId"),
        )
        return WatchEntry(
            item,
            long("positionMs") ?: 0L,
            long("durationMs") ?: 0L,
            long("updatedAt") ?: 0L,
            (get("finished") as? JsonPrimitive)?.booleanOrNull ?: false,
        )
    }

    companion object {
        private const val MAX_ENTRIES = 60
        private const val MAX_AGE_MS = 60L * 24 * 60 * 60 * 1_000
        const val MIN_POSITION_MS = 30_000L
        private const val FINISHED_FRACTION = 0.95
    }
}
