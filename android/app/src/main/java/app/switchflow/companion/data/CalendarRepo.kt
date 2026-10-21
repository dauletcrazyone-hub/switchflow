package app.switchflow.companion.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import app.switchflow.companion.net.AckDetail
import java.util.TimeZone

/** CalendarContract ↔ calendar-events.json. Events are added; nothing is changed or removed. */
class CalendarRepo(private val context: Context) {
    private val resolver get() = context.contentResolver

    fun exportAll(): List<CalendarEvent> {
        val out = mutableListOf<CalendarEvent>()
        val projection = arrayOf(
            Events.TITLE, Events.DTSTART, Events.DTEND, Events.DURATION, Events.ALL_DAY, Events.EVENT_TIMEZONE,
            Events.EVENT_LOCATION, Events.DESCRIPTION, Events.RRULE, Events.CALENDAR_DISPLAY_NAME,
        )
        resolver.query(Events.CONTENT_URI, projection, "${Events.DELETED} = 0", null, "${Events.DTSTART} ASC")?.use { c ->
            while (c.moveToNext()) {
                val start = c.getLong(1)
                val end = if (!c.isNull(2)) c.getLong(2) else c.getString(3)?.let(Rfc5545Duration::toMillis)?.let { start + it } ?: start
                out += CalendarEvent(
                    title = c.getString(0).orEmpty(),
                    startMs = start,
                    endMs = maxOf(end, start),
                    allDay = c.getInt(4) == 1,
                    timeZone = c.getString(5),
                    location = c.getString(6).orEmpty(),
                    notes = c.getString(7).orEmpty(),
                    rrule = c.getString(8)?.removePrefix("RRULE:")?.ifBlank { null },
                    calendarName = c.getString(9).orEmpty(),
                )
            }
        }
        return out
    }

    /** A calendar we may write to: the primary writable one, else a local "SwitchFlow" calendar. */
    private fun targetCalendarId(): Long {
        val sel = "${Calendars.CALENDAR_ACCESS_LEVEL} >= ${Calendars.CAL_ACCESS_CONTRIBUTOR} AND ${Calendars.VISIBLE} = 1"
        resolver.query(Calendars.CONTENT_URI, arrayOf(Calendars._ID, Calendars.IS_PRIMARY), sel, null, "${Calendars.IS_PRIMARY} DESC")?.use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        // No writable account calendar: create a local one through the sync-adapter URI.
        val uri = Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, "SwitchFlow")
            .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            .build()
        val values = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, "SwitchFlow")
            put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(Calendars.NAME, "SwitchFlow")
            put(Calendars.CALENDAR_DISPLAY_NAME, "SwitchFlow")
            put(Calendars.CALENDAR_COLOR, 0xFF478BFF.toInt())
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
            put(Calendars.OWNER_ACCOUNT, "SwitchFlow")
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
        }
        val created: Uri = resolver.insert(uri, values) ?: throw java.io.IOException("cannot create calendar")
        return created.lastPathSegment!!.toLong()
    }

    private fun exists(calendarId: Long, e: CalendarEvent): Boolean {
        val sel = "${Events.CALENDAR_ID} = ? AND ${Events.DTSTART} = ? AND ${Events.TITLE} = ? AND ${Events.DELETED} = 0"
        return resolver.query(Events.CONTENT_URI, arrayOf(Events._ID), sel, arrayOf(calendarId.toString(), e.startMs.toString(), e.title), null)
            ?.use { it.count > 0 } ?: false
    }

    fun import(events: List<CalendarEvent>): AckDetail {
        val calendarId = targetCalendarId()
        var imported = 0L
        var skipped = 0L
        for (e in events) {
            if (exists(calendarId, e)) {
                skipped++
                continue
            }
            val values = ContentValues().apply {
                put(Events.CALENDAR_ID, calendarId)
                put(Events.TITLE, e.title)
                put(Events.DESCRIPTION, e.notes)
                put(Events.EVENT_LOCATION, e.location)
                put(Events.DTSTART, e.startMs)
                put(Events.ALL_DAY, if (e.allDay) 1 else 0)
                // All-day events must be in UTC on Android.
                put(Events.EVENT_TIMEZONE, if (e.allDay) "UTC" else e.timeZone ?: TimeZone.getDefault().id)
                if (e.rrule != null) {
                    put(Events.RRULE, e.rrule)
                    put(Events.DURATION, Rfc5545Duration.fromMillis(maxOf(0, e.endMs - e.startMs)))
                } else {
                    put(Events.DTEND, maxOf(e.endMs, e.startMs))
                }
            }
            if (resolver.insert(Events.CONTENT_URI, values) != null) imported++
        }
        return AckDetail(imported = imported, skipped = skipped)
    }
}
