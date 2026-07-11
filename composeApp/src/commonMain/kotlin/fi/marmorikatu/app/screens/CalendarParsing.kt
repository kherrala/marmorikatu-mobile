package fi.marmorikatu.app.screens

import fi.marmorikatu.app.components.GarbagePickup
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** What [CalendarParsing.parseCalendar] splits a `get_calendar_events` response into. */
data class ParsedCalendar(
    val days: List<CalendarDay>,
    val garbage: List<GarbagePickup>,
) {
    companion object {
        val EMPTY = ParsedCalendar(emptyList(), emptyList())
    }
}

/**
 * Turns the MCP `get_calendar_events` payload into the family day list and the
 * waste-pickup schedule. Shared by the Kalenteri screen (which shows both) and
 * the Koti screen (which surfaces only the next pickup), so the emoji-based
 * garbage classification lives in exactly one place.
 *
 * See [KalenteriViewModel] for why garbage is told apart from family events by
 * PJHOY's emoji prefix rather than a `type` field.
 */
object CalendarParsing {

    // The kiosk (templates/calendar/app.js) fetches the same feed with `?days=30`;
    // matching it here is what makes the full waste schedule show more than the
    // one or two pickups that happen to fall in the coming week.
    const val CALENDAR_DAYS = 30

    /**
     * Parses `[{date, events:[{summary, time|start/end, location?}]}, …]` —
     * `get_calendar_events`' real shape, confirmed against
     * `handle_get_calendar_events` in marmorikatu-home-automation. Groups
     * arrive pre-sorted ascending by date with all-day events first, so this
     * only needs to fan events out into the garbage/family split; the
     * defensive re-sort below guards against that ordering changing upstream.
     */
    @OptIn(ExperimentalTime::class)
    fun parseCalendar(element: JsonElement): ParsedCalendar {
        // Accept a bare array (the real shape) or an object wrapping it, defensively.
        val groups = when (element) {
            is JsonArray -> element
            is JsonObject -> listOf("days", "events", "items", "result")
                .firstNotNullOfOrNull { element[it] as? JsonArray }
                ?: return ParsedCalendar.EMPTY
            else -> return ParsedCalendar.EMPTY
        }

        val zone = TimeZone.currentSystemDefault()
        val today = Clock.System.todayIn(zone)
        val tomorrow = today.plus(1, DateTimeUnit.DAY)

        val days = mutableListOf<CalendarDay>()
        val garbage = mutableListOf<Pair<LocalDate, GarbagePickup>>()

        for (node in groups) {
            val group = node as? JsonObject ?: continue
            val date = group.string("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: continue
            val events = group["events"] as? JsonArray ?: continue

            val familyEvents = mutableListOf<CalendarEvent>()
            for (evNode in events) {
                val ev = evNode as? JsonObject ?: continue
                val summary = ev.string("summary") ?: continue

                val garbageType = garbageTypeOrNull(summary)
                if (garbageType != null) {
                    val daysUntil = today.daysUntil(date).coerceAtLeast(0)
                    garbage += date to GarbagePickup(
                        type = garbageType,
                        dateLabel = dateLabel(date),
                        daysLabel = garbageDaysLabel(daysUntil),
                        soon = daysUntil <= 1,
                    )
                    continue
                }

                // All-day family events carry `time`; timed ones carry a pre-formatted "HH:MM" `start`.
                val allDay = ev["time"] != null
                familyEvents += CalendarEvent(
                    time = if (allDay) "koko pv" else ev.string("start").orEmpty(),
                    title = summary,
                    who = ev.string("location").orEmpty(),
                )
            }

            if (familyEvents.isNotEmpty()) {
                days += CalendarDay(
                    dayLabel = dayLabel(date, today, tomorrow),
                    dateLabel = dateLabel(date),
                    events = familyEvents,
                )
            }
        }

        return ParsedCalendar(
            days = days,
            garbage = garbage.sortedBy { it.first }.map { it.second },
        )
    }

    private fun dayLabel(date: LocalDate, today: LocalDate, tomorrow: LocalDate): String = when (date) {
        today -> "Tänään"
        tomorrow -> "Huomenna"
        else -> FI_WEEKDAYS[date.dayOfWeek.isoDayNumber - 1]
    }

    private fun dateLabel(date: LocalDate): String =
        "${FI_WEEKDAYS_SHORT[date.dayOfWeek.isoDayNumber - 1]} ${date.dayOfMonth}.${date.monthNumber}."

    /** "tänään" / "huomenna" / "N pv" — mirrors the kiosk's own <=1-day "urgent" cutoff. */
    private fun garbageDaysLabel(daysUntil: Int): String = when {
        daysUntil <= 0 -> "tänään"
        daysUntil == 1 -> "huomenna"
        else -> "$daysUntil pv"
    }

    /**
     * Null for family events; the clean waste-type name (leading emoji and
     * trailing " tyhjennys" stripped) for garbage. PJHOY's PRODUCT_GROUPS
     * always leads with one of these glyphs, including its fallback branch
     * for unmapped product groups (`🗑️ {name}`) — so the prefix check alone
     * is enough to classify, and the suffix strip is a no-op for names that
     * don't end in "tyhjennys".
     */
    private fun garbageTypeOrNull(summary: String): String? {
        val trimmed = summary.trim()
        val emoji = GARBAGE_EMOJIS.firstOrNull { trimmed.startsWith(it) } ?: return null
        return trimmed.removePrefix(emoji).trim().removeSuffix(" tyhjennys").trim()
    }

    private fun JsonObject.string(vararg keys: String): String? {
        for (key in keys) {
            (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    // isoDayNumber is 1 = Monday .. 7 = Sunday.
    private val FI_WEEKDAYS = listOf(
        "Maanantai", "Tiistai", "Keskiviikko", "Torstai", "Perjantai", "Lauantai", "Sunnuntai",
    )
    private val FI_WEEKDAYS_SHORT = listOf("ma", "ti", "ke", "to", "pe", "la", "su")

    // PJHOY's PRODUCT_GROUPS prefix (calendar_server.py, marmorikatu-home-automation).
    private val GARBAGE_EMOJIS = listOf("🗑️", "🍃", "📦", "🔄", "📄", "🔧", "🥃", "☣️")
}
