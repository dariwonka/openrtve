package es.openrtve.data

import es.openrtve.domain.CatalogItem
import es.openrtve.domain.CatalogLoad
import es.openrtve.domain.CatalogPage
import es.openrtve.domain.ExploreGroup
import es.openrtve.domain.QuickFilter
import es.openrtve.domain.RelatedKind
import es.openrtve.domain.SearchResults

/**
 * Catálogo limitado a Clan. Envuelve el repositorio general y cierra las tres
 * puertas por las que se llegaría al resto de RTVE Play:
 *
 * - el menú solo conserva el bloque infantil de la configuración (las portadas
 *   por edad), sin temáticas, canales ni canales temáticos en directo;
 * - "Similares" sale del recomendador general, que no distingue público: se
 *   devuelve vacío y las fichas ocultan la sección;
 * - el buscador general no se expone: sin comprobar que RTVE filtra por perfil
 *   infantil en el servidor, no devuelve nada.
 *
 * Portadas, fichas, capítulos y siguiente episodio pasan tal cual: solo se
 * llega a ellos desde una portada de Clan.
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

    override suspend fun search(query: String): SearchResults = SearchResults(
        programs = emptyList(),
        videos = emptyList(),
    )

    override suspend fun searchPage(relativeQuery: String, page: Int): CatalogPage = CatalogPage(emptyList(), page, page)

    private fun List<ExploreGroup>.kidsOnly(): List<ExploreGroup> = filter { it.isKids }
        .map { group -> group.copy(categories = group.categories.filterNot { it.isLive }) }
        .filter { it.categories.isNotEmpty() }
}
