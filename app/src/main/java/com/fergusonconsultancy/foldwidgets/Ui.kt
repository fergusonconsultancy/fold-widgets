package com.fergusonconsultancy.foldwidgets

import com.cerniera.kit.Finish
import com.cerniera.kit.Ground
import com.cerniera.kit.Surface
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** The settings screens' kit, in the family's palette: nothing falls back to a stock grey control. */
fun Context.px(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()

fun rounded(fill: Int, radius: Float, stroke: Int = 0, strokeW: Int = 0) = GradientDrawable().apply {
    setColor(fill); cornerRadius = radius
    if (strokeW > 0) setStroke(strokeW, stroke)
}

fun Context.label(s: CharSequence, size: Float, colour: Int = Palette.IVORY, weight: Int = 400, display: Boolean = false) =
    TextView(this).apply {
        text = s; textSize = size; setTextColor(colour)
        typeface = if (display) Type.display(context) else Type.sans(context, weight)
        includeFontPadding = false
        setLineSpacing(0f, 1.28f)
    }

/** Small tracked capitals in champagne that head a section. */
fun Context.eyebrow(s: String) = label(s.uppercase(), 11.5f, Palette.CHAMPAGNE, 500).apply {
    letterSpacing = 0.2f
    setPadding(px(4), px(30), 0, px(10))
}

/** A raised card holding rows. */
fun Context.card(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = rounded(Palette.SURFACE, px(20).toFloat(), Palette.HAIRLINE, px(1))
    setPadding(px(18), px(6), px(18), px(6))
}

/** A row: a title and a note on the left, an action on the right. */
fun Context.row(title: String, note: String, action: View?): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPadding(0, px(14), 0, px(14))
    addView(LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(label(title, 16f, Palette.IVORY, 500))
        addView(label(note, 13f, Palette.MUTED).apply { setPadding(0, px(4), 0, 0) })
    }, LinearLayout.LayoutParams(0, -2, 1f))
    if (action != null) addView(action, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = px(14) })
}

/** The one main action of a row: champagne on smoked glass. */
fun Context.button(text: String, primary: Boolean = true, onClick: () -> Unit) = label(text, 14f, if (primary) Palette.GROUND else Palette.IVORY, 500).apply {
    gravity = Gravity.CENTER
    background = if (primary) rounded(Palette.CHAMPAGNE, px(18).toFloat()) else rounded(Palette.RAISED, px(18).toFloat(), Palette.HAIRLINE, px(1))
    setPadding(px(16), px(10), px(16), px(10))
    setOnClickListener { onClick() }
}

/** A status: a small dot and a word, green when done. */
fun Context.status(done: Boolean, word: String = if (done) "On" else "Off") = label(word, 13f, if (done) Palette.GOOD else Palette.MUTED, 500).apply {
    setCompoundDrawablesRelativeWithIntrinsicBounds(GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(if (done) Palette.GOOD else Palette.MUTED); setSize(px(7), px(7))
    }, null, null, null)
    compoundDrawablePadding = px(8)
}

fun Context.screen(): Pair<ScrollView, LinearLayout> {
    val column = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(20), px(40), px(20), px(48))
    }
    val scroll = ScrollView(this).apply {
        setBackgroundColor(Palette.GROUND)
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(FrameLayout(context).apply {
            addView(column, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL))
        })
        // Both screens, both ways round (12 Nov): the bars and the camera cut-out are kept clear on
        // every side, so a landscape screen with the bar at its side loses nothing under it, and the
        // column stays a readable width, centred, however wide the glass is
        setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        addOnLayoutChangeListener { v, l, _, r, _, ol, _, or, _ ->
            if (r - l == or - ol) return@addOnLayoutChangeListener
            val room = (r - l) - v.paddingLeft - v.paddingRight
            column.layoutParams = (column.layoutParams as FrameLayout.LayoutParams).apply { width = minOf(room, px(620)) }
            v.post { column.requestLayout() }
        }
    }
    return scroll to column
}

/**
 * A finish shown as a tile of itself: a small widget over a ground that is bright on one side and
 * dark on the other, so an adaptive finish shows both its faces at once. The chosen one is ringed.
 */
class FinishTile(c: Context, val finish: Finish?, var chosen: Boolean, onPick: (Finish?) -> Unit) : LinearLayout(c) {
    private val picture = object : View(c) {
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0f) return
            // The ground: warm stone fading to a dark photograph-like green, crossed by fine diagonal
            // bands (1 Nov). On a smooth ground frost and clear glass looked identical; over detail,
            // Vetro visibly softens it and Cristallo shows it sharp, as on the home screen
            val ground = Bitmap.createBitmap(192, 160, Bitmap.Config.ARGB_8888).also {
                val g = Canvas(it)
                g.drawRect(0f, 0f, 192f, 160f, Paint().apply {
                    shader = LinearGradient(0f, 0f, 192f, 160f, intArrayOf(0xFFEDE6D8.toInt(), 0xFFC9B79A.toInt(), 0xFF2B3A2F.toInt(), 0xFF101613.toInt()),
                        floatArrayOf(0f, 0.42f, 0.58f, 1f), Shader.TileMode.CLAMP)
                })
                val band = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 3f; color = 0x4D000000 }
                val light = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2f; color = 0x40FFFFFF }
                var x = -160f
                while (x < 192f) { g.drawLine(x, 160f, x + 160f, 0f, band); g.drawLine(x + 7f, 160f, x + 167f, 0f, light); x += 16f }
            }
            canvas.drawBitmap(ground, null, RectF(0f, 0f, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
            val f = finish ?: Prefs.defaultFinish(context)
            val d = resources.displayMetrics.density
            val inset = 12f * d
            canvas.save(); canvas.translate(inset, inset); canvas.scale(d, d)
            val sw = (w - 2 * inset) / d; val sh = (h - 2 * inset) / d
            val s = Surface(f, Ground.of(ground, 0, true), sw, sh, 14f, 1f / d)
            s.paint(canvas)
            val p = context.ink(15f, s.ink(sw * 0.25f, sh * 0.5f), 500)
            canvas.drawText("Aa", 10f, sh / 2 + 5f, p)
            p.color = s.ink(sw * 0.75f, sh * 0.5f)
            canvas.drawText("Aa", sw / 2 + 10f, sh / 2 + 5f, p)
            canvas.restore()
            // Follow app is a link, not a swatch (1 Nov): a small chain in the corner says so
            if (finish == null) {
                val cx = w - 16f * d; val cy = 16f * d
                canvas.drawCircle(cx, cy, 11f * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6101412.toInt() })
                val link = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.6f * d; color = Palette.CHAMPAGNE }
                canvas.save(); canvas.rotate(-45f, cx, cy)
                canvas.drawRoundRect(RectF(cx - 7.5f * d, cy - 2.6f * d, cx + 0.8f * d, cy + 2.6f * d), 2.6f * d, 2.6f * d, link)
                canvas.drawRoundRect(RectF(cx - 0.8f * d, cy - 2.6f * d, cx + 7.5f * d, cy + 2.6f * d), 2.6f * d, 2.6f * d, link)
                canvas.restore()
            }
            if (chosen) canvas.drawRoundRect(RectF(1.5f * d, 1.5f * d, w - 1.5f * d, h - 1.5f * d), 16f * d, 16f * d,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d; color = Palette.CHAMPAGNE })
        }
    }.apply {
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, 16f * v.resources.displayMetrics.density)
        }
    }

    init {
        orientation = VERTICAL
        addView(picture, LayoutParams(-1, c.px(84)))
        // The first tile is not a finish of its own: it follows the finish chosen for the whole
        // app, so this widget changes when that does. Named for what it does (1 Nov: "Default ·
        // Ceramica" read as a second Ceramica)
        addView(c.label(finish?.short ?: "Follow app", 12.5f, if (chosen) Palette.CHAMPAGNE else Palette.MUTED, 500).apply {
            setPadding(c.px(2), c.px(8), 0, 0)
        })
        if (finish == null) addView(c.label("App finish: ${Prefs.defaultFinish(c).short}", 11f, Palette.MUTED, 400).apply { setPadding(c.px(2), c.px(2), 0, 0) })
        setOnClickListener { onPick(finish) }
    }
}

/** Finish tiles three to a row. */
fun Context.finishGrid(options: List<Finish?>, isChosen: (Finish?) -> Boolean, onPick: (Finish?) -> Unit): LinearLayout {
    val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val tiles = ArrayList<FinishTile>()
    lateinit var pick: (Finish?) -> Unit
    pick = { f ->
        onPick(f)
        tiles.forEach { t -> t.chosen = isChosen(t.finish); t.getChildAt(0).invalidate()
            (t.getChildAt(1) as TextView).setTextColor(if (t.chosen) Palette.CHAMPAGNE else Palette.MUTED) }
    }
    options.chunked(3).forEach { chunk ->
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chunk.forEachIndexed { i, f ->
            val t = FinishTile(this, f, isChosen(f)) { pick(it) }
            tiles += t
            line.addView(t, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = px(10) })
        }
        repeat(3 - chunk.size) { line.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = px(10) }) }
        grid.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(14) })
    }
    return grid
}

/** The Kit's drawn switch, for a row's action: on and off, nothing else. */
fun Context.toggle(on: Boolean, name: String, changed: (Boolean) -> Unit): View =
    com.cerniera.kit.Toggle(this, on, changed).apply { contentDescription = name }

/** The Kit's segmented choice, its thumb sliding like liquid between the options. */
fun Context.segments(options: List<String>, selected: Int, picked: (Int) -> Unit): View =
    com.cerniera.kit.Segmented(this, options, selected, picked)
