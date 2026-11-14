package com.fergusonconsultancy.foldwidgets

import com.cerniera.kit.Finish
import com.cerniera.kit.Ground
import com.cerniera.kit.Surface
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import java.util.concurrent.Executors

/** What a touch area does. */
sealed class Act {
    /** Opens something: a pending intent another app gave us (a player's own screen), or an intent. */
    class Open(val pending: PendingIntent? = null, val intent: Intent? = null, val fallback: Intent? = null) : Act()
    /** Acts in place: play and pause, refresh. */
    class Send(val action: String) : Act()
}

/** A touch area, in dp inside the widget. */
class Hit(val rect: RectF, val act: Act, val label: String)

/** The progress line, in dp inside the widget, with its value from 0 to 1. */
class Line(val rect: RectF, val value: Float, val track: Int, val fill: Int)

/** What a painter leaves besides the picture: where touches go, and the line if there is one. */
/** [listTop]: where a scrolling list of rows begins, in dp, for widgets whose content scrolls (agenda). */
class Scene(val hits: List<Hit>, val line: Line? = null, val listTop: Float = -1f)

/** Paints one kind of widget on a canvas measured in dp, over its finish. */
interface Painter {
    fun paint(c: Context, id: Int, canvas: Canvas, s: Surface): Scene
}

enum class Kind(val painter: Painter) {
    MUSIC(MusicPainter), AGENDA(AgendaPainter), WEATHER(WeatherPainter), MONTH(MonthPainter);

    /**
     * How much larger the agenda's whole composition is drawn (1 Nov). The agenda keeps one
     * type scale on the cover screen, where a larger widget shows more rows; on the main screen
     * widgets are two to three times the size, and the same small type sat in wide empty margins.
     * So from 300 dp in both directions the composition grows with the widget, up to half as large
     * again. Music, weather and month scale within their own layouts.
     */
    fun scaleFor(w: Float, h: Float): Float = when (this) {
        AGENDA -> minOf(w / 300f, h / 300f).coerceIn(1f, 1.5f)
        else -> 1f
    }
}

/**
 * Every widget is one picture drawn at its exact size, so the type, artwork and controls scale with
 * it and nothing is ever stretched. On a resize the launcher reports the new size and the picture
 * is drawn again.
 */
abstract class Widget : AppWidgetProvider() {
    abstract val kind: Kind

    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        Widgets.inBackground(this) { ids.forEach { Widgets.render(c, it) } }
        Clockwork.arm(c)
    }

    override fun onAppWidgetOptionsChanged(c: Context, m: AppWidgetManager, id: Int, options: Bundle) {
        Widgets.inBackground(this) { Widgets.render(c, id) }
    }

    override fun onDeleted(c: Context, ids: IntArray) = ids.forEach { Prefs.forget(c, it) }
}

class MusicCompact : Widget() { override val kind = Kind.MUSIC }
class MusicSmall : Widget() { override val kind = Kind.MUSIC }
class MusicLarge : Widget() { override val kind = Kind.MUSIC }
class AgendaSmall : Widget() { override val kind = Kind.AGENDA }
class AgendaLarge : Widget() { override val kind = Kind.AGENDA }
class MonthSmall : Widget() { override val kind = Kind.MONTH }
class MonthLarge : Widget() { override val kind = Kind.MONTH }
class WeatherSmall : Widget() {
    override val kind = Kind.WEATHER
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) { super.onUpdate(c, m, ids); RefreshJob.schedule(c) }
}
class WeatherLarge : Widget() {
    override val kind = Kind.WEATHER
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) { super.onUpdate(c, m, ids); RefreshJob.schedule(c) }
}

object Widgets {
    /**
     * Which entry in the picker a widget was added from (1 Nov): each entry is a real variant,
     * not only a starting size, so what its name promises holds at every size it is resized to.
     */
    fun variant(c: Context, id: Int): String =
        runCatching { AppWidgetManager.getInstance(c).getAppWidgetInfo(id)?.provider?.className?.substringAfterLast('.') }.getOrNull().orEmpty()
    fun detailed(c: Context, id: Int) = variant(c, id) == "AgendaLarge"
    fun titled(c: Context, id: Int) = variant(c, id) == "MonthLarge"
    fun forecast(c: Context, id: Int) = variant(c, id) != "WeatherSmall"

    const val EXTRA_HIT = "fw.hit"
    const val EXTRA_SIZE = "fw.size"
    const val EXTRA_PENDING = "fw.pending"
    const val EXTRA_TARGET = "fw.target"
    const val EXTRA_FALLBACK = "fw.fallback"
    const val EXTRA_ROOT = "fw.root"

    private val providers = mapOf(
        Kind.MUSIC to listOf(MusicCompact::class.java, MusicSmall::class.java, MusicLarge::class.java),
        Kind.AGENDA to listOf(AgendaSmall::class.java, AgendaLarge::class.java),
        Kind.WEATHER to listOf(WeatherSmall::class.java, WeatherLarge::class.java),
        Kind.MONTH to listOf(MonthSmall::class.java, MonthLarge::class.java),
    )

    /** One worker, so redraws never overlap and never block the main thread. */
    private val worker = Executors.newSingleThreadExecutor()

    fun later(work: () -> Unit) = worker.execute { runCatching(work).onFailure { android.util.Log.e("FoldWidgets", "draw", it) } }

    fun inBackground(receiver: android.content.BroadcastReceiver, work: () -> Unit) {
        val pending = receiver.goAsync()
        worker.execute {
            try { runCatching(work).onFailure { android.util.Log.e("FoldWidgets", "draw", it) } } finally { pending.finish() }
        }
    }

    fun ids(c: Context, kind: Kind): IntArray {
        val m = AppWidgetManager.getInstance(c)
        return providers.getValue(kind).flatMap { m.getAppWidgetIds(ComponentName(c, it)).toList() }.toIntArray()
    }

    fun kindOf(c: Context, id: Int): Kind? {
        val name = AppWidgetManager.getInstance(c).getAppWidgetInfo(id)?.provider?.className ?: return null
        return providers.entries.firstOrNull { (_, list) -> list.any { it.name == name } }?.key
    }

    fun renderAll(c: Context, kind: Kind? = null) =
        (if (kind == null) Kind.entries else listOf(kind)).forEach { k -> ids(c, k).forEach { render(c, it) } }

    /** The widget's kind and its current size in cells, e.g. "Weather · 4×3", for settings screens. */
    fun describe(c: Context, id: Int, kind: Kind): String {
        val r = reportedSize(c, id)
        // A cover-screen cell is about 64 x 90 dp (from Mechack's screenshots, 31 Oct)
        val cols = Math.round(r.width / 64f).coerceAtLeast(1); val rows = Math.round(r.height / 90f + 0.25f).coerceAtLeast(1)
        return kind.name.lowercase().replaceFirstChar { it.uppercase() } + " · about $cols×$rows"
    }

    /** The size the launcher reports for widget [id], in dp, for the screen's orientation. */
    fun reportedSize(c: Context, id: Int): SizeF {
        val o = AppWidgetManager.getInstance(c).getAppWidgetOptions(id)
        @Suppress("DEPRECATION")
        val sizes = o.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)?.filter { it.width > 0 && it.height > 0 }
        val screen = Backdrop.screenSize(c)
        val portrait = screen.y >= screen.x
        if (!sizes.isNullOrEmpty()) {
            // One size per orientation: the narrower one is the portrait one
            return if (portrait) sizes.minBy { it.width / it.height } else sizes.maxBy { it.width / it.height }
        }
        val w = o.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
        val h = o.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
        if (w > 0 && h > 0) return SizeF(w.toFloat(), h.toFloat())
        val info = AppWidgetManager.getInstance(c).getAppWidgetInfo(id)
        val d = Backdrop.density(c)
        return SizeF((info?.minWidth ?: 500) / d, (info?.minHeight ?: 430) / d)
    }

    /** The picture's size in pixels and the density it is drawn at. Its size in dp is px / density. */
    class Frame(val px: Int, val py: Int, val density: Float) {
        val dp get() = SizeF(px / density, py / density)
    }

    /**
     * The exact pixels the widget occupies, once a tap on it has measured them; until then the
     * launcher's report. Drawing to the true pixel size means the picture is shown 1:1, with no
     * resampling, which is what keeps text as sharp as the system's own.
     */
    fun frameOf(c: Context, id: Int): Frame {
        val d = Backdrop.density(c)
        val seat = Backdrop.seat(c, id)
        val screen = Backdrop.screenSize(c)
        val r = reportedSize(c, id)
        if (seat != null && seat.exact && seat.screenW == screen.x && seat.screenH == screen.y && seat.repW > 0f && seat.repH > 0f) {
            // Measured at this very size: draw to the measured pixels
            if (Math.abs(seat.repW - r.width) < 1.5f && Math.abs(seat.repH - r.height) < 1.5f)
                return Frame(Math.round(seat.width), Math.round(seat.height), d)
            // Resized since (31 Oct): the old measurement no longer applies. Carry over how far the
            // launcher's report was from the truth, which is the same at every size, until a tap
            // measures the new size exactly
            val fx = seat.width / (seat.repW * d); val fy = seat.height / (seat.repH * d)
            return Frame(Math.round(r.width * d * fx), Math.round(r.height * d * fy), d)
        }
        return Frame(Math.round(r.width * d), Math.round(r.height * d), d)
    }

    /**
     * The sizes the launcher has for widget [id], one per orientation (portrait first), in dp.
     * Empty when the launcher reports none.
     */
    fun orientedSizes(c: Context, id: Int): List<SizeF> {
        val o = AppWidgetManager.getInstance(c).getAppWidgetOptions(id)
        @Suppress("DEPRECATION")
        val sizes = o.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)?.filter { it.width > 0 && it.height > 0 }.orEmpty()
        if (sizes.size >= 2) {
            val p = sizes.minBy { it.width / it.height }; val l = sizes.maxBy { it.width / it.height }
            return if (Math.abs(p.width - l.width) < 1f && Math.abs(p.height - l.height) < 1f) listOf(p) else listOf(p, l)
        }
        // Older launchers: portrait is min width by max height, landscape max width by min height
        val pw = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH); val ph = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        val lw = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH); val lh = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
        if (pw > 0 && ph > 0 && lw > 0 && lh > 0 && (pw != lw || ph != lh)) return listOf(SizeF(pw.toFloat(), ph.toFloat()), SizeF(lw.toFloat(), lh.toFloat()))
        return sizes
    }

    private fun same(a: SizeF, b: SizeF) = Math.abs(a.width - b.width) < 1.5f && Math.abs(a.height - b.height) < 1.5f

    /**
     * Draws widget [id] and hands it to the launcher: one picture for each way the phone can be
     * held (12 Nov: turned sideways, a widget showed its portrait picture shrunk to fit, with bands
     * either side). The launcher shows whichever fits, the moment the phone turns. The orientation
     * in use is drawn to its measured pixels against the wallpaper behind it; the other at its
     * reported size against the wallpaper's colour, until the phone turns and it is redrawn.
     *
     * Music keeps a single picture: its progress line moves by partial updates, which Android
     * applies only to a single picture, so it is redrawn as the phone turns instead (MediaListener).
     */
    fun render(c: Context, id: Int) {
        val kind = kindOf(c, id) ?: return
        val now = reportedSize(c, id)
        val sizes = orientedSizes(c, id)
        val m = AppWidgetManager.getInstance(c)
        // Without notification access there is no listener to redraw music on turning, and no line to move
        if ((kind == Kind.MUSIC && MediaListener.instance != null) || sizes.size < 2) {
            val (rv, listed) = views(c, id, kind, frameOf(c, id), Backdrop.ground(c, id), now, true)
            m.updateAppWidget(id, rv)
            @Suppress("DEPRECATION")
            if (listed) m.notifyAppWidgetViewDataChanged(id, R.id.list)
            return
        }
        val d = Backdrop.density(c)
        // RemoteViews carry at most 1.5 screens of bitmap; the other orientation gives way first
        val screen = Backdrop.screenSize(c)
        val budget = screen.x.toLong() * screen.y * 4 * 3 / 2 * 85 / 100
        val current = frameOf(c, id)
        val (_, cpx, cpy) = drawn(current)
        val map = LinkedHashMap<SizeF, RemoteViews>()
        var listed = false
        for (s in sizes) {
            val isNow = same(s, now)
            val frame = if (isNow) current else {
                val spare = (budget - cpx.toLong() * cpy * 4).coerceAtLeast(budget / 4)
                val want = s.width * d * s.height * d * 4
                val k = if (want > spare) Math.sqrt(spare / want.toDouble()).toFloat() else 1f
                Frame(Math.round(s.width * d * k), Math.round(s.height * d * k), d * k)
            }
            val (rv, l) = views(c, id, kind, frame, if (isNow) Backdrop.ground(c, id) else Backdrop.flatGround(c), s, isNow)
            listed = listed || l
            map[s] = rv
        }
        if (map.keys.none { same(it, now) }) {
            // The size in use isn't among those listed (a launcher mid-resize): draw it as well
            map[now] = views(c, id, kind, current, Backdrop.ground(c, id), now, true).first
        }
        m.updateAppWidget(id, RemoteViews(map))
        @Suppress("DEPRECATION")
        if (listed) m.notifyAppWidgetViewDataChanged(id, R.id.list)
    }

    /** One picture of widget [id] for the size [key] (dp), with its touch areas, list and line. */
    private fun views(c: Context, id: Int, kind: Kind, frame: Frame, ground: Ground, key: SizeF, isNow: Boolean): Pair<RemoteViews, Boolean> {
        val size = frame.dp
        val (bitmap, scene) = draw(c, id, kind, frame, Prefs.finish(c, id), ground)
        val listed = scene.listTop >= 0f
        val rv = RemoteViews(c.packageName, if (listed) R.layout.widget_list else R.layout.widget)
        if (listed) {
            // A scrolling list of rows over the picture, filled by AgendaRows, each row its own picture,
            // drawn for this orientation's width
            place(rv, R.id.list, RectF(0f, scene.listTop, size.width, size.height - 6f))
            rv.setRemoteAdapter(R.id.list, Intent(c, AgendaRows::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AgendaRows.EXTRA_W, key.width).putExtra(AgendaRows.EXTRA_H, key.height)
                .setData(Uri.parse("foldwidgets://rows/$id/${key.width.toInt()}x${key.height.toInt()}")))
            rv.setPendingIntentTemplate(R.id.list, PendingIntent.getActivity(c, id * 32 + 31,
                Intent(c, TapActivity::class.java).setData(Uri.parse("foldwidgets://row/$id"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE))
        }
        rv.setImageViewBitmap(R.id.face, bitmap)
        rv.setContentDescription(R.id.face, kind.name.lowercase())
        // Each orientation's taps get their own request codes, so neither overwrites the other's
        val slot = if (isNow) 0 else 16
        HITS.forEachIndexed { i, viewId ->
            val hit = scene.hits.getOrNull(i)
            if (hit == null) { rv.setViewVisibility(viewId, View.GONE); return@forEachIndexed }
            val root = isRoot(hit.rect, size)
            if (root) fill(rv, viewId) else place(rv, viewId, hit.rect)
            rv.setViewVisibility(viewId, View.VISIBLE)
            rv.setContentDescription(viewId, hit.label)
            rv.setOnClickPendingIntent(viewId, pendingFor(c, id, slot + i, hit, size, root))
        }
        val line = scene.line
        if (line == null) rv.setViewVisibility(R.id.progress, View.GONE) else {
            place(rv, R.id.progress, line.rect)
            rv.setViewVisibility(R.id.progress, View.VISIBLE)
            rv.setProgressBar(R.id.progress, 1000, (line.value.coerceIn(0f, 1f) * 1000).toInt(), false)
            rv.setColorStateList(R.id.progress, "setProgressTintList", ColorStateList.valueOf(line.fill))
            rv.setColorStateList(R.id.progress, "setProgressBackgroundTintList", ColorStateList.valueOf(line.track))
        }
        if (isNow) {
            // What was reported, what was measured and what was drawn, for the settings screen
            val seat = Backdrop.seat(c, id)
            Prefs.of(c).edit().putString("diag_$id", "${kind.name.lowercase()}: reported ${key.width.toInt()} x ${key.height.toInt()} dp, " +
                (if (seat?.exact == true && Math.abs(seat.repW - key.width) < 1.5f && Math.abs(seat.repH - key.height) < 1.5f) "measured ${seat.width.toInt()} x ${seat.height.toInt()} px"
                 else "not yet measured at this size (tap it once)") +
                ", drawn ${bitmap.width} x ${bitmap.height} px at ${"%.3f".format(frame.density)}").apply()
        }
        return rv to listed
    }

    /** A touch area covering the whole widget: laid out to fill it, so a tap on it measures it. */
    private fun isRoot(r: RectF, size: SizeF) =
        r.left <= 0.5f && r.top <= 0.5f && r.right >= size.width - 0.5f && r.bottom >= size.height - 0.5f

    private fun fill(rv: RemoteViews, viewId: Int) {
        val px = TypedValue.COMPLEX_UNIT_PX
        rv.setViewLayoutWidth(viewId, android.view.ViewGroup.LayoutParams.MATCH_PARENT.toFloat(), px)
        rv.setViewLayoutHeight(viewId, android.view.ViewGroup.LayoutParams.MATCH_PARENT.toFloat(), px)
        rv.setViewLayoutMargin(viewId, RemoteViews.MARGIN_LEFT, 0f, px)
        rv.setViewLayoutMargin(viewId, RemoteViews.MARGIN_TOP, 0f, px)
    }

    /** Moves the progress line without redrawing the picture: cheap enough to do every second. */
    fun nudge(c: Context, id: Int, value: Float) {
        val rv = RemoteViews(c.packageName, R.layout.widget)
        rv.setProgressBar(R.id.progress, 1000, (value.coerceIn(0f, 1f) * 1000).toInt(), false)
        AppWidgetManager.getInstance(c).partiallyUpdateAppWidget(id, rv)
    }

    /**
     * The picture. Drawn on a canvas scaled to dp, so painters think in dp and the result is
     * sharp at the screen's density. [ground] is what is behind the widget.
     */
    /** The density and pixel size a widget's picture is drawn at: its frame, kept inside what a launcher accepts. */
    fun drawn(frame: Frame): Triple<Float, Int, Int> {
        var d = frame.density
        var px = frame.px.coerceAtLeast(1); var py = frame.py.coerceAtLeast(1)
        val longest = maxOf(px, py)
        if (longest > 1800) { val f = 1800f / longest; d *= f; px = (px * f).toInt(); py = (py * f).toInt() }
        return Triple(d, px, py)
    }

    fun surfaceFor(c: Context, id: Int, frame: Frame): Surface {
        val (d, px, py) = drawn(frame)
        val size = SizeF(px / d, py / d)
        return Surface(Prefs.finish(c, id), Backdrop.ground(c, id), size.width, size.height, Prefs.radius(c).toFloat().coerceAtMost(minOf(size.width, size.height) / 2f), 1.1f / d)
    }

    fun draw(c: Context, id: Int, kind: Kind, frame: Frame, finish: Finish, ground: Ground): Pair<Bitmap, Scene> {
        val (d, px, py) = drawn(frame)
        val size = SizeF(px / d, py / d)
        val bmp = Bitmap.createBitmap(px, py, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp).apply { scale(d, d) }
        val surface = Surface(finish, ground, size.width, size.height, Prefs.radius(c).toFloat().coerceAtMost(minOf(size.width, size.height) / 2f), 1.1f / d)
        surface.paint(canvas)
        val k = kind.scaleFor(size.width, size.height)
        if (k == 1f) return bmp to kind.painter.paint(c, id, canvas, surface)
        canvas.save(); canvas.scale(k, k)
        val inner = kind.painter.paint(c, id, canvas, surface.scaled(k))
        canvas.restore()
        fun RectF.up() = RectF(left * k, top * k, right * k, bottom * k)
        val scene = Scene(inner.hits.map { Hit(it.rect.up(), it.act, it.label) },
            inner.line?.let { Line(it.rect.up(), it.value, it.track, it.fill) },
            if (inner.listTop >= 0f) inner.listTop * k else -1f)
        return bmp to scene
    }

    private fun place(rv: RemoteViews, viewId: Int, r: RectF) {
        val dip = TypedValue.COMPLEX_UNIT_DIP
        rv.setViewLayoutWidth(viewId, r.width(), dip)
        rv.setViewLayoutHeight(viewId, r.height(), dip)
        rv.setViewLayoutMargin(viewId, RemoteViews.MARGIN_LEFT, r.left, dip)
        rv.setViewLayoutMargin(viewId, RemoteViews.MARGIN_TOP, r.top, dip)
    }

    /**
     * Every touch passes through this app first, carrying where it landed, so the widget learns
     * its seat. Mutable on purpose: the launcher fills in the touch's bounds on the way through,
     * and an immutable intent would drop them. The intents are explicit, to our own components.
     */
    private fun pendingFor(c: Context, id: Int, index: Int, hit: Hit, size: SizeF, root: Boolean): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        val code = id * 32 + index
        fun Intent.mark() = apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            putExtra(EXTRA_HIT, floatArrayOf(hit.rect.left, hit.rect.top, hit.rect.right, hit.rect.bottom))
            putExtra(EXTRA_SIZE, floatArrayOf(size.width, size.height))
            putExtra(EXTRA_ROOT, root)
            data = Uri.parse("foldwidgets://tap/$id/$index")
        }
        return when (val act = hit.act) {
            is Act.Open -> PendingIntent.getActivity(c, code, Intent(c, TapActivity::class.java).mark().apply {
                act.pending?.let { putExtra(EXTRA_PENDING, it) }
                act.intent?.let { putExtra(EXTRA_TARGET, it) }
                act.fallback?.let { putExtra(EXTRA_FALLBACK, it) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }, flags)
            is Act.Send -> PendingIntent.getBroadcast(c, code, Intent(c, TapReceiver::class.java).setAction(act.action).mark(), flags)
        }
    }

    /** Reads a tap's touch area and widget size back out of its intent. */
    fun hitOf(i: Intent): RectF? = i.getFloatArrayExtra(EXTRA_HIT)?.takeIf { it.size == 4 }?.let { RectF(it[0], it[1], it[2], it[3]) }
    fun sizeOf(i: Intent): SizeF? = i.getFloatArrayExtra(EXTRA_SIZE)?.takeIf { it.size == 2 }?.let { SizeF(it[0], it[1]) }

    /** Learns the seat from a tap, and redraws the widget if it moved. */
    fun seatFrom(c: Context, i: Intent) {
        val id = i.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
        if (Backdrop.record(c, i, id, hitOf(i), sizeOf(i), i.getBooleanExtra(EXTRA_ROOT, false), reportedSize(c, id))) later { render(c, id) }
    }

    private val HITS = intArrayOf(R.id.hit0, R.id.hit1, R.id.hit2, R.id.hit3, R.id.hit4, R.id.hit5,
        R.id.hit6, R.id.hit7, R.id.hit8, R.id.hit9, R.id.hit10, R.id.hit11)
}
