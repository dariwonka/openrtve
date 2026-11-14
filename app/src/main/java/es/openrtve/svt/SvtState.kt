package es.openrtve.svt

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Favoritos de SVT Barn, con las mismas reglas que Series y Películas: lo último que se ve se ancla
 * y sube el primero, los programas nuevos de SVT se anclan solos delante, y solo el usuario desancla.
 */
data class SvtState(
    /** Ya se hizo la primera descarga de la lista: los programas que había entonces no son novedades. */
    val initialized: Boolean = false,
    /** Programas que la app ya ha visto en la lista de SVT. */
    val known: Set<String> = emptySet(),
    val pinned: Set<String> = emptySet(),
    /** Anclados recientes (visto o novedad): van los primeros, el más reciente antes. */
    val pinnedFresh: Map<String, Long> = emptyMap(),
    /** Desanclados a mano: no se vuelven a anclar solos por ser novedad. */
    val unpinned: Set<String> = emptySet(),
) {
    /** Una lista nueva de SVT: lo que no se conocía se ancla delante (salvo en la primera descarga). */
    fun discover(ids: Collection<String>, now: Long): SvtState {
        if (!initialized) return copy(initialized = true, known = ids.toSet())
        val added = ids.filter { it !in known }
        if (added.isEmpty()) return this
        val pins = added.filter { it !in unpinned }
        return copy(known = known + added, pinned = pinned + pins, pinnedFresh = pinnedFresh + pins.associateWith { now })
    }

    /** Lo que se acaba de ver se ancla (aunque se hubiera desanclado) y pasa a ser el primero. */
    fun watched(id: String, now: Long) =
        copy(pinned = pinned + id, pinnedFresh = pinnedFresh + (id to now), unpinned = unpinned - id)

    /** Anclar o desanclar a mano. */
    fun toggled(id: String): SvtState =
        if (id in pinned) copy(pinned = pinned - id, pinnedFresh = pinnedFresh - id, unpinned = unpinned + id)
        else copy(pinned = pinned + id, unpinned = unpinned - id)

    /** Recientes primero (el último, delante), luego el resto de anclados y después los demás, por orden alfabético. */
    fun order(programs: List<SvtProgram>): List<SvtProgram> {
        val byTitle = compareBy<SvtProgram> { it.title.lowercase() }
        val fresh = programs.filter { it.id in pinned && it.id in pinnedFresh }
            .sortedWith(compareByDescending<SvtProgram> { pinnedFresh[it.id] }.then(byTitle))
        val others = programs.filter { it.id in pinned && it.id !in pinnedFresh }.sortedWith(byTitle)
        val rest = programs.filter { it.id !in pinned }.sortedWith(byTitle)
        return fresh + others + rest
    }

    fun toJson(): String = buildJsonObject {
        put("initialized", initialized)
        put("known", buildJsonArray { known.sorted().forEach { add(JsonPrimitive(it)) } })
        put("pinned", buildJsonArray { pinned.sorted().forEach { add(JsonPrimitive(it)) } })
        put("unpinned", buildJsonArray { unpinned.sorted().forEach { add(JsonPrimitive(it)) } })
        put("pinnedFresh", buildJsonObject { pinnedFresh.forEach { (k, v) -> put(k, v) } })
    }.toString()

    companion object {
        fun fromJson(raw: String): SvtState = runCatching {
            val o = Json.parseToJsonElement(raw).jsonObject
            fun set(name: String) = (o[name] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
            SvtState(
                initialized = (o["initialized"] as? JsonPrimitive)?.content == "true",
                known = set("known"),
                pinned = set("pinned"),
                unpinned = set("unpinned"),
                pinnedFresh = (o["pinnedFresh"] as? JsonObject).orEmpty().mapNotNull { (k, v) -> (v as? JsonPrimitive)?.longOrNull?.let { k to it } }.toMap(),
            )
        }.getOrDefault(SvtState())
    }
}

/** El estado de SVT en un archivo, con cambios atómicos entre sí. */
class SvtStateStore(private val file: File) {
    private val lock = Mutex()

    suspend fun read(): SvtState = withContext(Dispatchers.IO) {
        if (file.exists()) SvtState.fromJson(runCatching { file.readText() }.getOrDefault("")) else SvtState()
    }

    suspend fun update(change: (SvtState) -> SvtState): SvtState = lock.withLock {
        withContext(Dispatchers.IO) {
            val current = if (file.exists()) SvtState.fromJson(runCatching { file.readText() }.getOrDefault("")) else SvtState()
            val next = change(current)
            if (next != current) {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(next.toJson())
                if (!tmp.renameTo(file)) { file.writeText(next.toJson()); tmp.delete() }
            }
            next
        }
    }
}
