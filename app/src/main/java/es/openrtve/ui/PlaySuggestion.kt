package es.openrtve.ui

import es.openrtve.data.WatchEntry
import es.openrtve.data.WatchHistory
import es.openrtve.domain.CatalogItem
import es.openrtve.domain.EpisodeOrder

/** Qué debe hacer el botón grande de una ficha de programa. */
sealed interface PlaySuggestion {
    val item: CatalogItem

    data class Play(override val item: CatalogItem) : PlaySuggestion
    data class Continue(override val item: CatalogItem, val entry: WatchEntry) : PlaySuggestion
    data class Next(override val item: CatalogItem) : PlaySuggestion
}

/**
 * Sin historial: el primero de la lista (el más reciente, o el primer capítulo
 * si la serie se lee del principio). A medias: continuar. Terminado: el
 * siguiente cronológico; si no está en la lista, el más reciente cuando hay uno
 * más nuevo que el visto, o el primero de la lista. [order] dice hacia dónde va.
 */
fun suggestPlay(
    history: WatchHistory,
    programId: String,
    episodes: List<CatalogItem>,
    order: EpisodeOrder = EpisodeOrder.NEWEST_FIRST,
    accepts: (CatalogItem) -> Boolean = { true },
): PlaySuggestion? {
    val eligible = episodes.filter(accepts)
    val last = history.entries.value
        .filter { it.item.programId == programId && (it.inProgress || it.finished) && accepts(it.item) }
        .maxByOrNull { it.updatedAtMillis }
    val start = eligible.firstOrNull()
    val newestFirst = order == EpisodeOrder.NEWEST_FIRST
    return when {
        last == null -> start?.let { PlaySuggestion.Play(it) }
        last.inProgress -> PlaySuggestion.Continue(last.item, last)
        else -> {
            val index = eligible.indexOfFirst { it.id == last.item.id }
            val following = if (index < 0) -1 else if (newestFirst) index - 1 else index + 1
            when {
                following in eligible.indices -> PlaySuggestion.Next(eligible[following])
                newestFirst && start != null && start.id != last.item.id -> PlaySuggestion.Next(start)
                else -> start?.let { PlaySuggestion.Play(it) }
            }
        }
    }
}
