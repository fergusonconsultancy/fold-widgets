package com.fergusonconsultancy.foldwidgets

import com.cerniera.kit.Surface
import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.provider.CalendarContract
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** One occurrence of an event. All-day events are kept on their own calendar day. */
class Occurrence(val eventId: Long, val title: String, val begin: Long, val end: Long, val allDay: Boolean, val colour: Int, val day: Int, val location: String = "", val calendar: Long = -1L)

object Agenda {
    const val DAYS = 14

    fun allowed(c: Context) = c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** A day as a number that sorts, yyyymmdd. */
    fun dayKey(cal: Calendar) = cal.get(Calendar.YEAR) * 10000 + (cal.get(Calendar.MONTH) + 1) * 100 + cal.get(Calendar.DAY_OF_MONTH)

    /**
     * Everything from now to [DAYS] days ahead, in order, past events dropped. For widget [id], only
     * its chosen calendars and only as many days as it is set to show; declined invitations are
     * left out unless settings keep them (5 Nov 2026).
     */
    fun ahead(c: Context, id: Int? = null): List<Occurrence> {
        val all = everything(c)
        if (id == null) return all
        val cals = AgendaOptions.calendars(c, id)
        val last = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, AgendaOptions.days(c, id) - 1) }.let { dayKey(it) }
        return all.filter { (cals == null || it.calendar in cals) && it.day <= last }
    }

    private fun everything(c: Context): List<Occurrence> {
        if (!allowed(c)) return emptyList()
        val now = System.currentTimeMillis()
        val start = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val from = start.timeInMillis - 86_400_000L  // all-day events are stored in UTC, so look a day back
        val to = start.timeInMillis + DAYS * 86_400_000L
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from); ContentUris.appendId(it, to)
        }.build()
        val cols = arrayOf(
            CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.DISPLAY_COLOR,
            CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
        )
        val hideDeclined = AgendaOptions.hideDeclined(c)
        val out = ArrayList<Occurrence>()
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val local = Calendar.getInstance()
        val today = dayKey(local)
        runCatching {
            c.contentResolver.query(uri, cols, "${CalendarContract.Instances.VISIBLE} = 1", null, "${CalendarContract.Instances.BEGIN} ASC")?.use { cur ->
                while (cur.moveToNext()) {
                    if (hideDeclined && cur.getInt(8) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                    val allDay = cur.getInt(4) == 1
                    val begin = cur.getLong(2); val end = cur.getLong(3)
                    val day = if (allDay) { utc.timeInMillis = begin; dayKey(utc) } else { local.timeInMillis = begin; dayKey(local) }
                    if (allDay) {
                        // An all-day event ends at midnight UTC of the day after its last day
                        utc.timeInMillis = end - 1; if (dayKey(utc) < today) continue
                    } else if (end <= now) continue
                    // A timed event that began before today and runs on belongs to today
                    out += Occurrence(cur.getLong(0), cur.getString(1)?.takeIf { it.isNotBlank() } ?: "Busy", begin, end, allDay,
                        cur.getInt(5) or 0xFF000000.toInt(), if (allDay) day else maxOf(day, today), cur.getString(6)?.trim().orEmpty(), cur.getLong(7))
                }
            }
        }
        // Today's all-day events that began earlier still show today
        return out.map { if (it.allDay && it.day < today) Occurrence(it.eventId, it.title, it.begin, it.end, true, it.colour, today, it.location, it.calendar) else it }
            .sortedWith(compareBy<Occurrence> { it.day }.thenBy { !it.allDay }.thenBy { it.begin })
    }

    /** The moment the agenda next changes on its own: the next event's end, or midnight. */
    fun nextChange(c: Context): Long {
        val midnight = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 5)
        }.timeInMillis
        val nextEnd = ahead(c).filter { !it.allDay }.minOfOrNull { it.end } ?: Long.MAX_VALUE
        return minOf(midnight, nextEnd)
    }

    fun openDay(c: Context, millis: Long): Intent = Intent(Intent.ACTION_VIEW)
        .setData(CalendarContract.CONTENT_URI.buildUpon().appendPath("time").also { ContentUris.appendId(it, millis) }.build())
        .also { AgendaOptions.app(c)?.let(it::setPackage) }

    fun openEvent(c: Context, o: Occurrence): Intent = Intent(Intent.ACTION_VIEW)
        .setData(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, o.eventId))
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, o.begin)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, o.end)
        .also { AgendaOptions.app(c)?.let(it::setPackage) }

    /** One of the phone's calendars, as settings lists it. */
    class Cal(val id: Long, val name: String, val account: String, val colour: Int)

    /** Every calendar the phone shows, grouped by account. */
    fun calendars(c: Context): List<Cal> {
        if (!allowed(c)) return emptyList()
        val out = ArrayList<Cal>()
        runCatching {
            c.contentResolver.query(CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                    CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.CALENDAR_COLOR),
                "${CalendarContract.Calendars.VISIBLE} = 1", null, null)?.use { cur ->
                while (cur.moveToNext()) out += Cal(cur.getLong(0), cur.getString(1)?.takeIf { it.isNotBlank() } ?: "Calendar",
                    cur.getString(2).orEmpty(), cur.getInt(3) or 0xFF000000.toInt())
            }
        }
        return out.sortedWith(compareBy<Cal> { it.account.lowercase() }.thenBy { it.name.lowercase() })
    }
}

/**
 * What each agenda shows (5 Nov 2026): its calendars and how far ahead, per widget, so one agenda
 * can carry the fixtures and another the week's plans. Declined invitations and the calendar app a
 * tap opens are set once for every agenda and month.
 */
object AgendaOptions {
    val RANGES = listOf(1 to "Today", 3 to "3 days", 7 to "A week", Agenda.DAYS to "2 weeks")

    /** The widget's calendars, or null for every calendar (also for calendars added later). */
    fun calendars(c: Context, id: Int): Set<Long>? =
        Prefs.of(c).getStringSet("agenda_cals_$id", null)?.mapNotNull { it.toLongOrNull() }?.toSet()

    fun setCalendar(c: Context, id: Int, cal: Long, on: Boolean) {
        val every = Agenda.calendars(c).map { it.id }.toSet()
        val now = (calendars(c, id) ?: every).toMutableSet().apply { if (on) add(cal) else remove(cal) }
        Prefs.of(c).edit().apply {
            if (now.containsAll(every)) remove("agenda_cals_$id") else putStringSet("agenda_cals_$id", now.map { it.toString() }.toSet())
        }.apply()
    }

    fun days(c: Context, id: Int): Int = Prefs.of(c).getInt("agenda_days_$id", Agenda.DAYS)
    fun setDays(c: Context, id: Int, n: Int) = Prefs.of(c).edit().putInt("agenda_days_$id", n).apply()

    fun hideDeclined(c: Context): Boolean = Prefs.of(c).getBoolean("agenda_hide_declined", true)
    fun setHideDeclined(c: Context, on: Boolean) = Prefs.of(c).edit().putBoolean("agenda_hide_declined", on).apply()

    const val SAMSUNG = "com.samsung.android.calendar"
    const val GOOGLE = "com.google.android.calendar"

    /** The calendar app taps open, or null for the phone's default. A removed app falls back. */
    fun app(c: Context): String? = Prefs.of(c).getString("agenda_app", null)?.takeIf { installed(c, it) }
    fun setApp(c: Context, pkg: String?) = Prefs.of(c).edit().apply { if (pkg == null) remove("agenda_app") else putString("agenda_app", pkg) }.apply()
    fun installed(c: Context, pkg: String) = c.packageManager.getLaunchIntentForPackage(pkg) != null
}

/**
 * The agenda. The date heads it, the day number in Italiana; then the days ahead, each named in
 * small tracked capitals in the finish's light, each event a line with its calendar's colour as a
 * bar, its time and its title.
 *
 * Optimised for 4x3, 5x3, 4x4 and 5x4 on the cover screen (31 Oct). The type keeps one size at every
 * span, as Samsung's own agenda does, so a bigger widget shows more rather than shows it larger.
 * From four rows tall each event gains a second line, its time range and place. Below about
 * 130 dp the month's name, the day headings and the count of the rest fall away.
 */
object AgendaPainter : Painter {

    override fun paint(c: Context, id: Int, canvas: Canvas, s: Surface): Scene {
        val now = System.currentTimeMillis()
        val hits = ArrayList<Hit>()
        hits += Hit(RectF(0f, 0f, s.w, s.h), Act.Open(intent = Agenda.openDay(c, now)), "Open calendar")
        val bare = minOf(s.w, s.h) < 130f
        val wide = s.w > s.h * 1.55f && s.w >= 240f
        val tall = s.h >= 330f
        val p = if (bare) 12f else if (minOf(s.w, s.h) >= 220f) 18f else 15f

        // The date
        val cal = Calendar.getInstance()
        val num = cal.get(Calendar.DAY_OF_MONTH).toString()
        val numP = c.ink(if (tall) 42f else if (bare) 26f else 34f, s.ink(p + 20f, p + 18f), display = true)
        canvas.textTop(num, p, p, numP)
        val capNum = capHeight(numP)
        val numW = numP.measureText(num)
        val tx = p + numW + 10f
        val headW = (if (wide) s.w * 0.34f else s.w - p) - tx
        val dayP = c.ink(if (bare) 13f else 15f, s.ink(tx + 30f, p + 6f), 600)
        val monthP = c.meta(s.muted(tx + 30f, p + capNum), 1f, 13f)
        if (bare) canvas.drawText(fit(SimpleDateFormat("EEEE", Locale.getDefault()).format(cal.time), dayP, headW), tx, p + capNum, dayP)
        else {
            canvas.textTop(fit(SimpleDateFormat("EEEE", Locale.getDefault()).format(cal.time), dayP, headW), tx, p + 1f, dayP)
            canvas.drawText(fit(SimpleDateFormat("MMMM", Locale.getDefault()).format(cal.time), monthP, headW), tx, p + capNum, monthP)
        }
        val headBottom = p + capNum

        if (!Agenda.allowed(c)) {
            val noteP = c.ink(13f, s.muted(s.w / 2, headBottom + 30f), 450)
            canvas.textTop(fit("Tap to show your calendar", noteP, s.w - 2 * p), p, headBottom + 22f, noteP)
            hits[0] = Hit(RectF(0f, 0f, s.w, s.h), Act.Open(intent = Intent(c, SettingsActivity::class.java)), "Allow calendar access")
            return Scene(hits)
        }

        // The days ahead
        val x0 = if (wide) s.w * 0.34f + 8f else p
        var y = if (wide) p + 2f else headBottom + (if (bare) 12f else 18f)
        val listW = s.w - x0 - p
        val events = Agenda.ahead(c, id)
        val labelP = c.label(s.accent(x0 + listW / 2, y))
        val timeP = c.ink(14.5f, s.ink(x0 + 30f, y), 600)
        val titleP = c.ink(14.5f, s.ink(x0 + listW / 2, y), 450)
        val detailP = c.meta(s.muted(x0 + listW / 2, y))
        val fmt = android.text.format.DateFormat.getTimeFormat(c)
        val widest = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 22); set(Calendar.MINUTE, 58) }
        val timeW = timeP.measureText(fmt.format(widest.time)) + 8f
        val rowH = if (tall) 40f else 27f
        val labelH = 11f + 9f
        val bottom = s.h - p
        val todayKey = Agenda.dayKey(Calendar.getInstance())

        if (events.isEmpty()) {
            val emptyP = c.statement(s.ink(x0 + listW / 2, y + 12f))
            canvas.textTop(if (AgendaOptions.days(c, id) == 1) "Nothing more today" else "Nothing ahead", x0, y + 4f, emptyP)
            val noteP = c.ink(13f, s.muted(x0 + listW / 2, y + 34f), 450)
            canvas.textTop("Two clear weeks", x0, y + 4f + emptyP.textSize * 1.3f, noteP)
            return Scene(hits)
        }

        // The days ahead scroll (1 Nov): the date stays in the picture, and
        // each day heading and event is a row of its own in a scrolling list laid over the glass
        if (!bare) return Scene(hits, listTop = y - 4f)

        var shown = 0
        var lastDay = -1
        for ((i, o) in events.withIndex()) {
            val needsLabel = o.day != lastDay && !bare
            val thisRow = if (o.allDay) 27f else rowH
            val need = (if (needsLabel) labelH + (if (lastDay >= 0) 9f else 0f) else 0f) + thisRow
            val reserve = if (i < events.size - 1 && !bare) 16f else 0f
            if (y + need + reserve > bottom + 2f) break
            if (needsLabel) {
                if (lastDay >= 0) y += 9f
                labelP.color = s.accent(x0 + listW / 2, y)
                canvas.textTop(dayName(o.day), x0, y, labelP)
                y += labelH
                lastDay = o.day
            }
            val firstMid = y + 9f
            val detailed = tall && !o.allDay
            // The calendar's colour, as a bar spanning the event's lines
            canvas.drawRoundRect(RectF(x0, y + 1f, x0 + 3f, y + (if (detailed) 32f else 17f)), 1.5f, 1.5f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = o.colour })
            val tx0 = x0 + 11f
            val time = when {
                bare && o.day != todayKey -> SimpleDateFormat("EEE", Locale.getDefault()).format(java.util.Date(o.begin))
                o.allDay -> ""
                o.begin <= now -> "Now"
                else -> fmt.format(java.util.Date(o.begin))
            }
            // Timed events: the time, then the title. All-day: the title alone, as Samsung's does
            val titleX = if (time.isEmpty()) tx0 else tx0 + timeW
            if (time.isNotEmpty()) {
                timeP.color = s.ink(tx0, firstMid)
                canvas.drawText(fit(time, timeP, timeW), tx0, firstMid + capHeight(timeP) / 2, timeP)
            }
            titleP.color = s.ink(titleX + 30f, firstMid)
            canvas.drawText(fit(o.title, titleP, s.w - p - titleX), titleX, firstMid + capHeight(titleP) / 2, titleP)
            if (detailed) {
                val range = fmt.format(java.util.Date(o.begin)) + " – " + fmt.format(java.util.Date(o.end))
                val detail = if (o.location.isNotEmpty()) "$range  ·  ${o.location}" else range
                detailP.color = s.muted(titleX + 30f, firstMid + 17f)
                canvas.drawText(fit(detail, detailP, s.w - p - titleX), titleX, firstMid + 17f + capHeight(detailP) / 2, detailP)
            }
            if (hits.size < 12) hits += Hit(RectF(x0 - 4f, y - 3f, s.w, y + thisRow - 3f), Act.Open(intent = Agenda.openEvent(c, o)), o.title)
            y += thisRow
            shown++
        }
        if (shown < events.size && !bare) {
            val moreP = c.meta(s.muted(x0 + listW / 2, y + 6f))
            canvas.textTop("+ ${events.size - shown} more", x0 + 11f, y + 2f, moreP)
        }
        return Scene(hits)
    }

    /** The measures the scrolling list shares with the widget. */
    class RowStyle(val x0: Float, val tall: Boolean, val timeW: Float) {
        val labelH = 11f + 9f
        fun height(o: Occurrence?, first: Boolean) = if (o == null) labelH + (if (first) 0f else 9f) else if (o.allDay) 27f else if (tall) 40f else 27f
    }

    /** [detailed]: Agenda · Detailed gives every timed event its range and place; Agenda · List never does (1 Nov). */
    fun rowStyle(c: Context, s: Surface, detailed: Boolean): RowStyle {
        val wide = s.w > s.h * 1.55f && s.w >= 240f
        val p = if (minOf(s.w, s.h) >= 220f) 18f else 15f
        val fmt = android.text.format.DateFormat.getTimeFormat(c)
        val widest = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 22); set(Calendar.MINUTE, 58) }
        val timeW = c.ink(14.5f, 0, 600).measureText(fmt.format(widest.time)) + 8f
        return RowStyle(if (wide) s.w * 0.34f + 8f else p, detailed, timeW)
    }

    /** A day heading row: TODAY, TOMORROW, MON 2 NOV. */
    fun paintDay(c: Context, cv: Canvas, s: Surface, st: RowStyle, day: Int, first: Boolean, sampleY: Float) {
        val labelP = c.label(s.accent(s.w / 2, sampleY))
        cv.textTop(dayName(day), st.x0, if (first) 0f else 9f, labelP)
    }

    /** One event as a row of the scrolling list, [width] dp wide. */
    fun paintEvent(c: Context, cv: Canvas, s: Surface, st: RowStyle, o: Occurrence, width: Float, sampleY: Float) {
        val p = if (minOf(s.w, s.h) >= 220f) 18f else 15f
        val now = System.currentTimeMillis()
        val fmt = android.text.format.DateFormat.getTimeFormat(c)
        val firstMid = 9f
        val detailed = st.tall && !o.allDay
        cv.drawRoundRect(RectF(st.x0, 1f, st.x0 + 3f, if (detailed) 32f else 17f), 1.5f, 1.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = o.colour })
        val tx0 = st.x0 + 11f
        val time = when { o.allDay -> ""; o.begin <= now -> "Now"; else -> fmt.format(java.util.Date(o.begin)) }
        val titleX = if (time.isEmpty()) tx0 else tx0 + st.timeW
        if (time.isNotEmpty()) {
            val timeP = c.ink(14.5f, s.ink(tx0, sampleY), 600)
            cv.drawText(fit(time, timeP, st.timeW), tx0, firstMid + capHeight(timeP) / 2, timeP)
        }
        val titleP = c.ink(14.5f, s.ink(titleX + 30f, sampleY), 450)
        cv.drawText(fit(o.title, titleP, width - p - titleX), titleX, firstMid + capHeight(titleP) / 2, titleP)
        if (detailed) {
            val range = fmt.format(java.util.Date(o.begin)) + " – " + fmt.format(java.util.Date(o.end))
            val detail = if (o.location.isNotEmpty()) "$range  ·  ${o.location}" else range
            val detailP = c.meta(s.muted(titleX + 30f, sampleY))
            cv.drawText(fit(detail, detailP, width - p - titleX), titleX, firstMid + 17f + capHeight(detailP) / 2, detailP)
        }
    }

    /** TODAY, TOMORROW, then MON 2 NOV. */
    private fun dayName(key: Int): String {
        val cal = Calendar.getInstance()
        val today = Agenda.dayKey(cal)
        cal.add(Calendar.DAY_OF_YEAR, 1)
        val tomorrow = Agenda.dayKey(cal)
        return when (key) {
            today -> "TODAY"
            tomorrow -> "TOMORROW"
            else -> {
                val d = Calendar.getInstance().apply { set(key / 10000, (key / 100) % 100 - 1, key % 100) }
                SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(d.time).uppercase()
            }
        }
    }
}
