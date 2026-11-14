package com.fergusonconsultancy.foldwidgets

import com.cerniera.kit.Surface
import com.cerniera.kit.lum
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Environment
import android.os.StatFs
import android.provider.CalendarContract
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * The month. Weeks start on Monday. Today is a filled disc in the ink with its number cut out in
 * the ground's colour; days of the neighbouring months fade back; a day with something in your
 * calendar carries a small dot in the finish's light. On the large size the month's name heads it
 * in Italiana. Concentrato: smaller, the weekday initials fall away; smallest, only today is left,
 * its number in Italiana.
 */
object MonthPainter : Painter {

    override fun paint(c: Context, id: Int, canvas: Canvas, s: Surface): Scene {
        val open = Agenda.openDay(c, System.currentTimeMillis())
        val scene = Scene(listOf(Hit(RectF(0f, 0f, s.w, s.h), Act.Open(intent = open), "Open calendar")))
        // Turned sideways (12 Nov), a month too short for its grid keeps its week rather than shrinking
        // to today alone: the month's name, then this week, Monday first, today a filled disc
        if (s.h < 105f && s.w >= 2.2f * s.h && s.w >= 200f) { week(c, canvas, s); return scene }
        if (minOf(s.w, s.h) < 105f) { today(c, canvas, s); return scene }
        val large = s.w >= 220f && s.h >= 220f
        val k = if (large) minOf(s.w / 256f, s.h / 269f).coerceIn(0.8f, 1.6f) else minOf(s.w / 190f, s.h / 164f).coerceIn(0.62f, 1.5f)
        val p = (if (large) 18f else 13f) * k
        val today = Calendar.getInstance()
        var top = p

        // Month · Full is headed by the month's name at every size with room for it; Month ·
        // Compact is the grid alone, at every size (1 Nov: the two looked identical once placed)
        if (Widgets.titled(c, id) && s.h >= 140f) {
            val titleP = c.heading(s.ink(s.w / 2, p + 10f * k), k, 17f)
            canvas.textTop(SimpleDateFormat("MMMM", Locale.getDefault()).format(today.time), p + 2f * k, top, titleP)
            top += capHeight(titleP) + 14f * k
        }

        // The weeks this month touches, Monday first
        val first = (today.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) }
        val lead = (first.get(Calendar.DAY_OF_WEEK) + 5) % 7      // days of the previous month shown
        val days = today.getActualMaximum(Calendar.DAY_OF_MONTH)
        val rows = (lead + days + 6) / 7
        val start = (first.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, -lead) }
        val end = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, rows * 7) }
        val busy = busyDays(c, start.timeInMillis, end.timeInMillis)

        val cw = (s.w - 2 * p) / 7f
        if (minOf(s.w, s.h) >= 130f) {
            val headP = c.label(s.muted(s.w / 2, top + 4f * k), k, 9.5f).apply { textAlign = Paint.Align.CENTER }
            listOf("M", "T", "W", "T", "F", "S", "S").forEachIndexed { i, l -> canvas.textTop(l, p + cw * (i + 0.5f), top, headP) }
            top += capHeight(headP) + 9f * k
        }
        val rh = (s.h - p - top) / rows
        val size = minOf(cw * 0.46f, rh * 0.52f)

        val day = start.clone() as Calendar
        val todayKey = Agenda.dayKey(today)
        for (r in 0 until rows) for (col in 0 until 7) {
            val cx = p + cw * (col + 0.5f)
            val cy = top + rh * (r + 0.5f)
            val key = Agenda.dayKey(day)
            val inMonth = day.get(Calendar.MONTH) == today.get(Calendar.MONTH)
            val label = day.get(Calendar.DAY_OF_MONTH).toString()
            val numP = c.ink(size, if (inMonth) s.ink(cx, cy) else (s.ink(cx, cy) and 0x00FFFFFF) or 0x4D000000, 500)
                .apply { textAlign = Paint.Align.CENTER }
            if (key == todayKey) {
                val ink = s.ink(cx, cy)
                canvas.drawCircle(cx, cy, size * 0.95f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink })
                // The number in the ground's tone: dark on a light disc, light on a dark one
                numP.color = if (lum(ink) > 0.5f) 0xFF15191A.toInt() else Palette.IVORY
                numP.clearShadowLayer()
            }
            canvas.drawText(label, cx, cy + capHeight(numP) / 2, numP)
            if (key in busy && key != todayKey && inMonth) {
                canvas.drawCircle(cx, cy + size * 0.95f, size * 0.09f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = s.accent(cx, cy) })
            }
            day.add(Calendar.DAY_OF_MONTH, 1)
        }
        return scene
    }

    /** A wide, short month: its name, then the seven days of this week with their initials. */
    private fun week(c: Context, canvas: Canvas, s: Surface) {
        val today = Calendar.getInstance()
        val k = (s.h / 90f).coerceIn(0.75f, 1.3f)
        val p = 12f * k
        val titleP = c.label(s.muted(p + 20f, p + 4f), k, 10f)
        canvas.textTop(SimpleDateFormat("MMMM", Locale.getDefault()).format(today.time).uppercase(), p + 2f * k, p, titleP)
        val top = p + capHeight(titleP) + 8f * k
        val start = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, -((get(Calendar.DAY_OF_WEEK) + 5) % 7)) }
        val end = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 7) }
        val busy = busyDays(c, start.timeInMillis, end.timeInMillis)
        val cw = (s.w - 2 * p) / 7f
        val rowH = s.h - top - p
        val headP = c.label(s.muted(s.w / 2, top), k, 9.5f).apply { textAlign = Paint.Align.CENTER }
        val numY = top + capHeight(headP) + (rowH - capHeight(headP)) / 2f
        val size = minOf(cw * 0.42f, (rowH - capHeight(headP)) * 0.42f)
        val todayKey = Agenda.dayKey(today)
        val day = start.clone() as Calendar
        listOf("M", "T", "W", "T", "F", "S", "S").forEachIndexed { i, l ->
            val cx = p + cw * (i + 0.5f)
            canvas.textTop(l, cx, top, headP)
            val key = Agenda.dayKey(day)
            val numP = c.ink(size, s.ink(cx, numY), 500).apply { textAlign = Paint.Align.CENTER }
            if (key == todayKey) {
                val ink = s.ink(cx, numY)
                canvas.drawCircle(cx, numY, size * 0.95f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink })
                numP.color = if (lum(ink) > 0.5f) 0xFF15191A.toInt() else Palette.IVORY
            }
            canvas.drawText(day.get(Calendar.DAY_OF_MONTH).toString(), cx, numY + capHeight(numP) / 2, numP)
            if (key in busy && key != todayKey)
                canvas.drawCircle(cx, numY + size * 0.95f + 3f * k, size * 0.09f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = s.accent(cx, numY) })
            day.add(Calendar.DAY_OF_MONTH, 1)
        }
    }

    /** The vital thing alone: today's number in Italiana, its weekday beneath. */
    private fun today(c: Context, canvas: Canvas, s: Surface) {
        val now = Calendar.getInstance()
        val numP = c.ink(minOf(s.w, s.h) * 0.5f, s.ink(s.w / 2, s.h / 2), display = true).apply { textAlign = Paint.Align.CENTER }
        val dayP = c.ink(minOf(s.w, s.h) * 0.11f, s.muted(s.w / 2, s.h * 0.8f), 600, tracking = 0.14f).apply { textAlign = Paint.Align.CENTER }
        val gap = dayP.textSize * 0.9f
        val block = capHeight(numP) + gap + capHeight(dayP)
        val top = (s.h - block) / 2f
        canvas.drawText(now.get(Calendar.DAY_OF_MONTH).toString(), s.w / 2, top + capHeight(numP), numP)
        canvas.drawText(SimpleDateFormat("EEE", Locale.getDefault()).format(now.time).uppercase(), s.w / 2, top + block, dayP)
    }

    /** Days between [from] and [to] with at least one event, as day keys. Empty without access. */
    private fun busyDays(c: Context, from: Long, to: Long): Set<Int> {
        if (!Agenda.allowed(c)) return emptySet()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from - 86_400_000L); ContentUris.appendId(it, to)
        }.build()
        val out = HashSet<Int>()
        val local = Calendar.getInstance()
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        runCatching {
            c.contentResolver.query(uri, arrayOf(CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY),
                "${CalendarContract.Instances.VISIBLE} = 1", null, null)?.use { cur ->
                while (cur.moveToNext()) {
                    val cal = if (cur.getInt(1) == 1) utc else local
                    cal.timeInMillis = cur.getLong(0)
                    out += Agenda.dayKey(cal)
                }
            }
        }
        return out
    }
}
