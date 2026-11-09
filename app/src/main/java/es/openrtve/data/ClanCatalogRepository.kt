package es.openrtve.data

import es.openrtve.domain.CatalogItem
import es.openrtve.domain.CatalogLoad
import es.openrtve.domain.ExploreGroup
import es.openrtve.domain.QuickFilter
import es.openrtve.domain.RelatedKind

/**
 * Catálogo limitado al público infantil. Envuelve el repositorio general y cierra
 * las puertas por las que se llegaría al resto de RTVE Play:
 *
 * - el menú solo conserva el bloque infantil de la configuración, sin temáticas,
 *   canales ni canales temáticos en directo;
 * - "Similares" sale del recomendador general, que no distingue público: se
 *   devuelve vacío y las fichas ocultan la sección;
 * - las búsquedas predefinidas del buscador general ("Más buscados", "Series TV")
 *   no se ofrecen.
 *
 * La búsqueda por texto sí pasa, porque el repositorio de dentro la hace con el
 * perfil infantil (`isChild=true`, ver `DefaultCatalogRepository.childProfile`).
 * Portadas, fichas, capítulos y siguiente episodio pasan tal cual.
 */
class ClanCatalogRepository(private val delegate: CatalogRepository) : CatalogRepository by delegate {
    /**
     * Franjas de edad de Clan. El bloque `infantil` está en la configuración de
     * móvil; si RTVE lo moviese, se prueba la de TV. Sin franjas la lista queda
     * vacía y la app sigue funcionando con la portada completa de Clan.
     */
    override suspend fun loadExplore(forceRefresh: Boolean, tv: Boolean): CatalogLoad<List<ExploreGroup>> {
        val mobile = delegate.loadExplore(forceRefresh, tv = false)
        val kids = mobile.value.kidsOnly()
        if (kids.isNotEmpty()) return CatalogLoad(kids, mobile.isStale)
        val television = delegate.loadExplore(forceRefresh, tv = true)
        return CatalogLoad(television.value.kidsOnly(), television.isStale)
    }

    override suspend fun loadRelated(kind: RelatedKind, id: String): CatalogLoad<List<CatalogItem>> =
        CatalogLoad(emptyList(), isStale = false)

    override suspend fun loadQuickFilters(): CatalogLoad<List<QuickFilter>> = CatalogLoad(emptyList(), isStale = false)

    private fun List<ExploreGroup>.kidsOnly(): List<ExploreGroup> = filter { it.isKids }
        .map { group -> group.copy(categories = group.categories.filterNot { it.isLive }) }
        .filter { it.categories.isNotEmpty() }
}
