package es.openrtve.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import es.openrtve.data.CatalogRepository
import es.openrtve.data.ClanCatalogStore
import es.openrtve.data.StoredCatalog
import es.openrtve.domain.CatalogItem
import es.openrtve.domain.HomeRow
import es.openrtve.domain.ContentKind
import es.openrtve.domain.LinkKind
import es.openrtve.domain.MediaForm
import es.openrtve.domain.ProgramStructure
import es.openrtve.domain.RtveUrls
import es.openrtve.domain.isFullMovie
import es.openrtve.domain.mediaForm
import es.openrtve.domain.parentStructure
import es.openrtve.domain.referenceItem
import java.text.Collator
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Las dos listas del inicio. */
enum class ClanSection { SERIES, MOVIES }

/** Títulos únicos de Clan, cada lista en orden alfabético. */
data class ClanCatalog(
    val series: List<CatalogItem> = emptyList(),
    val movies: List<CatalogItem> = emptyList(),
) {
    val isEmpty: Boolean get() = series.isEmpty() && movies.isEmpty()

    fun titles(section: ClanSection): List<CatalogItem> = when (section) {
        ClanSection.SERIES -> series
        ClanSection.MOVIES -> movies
    }
}

/** Una fila de la portada ya descargada: su título editorial y sus piezas. */
data class LoadedRow(
    val title: String,
    val items: List<CatalogItem>,
    /** Viene de la portada de películas: lo que trae es cine aunque su título no lo diga. */
    val isMovies: Boolean = false,
)

/**
 * Reúne las filas editoriales de la portada de Clan ("Princesas", "Para descubrir
 * dinosaurios"...) en dos listas planas de títulos únicos:
 *
 * - **Series**: cada programa una sola vez, venga como programa o como uno de
 *   sus capítulos (un capítulo aporta su serie, no a sí mismo);
 * - **Películas**: los vídeos que RTVE clasifica como película, las piezas de un
 *   contenedor de cine y lo que venga en una fila titulada como películas o cine.
 *
 * Las filas dejan de existir: solo importa qué títulos contienen.
 */
fun buildClanCatalog(rows: List<LoadedRow>, sorted: Boolean = true, byDuration: Boolean = true): ClanCatalog {
    val programs = linkedMapOf<String, CatalogItem>()
    val fromEpisodes = linkedMapOf<String, CatalogItem>()
    val movies = linkedMapOf<String, CatalogItem>()
    for (row in rows) {
        val movieRow = row.isMovies || row.title.isMovieRowTitle()
        for (item in row.items) {
            when (item.kind) {
                ContentKind.PROGRAM -> (if (movieRow) movies else programs).addTitle(item.copy(subtitle = null))
                ContentKind.VIDEO -> when {
                    item.mediaForm == MediaForm.EXTRA -> item.seriesTitle()?.let(fromEpisodes::addTitle)
                    // Un vídeo suelto de 58 minutos o más es una película (en la descarga decide la revisión de cada serie).
                    movieRow || item.isFullMovie || item.parentStructure == ProgramStructure.MOVIES ||
                        (byDuration && (item.durationMs ?: 0L) >= MOVIE_MIN_MS) ->
                        movies.addTitle(item.copy(subtitle = null))
                    else -> item.seriesTitle()?.let(fromEpisodes::addTitle)
                }
                else -> Unit
            }
        }
    }
    // La imagen del programa manda sobre la de uno de sus capítulos.
    fromEpisodes.values.forEach(programs::addTitle)
    // Lo que una fila presenta como película no se repite entre las series.
    movies.keys.forEach(programs::remove)
    return if (sorted) ClanCatalog(series = programs.values.alphabetical(), movies = movies.values.alphabetical())
    else ClanCatalog(series = programs.values.toList(), movies = movies.values.toList())
}

/** Duración a partir de la cual un vídeo cuenta como película. */
const val MOVIE_MIN_MS = 58L * 60_000L

/** Por debajo de esto un vídeo es un avance o un corte: no se enseña. */
const val EPISODE_MIN_MS = 4L * 60_000L

/**
 * El título del catálogo que corresponde a un resultado cualquiera: el propio programa,
 * la película, o la serie a la que pertenece un capítulo. `null` si no encaja (un avance...).
 */
fun catalogEntryOf(item: CatalogItem): CatalogItem? =
    buildClanCatalog(listOf(LoadedRow("", listOf(item)))).let { it.movies.firstOrNull() ?: it.series.firstOrNull() }

/** Serie a la que pertenece un capítulo, como título propio; `null` si el feed no la nombra. */
private fun CatalogItem.seriesTitle(): CatalogItem? {
    val id = programId ?: return null
    val name = subtitle?.takeIf { it.isNotBlank() } ?: return null
    if (parentStructure.isCollection) return null
    return referenceItem(id, ContentKind.PROGRAM, name, imageUrl)
}

/** El primero que llega se queda; los siguientes solo aportan la imagen que le falte. */
private fun MutableMap<String, CatalogItem>.addTitle(item: CatalogItem) {
    val known = this[item.id]
    this[item.id] = known?.copy(imageUrl = known.imageUrl ?: item.imageUrl, posterUrl = known.posterUrl ?: item.posterUrl) ?: item
}

private fun String.isMovieRowTitle(): Boolean {
    val plain = Normalizer.normalize(lowercase(), Normalizer.Form.NFD).replace(DIACRITICS, "")
    return "pelicula" in plain || Regex("\\bcine\\b").containsMatchIn(plain)
}

/** Orden de diccionario en español: sin distinguir mayúsculas ni tildes, ni contar los signos de apertura. */
private fun Collection<CatalogItem>.alphabetical(): List<CatalogItem> {
    val collator = Collator.getInstance(Locale.forLanguageTag("es-ES")).apply { strength = Collator.PRIMARY }
    return sortedWith { a, b -> collator.compare(a.sortKey(), b.sortKey()) }
}

private fun CatalogItem.sortKey(): String = title.trimStart { !it.isLetterOrDigit() }

private val DIACRITICS = Regex("\\p{Mn}+")

/** Un título eliminado a mano; [name] es `null` si no se conoce. */
data class RemovedTitle(val id: String, val name: String?)

data class ClanCatalogUiState(
    val catalog: ClanCatalog = ClanCatalog(),
    val removed: List<RemovedTitle> = emptyList(),
    /** Ids anclados, para el menú (Anclar / Desanclar). */
    val pinnedIds: Set<String> = emptySet(),
    /** Ids de todo lo que ya está en la base de datos (series y películas). */
    val knownIds: Set<String> = emptySet(),
    /** Hay algo definitivo que enseñar: hasta entonces se ve la pantalla de inicio. */
    val ready: Boolean = false,
    val isLoading: Boolean = true,
    /** Solo si no hay ningún título que enseñar. */
    val error: LoadError? = null,
)

/** Sin copia guardada, si el catálogo tarda más en descargarse se enseña lo que haya. */
private const val READY_TIMEOUT_MS = 8_000L

/** Títulos que se ven al abrir (tres filas de cuatro): con copia guardada no cambian durante la sesión. */
const val CATALOG_HEAD_SIZE = 12
private const val BASELINE_KEY = "#baseline"

/** Lo que hay dentro de una serie. */
private class ProgramScan(
    val programId: String,
    /** Su único vídeo, si dura 58 minutos o más: la serie es en realidad una película. */
    val film: CatalogItem?,
)

/** Lo que trae una vuelta de descargas. */
private class Download(
    /** Con la lista fija incluida. */
    val all: ClanCatalog,
    /** Solo lo que ha entregado RTVE ahora. */
    val network: ClanCatalog,
    /** Lo destacado en las portadas de Clan, en su orden. */
    val highlights: List<CatalogItem>,
    /** Todas las fuentes principales respondieron. */
    val complete: Boolean,
)

/**
 * Catálogo del inicio. Primero pinta la copia guardada ([ClanCatalogStore]), sin red,
 * y después lo rehace en segundo plano, siempre **sumando**: lo que ya se conocía no se
 * borra aunque una fuente falle o RTVE deje de ofrecerlo. Solo se quita lo que el
 * usuario elimina a mano; eso no vuelve hasta que RTVE lo retire y lo vuelva a añadir.
 *
 * - el listado A-Z de la cadena Clan aporta sus series;
 * - las portadas de Clan (principal, tres edades y películas) aportan las películas y
 *   las series que el listado no traiga;
 * - los favoritos y los títulos nuevos (carrusel) viven en la misma copia;
 * - las películas que haya dentro de una serie salen sueltas en Películas ([scanForFilms]).
 */
class ClanCatalogViewModel(
    private val repository: CatalogRepository,
    private val store: ClanCatalogStore? = null,
    /** Programas infantiles de RTVE (género Infantil) que se suman al catálogo de Clan. */
    private val seed: List<CatalogItem> = emptyList(),
    /** Películas infantiles conocidas, para tenerlas desde el primer segundo. */
    private val movieSeed: List<CatalogItem> = emptyList(),
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(ClanCatalogUiState())
    val uiState: StateFlow<ClanCatalogUiState> = mutableUiState.asStateFlow()
    private var job: Job? = null
    private var scanJob: Job? = null
    private var stored = StoredCatalog(emptyList(), emptyList())
    private var started = false
    /** Los primeros títulos de cada lista, fijos tras abrir con copia guardada (las novedades entran al siguiente arranque). */
    private var headSeries: List<String>? = null
    private var headMovies: List<String>? = null
    /** Todo título visto alguna vez (fijos y descargados), para dar nombre a los eliminados. */
    private val known = mutableMapOf<String, CatalogItem>()

    init {
        (seed + movieSeed).forEach { known[it.id] = it }
        load()
        viewModelScope.launch {
            delay(READY_TIMEOUT_MS)
            markReady()
        }
    }

    fun retry() = load()

    /** Quita un título de las listas y de favoritos; no vuelve salvo que RTVE lo retire y lo reponga. */
    fun remove(result: CatalogItem) {
        val item = entryFor(result) ?: return
        stored = stored.copy(
            series = stored.series.filter { it.id != item.id },
            movies = stored.movies.filter { it.id != item.id },
            removed = stored.removed + (item.id to false),
            removedItems = stored.removedItems.filter { it.id != item.id } + item,
            pinned = stored.pinned - item.id,
            pinnedFresh = stored.pinnedFresh - item.id,
        )
        persist()
        publish()
    }

    /** Vuelve a mostrar un título eliminado; si no se tiene entero, vuelve en la próxima descarga. */
    fun restore(id: String) {
        unlockHead()
        val item = stored.removedItems.firstOrNull { it.id == id } ?: known[id]
        stored = stored.copy(
            removed = stored.removed - id,
            removedItems = stored.removedItems.filter { it.id != id },
            series = if (item != null && item.kind != ContentKind.VIDEO) (stored.series.filter { it.id != id } + item).alphabetical() else stored.series,
            movies = if (item != null && item.kind == ContentKind.VIDEO) (stored.movies.filter { it.id != id } + item).alphabetical() else stored.movies,
        )
        persist()
        publish()
        if (item == null) load()
    }

    /**
     * Ancla o desancla un título. Lo desanclado a mano se recuerda: aunque RTVE lo vuelva
     * a poner como novedad, aquí no se ancla otra vez.
     */
    fun togglePin(result: CatalogItem) {
        val item = entryFor(result) ?: return
        unlockHead()
        addEntry(item)
        stored = if (item.id in stored.pinned) {
            stored.copy(pinned = stored.pinned - item.id, pinnedFresh = stored.pinnedFresh - item.id, unpinned = stored.unpinned + item.id)
        } else {
            stored.copy(pinned = stored.pinned + item.id, unpinned = stored.unpinned - item.id)
        }
        persist()
        publish()
    }

    /** Lo que ya está en la base de datos se usa tal cual; si no (un resultado del buscador), se deduce. */
    private fun entryFor(item: CatalogItem): CatalogItem? =
        if (stored.series.any { it.id == item.id } || stored.movies.any { it.id == item.id }) item else catalogEntryOf(item)

    private fun addEntry(entry: CatalogItem) {
        if (stored.series.any { it.id == entry.id } || stored.movies.any { it.id == entry.id }) return
        val isMovie = entry.kind == ContentKind.VIDEO
        stored = stored.copy(
            series = if (isMovie) stored.series else (stored.series + entry).alphabetical(),
            movies = if (isMovie) (stored.movies + entry).alphabetical() else stored.movies,
            removed = stored.removed - entry.id,
            removedItems = stored.removedItems.filter { it.id != entry.id },
        )
        known[entry.id] = entry
    }

    /** Añade a la lista un resultado del buscador que aún no está en la base de datos. */
    fun add(result: CatalogItem) {
        val entry = entryFor(result) ?: return
        unlockHead()
        addEntry(entry)
        persist()
        publish()
    }

    /** El reproductor puede haber eliminado un título; al volver a la lista se refleja. */
    /** Sustituye todo lo que se muestra por lo guardado (tras restaurar una copia de seguridad). */
    fun reloadFromStore() {
        viewModelScope.launch {
            stored = store?.read() ?: return@launch
            unlockHead()
            publish()
        }
    }

    fun syncFromStore() {
        viewModelScope.launch {
            val saved = store?.read() ?: return@launch
            val gone = saved.removed.keys - stored.removed.keys
            if (gone.isEmpty()) return@launch
            stored = stored.copy(
                removedItems = stored.removedItems.filter { it.id !in gone } + saved.removedItems.filter { it.id in gone },
                series = stored.series.filter { it.id !in gone },
                movies = stored.movies.filter { it.id !in gone },
                removed = stored.removed + saved.removed.filterKeys { it in gone },
                pinned = stored.pinned - gone,
                pinnedFresh = stored.pinnedFresh - gone,
            )
            publish()
        }
    }

    private fun persist() {
        val snapshot = stored
        viewModelScope.launch { store?.write(snapshot) }
    }

    /** Primero los anclados (las novedades de RTVE recién llegadas delante, el resto por orden alfabético) y luego el resto. */
    private fun List<CatalogItem>.pinnedFirst(): List<CatalogItem> {
        val (pinned, rest) = partition { it.id in stored.pinned }
        val (fresh, older) = pinned.partition { it.id in stored.pinnedFresh }
        return fresh.sortedByDescending { stored.pinnedFresh[it.id] } + older + rest
    }

    /** Mantiene en su sitio los títulos de cabecera fijados; lo nuevo va detrás. */
    private fun List<CatalogItem>.keepingHead(head: List<String>?): List<CatalogItem> {
        if (head == null) return this
        val byId = associateBy { it.id }
        val first = head.mapNotNull { byId[it] }
        val taken = first.mapTo(HashSet()) { it.id }
        return first + filter { it.id !in taken }
    }

    private fun lockHead() {
        val current = mutableUiState.value.catalog
        headSeries = current.series.take(CATALOG_HEAD_SIZE).map { it.id }
        headMovies = current.movies.take(CATALOG_HEAD_SIZE).map { it.id }
    }

    /** Una acción del usuario (anclar, añadir, restaurar) reordena en el acto. */
    private fun unlockHead() {
        headSeries = null
        headMovies = null
    }

    private fun publish() {
        val catalog = ClanCatalog(stored.series.pinnedFirst().keepingHead(headSeries), stored.movies.pinnedFirst().keepingHead(headMovies))
        val removedTitles = stored.removed.keys.map { id ->
            RemovedTitle(id, (stored.removedItems.firstOrNull { it.id == id } ?: known[id])?.title)
        }.sortedWith(compareBy({ it.name == null }, { it.name?.lowercase() }))
        mutableUiState.update {
            it.copy(catalog = catalog, pinnedIds = stored.pinned, knownIds = (stored.series + stored.movies).map { t -> t.id }.toSet(), removed = removedTitles)
        }
    }

    private fun markReady() = mutableUiState.update { it.copy(ready = true) }

    private fun load() {
        job?.cancel()
        scanJob?.cancel()
        job = viewModelScope.launch {
            mutableUiState.update { it.copy(isLoading = it.catalog.isEmpty, error = null) }
            if (!started) {
                started = true
                val saved = store?.read()
                stored = saved ?: buildClanCatalog(listOf(LoadedRow(SEED_ROW_TITLE, seed), LoadedRow(SEED_ROW_TITLE, movieSeed, true)))
                    .let { StoredCatalog(it.series, it.movies) }
                if (saved != null) {
                    // Lo quitado a mano no se deja ver aunque la copia lo traiga.
                    stored = stored.copy(
                        series = stored.series.filter { it.id !in stored.removed },
                        movies = stored.movies.filter { it.id !in stored.removed },
                    )
                }
                publish()
                if (!mutableUiState.value.catalog.isEmpty) mutableUiState.update { it.copy(isLoading = false) }
                if (saved != null && !mutableUiState.value.catalog.isEmpty) {
                    // Hay una copia: se enseña ya, con las primeras filas definitivas; la descarga solo sumará detrás.
                    lockHead()
                    markReady()
                }
            }
            try {
                val result = download()
                merge(result)
                mutableUiState.update { it.copy(isLoading = false, ready = true) }
                scanJob = viewModelScope.launch { scanForFilms() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Con algo en pantalla, un fallo de red no se enseña.
                mutableUiState.update { it.copy(isLoading = false, ready = true, error = error.toLoadError().takeIf { _ -> it.catalog.isEmpty }) }
            }
        }
    }

    /** Suma lo descargado a lo que ya había; nunca resta. */
    private fun merge(result: Download) {
        val before = stored
        val netIds = (result.network.series + result.network.movies).map { it.id }.toSet()
        val removed = before.removed.toMutableMap()
        for ((id, gone) in before.removed) {
            when {
                gone && id in netIds -> removed.remove(id) // RTVE lo ha vuelto a ofrecer
                !gone && result.complete && id !in netIds -> removed[id] = true
            }
        }
        fun mergeList(old: List<CatalogItem>, fresh: List<CatalogItem>, freshOther: List<CatalogItem>): List<CatalogItem> {
            val otherIds = freshOther.map { it.id }.toSet()
            val merged = linkedMapOf<String, CatalogItem>()
            old.filter { it.id !in otherIds }.forEach { merged[it.id] = it }
            for (item in fresh) {
                val known = merged[item.id]
                merged[item.id] = known?.let { item.copy(imageUrl = item.imageUrl ?: it.imageUrl, posterUrl = item.posterUrl ?: it.posterUrl) } ?: item
            }
            return merged.values.filter { it.id !in removed }.alphabetical()
        }
        val series = mergeList(before.series, result.all.series, result.all.movies).filter { it.id !in before.filmOnly }
        val movies = mergeList(before.movies, result.all.movies, result.all.series)
        val firstSeen = before.firstSeen.toMutableMap()
        if (result.complete) {
            val baselineDone = BASELINE_KEY in firstSeen
            val now = clock()
            for (item in series + movies) {
                if (item.id !in firstSeen) firstSeen[item.id] = if (baselineDone && item.id in netIds) now else 0L
            }
            firstSeen[BASELINE_KEY] = 1L
        }
        (result.all.series + result.all.movies).forEach { known[it.id] = it }
        // RTVE ancla sus novedades: lo recién descubierto y lo destacado en sus portadas. Lo desanclado a mano no vuelve.
        val inCatalog = (series + movies).associateBy { it.id }
        val pinned = before.pinned.toMutableSet()
        val fresh = before.pinnedFresh.toMutableMap()
        val baselineBefore = BASELINE_KEY in before.firstSeen
        val now = clock()
        val offered = inCatalog.values.filter { (firstSeen[it.id] ?: 0L) > 0L }.map { it.id } +
            result.highlights.map { it.id }.filter { it in inCatalog }
        for (id in offered) {
            if (id in pinned || id in before.unpinned) continue
            pinned += id
            // En el primer arranque no se sabe qué es nuevo: se anclan, pero sin ir delante.
            if (baselineBefore) fresh[id] = now
        }
        stored = StoredCatalog(
            series, movies, removed,
            pinned.filter { it in inCatalog }.toSet(),
            fresh.filterKeys { it in inCatalog },
            before.unpinned,
            firstSeen,
            removedItems = before.removedItems.filter { it.id in removed },
            scanned = before.scanned,
            filmOnly = before.filmOnly,
        )
        if (stored != before) persist()
        publish()
    }

    /**
     * Mira dentro de cada serie. Una serie con varios vídeos se queda siempre como serie,
     * dure lo que dure cada uno. Solo si tiene un único vídeo y dura 58 minutos o más es
     * una película: pasa a Películas y deja de salir entre las series. Cada serie se revisa
     * de nuevo cada pocos días, por si cambia.
     */
    private suspend fun scanForFilms() {
        val now = clock()
        val due = (stored.series.filter { it.kind == ContentKind.PROGRAM }.map { it.id } + stored.filmOnly)
            .distinct()
            .filter { id -> stored.scanned[id]?.let { now - it !in 0 until RESCAN_MS } ?: true }
            .sortedBy { stored.scanned[it] ?: 0L }
        val permits = Semaphore(MAX_PARALLEL_SCANS)
        for (batch in due.chunked(SCAN_BATCH)) {
            val results = coroutineScope { batch.map { id -> async { permits.withPermit { scanProgram(id) } } }.awaitAll() }.filterNotNull()
            if (results.isNotEmpty()) applyScan(results, now)
        }
    }

    /** `null` si RTVE no responde: se reintenta en el próximo arranque. */
    private suspend fun scanProgram(programId: String): ProgramScan? = try {
        val shown = mutableListOf<CatalogItem>()
        var page = 1
        var more: Boolean
        do {
            val result = repository.scanProgramVideos(programId, page)
            for (video in result.items) {
                if (video.kind != ContentKind.VIDEO || video.mediaForm == MediaForm.EXTRA) continue
                // Los de menos de 4 minutos (avances, cortes) no cuentan como vídeos de la serie.
                if ((video.durationMs ?: EPISODE_MIN_MS) >= EPISODE_MIN_MS) shown += video
            }
            more = result.hasMore && result.items.isNotEmpty()
            page++
        } while (more && page <= MAX_SCAN_PAGES)
        val only = shown.distinctBy { it.id }.singleOrNull()
        // Si quedan páginas sin mirar no se puede afirmar que sea el único.
        ProgramScan(programId, film = only?.takeIf { !more && (it.durationMs ?: 0L) >= MOVIE_MIN_MS })
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        null
    }

    private fun applyScan(results: List<ProgramScan>, now: Long) {
        val before = stored
        var series = before.series
        var movies = before.movies
        var filmOnly = before.filmOnly
        val pinned = before.pinned.toMutableSet()
        val fresh = before.pinnedFresh.toMutableMap()
        val scanned = before.scanned.toMutableMap()
        for (scan in results) {
            scanned[scan.programId] = now
            val program = series.firstOrNull { it.id == scan.programId } ?: known[scan.programId]
            // Lo que una revisión anterior sacó de esta serie se quita siempre: se vuelve a poner solo si sigue valiendo.
            movies = movies.filter { it.programId != scan.programId || it.id == scan.film?.id }
            val film = scan.film?.takeIf { it.id !in before.removed }
            if (film != null) {
                val entry = film.asFilmOf(program?.title)
                known[entry.id] = entry
                movies = movies.filter { it.id != entry.id } + entry
                // Si la serie estaba anclada, lo anclado pasa a ser su película.
                if (pinned.remove(scan.programId)) {
                    val at = fresh.remove(scan.programId)
                    if (entry.id !in before.unpinned) {
                        pinned += entry.id
                        if (at != null) fresh[entry.id] = at
                    }
                }
                series = series.filter { it.id != scan.programId }
                filmOnly = filmOnly + scan.programId
            } else if (scan.programId in filmOnly) {
                // Ya no es una sola película: vuelve a ser una serie.
                filmOnly = filmOnly - scan.programId
                if (program != null && program.id !in before.removed && series.none { it.id == program.id }) series = series + program
            }
        }
        stored = before.copy(
            series = if (series == before.series) series else series.alphabetical(),
            movies = if (movies == before.movies) movies else movies.alphabetical(),
            pinned = pinned,
            pinnedFresh = fresh,
            scanned = scanned,
            filmOnly = filmOnly,
        )
        persist()
        if (stored.series != before.series || stored.movies != before.movies || stored.pinned != before.pinned) publish()
    }

    /** Una película sacada de una serie se titula "Serie - E2 · Título": primero de dónde viene. */
    private fun CatalogItem.asFilmOf(series: String?): CatalogItem {
        val number = episode?.takeUnless { Regex("\\b$it\\b").containsMatchIn(title) }
        val own = if (number != null) "E$number · $title" else title
        return copy(
            subtitle = null,
            title = if (series.isNullOrBlank() || title.startsWith(series, ignoreCase = true)) own else "$series - $own",
        )
    }

    /** Lanza el primer fallo solo si ninguna fuente ha dado títulos. */
    private suspend fun download(): Download = coroutineScope {
        var firstFailure: Exception? = null
        suspend fun <T> orNull(block: suspend () -> T): T? = try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (firstFailure == null) firstFailure = error
            null
        }
        // Las fuentes extra de películas no cuentan como fallo: el catálogo ya tiene su copia fija.
        suspend fun <T> quiet(block: suspend () -> T): T? = try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            null
        }
        // Películas: la lista de RTVE y todos los vídeos de sus programas de cine para niños.
        val topMovies = async {
            quiet {
                repository.loadModule(
                    HomeRow(id = -1, title = SEED_ROW_TITLE, order = 0, moduleType = null, presentation = null, contentUrl = RtveUrls.CLAN_MOVIES_TOPS),
                ).value.items
            }.orEmpty()
        }
        val containerMovies = RtveUrls.CLAN_MOVIE_PROGRAMS.map { programId ->
            async {
                quiet {
                    val all = mutableListOf<CatalogItem>()
                    var page = 1
                    do {
                        val result = repository.loadProgramVideos(programId, page = page).value
                        all += result.items
                        page++
                    } while (result.hasMore && page <= MAX_MOVIE_PAGES)
                    all.toList()
                }.orEmpty()
            }
        }
        val kidsCollection = async {
            quiet {
                repository.loadModule(
                    HomeRow(id = -2, title = SEED_ROW_TITLE, order = 0, moduleType = null, presentation = null, contentUrl = RtveUrls.KIDS_MOVIES_COLLECTION),
                ).value.items
            }.orEmpty()
        }
        val listing = async { orNull { repository.loadChannelPrograms(RtveUrls.CLAN_CHANNEL_ID).value } }
        // La portada de Clan, sus tres franjas de edad y la de películas, a la vez.
        val portadas = listOf(RtveUrls.CLAN_HOME to false) + RtveUrls.CLAN_AGE_PORTADAS.map { it to false } + (RtveUrls.CLAN_MOVIES_PORTADA to true)
        val feeds = portadas
            .map { (url, isMovies) -> async { orNull { repository.loadPortada(url).value }?.let { Triple(url, it, isMovies) } } }
            .awaitAll()
            .filterNotNull()
        // Los accesos directos de una fila `links` ya vienen en la portada.
        val linked = feeds.flatMap { (_, feed, isMovies) ->
            feed.rows.filter { it.isInline }.map { row ->
                LoadedRow(
                    row.title,
                    row.links.filter { it.kind == LinkKind.PROGRAM }.mapNotNull { link ->
                        link.apiId?.let { referenceItem(it, ContentKind.PROGRAM, link.title, link.imageUrl) }
                    },
                    isMovies,
                )
            }
        }
        // Las portadas comparten colecciones: cada una se descarga una vez, y es de cine
        // si alguna de las que la enseñan es la de películas.
        val remote = feeds
            .flatMap { (_, feed, isMovies) -> feed.rows.filter { !it.isInline && !it.isLive && it.contentUrl != null }.map { it to isMovies } }
            .groupBy({ it.first.contentUrl }, { it })
            .values
            .map { same -> same.first().first to same.any { it.second } }
        val permits = Semaphore(MAX_PARALLEL_ROWS)
        val loadedPairs = remote
            .map { (row, isMovies) ->
                async { permits.withPermit { orNull { row.contentUrl to LoadedRow(row.title, repository.loadModule(row).value.items, isMovies) } } }
            }
            .awaitAll()
            .filterNotNull()
        val loaded = loadedPairs.map { it.second }
        val byUrl = loadedPairs.toMap()
        // Lo destacado: las dos primeras filas de la portada de Clan y de la de películas.
        val highlightRows = feeds
            .filter { (url, _, _) -> url == RtveUrls.CLAN_HOME || url == RtveUrls.CLAN_MOVIES_PORTADA }
            .flatMap { (_, feed, _) ->
                feed.rows.filter { !it.isInline && !it.isLive && it.contentUrl != null }.take(2).mapNotNull { byUrl[it.contentUrl] }
            }
        val highlighted = buildClanCatalog(highlightRows, sorted = false, byDuration = false).let { it.series + it.movies }
        val seedRows = listOf(LoadedRow(SEED_ROW_TITLE, seed), LoadedRow(SEED_ROW_TITLE, movieSeed, true))
        val networkRows = listOf(LoadedRow(SEED_ROW_TITLE, topMovies.await(), true), LoadedRow(SEED_ROW_TITLE, kidsCollection.await(), true)) +
            containerMovies.map { LoadedRow(SEED_ROW_TITLE, it.await(), true) } + linked + loaded +
            // El listado va al final: la imagen y el nombre de una portada mandan sobre los suyos.
            LoadedRow(AZ_ROW_TITLE, listing.await().orEmpty())
        val all = buildClanCatalog(seedRows + networkRows, byDuration = false)
        val network = buildClanCatalog(networkRows, byDuration = false)
        val failure = firstFailure
        if (all.isEmpty && failure != null) throw failure
        Download(all, network, highlighted, complete = failure == null)
    }

    private companion object {
        const val MAX_PARALLEL_ROWS = 6
        const val MAX_MOVIE_PAGES = 8
        const val MAX_PARALLEL_SCANS = 4
        const val SCAN_BATCH = 16
        /** Páginas de 60 vídeos: hasta 720 por serie. */
        const val MAX_SCAN_PAGES = 12
        const val RESCAN_MS = 3L * 24 * 60 * 60 * 1_000
        /** Nombre interno de la "fila" del listado A-Z; no es de películas. */
        const val AZ_ROW_TITLE = "A-Z"
        const val SEED_ROW_TITLE = "Infantil"
    }
}
