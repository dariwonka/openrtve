package es.openrtve.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import es.openrtve.data.CatalogRepository
import es.openrtve.domain.CatalogItem
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
enum class ClanSection { SERIES, MOVIES }

/** Títulos únicos de Clan, cada lista en orden alfabético. */
data class ClanCatalog(
    val series: List<CatalogItem> = emptyList(),
    val movies: List<CatalogItem> = emptyList(),
) {
    fun titles(section: ClanSection): List<CatalogItem> = if (section == ClanSection.SERIES) series else movies
}

/** Una fila de la portada ya descargada: su título editorial y sus piezas. */
data class LoadedRow(val title: String, val items: List<CatalogItem>)

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
fun buildClanCatalog(rows: List<LoadedRow>): ClanCatalog {
    val programs = linkedMapOf<String, CatalogItem>()
    val fromEpisodes = linkedMapOf<String, CatalogItem>()
    val movies = linkedMapOf<String, CatalogItem>()
    for (row in rows) {
        val movieRow = row.title.isMovieRowTitle()
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
    return ClanCatalog(series = programs.values.alphabetical(), movies = movies.values.alphabetical())
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
    val isLoading: Boolean = true,
    /** Solo si no se pudo reunir ningún título. */
    val error: LoadError? = null,
)

/**
 * Catálogo del inicio: descarga la portada de Clan y todas sus filas (seis a la
 * vez) y las funde con [buildClanCatalog]. Una fila que falla no impide mostrar
 * el resto; si fallan todas, se explica y se puede reintentar.
 */
class ClanCatalogViewModel(private val repository: CatalogRepository) : ViewModel() {
    private val mutableUiState = MutableStateFlow(ClanCatalogUiState())
    val uiState: StateFlow<ClanCatalogUiState> = mutableUiState.asStateFlow()
    private var job: Job? = null

    init {
        load()
    }

    fun retry() = load()

    private fun load() {
        job?.cancel()
        job = viewModelScope.launch {
            mutableUiState.update { it.copy(isLoading = true, error = null) }
            try {
                val feed = repository.loadPortada(RtveUrls.CLAN_HOME).value
                // Los accesos directos de una fila `links` ya vienen en la portada.
                val linked = feed.rows.filter { it.isInline }.map { row ->
                    LoadedRow(row.title, row.links.filter { it.kind == LinkKind.PROGRAM }.mapNotNull { link ->
                        link.apiId?.let { referenceItem(it, ContentKind.PROGRAM, link.title, link.imageUrl) }
                    })
                }
                val remote = feed.rows.filter { !it.isInline && !it.isLive && it.contentUrl != null }
                val permits = Semaphore(MAX_PARALLEL_ROWS)
                var firstFailure: Exception? = null
                val loaded = coroutineScope {
                    remote.map { row ->
                        async {
                            permits.withPermit {
                                try {
                                    LoadedRow(row.title, repository.loadModule(row).value.items)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    if (firstFailure == null) firstFailure = error
                                    null
                                }
                            }
                        }
                    }.awaitAll().filterNotNull()
                }
                val catalog = buildClanCatalog(linked + loaded)
                val failure = firstFailure
                if (catalog.series.isEmpty() && catalog.movies.isEmpty() && failure != null) throw failure
                mutableUiState.update { it.copy(catalog = catalog, isLoading = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableUiState.update { it.copy(isLoading = false, error = error.toLoadError()) }
            }
        }
    }

    private companion object {
        const val MAX_PARALLEL_ROWS = 6
    }
}
