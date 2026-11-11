package es.openrtve.domain

/**
 * Clasificación editorial de RTVE, en un solo sitio. La API la da con ids estables
 * y, en feeds antiguos, solo con el nombre; nada se deduce del título.
 *
 * Tres ejes independientes:
 * - la **forma** del medio (`type`): emisión completa o extra (fragmento, avance);
 * - su **tema** (`subType`): película, documental u otro (un tráiler puede ser de película);
 * - la **estructura** del programa (`programType`): una serie con capítulos o un
 *   contenedor de piezas sueltas ("Contenedor Películas" y similares).
 */
object RtveTypes {
    /** `type.id` de un medio. */
    const val COMPLETE = "39816"
    const val FRAGMENT = "39978"
    const val PREVIEW = "39980"

    /** `subType.id` de un medio. */
    const val MOVIE = "130734"
    const val DOCUMENTARY = "130735"

    /** `programTypeId` de los ciclos de cine. */
    const val MOVIE_CONTAINER = "137310"

    /** Prefijo de `programType` de todos los contenedores. */
    const val CONTAINER_PREFIX = "Contenedor "
}

enum class MediaForm { COMPLETE, EXTRA, UNKNOWN }

enum class MediaSubject { MOVIE, DOCUMENTARY, OTHER }

/** Cómo se organiza un programa: una serie que se ve por capítulos o un contenedor de piezas sueltas. */
enum class ProgramStructure {
    EPISODIC,
    MOVIES,
    DOCUMENTARIES,
    VIDEOS,
    ;

    /** Un contenedor se recorre como catálogo: cada pieza tiene su propia ficha. */
    val isCollection: Boolean get() = this != EPISODIC
}

/** La única regla de estructura; la usan el parser, las fichas y el reproductor. */
fun programStructure(programTypeId: String?, programType: String?): ProgramStructure = when {
    programTypeId == RtveTypes.MOVIE_CONTAINER || programType.equals("Contenedor Películas", ignoreCase = true) -> ProgramStructure.MOVIES
    programType?.startsWith(RtveTypes.CONTAINER_PREFIX, ignoreCase = true) != true -> ProgramStructure.EPISODIC
    programType.contains("documental", ignoreCase = true) -> ProgramStructure.DOCUMENTARIES
    else -> ProgramStructure.VIDEOS
}

/** Estructura del programa al que pertenece un medio (`programInfo`). */
val CatalogItem.parentStructure: ProgramStructure get() = programStructure(programTypeId, programType)

val CatalogItem.mediaForm: MediaForm
    get() = when (mediaTypeId) {
        RtveTypes.COMPLETE -> MediaForm.COMPLETE
        RtveTypes.FRAGMENT, RtveTypes.PREVIEW -> MediaForm.EXTRA
        else -> when (mediaTypeName?.lowercase()) {
            "completo" -> MediaForm.COMPLETE
            "fragmento", "avance" -> MediaForm.EXTRA
            else -> MediaForm.UNKNOWN
        }
    }

val CatalogItem.mediaSubject: MediaSubject
    get() = when (mediaSubtypeId) {
        RtveTypes.MOVIE -> MediaSubject.MOVIE
        RtveTypes.DOCUMENTARY -> MediaSubject.DOCUMENTARY
        else -> when (mediaSubtypeName?.lowercase()) {
            "película", "pelicula" -> MediaSubject.MOVIE
            "documental" -> MediaSubject.DOCUMENTARY
            else -> MediaSubject.OTHER
        }
    }

val CatalogItem.isMovie: Boolean get() = mediaSubject == MediaSubject.MOVIE

/** Una película completa tiene ficha propia; su tráiler sigue siendo un extra. */
val CatalogItem.isFullMovie: Boolean get() = isMovie && mediaForm != MediaForm.EXTRA

/** Solo los capítulos de una serie encadenan el siguiente; nunca películas ni piezas de un contenedor. */
val CatalogItem.canHaveNextEpisode: Boolean
    get() = kind == ContentKind.VIDEO && mediaForm != MediaForm.EXTRA && !isFullMovie && parentStructure == ProgramStructure.EPISODIC
