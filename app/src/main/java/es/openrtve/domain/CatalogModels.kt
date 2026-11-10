package es.openrtve.domain

object RtveUrls {
    const val TV_HOME = "https://www.rtve.es/play/index_apps.json"
    /** Portada de Clan con todas las edades (`television.secciones.portadaInfantil` de la configuración). */
    const val CLAN_HOME = "https://www.rtve.es/play/clan/index_apps.json"
    /**
     * Las demás portadas del menú de la app oficial de Clan (`estructuraClanNativa.json`,
     * `television.menuBloques`): sus tres franjas de edad. Sus títulos se suman al catálogo.
     */
    val CLAN_AGE_PORTADAS = listOf(
        "https://www.rtve.es/play/clan/preescolar/index_apps.json",
        "https://www.rtve.es/play/clan/infantil/index_apps.json",
        "https://www.rtve.es/play/clan/junior/index_apps.json",
    )
    /** Portada "PELÍCULAS" del mismo menú: todo lo que trae es cine infantil. */
    /** Lista de RTVE de películas infantiles (vídeos con subtipo película). */
    const val CLAN_MOVIES_TOPS = "https://recomsys.rtve.es/recommendation/tops?source=apps&recoId=peliculasInfantil&contentType=video"
    /** Programas contenedores de cine para niños: Cine en Familia y Cine Clan. */
    val CLAN_MOVIE_PROGRAMS = listOf("1001122", "134773")
    const val CLAN_MOVIES_PORTADA = "https://www.rtve.es/play/clan/peliculas-infantiles/index_apps.json"
    /** Cadena Clan en la API (`cadenas/417`), la misma que usa su portada en "Para ver hoy". */
    const val CLAN_CHANNEL_ID = "417"
    const val REMOTE_CONFIG = "https://www.rtve.es/m/configs/rtve_play/2026/estructura2.json"
    /** La que lee la app oficial de Android TV: su propio menú y sus propias imágenes. */
    const val REMOTE_CONFIG_TV = "https://www.rtve.es/m/configs/rtve_play/2026/estructuraAndroidTV2.json"
    const val QUICK_SEARCHES = "https://www.rtve.es/m/configs/buscador/predefinidos.json"
    const val SEARCH = "https://api.rtve.es/api/search/results"
    /** Base de las páginas siguientes de cada bloque de búsqueda: se le añade su `query` relativo. */
    const val SEARCH_PAGES = "https://api.rtve.es/api/search"
    const val RECOMMENDATIONS = "https://recomsys.rtve.es/recommendation"
}

/**
 * Recomendaciones editoriales de RTVE (`recomsys`): "Similares" de una ficha.
 * Cada tipo tiene su modelo y su forma de identificar el item.
 */
enum class RelatedKind(val path: String, val itemPrefix: String) {
    PROGRAM("program", "pr"),
    FILM("film", "v"),
    DOCUMENTARY("documental", "v"),
}

/** Qué recorte pinta cada presentación, con sus respaldos; la única definición para móvil y TV. */
fun CatalogItem.imageFor(layout: RowLayout): String? = when (layout) {
    RowLayout.POSTER -> posterUrl ?: imageUrl
    RowLayout.POSTER_TALL -> tallPosterUrl ?: posterUrl ?: imageUrl
    RowLayout.SQUARE -> squareUrl ?: imageUrl ?: posterUrl
    RowLayout.HERO, RowLayout.FEATURED, RowLayout.LANDSCAPE, RowLayout.RANKED -> imageUrl
}

/** Item mínimo que solo aporta id y tipo: la pantalla de destino carga la ficha completa. */
fun referenceItem(id: String, kind: ContentKind, title: String, imageUrl: String? = null) = CatalogItem(
    id = id,
    playbackId = if (kind == ContentKind.PROGRAM) null else id,
    assetId = null,
    title = title,
    subtitle = null,
    imageUrl = imageUrl,
    kind = kind,
    allowedInCountry = null,
    loginRequired = false,
    paid = false,
    drm = false,
    programId = if (kind == ContentKind.PROGRAM) id else null,
)

/** Fila sintética de colección (`collection/{id}.json`), para abrirla desde un enlace. */
fun collectionRow(url: String, title: String) = HomeRow(
    id = -1,
    title = title,
    order = 0,
    moduleType = "Collection",
    presentation = "ColeccionPoster",
    contentUrl = url,
    layout = RowLayout.POSTER,
)

/** Fila sintética de directos sobre un feed de `lives`, para abrir un directo que no viene de una portada. */
fun liveRow(url: String, title: String = "") = HomeRow(
    id = -1,
    title = title,
    order = 0,
    moduleType = "livesCollection",
    presentation = "directosTV",
    contentUrl = url,
    layout = RowLayout.LANDSCAPE,
)

/**
 * Cómo pinta la UI una fila; se deduce del `tipo` editorial y nunca es un enum
 * cerrado en el feed. Cada valor corresponde a una presentación distinta en
 * RTVE Play: dos `tipo` distintos de la API no se funden en una misma tarjeta.
 */
enum class RowLayout {
    /** `ColeccionDestacado`: carrusel a ancho completo, un item por página. */
    HERO,
    /** `ColeccionSuperDestacado`: un solo destacado con imagen, título, descripción y botón "Ver". */
    FEATURED,
    /** `ColeccionPoster`, `videoPoster`, `programas`: pósters verticales 2:3. */
    POSTER,
    /** `ColeccionSuper`: pósters altos 1:2 con la imagen `imgCol`, distinta del póster. */
    POSTER_TALL,
    /** `ColeccionCuadrado*`: tarjetas cuadradas. */
    SQUARE,
    /** `ColeccionApaisado`, `videos`, `directosTV*`: tarjetas apaisadas 16:9. */
    LANDSCAPE,
    /** `ColleccionTops`, `Tops`: apaisadas con su posición en el ranking. */
    RANKED,
    ;

    /** Tarjetas más altas que anchas: dos líneas de título y sin subtítulo. */
    val isVertical: Boolean
        get() = when (this) {
            POSTER, POSTER_TALL -> true
            HERO, FEATURED, SQUARE, LANDSCAPE, RANKED -> false
        }

    /** Proporción ancho/alto de la imagen de cada tarjeta. */
    val aspectRatio: Float
        get() = when (this) {
            HERO, FEATURED, LANDSCAPE, RANKED -> 16f / 9f
            POSTER -> 2f / 3f
            POSTER_TALL -> 1f / 2f
            SQUARE -> 1f
        }

    /** En rejilla los destacados no tienen sentido: se degradan a apaisada. */
    val gridLayout: RowLayout
        get() = when (this) {
            HERO, FEATURED -> LANDSCAPE
            POSTER, POSTER_TALL, SQUARE, LANDSCAPE, RANKED -> this
        }
}

data class HomeFeed(
    val id: String?,
    val title: String,
    val rows: List<HomeRow>,
)

/** A qué apunta un enlace de una fila `links`; `enlaceExterno` sale de la app y no se modela. */
enum class LinkKind { COLLECTION, PORTADA, PROGRAM, VIDEO }

/** Acceso editorial de una fila `links`: viene completo en la portada, sin descarga. */
data class HomeLink(
    val title: String,
    val imageUrl: String?,
    val url: String,
    val kind: LinkKind,
) {
    /** Último segmento numérico de la URL de la API (`/api/programas/1234.json` -> `1234`). */
    val apiId: String?
        get() = API_ID.find(url)?.groupValues?.get(1)

    private companion object {
        val API_ID = Regex("/(\\d+)(?:\\.json)?/?$")
    }
}

data class HomeRow(
    /** Posición estable dentro de la portada; los títulos y `orden` pueden repetirse. */
    val id: Int,
    val title: String,
    val order: Int,
    val moduleType: String?,
    val presentation: String?,
    val contentUrl: String?,
    val layout: RowLayout = RowLayout.LANDSCAPE,
    /** Fila `links`: sus accesos vienen en la propia portada y `contentUrl` no se usa. */
    val links: List<HomeLink> = emptyList(),
) {
    /** Su contenido ya viene en la portada: no hay módulo que descargar. */
    val isInline: Boolean
        get() = links.isNotEmpty()

    /** Se puede pintar: o trae su contenido, o tiene una fuente remota admitida. */
    val isRenderable: Boolean
        get() = isInline || contentUrl != null

    /** Fila de directos: su contenido caduca en minutos. */
    val isLive: Boolean
        get() = presentation?.lowercase()?.startsWith("directos") == true ||
            moduleType.equals("livesCollection", ignoreCase = true)
}

enum class ContentKind {
    PROGRAM,
    VIDEO,
    LIVE,
    UNKNOWN,
}

data class CatalogItem(
    val id: String,
    /** ID del medio reproducible. `null` cuando el item no apunta a ningún medio (p. ej. un programa sin último episodio). */
    val playbackId: String?,
    val assetId: String?,
    val title: String,
    val subtitle: String?,
    /** Imagen apaisada. */
    val imageUrl: String?,
    val kind: ContentKind,
    /** Póster vertical 2:3. */
    val posterUrl: String? = null,
    /** Imagen cuadrada 1:1 (filas `ColeccionCuadrado`). */
    val squareUrl: String? = null,
    /** Imagen `imgCol` de un programa: el recorte alto (1:2) que usa `ColeccionSuper`; no es el póster. */
    val tallPosterUrl: String? = null,
    val allowedInCountry: Boolean?,
    val loginRequired: Boolean,
    val paid: Boolean,
    val drm: Boolean,
    /** Programa al que pertenece el medio, si el feed lo indica. */
    val programId: String? = null,
    /** Temporada a la que pertenece el medio; permite cargar los demás capítulos desde una ficha. */
    val seasonId: String? = null,
    /** Tipo editorial de RTVE (`39816` completo, `39978` fragmento, `39980` avance). */
    val mediaTypeId: String? = null,
    val mediaTypeName: String? = null,
    val mediaSubtypeName: String? = null,
    /** El programa publica fragmentos/avances además de emisiones completas. */
    val programShowsFragments: Boolean? = null,
    val durationMs: Long? = null,
    /** Fecha tal como la entrega el feed (`dd-MM-yyyy HH:mm:ss`); la UI la formatea. */
    val publicationDate: String? = null,
    val episode: Int? = null,
    val seasonTitle: String? = null,
    /** Solo en directos: emisión en curso o programada. */
    val live: LiveInfo? = null,
    /** Página web del item en rtve.es; sirve para compartir y para casar enlaces. */
    val webUrl: String? = null,
    /** `subType.id` del medio; `130734` identifica una película. */
    val mediaSubtypeId: String? = null,
    /** Tipo del programa padre, según `programInfo`; no se deduce del título. */
    val programTypeId: String? = null,
    val programType: String? = null,
)

/**
 * Lo que el feed de directos sabe de la emisión: si está en el aire, cuándo
 * empieza, cuánto dura y el logo del canal. Las horas del feed son de Madrid.
 */
data class LiveInfo(
    val isOnAir: Boolean,
    val startsAtMillis: Long?,
    val durationMinutes: Int?,
    /** Progreso que envía el feed; la UI lo recalcula con el reloj si conoce inicio y duración. */
    val progressPercent: Int?,
    val channelLogoUrl: String?,
    val category: String?,
) {
    fun progressAt(nowMillis: Long): Float? {
        val start = startsAtMillis
        val minutes = durationMinutes
        if (start != null && minutes != null && minutes > 0) {
            return ((nowMillis - start).toFloat() / (minutes * 60_000L)).coerceIn(0f, 1f)
        }
        return progressPercent?.let { (it / 100f).coerceIn(0f, 1f) }
    }

    /** Programado y aún no empezado según el reloj. */
    fun isUpcomingAt(nowMillis: Long): Boolean =
        !isOnAir && startsAtMillis != null && startsAtMillis > nowMillis
}

data class CatalogModule(
    val title: String,
    val items: List<CatalogItem>,
)

/** "Ver todo" solo tiene sentido si la fila trae más de lo que cabe de un vistazo. */
const val SEE_ALL_MIN_ITEMS = 6

data class CatalogPage(
    val items: List<CatalogItem>,
    val page: Int,
    val totalPages: Int,
) {
    val hasMore: Boolean get() = page < totalPages
}

data class ProgramSeason(
    val id: String,
    val title: String,
    val episodeCount: Int?,
    /** Posición editorial (`orden`); decide el orden de las pestañas. */
    val order: Int? = null,
)

data class ProgramDetail(
    val id: String,
    val title: String,
    /** Puede contener HTML; la UI lo renderiza. */
    val description: String?,
    val imageUrl: String?,
    val emission: String?,
    /** Ya en el orden en que se muestran (ver [seasonsInDisplayOrder]). */
    val seasons: List<ProgramSeason>,
    val webUrl: String? = null,
    /** `programTypeId`/`programTypeID` del feed ("136519" = Series Prime Time...). */
    val programTypeId: String? = null,
    /** `outOfEmission == false`: sigue publicando episodios. */
    val inEmission: Boolean = true,
    /** `isComplete`: la serie está completa en el catálogo. */
    val isComplete: Boolean = false,
    /** RTVE anuncia que el programa publica fragmentos y avances. */
    val showFragments: Boolean = false,
    val year: String? = null,
    val ageRating: String? = null,
    val genres: List<String> = emptyList(),
    val director: String? = null,
    val cast: List<String> = emptyList(),
    val originalLanguage: String? = null,
    val country: String? = null,
    /** Tipo editorial: distingue series de ciclos como "Contenedor Películas". */
    val programType: String? = null,
)

data class CatalogLoad<T>(
    val value: T,
    val isStale: Boolean,
)

data class SearchResults(
    val programs: List<CatalogItem>,
    val videos: List<CatalogItem>,
    /** Paginación de los vídeos: la página cargada, el total y el `query` relativo para pedir más. */
    val videosPage: Int = 1,
    val videosTotalPages: Int = 1,
    val videosQuery: String? = null,
) {
    val isEmpty: Boolean get() = programs.isEmpty() && videos.isEmpty()
    val hasMoreVideos: Boolean get() = videosQuery != null && videosPage < videosTotalPages
}

/** Pestaña de búsqueda predefinida ("Más buscados", "Series TV"...). */
data class QuickFilter(
    val title: String,
    val contentUrl: String,
)

/** Entrada del menú de Explorar: una portada, o un canal temático en directo (`broadcasts/{id}.json`). */
data class ExploreCategory(
    val title: String,
    val imageUrl: String?,
    val contentUrl: String,
    val isLive: Boolean = false,
    /**
     * Imagen de la misma portada en la otra configuración (móvil o TV), por si la
     * propia no carga: RTVE deja a veces imágenes rotas en una de las dos.
     */
    val fallbackImageUrl: String? = null,
    /**
     * Lo que emite ahora un canal temático ([isLive]): su tarjeta es la de un directo,
     * como en la portada, y al tocarla se reproduce. `null` hasta que llega o si falla.
     */
    val nowPlaying: CatalogItem? = null,
)

/**
 * Sección de Explorar. Sin [title] es el bloque principal del menú (se presenta como
 * "Temáticas", la pareja de "Otras temáticas"); [isKids] es el bloque infantil (`infantil: true`).
 */
data class ExploreGroup(
    val title: String?,
    val categories: List<ExploreCategory>,
    val isKids: Boolean = false,
)

/** Ficha completa de un vídeo (`videos/{id}.json`); [item] trae sus derechos y su programa. */
data class VideoDetail(
    val item: CatalogItem,
    val backdropUrl: String?,
    /** Puede contener HTML; la UI lo renderiza. */
    val description: String?,
    /** Reclamo corto (`promoDesc`): una frase para un destacado, distinta de la sinopsis. */
    val promo: String? = null,
    val programTitle: String?,
    val year: String?,
    val ageRating: String?,
    val genres: List<String>,
    val director: String?,
    val cast: List<String>,
    val originalLanguage: String?,
    val expirationDate: String?,
    val webUrl: String?,
)

/** Miniaturas de la barra de progreso: un sprite y las regiones que corresponden a cada tramo. */
data class PreviewSprite(
    val imageUrl: String,
    val cues: List<SpriteCue>,
) {
    fun cueAt(positionMs: Long): SpriteCue? = cues.firstOrNull { positionMs >= it.startMs && positionMs < it.endMs }
        ?: cues.lastOrNull()?.takeIf { positionMs >= it.endMs }
}

data class SpriteCue(
    val startMs: Long,
    val endMs: Long,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)
