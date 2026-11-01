package es.openrtve.ui

import es.openrtve.R
import es.openrtve.domain.ProgramDetail
import es.openrtve.domain.VideoDetail

/**
 * Pestañas de las fichas del móvil, según Material 3 ("las pestañas agrupan
 * contenido del mismo nivel; lo que se lee junto no va en pestañas"):
 *
 * - **Cabecera** = lo que hace falta para decidir y darle al play: título, línea
 *   de datos, botón, sinopsis y ficha técnica (reparto, dirección...) plegadas.
 *   Nunca en una pestaña.
 * - **Pestañas** = colecciones que se recorren: la principal del programa
 *   (Capítulos, Películas, Documentales o Vídeos), Extras y Similares. Con una
 *   sola no hay fila de pestañas: va su título y su contenido.
 *
 * En TV no hay pestañas de sección: cada colección es una fila (ver TvDetails).
 * Qué pieza va a qué sección se decide en ContentSections.
 */
enum class ProgramTab {
    EPISODES,
    MOVIES,
    DOCUMENTARIES,
    VIDEOS,
    MORE_VIDEOS,
    /** "Similares" de RTVE (`recomsys`): otros programas o vídeos que ver después. */
    RELATED,
}

/** Pestañas de la ficha de un programa, según los datos que realmente existen. */
fun programTabs(state: ProgramUiState): List<ProgramTab> = buildList {
    addAll(state.contentSections.map { it.tab })
    // Un contenedor es un catálogo: sus piezas ya son "lo siguiente que ver".
    if (!state.structure.isCollection && state.related.isNotEmpty()) add(ProgramTab.RELATED)
}

/** Pestañas de la ficha de un vídeo: capítulos o piezas del programa, extras y similares, si los hay. */
fun videoTabs(state: VideoUiState): List<ProgramTab> = buildList {
    addAll(state.contentSections.map { it.tab })
    if (state.related.isNotEmpty()) add(ProgramTab.RELATED)
}

/**
 * Ficha técnica: lo que no va en la línea de datos de la cabecera (año, edad y
 * géneros ya están ahí). Reparto y dirección van primero: son las que se ven
 * con la sinopsis plegada.
 */
data class FichaFacts(
    val cast: List<String> = emptyList(),
    val director: String? = null,
    /** Solo cuando son más de los que caben en la cabecera. */
    val genres: List<String> = emptyList(),
    val originalLanguage: String? = null,
    val country: String? = null,
    val expirationDate: String? = null,
) {
    val hasDetails: Boolean get() = rows.isNotEmpty()

    /** Filas etiqueta/valor, omitiendo lo que falte. */
    val rows: List<Pair<Int, String>> = buildList {
        cast.joinToString(", ").takeIf { it.isNotBlank() }?.let { add(R.string.video_cast to it) }
        director?.takeIf { it.isNotBlank() }?.let { add(R.string.video_director to it) }
        genres.joinToString(", ").takeIf { it.isNotBlank() }?.let { add(R.string.video_genres to it) }
        originalLanguage?.let(::languageName)?.let { add(R.string.video_language to it) }
        country?.let(::countryName)?.let { add(R.string.video_country to it) }
        expirationDate?.let { add(R.string.video_available_until to it.feedDateToDisplay()) }
    }

    /** Cuántas de las primeras filas (reparto, dirección) se ven con la sinopsis plegada. */
    val compactRowCount: Int = listOf(cast.isNotEmpty(), !director.isNullOrBlank()).count { it }
}

/** Lo que la línea de datos de la cabecera no dice: en el móvil se despliega con la sinopsis; en TV va en Detalles. */
val VideoDetail.facts: FichaFacts
    get() = FichaFacts(
        cast = cast,
        director = director,
        genres = genres.takeIf { it.size > MAX_HEADER_GENRES }.orEmpty(),
        originalLanguage = originalLanguage,
        expirationDate = expirationDate,
    )

val ProgramDetail.facts: FichaFacts
    get() = FichaFacts(
        cast = cast,
        director = director,
        genres = genres.takeIf { it.size > MAX_HEADER_GENRES }.orEmpty(),
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
