package es.openrtve.domain

import java.net.URI
import java.net.URLDecoder

/** Destino que codifica un enlace de RTVE Play. */
sealed interface DeepLink {
    data class Program(val id: String) : DeepLink
    data class Video(val id: String) : DeepLink
    data class Live(val assetId: String) : DeepLink

    /** `/play/videos/{permalink}/`: el programa hay que buscarlo. */
    data class ProgramPermalink(val permalink: String) : DeepLink

    /** `/play/videos/directo/{permalink}/`: el directo hay que buscarlo en el feed. */
    data class LivePermalink(val permalink: String) : DeepLink
}

/**
 * Función pura sobre la URL, sin red. Acepta los formatos del manifiesto de
 * RTVE Play (`play.rtve.es/pr|v|d/{id}`, `play.rtve.es/content?uri=`) y las
 * URLs web de `rtve.es/play/...`. Devuelve `null` si no es un enlace de RTVE.
 */
fun parseDeepLink(raw: String): DeepLink? {
    val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
    val host = uri.host?.lowercase() ?: return null
    if (host != "rtve.es" && !host.endsWith(".rtve.es")) return null
    val segments = uri.path.orEmpty().split('/').filter { it.isNotBlank() }

    if (host == "play.rtve.es") {
        if (segments.firstOrNull() == "content") {
            val inner = uri.rawQuery?.split('&')
                ?.firstOrNull { it.startsWith("uri=") }
                ?.removePrefix("uri=")
                ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
                ?: return null
            // Una sola decodificación: el parámetro ya viene codificado una vez.
            return parseDeepLink(inner)
        }
        val id = segments.getOrNull(1)?.takeIf { it.all(Char::isDigit) } ?: return null
        return when (segments.firstOrNull()) {
            "pr" -> DeepLink.Program(id)
            "v" -> DeepLink.Video(id)
            "d" -> DeepLink.Live(id)
            else -> null
        }
    }

    // Solo vídeo: los audios (`/play/audios/...`) son de RTVE Audio y no se abren aquí.
    if (segments.firstOrNull() != "play" || segments.getOrNull(1) != "videos") return null
    val rest = segments.drop(2)
    return when {
        rest.firstOrNull() == "directo" -> rest.getOrNull(1)?.let { DeepLink.LivePermalink(it) }
        rest.lastOrNull()?.all(Char::isDigit) == true -> DeepLink.Video(rest.last())
        rest.size == 1 -> DeepLink.ProgramPermalink(rest.first())
        else -> null
    }
}
