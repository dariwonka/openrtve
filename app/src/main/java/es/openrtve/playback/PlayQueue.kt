package es.openrtve.playback

import es.openrtve.domain.CatalogItem

/**
 * Los capítulos de la lista desde la que se ha empezado a ver algo, en el orden en que aparecen.
 * Al acabar uno, el siguiente es el que viene detrás en esa lista, sea de RTVE, Boing o SVT.
 */
class PlayQueue {
    @Volatile private var items: List<CatalogItem> = emptyList()

    fun set(list: List<CatalogItem>) {
        items = list
    }

    /** El que sigue a [id] en la lista, o `null` si [id] es el último o no está en ella. */
    fun after(id: String?): CatalogItem? {
        if (id == null) return null
        val list = items
        val index = list.indexOfFirst { it.id == id }
        return if (index >= 0) list.getOrNull(index + 1) else null
    }
}
