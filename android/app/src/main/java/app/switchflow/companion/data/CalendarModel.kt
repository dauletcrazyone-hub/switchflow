package app.switchflow.companion.data

import kotlinx.serialization.Serializable

/** Platform-neutral calendar export (calendar-events.json), shared with the iOS app. */
@Serializable
data class CalendarEvent(
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val allDay: Boolean = false,
    val timeZone: String? = null,
    val location: String = "",
    val notes: String = "",
    /** RFC 5545 recurrence rule without the "RRULE:" prefix. */
    val rrule: String? = null,
    val calendarName: String = "",
)

@Serializable
data class CalendarExport(val version: Int = 1, val events: List<CalendarEvent>)

object Rfc5545Duration {
    // Android writes "P3600S" without the "T" that RFC 5545 requires, so "T" is optional here.
    private val re = Regex("""^([+-])?P(?:(\d+)W)?(?:(\d+)D)?(?:T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$""")

    /** "PT1H30M" → 5 400 000; Android stores recurring event lengths this way. */
    fun toMillis(d: String): Long? {
        val m = re.matchEntire(d.trim().uppercase()) ?: return null
        val (sign, w, day, h, min, s) = m.destructured
        val secs = (w.toLongOrNull() ?: 0) * 604800 + (day.toLongOrNull() ?: 0) * 86400 +
            (h.toLongOrNull() ?: 0) * 3600 + (min.toLongOrNull() ?: 0) * 60 + (s.toLongOrNull() ?: 0)
        return (if (sign == "-") -secs else secs) * 1000
    }

    fun fromMillis(ms: Long): String = "P${ms / 1000}S"
}
