package es.openrtve.domain

enum class BlockReason {
    GEO_RESTRICTED,
    LOGIN_REQUIRED,
    SUBSCRIPTION_REQUIRED,
    NO_SOURCE,
    NOT_STARTED_YET,
}

/**
 * Dónde pedir la licencia Widevine. El token es temporal y se obtiene en el
 * momento de reproducir; nunca se cachea ni se registra.
 */
data class DrmSpec(val tokenUrl: String)

sealed interface PlaybackDecision {
    data class Ready(
        val uri: String,
        val mimeType: String,
        val title: String,
        val drm: DrmSpec? = null,
        /** Variante sin protección para dispositivos sin Widevine. */
        val fallbackUri: String? = null,
        /** Clave del item en "Seguir viendo"; `null` si no se guarda progreso (directos). */
        val historyKey: String? = null,
        /** ID del vídeo bajo demanda: da acceso a previews de la barra y al siguiente episodio. */
        val videoId: String? = null,
    ) : PlaybackDecision

    data class Blocked(val reason: BlockReason) : PlaybackDecision
}

/**
 * @param enforceLoginGate `requireLogged` es una puerta del cliente móvil oficial:
 * los streams se sirven sin credenciales y el cliente de TV no la aplica
 * (verificado el 12-09-2026). Por defecto se ignora, como hace la TV.
 */
class PlaybackResolver(
    private val enforceLoginGate: Boolean = false,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    fun resolve(item: CatalogItem): PlaybackDecision {
        if (item.allowedInCountry == false) {
            return PlaybackDecision.Blocked(BlockReason.GEO_RESTRICTED)
        }
        if (item.live?.isUpcomingAt(nowMillis()) == true) {
            return PlaybackDecision.Blocked(BlockReason.NOT_STARTED_YET)
        }
        if (enforceLoginGate && item.loginRequired) {
            return PlaybackDecision.Blocked(BlockReason.LOGIN_REQUIRED)
        }
        if (item.paid) {
            return PlaybackDecision.Blocked(BlockReason.SUBSCRIPTION_REQUIRED)
        }

        val source = when (item.kind) {
            // Los directos con DRM solo son reproducibles en Android por DASH; el HLS lleva FairPlay.
            ContentKind.LIVE -> item.assetId?.let { if (item.drm) dashSource(it) else hlsSource(it) }
            ContentKind.VIDEO, ContentKind.PROGRAM -> item.playbackId?.let(::dashSource)
            ContentKind.UNKNOWN -> null
        } ?: return PlaybackDecision.Blocked(BlockReason.NO_SOURCE)

        return PlaybackDecision.Ready(
            uri = source.uri,
            mimeType = source.mimeType,
            // Un programa se reproduce por su último episodio (subtítulo); un vídeo por su propio título.
            title = if (item.kind == ContentKind.PROGRAM) item.subtitle ?: item.title else item.title,
            drm = source.drm,
            fallbackUri = source.fallbackUri,
            historyKey = item.id.takeIf { item.kind != ContentKind.LIVE },
            videoId = item.playbackId.takeIf { item.kind == ContentKind.VIDEO || item.kind == ContentKind.PROGRAM },
        )
    }

    /**
     * Los flags `hasDRM` faltan en muchos feeds, así que todo DASH lleva la
     * referencia a la licencia: Media3 solo la usa si el manifiesto está protegido.
     */
    private fun dashSource(id: String) = Source(
        uri = "$ZTNR/$id.mpd",
        mimeType = MIME_DASH,
        drm = DrmSpec("$API/token/$id"),
        fallbackUri = "$ZTNR/tv/$id.mpd",
    )

    private fun hlsSource(assetId: String) = Source("$ZTNR/$assetId.m3u8", MIME_HLS)

    private data class Source(
        val uri: String,
        val mimeType: String,
        val drm: DrmSpec? = null,
        val fallbackUri: String? = null,
    )

    private companion object {
        const val ZTNR = "https://ztnr.rtve.es/ztnr"
        const val API = "https://api.rtve.es/api"
        const val MIME_HLS = "application/x-mpegURL"
        const val MIME_DASH = "application/dash+xml"
    }
}
