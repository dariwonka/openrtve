package es.openrtve.ui

import es.openrtve.domain.CatalogItem
import es.openrtve.domain.ContentKind
import es.openrtve.domain.MediaForm
import es.openrtve.domain.MediaSubject
import es.openrtve.domain.ProgramStructure
import es.openrtve.domain.isMovie
import es.openrtve.domain.mediaForm
import es.openrtve.domain.mediaSubject
import es.openrtve.domain.parentStructure
import es.openrtve.domain.structure

/*
 * Secciones de las fichas, comunes a móvil y TV. La API entrega dos listas
 * paginadas por programa (emisiones completas y extras); cada pieza va a la
 * sección que le corresponde por su forma, su tema y la estructura del programa
 * (ver MediaClassification), sin deducir nada del título.
 */

/** Sin ficha todavía se trata como una serie, lo más común. */
val ProgramUiState.structure: ProgramStructure get() = detail?.structure ?: ProgramStructure.EPISODIC

/** Al llegar la ficha, [VideoUiState.item] pasa a ser el suyo, con el tipo del programa padre. */
val VideoUiState.structure: ProgramStructure get() = item.parentStructure

/** En un contenedor, o si es una película, cada pieza abre su ficha; un capítulo o un extra se reproduce. */
fun CatalogItem.opensDetailsIn(structure: ProgramStructure): Boolean =
    kind == ContentKind.PROGRAM || (mediaForm != MediaForm.EXTRA && (structure.isCollection || isMovie))

/** Sección de una pieza: primero su forma (un extra siempre es extra), luego su tema y, por último, la estructura. */
fun CatalogItem.contentTab(structure: ProgramStructure, fromExtras: Boolean = false): ProgramTab = when {
    mediaForm == MediaForm.EXTRA || (mediaForm == MediaForm.UNKNOWN && fromExtras) -> ProgramTab.MORE_VIDEOS
    isMovie -> ProgramTab.MOVIES
    structure.isCollection && mediaSubject == MediaSubject.DOCUMENTARY -> ProgramTab.DOCUMENTARIES
    structure.isCollection -> ProgramTab.VIDEOS
    else -> ProgramTab.EPISODES
}

/** Películas y documentales se recorren como pósters; el resto, como lista de capítulos. */
val ProgramTab.showsPosters: Boolean get() = this == ProgramTab.MOVIES || this == ProgramTab.DOCUMENTARIES

/** La sección principal de cada estructura: Capítulos en una serie, Películas en un ciclo de cine... */
val ProgramStructure.primaryTab: ProgramTab
    get() = when (this) {
        ProgramStructure.EPISODIC -> ProgramTab.EPISODES
        ProgramStructure.MOVIES -> ProgramTab.MOVIES
        ProgramStructure.DOCUMENTARIES -> ProgramTab.DOCUMENTARIES
        ProgramStructure.VIDEOS -> ProgramTab.VIDEOS
    }

/**
 * Una sección de la ficha. Sus piezas pueden salir de las dos listas de la API, así
 * que su estado de carga es el de esas fuentes; pedir más o reintentar actúa sobre ellas.
 */
data class ContentSection(
    val tab: ProgramTab,
    val items: List<CatalogItem>,
    val loadsComplete: Boolean,
    val loadsExtras: Boolean,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val error: LoadError? = null,
    /**
     * Páginas cargadas de sus fuentes. Cambia con cada página aunque no traiga piezas
     * de esta sección (una página sin películas): así el scroll infinito vuelve a pedir.
     */
    val progressKey: Int = 0,
) {
    /** Lo que se elige en Extras se reproduce; en el resto, según la pieza y la estructura (ver [opensDetailsIn]). */
    fun opensDetails(item: CatalogItem, structure: ProgramStructure): Boolean =
        tab != ProgramTab.MORE_VIDEOS && item.opensDetailsIn(structure)
}

val ProgramUiState.contentSections: List<ContentSection>
    get() = contentSections(structure, episodes, extras, includePrimary = true)

val VideoUiState.contentSections: List<ContentSection>
    get() = contentSections(structure, chapters, extras, includePrimary = false)

/**
 * Reparte las dos fuentes en secciones, en el orden de las fichas. [includePrimary]
 * mantiene la sección principal aunque aún no tenga piezas (cargando, con error o
 * vacía), para que la ficha de un programa explique siempre qué pasa.
 */
internal fun contentSections(
    structure: ProgramStructure,
    complete: PagedItemsState,
    extras: PagedItemsState,
    includePrimary: Boolean,
): List<ContentSection> {
    val groups = linkedMapOf<ProgramTab, MutableList<CatalogItem>>()
    val fromComplete = mutableSetOf<ProgramTab>()
    val fromExtras = mutableSetOf<ProgramTab>()
    val seen = mutableSetOf<String>()
    for ((page, isExtras) in listOf(complete to false, extras to true)) {
        for (item in page.items) {
            if (!seen.add(item.id)) continue
            val tab = item.contentTab(structure, isExtras)
            groups.getOrPut(tab) { mutableListOf() }.add(item)
            (if (isExtras) fromExtras else fromComplete) += tab
        }
    }
    val primary = structure.primaryTab
    val completePending = complete.isLoading || complete.error != null || (complete.hasMore && complete.items.isEmpty())
    if (completePending || (includePrimary && (complete.hasMore || groups.isEmpty()))) groups.getOrPut(primary) { mutableListOf() }
    if (extras.isLoading || extras.error != null || (extras.hasMore && extras.items.isEmpty())) {
        groups.getOrPut(ProgramTab.MORE_VIDEOS) { mutableListOf() }
    }
    val order = listOf(primary, ProgramTab.EPISODES, ProgramTab.MOVIES, ProgramTab.DOCUMENTARIES, ProgramTab.VIDEOS, ProgramTab.MORE_VIDEOS).distinct()
    return order.filter { it in groups }.map { tab ->
        val loadsComplete = tab != ProgramTab.MORE_VIDEOS || tab in fromComplete
        val loadsExtras = tab in fromExtras || tab == ProgramTab.MORE_VIDEOS
        val sources = listOfNotNull(complete.takeIf { loadsComplete }, extras.takeIf { loadsExtras })
        ContentSection(
            tab = tab,
            items = groups.getValue(tab),
            loadsComplete = loadsComplete,
            loadsExtras = loadsExtras,
            isLoading = sources.any { it.isLoading },
            isLoadingMore = sources.any { it.isLoadingMore },
            hasMore = sources.any { it.hasMore },
            error = sources.firstNotNullOfOrNull { it.error },
            progressKey = sources.sumOf { it.page },
        )
    }
}
