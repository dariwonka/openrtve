package es.openrtve.ui

import android.content.Context
import androidx.annotation.StringRes
import es.openrtve.R
import es.openrtve.data.WatchEntry
import es.openrtve.domain.BlockReason
import es.openrtve.domain.CatalogItem
import es.openrtve.domain.LiveInfo
import es.openrtve.domain.ProgramDetail
import es.openrtve.domain.ProgramStructure
import es.openrtve.domain.VideoDetail
import es.openrtve.domain.canHaveNextEpisode
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

fun LoadError.text(context: Context): String = when (this) {
    LoadError.Offline -> context.getString(R.string.error_offline)
    LoadError.Timeout -> context.getString(R.string.error_timeout)
    is LoadError.Http -> context.getString(R.string.error_http, statusCode)
    LoadError.Unknown -> context.getString(R.string.error_unknown)
}

fun BlockReason.text(context: Context): String = when (this) {
    BlockReason.GEO_RESTRICTED -> context.getString(R.string.blocked_geo)
    BlockReason.LOGIN_REQUIRED -> context.getString(R.string.blocked_login)
    BlockReason.SUBSCRIPTION_REQUIRED -> context.getString(R.string.blocked_paid)
    BlockReason.NO_SOURCE -> context.getString(R.string.blocked_no_source)
    BlockReason.NOT_STARTED_YET -> context.getString(R.string.blocked_not_started)
}

/** "Hoy · 16:10" o "Mañana · 16:10" o "15/09 · 16:10", según el reloj. */
fun LiveInfo.scheduleLabel(context: Context, nowMillis: Long): String? {
    val start = startsAtMillis ?: return null
    val formats = ScheduleFormats.current()
    val startDay = formats.day.format(start).toInt()
    val today = formats.day.format(nowMillis).toInt()
    val tomorrow = formats.day.format(nowMillis + 24L * 60 * 60 * 1_000).toInt()
    val day = when (startDay) {
        today -> context.getString(R.string.schedule_today)
        tomorrow -> context.getString(R.string.schedule_tomorrow)
        else -> formats.date.format(start)
    }
    return "$day · ${formats.time.format(start)}"
}

/**
 * Formateadores del horario, uno por hilo (`SimpleDateFormat` no es thread-safe)
 * y renovados si cambian idioma o zona: cada tarjeta de directo los usa en cada
 * tick del reloj y no merece la pena construirlos cada vez.
 */
private class ScheduleFormats(val locale: Locale, val zone: TimeZone) {
    val time = SimpleDateFormat("HH:mm", locale).apply { timeZone = zone }
    val day = SimpleDateFormat("yyyyMMdd", Locale.ROOT).apply { timeZone = zone }
    val date = SimpleDateFormat("dd/MM", locale).apply { timeZone = zone }

    companion object {
        private val perThread = ThreadLocal<ScheduleFormats>()

        fun current(): ScheduleFormats {
            val locale = Locale.getDefault()
            val zone = TimeZone.getDefault()
            val cached = perThread.get()
            if (cached != null && cached.locale == locale && cached.zone == zone) return cached
            return ScheduleFormats(locale, zone).also(perThread::set)
        }
    }
}

fun UiMessage.text(context: Context): String = when (this) {
    is UiMessage.Error -> error.text(context)
    is UiMessage.Blocked -> reason.text(context)
    is UiMessage.Text -> text
    is UiMessage.StartsAt -> context.getString(R.string.blocked_starts_at, schedule)
}

/**
 * "Temporada 1 · E3 · 12/09/2026 · 52 min", omitiendo lo que falte. El número de
 * episodio no se repite si el título ya lo dice ("Episodio 512").
 */
fun CatalogItem.metaLine(context: Context, episodic: Boolean = canHaveNextEpisode): String? = listOfNotNull(
    seasonTitle.takeIf { episodic },
    episode?.takeIf { episodic }?.takeUnless { Regex("\\b$it\\b").containsMatchIn(title) }?.let { context.getString(R.string.meta_episode, it) },
    publicationDate?.feedDateToDisplay(),
    durationMs?.let { minutesLabel(context, it) },
).takeIf { it.isNotEmpty() }?.joinToString(" · ")

/** "52 min", redondeando; nada por debajo del medio minuto (un "0 min" no dice nada). */
private fun minutesLabel(context: Context, durationMs: Long): String? =
    ((durationMs + 30_000L) / 60_000L).toInt().takeIf { it > 0 }?.let { context.getString(R.string.meta_minutes, it) }

/**
 * Cabecera de un grupo de Explorar, o `null` si no lleva: el menú oficial es plano y
 * el bloque principal se lista sin título; el infantil se nombra y los submenús
 * traen título editorial.
 */
/**
 * Título de una portada abierta desde el menú. El menú lo da en mayúsculas ("LA REVUELTA")
 * y el feed con un nombre interno que lo contiene bien escrito ("Portada RTVE Play La
 * Revuelta"): se toma esa grafía. Si el feed no lo contiene, mayúscula inicial ("70 años"),
 * como el resto de cabeceras. Un título ya escrito con minúsculas se respeta.
 */
fun portadaTitle(given: String, feedTitle: String): String {
    if (given.isBlank()) return feedTitle
    if (given != given.uppercase()) return given
    val start = feedTitle.indexOf(given, ignoreCase = true)
    if (start >= 0) return feedTitle.substring(start, start + given.length)
    return given.lowercase().replaceFirstChar { it.uppercase() }
}

/**
 * Línea de datos de la cabecera, como en Google TV: "2018 · 108 min · +12 ·
 * Comedia, Drama", omitiendo lo que falte. Los géneros, como mucho dos.
 */
fun VideoDetail.metaLine(context: Context): String? =
    headerMetaLine(year, item.durationMs?.let { minutesLabel(context, it) }, ageRating, genres)

/** La misma línea para un programa: "2024 · +12 · Drama"; el horario de emisión va aparte. */
fun ProgramDetail.metaLine(): String? = headerMetaLine(year, minutes = null, ageRating, genres)

private fun headerMetaLine(year: String?, minutes: String?, ageRating: String?, genres: List<String>): String? = listOfNotNull(
    year,
    minutes,
    ageBadge(ageRating),
    genres.take(MAX_HEADER_GENRES).joinToString(", ").ifBlank { null },
).takeIf { it.isNotEmpty() }?.joinToString(" · ")

/**
 * Calificación por edades en su forma corta, la de las carátulas y las guías:
 * "No recomendable para menores de 7 años" -> "+7", "Recomendable para todos los
 * públicos" -> "TP", "Especialmente recomendada para la infancia" -> "Infantil".
 * Lo que no se reconoce no se muestra en la cabecera: la frase entera no cabe.
 */
fun ageBadge(ageRange: String?): String? {
    val text = ageRange?.lowercase() ?: return null
    val minimumAge = AGE_DIGITS.find(text)?.groupValues?.get(1)
    return when {
        minimumAge != null -> "+$minimumAge"
        "todos los públicos" in text -> "TP"
        "infancia" in text -> "Infantil"
        else -> null
    }
}

private val AGE_DIGITS = Regex("menores de (\\d+)")

/** Géneros que caben en la línea de datos de la cabecera; el resto va a la ficha técnica. */
internal const val MAX_HEADER_GENRES = 2

/** Título de una sección de la ficha: pestaña en el móvil, fila en TV. */
@get:StringRes
val ProgramTab.titleRes: Int
    get() = when (this) {
        ProgramTab.EPISODES -> R.string.tab_episodes
        ProgramTab.MOVIES -> R.string.tab_movies
        ProgramTab.DOCUMENTARIES -> R.string.tab_documentaries
        ProgramTab.VIDEOS -> R.string.tab_videos
        ProgramTab.MORE_VIDEOS -> R.string.tab_more_videos
        ProgramTab.RELATED -> R.string.tab_related
    }

/**
 * Enlace de una pieza a su colección: "Más en Cine de siempre". El nombre de un ciclo
 * no dice por sí solo que lleva a más títulos; el de la serie de un capítulo sí, y
 * se queda tal cual.
 */
fun Context.collectionLinkText(structure: ProgramStructure, collection: String?): String? =
    collection?.takeIf { structure.isCollection }?.let { getString(R.string.collection_more_in, it) }

/** Botón grande de la ficha de programa: "Continuar: …", "Siguiente: …" o "Reproducir: …". */
fun PlaySuggestion.label(context: Context): String = context.getString(
    when (this) {
        is PlaySuggestion.Continue -> R.string.program_continue_episode
        is PlaySuggestion.Next -> R.string.program_next_episode
        is PlaySuggestion.Play -> R.string.program_play_episode
    },
    item.title,
)

/**
 * Etiqueta de una pieza en su sección. En Capítulos, dónde está el espectador: el
 * que se ve en esta ficha, los vistos y el siguiente; en el resto, su tipo.
 */
fun ContentSection.badge(
    context: Context,
    item: CatalogItem,
    entry: WatchEntry?,
    suggestion: PlaySuggestion?,
    currentItemId: String?,
): String? = when {
    tab != ProgramTab.EPISODES -> if (tab == ProgramTab.MORE_VIDEOS) item.mediaTypeName else item.mediaSubtypeName
    item.id == currentItemId -> context.getString(R.string.episode_current)
    entry?.finished == true -> context.getString(R.string.episode_watched)
    suggestion is PlaySuggestion.Next && suggestion.item.id == item.id -> context.getString(R.string.episode_next)
    else -> null
}

/** Fecha del feed (`dd-MM-yyyy HH:mm:ss`) como `dd/MM/yyyy`. */
fun String.feedDateToDisplay(): String = take(10).replace('-', '/')

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1L shl 10 -> "%d KB".format(bytes / 1024)
    else -> "$bytes B"
}
