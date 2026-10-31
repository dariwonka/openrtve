package es.openrtve.ui

import es.openrtve.R
import es.openrtve.domain.CatalogItem
import es.openrtve.domain.ContentKind
import es.openrtve.domain.MediaForm
import es.openrtve.domain.MediaSubject
import es.openrtve.domain.mediaForm
import es.openrtve.domain.mediaSubject
import es.openrtve.domain.isContainer
import es.openrtve.domain.isMovieContainer

/** La API llama programas tanto a las series como a los ciclos de piezas independientes. */
enum class ProgramContents(val label: Int, val browseLabel: Int) {
    EPISODES(R.string.tab_episodes, R.string.action_browse_videos),
    MOVIES(R.string.tab_movies, R.string.action_browse_movies),
    DOCUMENTARIES(R.string.tab_documentaries, R.string.action_browse_documentaries),
    VIDEOS(R.string.tab_videos, R.string.action_browse_videos);

    val isCollection: Boolean get() = this != EPISODES
}

val ProgramUiState.contents: ProgramContents
    get() = when {
        detail?.isMovieContainer == true -> ProgramContents.MOVIES
        detail?.isContainer == true && detail.programType?.contains("documental", ignoreCase = true) == true -> ProgramContents.DOCUMENTARIES
        detail?.isContainer == true -> ProgramContents.VIDEOS
        else -> ProgramContents.EPISODES
    }

/** Las piezas independientes se eligen en su ficha; los capítulos permiten reproducción directa. */
fun ProgramUiState.opensItemDetails(item: CatalogItem): Boolean = item.opensDetailsIn(contents)

val VideoUiState.contents: ProgramContents
    get() {
        val type = detail?.programType ?: item.programType
        return when {
            item.programTypeId == "137310" || type.equals("Contenedor Películas", true) -> ProgramContents.MOVIES
            type?.startsWith("Contenedor ", true) == true ->
                if (type.contains("documental", true)) ProgramContents.DOCUMENTARIES else ProgramContents.VIDEOS
            else -> ProgramContents.EPISODES
        }
    }

fun CatalogItem.opensDetailsIn(contents: ProgramContents): Boolean =
    kind == ContentKind.PROGRAM || (mediaForm != MediaForm.EXTRA && (contents.isCollection || mediaSubject == MediaSubject.MOVIE))

/** Orden: estructura del programa, forma editorial y, por último, subtipo de cada pieza. */
fun CatalogItem.contentTab(contents: ProgramContents, fromExtras: Boolean = false): ProgramTab = when {
    mediaForm == MediaForm.EXTRA || (mediaForm == MediaForm.UNKNOWN && fromExtras) -> ProgramTab.MORE_VIDEOS
    mediaSubject == MediaSubject.MOVIE -> ProgramTab.MOVIES
    contents.isCollection && mediaSubject == MediaSubject.DOCUMENTARY -> ProgramTab.DOCUMENTARIES
    contents.isCollection -> ProgramTab.VIDEOS
    else -> ProgramTab.EPISODES
}

val ProgramContents.primaryTab: ProgramTab
    get() = when (this) {
        ProgramContents.EPISODES -> ProgramTab.EPISODES
        ProgramContents.MOVIES -> ProgramTab.MOVIES
        ProgramContents.DOCUMENTARIES -> ProgramTab.DOCUMENTARIES
        ProgramContents.VIDEOS -> ProgramTab.VIDEOS
    }

/** Las secciones comparten las páginas originales: filtrar por subtipo no debe perder vídeos. */
data class ContentSection(
    val tab: ProgramTab,
    val page: PagedItemsState,
    val loadsComplete: Boolean,
    val loadsExtras: Boolean,
)

val ProgramUiState.contentSections: List<ContentSection>
    get() = contentSections(
        contents,
        PagedItemsState(episodes, page, totalPages, isLoading, isLoadingMore, isStale, error),
        extras,
        includePrimary = true,
    )

val VideoUiState.contentSections: List<ContentSection>
    get() = contentSections(contents, chapters, extras, includePrimary = false)

internal fun contentSections(
    contents: ProgramContents,
    complete: PagedItemsState,
    extras: PagedItemsState,
    includePrimary: Boolean,
): List<ContentSection> {
    val groups = linkedMapOf<ProgramTab, MutableList<CatalogItem>>()
    val origins = mutableMapOf<ProgramTab, MutableSet<Boolean>>()
    val seen = mutableSetOf<String>()
    for ((page, fromExtras) in listOf(complete to false, extras to true)) {
        for (item in page.items) {
            if (!seen.add(item.id)) continue
            val tab = item.contentTab(contents, fromExtras)
            groups.getOrPut(tab) { mutableListOf() }.add(item)
            origins.getOrPut(tab) { mutableSetOf() }.add(fromExtras)
        }
    }
    if (includePrimary && (complete.isLoading || complete.hasMore || complete.error != null || groups.isEmpty())) {
        groups.getOrPut(contents.primaryTab) { mutableListOf() }
    }
    if (extras.isLoading || extras.error != null || (extras.hasMore && extras.items.isEmpty())) {
        groups.getOrPut(ProgramTab.MORE_VIDEOS) { mutableListOf() }
    }
    if (!includePrimary && (complete.isLoading || complete.error != null || (complete.hasMore && complete.items.isEmpty()))) {
        groups.getOrPut(contents.primaryTab) { mutableListOf() }
    }
    val order = listOf(contents.primaryTab, ProgramTab.EPISODES, ProgramTab.MOVIES, ProgramTab.DOCUMENTARIES, ProgramTab.VIDEOS, ProgramTab.MORE_VIDEOS).distinct()
    return order.filter { it in groups }.map { tab ->
        val loadsComplete = tab != ProgramTab.MORE_VIDEOS || origins[tab]?.contains(false) == true
        val loadsExtras = tab == ProgramTab.MORE_VIDEOS || origins[tab]?.contains(true) == true
        val sources = listOfNotNull(complete.takeIf { loadsComplete }, extras.takeIf { loadsExtras })
        ContentSection(
            tab,
            PagedItemsState(
                items = groups.getValue(tab),
                // Se suman para que cada avance de cualquiera de las fuentes reactive el scroll.
                page = sources.sumOf { it.page },
                totalPages = sources.sumOf { it.totalPages },
                isLoading = sources.any { it.isLoading },
                isLoadingMore = sources.any { it.isLoadingMore },
                isStale = sources.any { it.isStale },
                error = sources.firstNotNullOfOrNull { it.error },
            ),
            loadsComplete,
            loadsExtras,
        )
    }
}
