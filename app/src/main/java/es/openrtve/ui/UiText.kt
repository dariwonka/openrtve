package es.openrtve.ui

import android.content.Context
import es.openrtve.R
import es.openrtve.domain.BlockReason
import es.openrtve.domain.CatalogItem
import es.openrtve.domain.ExploreGroup
import es.openrtve.domain.LiveInfo
import es.openrtve.domain.VideoDetail
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
    UiMessage.LinkNotFound -> context.getString(R.string.deeplink_not_found)
    UiMessage.OpeningLink -> context.getString(R.string.deeplink_opening)
    is UiMessage.RemovedFromHistory -> context.getString(R.string.continue_removed, entry.item.title)
}

/**
 * "Temporada 1 · E3 · 12/09/2026 · 52 min", omitiendo lo que falte. El número de
 * episodio no se repite si el título ya lo dice ("Episodio 512").
 */
fun CatalogItem.metaLine(context: Context): String? = listOfNotNull(
    seasonTitle,
    episode?.takeUnless { Regex("\\b$it\\b").containsMatchIn(title) }?.let { context.getString(R.string.meta_episode, it) },
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
fun ExploreGroup.header(context: Context): String? =
    if (isKids) context.getString(R.string.explore_kids) else title

/** "2018 · 108 min · No recomendable para menores de 12 años", omitiendo lo que falte. */
fun VideoDetail.metaLine(context: Context): String? = listOfNotNull(
    year,
    item.durationMs?.let { minutesLabel(context, it) },
    ageRating,
).takeIf { it.isNotEmpty() }?.joinToString(" · ")

/** Fecha del feed (`dd-MM-yyyy HH:mm:ss`) como `dd/MM/yyyy`. */
fun String.feedDateToDisplay(): String = take(10).replace('-', '/')

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1L shl 10 -> "%d KB".format(bytes / 1024)
    else -> "$bytes B"
}
