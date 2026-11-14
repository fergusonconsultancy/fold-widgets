package com.fergusonconsultancy.foldwidgets

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.Executor

/**
 * Live mode (2 Nov 2026). Mechack's standard: the widgets must keep their type as legible as the
 * taskbar does, page after page. A widget is a picture the launcher shows; Android never lets it
 * see what is behind it, and the launcher scrolls the wallpaper between pages without telling
 * anyone. So the widgets were tinted for where the wallpaper was when they were drawn, not where
 * it is now.
 *
 * This service is the widgets' eyes, as the taskbar's service is the bar's. Each time the home
 * screen comes to rest it takes one screenshot and:
 * 1. finds every Fold widget on screen, to the pixel, from the launcher's own view tree, so a
 *    widget is placed and measured without a tap;
 * 2. finds where the launcher has scrolled the wallpaper, by sliding the wallpaper image across
 *    the screenshot until the visible parts agree, and how the launcher has tinted it (One UI
 *    dims wallpapers in dark mode), from the same comparison;
 * 3. redraws only the widgets whose ground has really changed.
 *
 * The screenshot never leaves the phone, is never saved, and is taken only while the home screen
 * is in front.
 */
class LiveService : AccessibilityService() {

    private lateinit var worker: Handler
    private var homes: Set<String> = emptySet()
    private var lastShot = 0L

    override fun onServiceConnected() {
        worker = Handler(HandlerThread("live").apply { start() }.looper)
        homes = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }.toSet()
        running = true
        Live.note(this, "Live mode on")
        soon(600)
    }

    override fun onDestroy() { running = false; super.onDestroy() }
    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        val pkg = e?.packageName?.toString() ?: return
        if (pkg !in homes && e.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return
        // Wait for the page to come to rest: every scroll pushes the moment back
        soon(SETTLE_MS)
    }

    private val capture = Runnable { shoot() }
    private fun soon(ms: Long) { if (::worker.isInitialized) { worker.removeCallbacks(capture); worker.postDelayed(capture, ms) } }

    private fun homeInFront(): Boolean =
        runCatching { rootInActiveWindow?.packageName?.toString() in homes }.getOrDefault(false)

    private fun shoot() {
        if (!homeInFront()) return
        // Android allows a screenshot about three times a second at most
        val wait = lastShot + MIN_GAP_MS - SystemClock.uptimeMillis()
        if (wait > 0) { soon(wait); return }
        val boxes = findWidgets()
        if (boxes.isEmpty()) return
        lastShot = SystemClock.uptimeMillis()
        takeScreenshot(Display.DEFAULT_DISPLAY, Executor { worker.post(it) }, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val hw = runCatching { Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace) }.getOrNull()
                val shot = hw?.copy(Bitmap.Config.ARGB_8888, false)
                result.hardwareBuffer.close()
                if (shot == null) return
                // Still home? A screenshot taken as an app opens is of the app
                if (!homeInFront()) { shot.recycle(); return }
                runCatching { Live.apply(this@LiveService, shot, boxes) }.onFailure { Live.note(this@LiveService, "Live: ${it.javaClass.simpleName}") }
                shot.recycle()
            }
            override fun onFailure(code: Int) {
                // Too soon after the last one: try once more after the gap
                if (code == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) soon(MIN_GAP_MS)
            }
        })
    }

    /**
     * Every Fold widget on screen, with its bounds, from the launcher's view tree. A widget's
     * picture fills a view this app names "root"; the launcher reports it like any other view.
     */
    private fun findWidgets(): List<Rect> {
        val out = ArrayList<Rect>()
        val roots = runCatching { windows.mapNotNull { it.root } }.getOrDefault(emptyList())
        for (r in roots) walk(r, out, 0)
        return out.distinctBy { "${it.left},${it.top},${it.width()},${it.height()}" }
    }

    private fun walk(n: AccessibilityNodeInfo, out: MutableList<Rect>, depth: Int) {
        if (depth > 40) return
        val name = n.viewIdResourceName
        if (name == "$packageName:id/root") {
            val b = Rect().also { n.getBoundsInScreen(it) }
            if (b.width() > 20 && b.height() > 20) out += b
            return
        }
        for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, out, depth + 1) }
    }

    companion object {
        @Volatile var running = false
        private const val SETTLE_MS = 380L
        private const val MIN_GAP_MS = 400L

        fun enabled(c: Context): Boolean {
            val on = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return on.split(':').any { ComponentName.unflattenFromString(it)?.let { n -> n.packageName == c.packageName && n.className == LiveService::class.java.name } == true }
        }
    }
}

/** The work live mode does with one screenshot, kept apart from the service so it can be read alone. */
object Live {

    /** Where the launcher has the wallpaper for one widget's page, and how it has tinted it. */
    data class View(val ox: Float, val oy: Float, val scale: Float, val gain: FloatArray, val screenW: Int, val screenH: Int) {
        fun encode() = listOf(ox, oy, scale, gain[0], gain[1], gain[2], screenW, screenH).joinToString(",")
        companion object {
            fun decode(s: String?): View? = runCatching {
                val p = s!!.split(",")
                View(p[0].toFloat(), p[1].toFloat(), p[2].toFloat(), floatArrayOf(p[3].toFloat(), p[4].toFloat(), p[5].toFloat()), p[6].toInt(), p[7].toInt())
            }.getOrNull()
        }
    }

    fun viewOf(c: Context, id: Int): View? = View.decode(Prefs.of(c).getString("live_$id", null))

    fun note(c: Context, s: String) = Prefs.of(c).edit().putString("live_note", s).apply()
    fun lastNote(c: Context): String? = Prefs.of(c).getString("live_note", null)

    fun apply(c: Context, shot: Bitmap, boxes: List<Rect>) {
        val screen = Backdrop.screenSize(c)
        val sw = shot.width; val sh = shot.height
        val ids = Kind.entries.flatMap { Widgets.ids(c, it).toList() }
        val claimed = HashSet<Int>()
        // 1. Which widget is which: the one whose last known place and size are closest
        val matched = boxes.mapNotNull { b ->
            val best = ids.filter { it !in claimed }.minByOrNull { id -> distance(c, id, b, screen.x, screen.y) } ?: return@mapNotNull null
            if (distance(c, best, b, screen.x, screen.y) > LOST) return@mapNotNull null
            claimed += best
            best to b
        }
        // 2. Where the wallpaper is, from everything on screen that is not a Fold widget
        val wall = Backdrop.wallpaper(c, com.cerniera.kit.Backdrop.Seat(0f, 0f, 1f, 1f, sw, sh))
        val view = wall?.let { register(it, shot, boxes) }
        var redrawn = 0
        for ((id, b) in matched) {
            var changed = false
            val reported = Widgets.reportedSize(c, id)
            val old = Backdrop.seat(c, id)
            val seat = com.cerniera.kit.Backdrop.Seat(b.left.toFloat(), b.top.toFloat(), b.width().toFloat(), b.height().toFloat(), sw, sh, true, reported.width, reported.height)
            if (old == null || !old.exact || old.screenW != sw || old.screenH != sh || Math.abs(old.left - seat.left) > 2f || Math.abs(old.top - seat.top) > 2f ||
                Math.abs(old.width - seat.width) > 1f || Math.abs(old.height - seat.height) > 1f || Math.abs(old.repW - seat.repW) > 1.5f || Math.abs(old.repH - seat.repH) > 1.5f) {
                Prefs.of(c).edit().putString("seat_$id", seat.encode()).apply(); changed = true
            }
            if (view != null) {
                val was = viewOf(c, id)
                if (was == null || was.screenW != view.screenW || Math.abs(was.ox - view.ox) > 3f || Math.abs(was.oy - view.oy) > 3f ||
                    (0..2).any { Math.abs(was.gain[it] - view.gain[it]) > 0.04f }) {
                    Prefs.of(c).edit().putString("live_$id", view.encode()).apply(); changed = true
                }
            }
            if (changed) { Widgets.render(c, id); redrawn++ }
        }
        note(c, "Last look: ${matched.size} of ${boxes.size} widgets found, " +
            (if (view == null) "wallpaper not matched" else "wallpaper at ${view.ox.toInt()}, ${view.oy.toInt()}") + ", $redrawn redrawn")
    }

    private const val LOST = 900f

    /** How far widget [id]'s last known place and size are from box [b], in px; size counts triple. */
    private fun distance(c: Context, id: Int, b: Rect, sw: Int, sh: Int): Float {
        val seat = Backdrop.seat(c, id)
        val f = Widgets.frameOf(c, id)
        val sizeOff = Math.abs(f.px - b.width()) + Math.abs(f.py - b.height()).toFloat()
        if (seat == null || seat.screenW != sw || seat.screenH != sh) return 400f + 3f * sizeOff
        return Math.abs(seat.left - b.left) + Math.abs(seat.top - b.top) + 3f * (Math.abs(seat.width - b.width()) + Math.abs(seat.height - b.height()))
    }

    /**
     * Finds where the launcher shows the wallpaper. Launchers scale the image to fill the screen
     * and slide it as you change page. The wallpaper is laid over the screenshot at each possible
     * position, and the one where most of the screen agrees wins: icons, other widgets and the
     * taskbar simply disagree everywhere and are outvoted. Fold widgets are left out entirely.
     * The agreeing points also give the launcher's tint, channel by channel.
     */
    fun register(wall: Bitmap, shot: Bitmap, mask: List<Rect>): View? {
        val sw = shot.width; val sh = shot.height
        val scale = maxOf(sw.toFloat() / wall.width, sh.toFloat() / wall.height)
        val slackX = wall.width * scale - sw; val slackY = wall.height * scale - sh
        // Work small: 1/8 of the screen, the wallpaper at the same scale
        val f = 1f / 8f
        val small = Bitmap.createScaledBitmap(shot, (sw * f).toInt().coerceAtLeast(8), (sh * f).toInt().coerceAtLeast(8), true)
        val ws = Bitmap.createScaledBitmap(wall, (wall.width * scale * f).toInt().coerceAtLeast(8), (wall.height * scale * f).toInt().coerceAtLeast(8), true)
        val spx = IntArray(small.width * small.height).also { small.getPixels(it, 0, small.width, 0, 0, small.width, small.height) }
        val wpx = IntArray(ws.width * ws.height).also { ws.getPixels(it, 0, ws.width, 0, 0, ws.width, ws.height) }
        val samples = ArrayList<Int>()
        for (y in 1 until small.height step 2) for (x in 1 until small.width step 2) {
            val X = (x / f).toInt(); val Y = (y / f).toInt()
            if (mask.none { it.contains(X, Y) }) samples += y * small.width + x
        }
        if (samples.size < 60) return null
        fun agree(ox: Int, oy: Int): Int {
            var n = 0
            for (s in samples) {
                val x = s % small.width + ox; val y = s / small.width + oy
                if (x < 0 || y < 0 || x >= ws.width || y >= ws.height) continue
                val a = spx[s]; val b = wpx[y * ws.width + x]
                // Generous, so a launcher's dimming still agrees; the winner is refined by its tint
                if (Math.abs(Color.red(a) - Color.red(b) * DIM) < 44 && Math.abs(Color.green(a) - Color.green(b) * DIM) < 44 && Math.abs(Color.blue(a) - Color.blue(b) * DIM) < 44) n++
            }
            return n
        }
        val maxX = (slackX * f).toInt().coerceAtLeast(0); val maxY = (slackY * f).toInt().coerceAtLeast(0)
        var best = -1; var bx = maxX / 2; var by = maxY / 2
        for (oy in 0..maxY) for (ox in 0..maxX) { val a = agree(ox, oy); if (a > best) { best = a; bx = ox; by = oy } }
        if (best < samples.size * 0.30f) return null
        // The tint, from the points that agree
        val sum = DoubleArray(3); val ref = DoubleArray(3)
        for (s in samples) {
            val x = s % small.width + bx; val y = s / small.width + by
            if (x >= ws.width || y >= ws.height) continue
            val a = spx[s]; val b = wpx[y * ws.width + x]
            if (Math.abs(Color.red(a) - Color.red(b) * DIM) < 44 && Math.abs(Color.green(a) - Color.green(b) * DIM) < 44 && Math.abs(Color.blue(a) - Color.blue(b) * DIM) < 44) {
                sum[0] += Color.red(a).toDouble(); sum[1] += Color.green(a).toDouble(); sum[2] += Color.blue(a).toDouble()
                ref[0] += Color.red(b).toDouble(); ref[1] += Color.green(b).toDouble(); ref[2] += Color.blue(b).toDouble()
            }
        }
        val gain = FloatArray(3) { ((sum[it] + 1) / (ref[it] + 1)).toFloat().coerceIn(0.4f, 1.3f) }
        small.recycle(); ws.recycle()
        return View(bx / f, by / f, scale, gain, sw, sh)
    }

    /** One UI and many launchers dim the wallpaper a little; the search allows for it. */
    private const val DIM = 0.92f
}
