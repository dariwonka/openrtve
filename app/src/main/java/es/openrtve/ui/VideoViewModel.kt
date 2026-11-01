package es.openrtve.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import es.openrtve.data.CatalogRepository
import es.openrtve.domain.CatalogItem
import es.openrtve.domain.EpisodeOrder
import es.openrtve.domain.RelatedKind
import es.openrtve.domain.belongsToEpisodicProgram
import es.openrtve.domain.parentStructure
import es.openrtve.domain.episodeOrder
import es.openrtve.domain.isFirstSeason
import es.openrtve.domain.VideoDetail
import es.openrtve.domain.MediaForm
import es.openrtve.domain.MediaSubject
import es.openrtve.domain.mediaForm
import es.openrtve.domain.mediaSubject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VideoUiState(
    /** Lo que ya sabíamos del item antes de abrir la ficha; permite pintar y reproducir sin esperar. */
    val item: CatalogItem,
    val detail: VideoDetail? = null,
    /** Otros capítulos de la misma temporada. */
    val chapters: PagedItemsState = PagedItemsState(),
    /** Fragmentos, avances y tráilers del programa. */
    val extras: PagedItemsState = PagedItemsState(),
    /** "Similares" de RTVE para películas y documentales; vacío si no hay. */
    val related: List<CatalogItem> = emptyList(),
    /**
     * Otras piezas del contenedor al que pertenece (las películas de un ciclo de cine):
     * su selección editorial, distinta de Similares. Vacío si no es de un contenedor.
     */
    val moreFromProgram: List<CatalogItem> = emptyList(),
    val isLoading: Boolean = true,
    /** Fallo de la ficha: se puede reproducir igual con [item], y se pinta con su reintento. */
    val error: LoadError? = null,
)

/** Ficha de vídeo o película: su ficha y, según el programa al que pertenece, capítulos, extras o más piezas. */
class VideoViewModel(
    private val repository: CatalogRepository,
    item: CatalogItem,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(VideoUiState(item = item))
    val uiState: StateFlow<VideoUiState> = mutableUiState.asStateFlow()
    private var job: Job? = null
    /** Orden de los capítulos según la regla de RTVE Play para el programa (ver `episodeOrder`). */
    private var chaptersOrder: EpisodeOrder? = null

    /** Solo se cargan cuando el medio tiene programa (ver [load]). */
    private val programId: String get() = checkNotNull(mutableUiState.value.item.programId)

    private val chaptersLoader = PagedItemsLoader(viewModelScope, mutableUiState, VideoUiState::chapters, { copy(chapters = it) }) { page ->
        val seasonId = mutableUiState.value.item.seasonId
        repository.loadProgramVideos(programId, seasonId, page, completeOnly = true, order = chaptersOrder(programId, seasonId)).value
    }

    private val extrasLoader = PagedItemsLoader(viewModelScope, mutableUiState, VideoUiState::extras, { copy(extras = it) }) { page ->
        repository.loadProgramExtras(programId, page).value
    }

    init {
        load()
    }

    fun retry() = load()

    fun loadMore(section: ContentSection) {
        if (section.loadsComplete) chaptersLoader.loadMore()
        if (section.loadsExtras) extrasLoader.loadMore()
    }

    fun retry(section: ContentSection) {
        if (section.loadsComplete) chaptersLoader.retry()
        if (section.loadsExtras) extrasLoader.retry()
    }

    private fun load() {
        val id = mutableUiState.value.item.playbackId ?: mutableUiState.value.item.id
        job?.cancel()
        chaptersLoader.cancel()
        extrasLoader.cancel()
        job = viewModelScope.launch {
            mutableUiState.update { it.copy(isLoading = true, error = null) }
            try {
                val detail = repository.loadVideo(id).value
                // La ficha trae los derechos reales, que faltan en muchos feeds de portada.
                mutableUiState.update { it.copy(detail = detail, item = detail.item, isLoading = false) }
                val media = detail.item
                val programId = media.programId
                if (programId != null && media.belongsToEpisodicProgram) {
                    chaptersLoader.load(firstPage = true)
                    // Los extras de un ciclo pertenecen a todo el ciclo, no a esta película.
                    if (media.programShowsFragments == true) extrasLoader.load(firstPage = true)
                }
                if (programId != null && media.parentStructure.isCollection) launch {
                    val pieces = loadContainerPiecesOrEmpty(programId, except = media.id)
                    mutableUiState.update { it.copy(moreFromProgram = pieces) }
                }
                relatedKind(media)?.let { kind ->
                    val related = loadRelatedOrEmpty(repository, kind, media.id)
                    mutableUiState.update { it.copy(related = related) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableUiState.update { it.copy(isLoading = false, error = error.toLoadError()) }
            }
        }
    }

    /** Primera página del catálogo del contenedor, sin la pieza que se está viendo; secundaria, sin avisos. */
    private suspend fun loadContainerPiecesOrEmpty(programId: String, except: String): List<CatalogItem> = try {
        repository.loadProgramVideos(programId, page = 1, completeOnly = true, order = EpisodeOrder.NEWEST_FIRST)
            .value.items.filter { it.id != except }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * El mismo orden que la ficha del programa: una serie completa se lee del
     * principio, un informativo del final... Sale de la ficha del programa (en
     * caché si se viene de ella); sin ficha, del más nuevo al más antiguo.
     */
    private suspend fun chaptersOrder(programId: String, seasonId: String?): EpisodeOrder = chaptersOrder ?: try {
        val program = repository.loadProgram(programId).value
        program.episodeOrder(isFirstSeason = program.isFirstSeason(seasonId))
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        EpisodeOrder.NEWEST_FIRST
    }.also { chaptersOrder = it }
}

/** Qué recomendaciones tiene una ficha de vídeo: solo películas y documentales tienen modelo propio. */
private fun relatedKind(item: CatalogItem): RelatedKind? = when {
    item.mediaForm == MediaForm.EXTRA -> null
    item.mediaSubject == MediaSubject.MOVIE -> RelatedKind.FILM
    item.mediaSubject == MediaSubject.DOCUMENTARY -> RelatedKind.DOCUMENTARY
    else -> null
}

/** Las recomendaciones son secundarias: un fallo deja la pestaña sin pintar, sin avisos. */
internal suspend fun loadRelatedOrEmpty(repository: CatalogRepository, kind: RelatedKind, id: String): List<CatalogItem> = try {
    repository.loadRelated(kind, id).value
} catch (error: CancellationException) {
    throw error
} catch (_: Exception) {
    emptyList()
}
