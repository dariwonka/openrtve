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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Las dos listas del inicio. */
enum class ClanSection { SERIES, MOVIES, FAVORITES }

/** Títulos únicos de Clan, cada lista en orden alfabético. */
data class ClanCatalog(
    val series: List<CatalogItem> = emptyList(),
    val movies: List<CatalogItem> = emptyList(),
) {
    val isEmpty: Boolean get() = series.isEmpty() && movies.isEmpty()

    fun titles(section: ClanSection): List<CatalogItem> = when (section) {
        ClanSection.SERIES -> series
        ClanSection.MOVIES -> movies
        ClanSection.FAVORITES -> emptyList()
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
fun buildClanCatalog(rows: List<LoadedRow>, sorted: Boolean = true): ClanCatalog {
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
                    movieRow || item.isFullMovie || item.parentStructure == ProgramStructure.MOVIES ->
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

data class ClanCatalogUiState(
    val catalog: ClanCatalog = ClanCatalog(),
    /** Favoritos (series y películas), en orden alfabético. */
    val favorites: List<CatalogItem> = emptyList(),
    val favoriteIds: Set<String> = emptySet(),
    /** Carrusel de novedades: como mucho [NOVELTIES_MAX], menos si no hay tantas. */
    val novelties: List<CatalogItem> = emptyList(),
    val isLoading: Boolean = true,
    /** Solo si no hay ningún título que enseñar. */
    val error: LoadError? = null,
)

const val NOVELTIES_MAX = 10
private const val NOVELTY_WINDOW_MS = 30L * 24 * 60 * 60 * 1000
private const val BASELINE_KEY = "#baseline"

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
 * - los favoritos y los títulos nuevos (carrusel) viven en la misma copia.
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
    private var stored = StoredCatalog(emptyList(), emptyList())
    private var highlights: List<CatalogItem> = emptyList()
    private var started = false

    init {
        load()
    }

    fun retry() = load()

    /** Quita un título de las listas y de favoritos; no vuelve salvo que RTVE lo retire y lo reponga. */
    fun remove(item: CatalogItem) {
        stored = stored.copy(
            series = stored.series.filter { it.id != item.id },
            movies = stored.movies.filter { it.id != item.id },
            removed = stored.removed + (item.id to false),
            favorites = stored.favorites - item.id,
        )
        persist()
        publish()
    }

    fun toggleFavorite(item: CatalogItem) {
        stored = stored.copy(favorites = if (item.id in stored.favorites) stored.favorites - item.id else stored.favorites + item.id)
        persist()
        publish()
    }

    /** El reproductor puede haber eliminado un título; al volver a la lista se refleja. */
    fun syncFromStore() {
        viewModelScope.launch {
            val saved = store?.read() ?: return@launch
            val gone = saved.removed.keys - stored.removed.keys
            if (gone.isEmpty()) return@launch
            stored = stored.copy(
                series = stored.series.filter { it.id !in gone },
                movies = stored.movies.filter { it.id !in gone },
                removed = stored.removed + saved.removed.filterKeys { it in gone },
                favorites = stored.favorites - gone,
            )
            publish()
        }
    }

    private fun persist() {
        val snapshot = stored
        viewModelScope.launch { store?.write(snapshot) }
    }

    private fun publish() {
        val catalog = ClanCatalog(stored.series, stored.movies)
        val all = (stored.series + stored.movies).associateBy { it.id }
        val favorites = stored.favorites.mapNotNull(all::get).alphabetical()
        val cutoff = clock() - NOVELTY_WINDOW_MS
        val recent = all.values
            .filter { (stored.firstSeen[it.id] ?: 0L) > cutoff }
            .sortedWith(compareByDescending<CatalogItem> { stored.firstSeen[it.id] ?: 0L }.thenBy { it.title })
        val featured = highlights.mapNotNull { all[it.id] }
        val novelties = (recent + featured).distinctBy { it.id }.take(NOVELTIES_MAX)
        mutableUiState.update {
            it.copy(catalog = catalog, favorites = favorites, favoriteIds = stored.favorites, novelties = novelties)
        }
    }

    private fun load() {
        job?.cancel()
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
            }
            try {
                val result = download()
                merge(result)
                mutableUiState.update { it.copy(isLoading = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Con algo en pantalla, un fallo de red no se enseña.
                mutableUiState.update { it.copy(isLoading = false, error = error.toLoadError().takeIf { _ -> it.catalog.isEmpty }) }
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
        val series = mergeList(before.series, result.all.series, result.all.movies)
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
        stored = StoredCatalog(series, movies, removed, before.favorites.filter { id -> series.any { it.id == id } || movies.any { it.id == id } }.toSet(), firstSeen)
        highlights = result.highlights
        if (stored != before) persist()
        publish()
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
        val highlighted = buildClanCatalog(highlightRows, sorted = false).let { it.series + it.movies }
        val seedRows = listOf(LoadedRow(SEED_ROW_TITLE, seed), LoadedRow(SEED_ROW_TITLE, movieSeed, true))
        val networkRows = listOf(LoadedRow(SEED_ROW_TITLE, topMovies.await(), true)) +
            containerMovies.map { LoadedRow(SEED_ROW_TITLE, it.await(), true) } + linked + loaded +
            // El listado va al final: la imagen y el nombre de una portada mandan sobre los suyos.
            LoadedRow(AZ_ROW_TITLE, listing.await().orEmpty())
        val all = buildClanCatalog(seedRows + networkRows)
        val network = buildClanCatalog(networkRows)
        val failure = firstFailure
        if (all.isEmpty && failure != null) throw failure
        Download(all, network, highlighted, complete = failure == null)
    }

    private companion object {
        const val MAX_PARALLEL_ROWS = 6
        const val MAX_MOVIE_PAGES = 8
        /** Nombre interno de la "fila" del listado A-Z; no es de películas. */
        const val AZ_ROW_TITLE = "A-Z"
        const val SEED_ROW_TITLE = "Infantil"
    }
}
