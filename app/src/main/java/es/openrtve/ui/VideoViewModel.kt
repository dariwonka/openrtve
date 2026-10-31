package es.openrtve.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import es.openrtve.data.CatalogRepository
import es.openrtve.domain.CatalogItem
import es.openrtve.domain.EpisodeOrder
import es.openrtve.domain.RelatedKind
import es.openrtve.domain.belongsToEpisodicProgram
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
    val isLoading: Boolean = true,
    /** Fallo de la ficha: se puede reproducir igual con [item], y se pinta con su reintento. */
    val error: LoadError? = null,
)

/** Ficha de vídeo o película, al estilo de la pantalla de película de Findroid. */
class VideoViewModel(
    private val repository: CatalogRepository,
    item: CatalogItem,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(VideoUiState(item = item))
    val uiState: StateFlow<VideoUiState> = mutableUiState.asStateFlow()
    private var job: Job? = null
    private var chaptersJob: Job? = null
    private var extrasJob: Job? = null
    /** Orden de los capítulos según la regla de RTVE Play para el programa (ver `episodeOrder`). */
    private var chaptersOrder: EpisodeOrder? = null

    init {
        load()
    }

    fun retry() = load()

    fun loadMoreChapters() = loadChapters(firstPage = false)

    fun retryChapters() = loadChapters(firstPage = mutableUiState.value.chapters.items.isEmpty())

    fun loadMoreExtras() = loadExtras(firstPage = false)

    fun retryExtras() = loadExtras(firstPage = mutableUiState.value.extras.items.isEmpty())

    fun loadMore(section: ContentSection) {
        if (section.loadsComplete) loadMoreChapters()
        if (section.loadsExtras) loadMoreExtras()
    }

    fun retry(section: ContentSection) {
        if (section.loadsComplete) retryChapters()
        if (section.loadsExtras) retryExtras()
    }

    private fun load() {
        val id = mutableUiState.value.item.playbackId ?: mutableUiState.value.item.id
        job?.cancel()
        chaptersJob?.cancel()
        extrasJob?.cancel()
        job = viewModelScope.launch {
            mutableUiState.update { it.copy(isLoading = true, error = null) }
            try {
                val detail = repository.loadVideo(id).value
                // La ficha trae los derechos reales, que faltan en muchos feeds de portada.
                mutableUiState.update { it.copy(detail = detail, item = detail.item, isLoading = false) }
                val media = detail.item
                if (detail.belongsToEpisodicProgram) {
                    loadChapters(firstPage = true)
                }
                // Los extras de un ciclo pertenecen a todo el ciclo, no a esta película.
                if (detail.belongsToEpisodicProgram && media.programShowsFragments == true) {
                    loadExtras(firstPage = true)
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

    private fun loadChapters(firstPage: Boolean) {
        val state = mutableUiState.value
        val programId = state.item.programId ?: return
        val current = state.chapters
        if (!firstPage && (!current.hasMore || current.isLoading || current.isLoadingMore)) return
        chaptersJob?.cancel()
        chaptersJob = viewModelScope.launch {
            mutableUiState.update {
                it.copy(chapters = it.chapters.copy(isLoading = firstPage, isLoadingMore = !firstPage, error = null))
            }
            try {
                val latest = mutableUiState.value
                val result = repository.loadProgramVideos(
                    programId = programId,
                    seasonId = latest.item.seasonId,
                    page = if (firstPage) 1 else latest.chapters.page + 1,
                    completeOnly = true,
                    order = chaptersOrder(programId, latest.item.seasonId),
                )
                mutableUiState.update {
                    val items = if (firstPage) result.value.items else it.chapters.items + result.value.items
                    it.copy(
                        chapters = PagedItemsState(
                            items = items.distinctBy(CatalogItem::id),
                            page = result.value.page,
                            totalPages = result.value.totalPages,
                            isStale = it.chapters.isStale || result.isStale,
                        ),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableUiState.update {
                    it.copy(chapters = it.chapters.copy(isLoading = false, isLoadingMore = false, error = error.toLoadError()))
                }
            }
        }
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

    private fun loadExtras(firstPage: Boolean) {
        val state = mutableUiState.value
        val programId = state.item.programId ?: return
        val current = state.extras
        if (!firstPage && (!current.hasMore || current.isLoading || current.isLoadingMore)) return
        extrasJob?.cancel()
        extrasJob = viewModelScope.launch {
            mutableUiState.update {
                it.copy(extras = it.extras.copy(isLoading = firstPage, isLoadingMore = !firstPage, error = null))
            }
            try {
                val latest = mutableUiState.value
                val result = repository.loadProgramExtras(
                    programId = programId,
                    page = if (firstPage) 1 else latest.extras.page + 1,
                )
                mutableUiState.update {
                    val items = if (firstPage) result.value.items else it.extras.items + result.value.items
                    it.copy(
                        extras = PagedItemsState(
                            items = items.distinctBy(CatalogItem::id),
                            page = result.value.page,
                            totalPages = result.value.totalPages,
                            isStale = it.extras.isStale || result.isStale,
                        ),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableUiState.update {
                    it.copy(extras = it.extras.copy(isLoading = false, isLoadingMore = false, error = error.toLoadError()))
                }
            }
        }
    }
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
