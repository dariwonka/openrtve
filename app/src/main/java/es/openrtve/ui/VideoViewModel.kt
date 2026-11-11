package es.openrtve.ui

import es.openrtve.data.CatalogRepository
import es.openrtve.domain.CatalogItem
import es.openrtve.domain.RelatedKind
import es.openrtve.domain.VideoDetail
import kotlinx.coroutines.CancellationException

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

/** Las recomendaciones son secundarias: un fallo deja la pestaña sin pintar, sin avisos. */
internal suspend fun loadRelatedOrEmpty(repository: CatalogRepository, kind: RelatedKind, id: String): List<CatalogItem> = try {
    repository.loadRelated(kind, id).value
} catch (error: CancellationException) {
    throw error
} catch (_: Exception) {
    emptyList()
}
