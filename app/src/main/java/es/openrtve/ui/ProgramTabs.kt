package es.openrtve.ui

import es.openrtve.R
import es.openrtve.domain.CatalogItem
import es.openrtve.domain.ProgramDetail
import es.openrtve.domain.VideoDetail

/**
 * Pestañas de las fichas, con la misma regla que en LibreTres:
 *
 * - **Cabecera** = lo que hace falta para decidir y darle al play (título, datos,
 *   sinopsis plegada, botón). Nunca en una pestaña.
 * - **Pestañas** = colecciones que se recorren. Sólo se enseña la fila si hay más
 *   de una; con una, su contenido va directamente debajo de la cabecera.
 * - **Detalles** = la cola larga (ficha técnica, reparto): sólo si cuenta algo más
 *   que la cabecera.
 *
 * RTVE separa las emisiones completas (`39816`) de fragmentos (`39978`) y
 * avances/tráilers (`39980`). Esa separación alimenta Capítulos y Más vídeos;
 * no se mezclan los programas relacionados, que son otro tipo de contenido.
 */
enum class ProgramTab {
    EPISODES,
    MORE_VIDEOS,
    /** "Similares" de RTVE (`recomsys`): otros programas o vídeos que ver después. */
    RELATED,
    DETAILS,
}

/** Estado paginado común a capítulos y extras. */
data class PagedItemsState(
    val items: List<CatalogItem> = emptyList(),
    val page: Int = 0,
    val totalPages: Int = 0,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isStale: Boolean = false,
    val error: LoadError? = null,
) {
    val hasMore: Boolean get() = page < totalPages
    /** Un error conserva la pestaña para que el usuario pueda reintentarla. */
    val isAvailable: Boolean get() = items.isNotEmpty() || error != null
}

/** Pestañas de la ficha de un programa, según los datos que realmente existen. */
fun programTabs(body: ProgramBody, state: ProgramUiState): List<ProgramTab> = buildList {
    add(ProgramTab.EPISODES)
    if (state.extras.isAvailable) add(ProgramTab.MORE_VIDEOS)
    if (state.related.isNotEmpty()) add(ProgramTab.RELATED)
    if (state.detail?.facts?.hasDetails == true) add(ProgramTab.DETAILS)
}.take(MAX_TABS)

/** Pestañas de la ficha de un vídeo: capítulos, extras, similares y ficha técnica, si los hay. */
fun videoTabs(state: VideoUiState): List<ProgramTab> = buildList {
    if (state.chapters.isAvailable) add(ProgramTab.EPISODES)
    if (state.extras.isAvailable) add(ProgramTab.MORE_VIDEOS)
    if (state.related.isNotEmpty()) add(ProgramTab.RELATED)
    if (state.detail?.facts?.hasDetails == true) add(ProgramTab.DETAILS)
}.take(MAX_TABS)

internal const val MAX_TABS = 4

/** Ficha técnica de un vídeo, en filas etiqueta/valor; la sinopsis va aparte, en la cabecera. */
data class FichaFacts(
    val year: String? = null,
    val ageRating: String? = null,
    val genres: List<String> = emptyList(),
    val director: String? = null,
    val cast: List<String> = emptyList(),
    val originalLanguage: String? = null,
    val expirationDate: String? = null,
    val country: String? = null,
) {
    /** Si la pestaña de detalles tiene algo que enseñar. */
    val hasDetails: Boolean get() = rows.isNotEmpty()

    /** Las filas etiqueta/valor, omitiendo lo que falte. */
    val rows: List<Pair<Int, String>> = buildList {
        year?.takeIf { it.isNotBlank() }?.let { add(R.string.video_year to it) }
        ageRating?.takeIf { it.isNotBlank() }?.let { add(R.string.video_age_rating to it) }
        genres.joinToString(", ").takeIf { it.isNotBlank() }?.let { add(R.string.video_genres to it) }
        director?.takeIf { it.isNotBlank() }?.let { add(R.string.video_director to it) }
        cast.joinToString(", ").takeIf { it.isNotBlank() }?.let { add(R.string.video_cast to it) }
        originalLanguage?.let(::languageName)?.let { add(R.string.video_language to it) }
        expirationDate?.let { add(R.string.video_available_until to it.feedDateToDisplay()) }
        country?.let(::countryName)?.let { add(R.string.video_country to it) }
    }
}

/** Año y edad ya van en la línea de datos de la cabecera ([metaLine]): aquí no se repiten. */
val VideoDetail.facts: FichaFacts
    get() = FichaFacts(
        genres = genres,
        director = director,
        cast = cast,
        originalLanguage = originalLanguage,
        expirationDate = expirationDate,
    )

val ProgramDetail.facts: FichaFacts
    get() = FichaFacts(
        year = year,
        ageRating = ageRating,
        genres = genres,
        director = director,
        cast = cast,
        originalLanguage = originalLanguage,
        country = country,
    )

/** Nombre en español de un código ISO ("en" -> "Inglés"); si el feed trae otra cosa, se muestra tal cual. */
private fun languageName(code: String): String? =
    java.util.Locale.forLanguageTag(code)
        .getDisplayLanguage(SPANISH)
        .ifBlank { code }
        .replaceFirstChar { it.uppercase() }
        .takeIf { it.isNotBlank() }

/** Nombre en español de un código de país ("ES" -> "España"); `setRegion` rechaza lo que no sea ISO. */
private fun countryName(code: String): String? =
    runCatching { java.util.Locale.Builder().setRegion(code).build().getDisplayCountry(SPANISH) }
        .getOrNull()
        .orEmpty()
        .ifBlank { code }
        .takeIf { it.isNotBlank() }

private val SPANISH = java.util.Locale.forLanguageTag("es")
