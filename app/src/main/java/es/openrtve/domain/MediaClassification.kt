package es.openrtve.domain

/** `type` y `subType` son ejes independientes: un avance puede tener subtipo de película. */
enum class MediaForm { COMPLETE, EXTRA, UNKNOWN }
enum class MediaSubject { MOVIE, DOCUMENTARY, OTHER }

val CatalogItem.mediaForm: MediaForm
    get() = when (mediaTypeId) {
        "39816" -> MediaForm.COMPLETE
        "39978", "39980" -> MediaForm.EXTRA
        else -> when (mediaTypeName?.lowercase()) {
            "completo" -> MediaForm.COMPLETE
            "fragmento", "avance" -> MediaForm.EXTRA
            else -> MediaForm.UNKNOWN
        }
    }

val CatalogItem.mediaSubject: MediaSubject
    get() = when (mediaSubtypeId) {
        "130734" -> MediaSubject.MOVIE
        "130735" -> MediaSubject.DOCUMENTARY
        else -> when (mediaSubtypeName?.lowercase()) {
            "película", "pelicula" -> MediaSubject.MOVIE
            "documental" -> MediaSubject.DOCUMENTARY
            else -> MediaSubject.OTHER
        }
    }

val CatalogItem.isMovie: Boolean get() = mediaSubject == MediaSubject.MOVIE

/** Una película completa tiene ficha propia; su tráiler sigue siendo un extra. */
val CatalogItem.isFullMovie: Boolean get() = isMovie && mediaForm != MediaForm.EXTRA

/** El siguiente capítulo nunca encadena películas o piezas de un contenedor. */
val CatalogItem.canHaveNextEpisode: Boolean
    get() = kind == ContentKind.VIDEO && mediaForm != MediaForm.EXTRA && !isFullMovie &&
        programTypeId != "137310" && programType?.startsWith("Contenedor ", ignoreCase = true) != true
