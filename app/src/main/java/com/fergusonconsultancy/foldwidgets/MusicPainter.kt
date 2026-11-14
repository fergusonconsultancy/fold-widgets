package com.fergusonconsultancy.foldwidgets

import com.cerniera.kit.Surface
import com.cerniera.kit.lum
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader

/**
 * The music widget. Its content belongs to whatever is playing: the player's name and icon, the
 * artwork, the title and artist, and the one colour the artwork lends to the play disc and the
 * progress line.
 *
 * Concentrato by size: as the widget shrinks, things fall away in a fixed order until only the
 * vital thing is left. Album, then the player's name, then the progress line and the artist, then
 * the title, until only the artwork and the play disc remain.
 * - Poster, the large default: large artwork, the title set in Italiana beside it.
 * - Stack, the small default: artwork and words above, the line and the play disc below.
 * - Compact: artwork, title and play.
 * - Tile: the artwork alone, with play.
 * - Wide, for long thin shapes: artwork the full height on the left, everything else beside it.
 *
 * One control: play and pause (Mechack, 31 Oct). Everything else is done in the player, which any
 * other tap on the widget opens.
 */
object MusicPainter : Painter {

    override fun paint(c: Context, id: Int, canvas: Canvas, s: Surface): Scene {
        if (!Media.allowed(c)) return locked(c, canvas, s)
        val np = Media.now(c)
        val w = s.w; val h = s.h
        return when {
            w >= 90f && w < 220f && h >= w * 1.3f -> slim(c, canvas, s, np)
            minOf(w, h) < 90f -> tile(c, canvas, s, np)
            w > h * 2f -> wide(c, canvas, s, np)
            w >= 220f && h >= 220f && h >= w * 1.3f -> portrait(c, canvas, s, np)
            w >= 220f && h >= 220f && w >= h * 1.35f -> landscape(c, canvas, s, np)
            w >= 220f && h >= 220f -> poster(c, canvas, s, np)
            minOf(w, h) < 150f || (w < 170f && h < 220f) -> cover(c, canvas, s, np)
            else -> stack(c, canvas, s, np)       // 4x2 and 3x2 on the cover screen
        }
    }

    // ---- Arrangements ---------------------------------------------------------------------------

    /**
     * 4x2 and 3x2: the artwork and the words side by side, the line and the disc beneath. Every
     * measure is a multiple of the widget's scale against the 4x2 cover-screen widget, so a larger
     * widget is the same composition larger, never the same small type floating in space (31 Oct).
     */
    private fun stack(c: Context, cv: Canvas, s: Surface, np: NowPlaying?): Scene {
        val k = minOf(s.w / 255f, s.h / 164f).coerceIn(0.8f, 2.4f)
        val p = 14f * k
        val r = 21f * k
        val a = minOf(s.h - 2 * p - 2 * r - 12f * k, s.w * 0.40f)
        val art = RectF(p, p, p + a, p + a)
        artwork(cv, art, np, s, 11f * k)
        val x0 = art.right + 12f * k
        val tw = s.w - x0 - p
        val (titleP, lines) = c.title(titleOf(np), s.ink(x0 + tw / 2, p + a / 2), tw, 2, listOf(16.5f * k, 15.5f * k, 14.5f * k), 600)
        val artistP = c.meta(s.muted(x0 + tw / 2, art.bottom - 6f), k, 13.5f)
        val eyebrowH = 11f * k * 1.4f + 7f * k
        val titleH = { n: Int -> n * titleP.textSize * 1.18f }
        // Whatever does not fit beside the artwork falls away: the player's name first, then a title line
        var shownLines = lines
        var showEyebrow = tw >= 72f * k
        fun blockH() = (if (showEyebrow) eyebrowH else 0f) + titleH(shownLines.size) + 5f * k + artistP.textSize * 1.15f
        if (blockH() > a) showEyebrow = false
        if (blockH() > a && shownLines.size > 1) shownLines = wrap(titleOf(np), titleP, tw, 1)
        var y = p + 1f * k
        if (showEyebrow) { eyebrow(c, cv, x0, y, tw, 11f * k, np, s); y += eyebrowH }
        for (l in shownLines) { cv.textTop(l, x0, y, titleP); y += titleP.textSize * 1.18f }
        cv.textTop(fit(artistOf(c, np), artistP, tw), x0, y + 5f * k, artistP)
        return foot(c, cv, s, np, p, s.w - p, s.h - p - r, r, 1.6f * k, 14f * k)
    }

    /**
     * 4x3 and larger: a poster. The artwork with the player and artist beside it; the title across
     * the full width, resting on the line and the disc at the foot. One typeface for the title at
     * every size, Jost, as in every other arrangement: Italiana's hairlines are kept for large
     * figures, where they hold (31 Oct, after the title broke down on the main screen).
     * Every measure scales with the widget against the 4x3 cover-screen widget; the artwork takes
     * whatever height the title and foot leave it.
     */
    private fun poster(c: Context, cv: Canvas, s: Surface, np: NowPlaying?): Scene {
        val k = minOf(s.w / 255f, s.h / 267f).coerceIn(0.8f, 2.4f)
        val p = 18f * k
        val r = 25f * k
        val footTop = s.h - p - 2 * r
        val base = 20f * k
        val a = minOf((s.w - 2 * p) * 0.5f, footTop - p - 2 * base * 1.2f - 24f * k).coerceAtLeast(40f * k)
        val art = RectF(p, p, p + a, p + a)
        artwork(cv, art, np, s, 14f * k)
        val x0 = art.right + 14f * k
        val tw = s.w - x0 - p
        eyebrow(c, cv, x0, p + 2f * k, tw, 11f * k, np, s)
        var y = p + 2f * k + 11f * k * 1.4f + 10f * k
        val artistP = c.meta(s.muted(x0 + tw / 2, y + 8f * k), k, 13.5f)
        val artistRoom = ((art.bottom - y) / (artistP.textSize * 1.3f)).toInt().coerceIn(0, 3)
        if (artistRoom > 0) for (line in wrap(artistOf(c, np), artistP, tw, artistRoom)) {
            cv.textTop(line, x0, y, artistP); y += artistP.textSize * 1.3f
        }
        if (np != null && np.album.isNotEmpty() && np.album != np.title && y + 12f * k * 1.3f < art.bottom) {
            val albumP = c.meta(s.muted(x0 + tw / 2, y + 12f * k), k, 12.5f)
            cv.textTop(fit(np.album, albumP, tw), x0, y + 3f * k, albumP)
        }

        // The title across the full width, set on the foot like a caption
        val titleTop = art.bottom + 14f * k
        val bottom = footTop - 12f * k
        val room = bottom - titleTop
        val width = s.w - 2 * p
        val colour = s.ink(s.w / 2, (titleTop + bottom) / 2)
        val text = titleOf(np)
        val sizes = listOf(22f, 20.5f, 19f, 17.5f, 16f).map { it * k }
        val most = (room / (sizes.last() * 1.2f)).toInt().coerceIn(1, 3)
        val (tp, lines) = c.title(text, colour, width, most, sizes, 600)
        val lead = tp.textSize * 1.2f
        var ty = bottom - lines.size * lead + (lead - tp.textSize) / 2
        for (l in lines) { cv.textTop(l, p, ty, tp); ty += lead }
        return foot(c, cv, s, np, p, s.w - p, s.h - p - r, r, 1.6f * k, 18f * k)
    }

    /**
     * Tall (1 Nov: the poster stretched upwards left a void between the artwork and the title on the
     * cover screen's 4x4 to 4x6). The artwork takes the full width, the measure of everything else
     * is taken from the width, and the words sit between artwork and foot, centred in the room, so
     * a taller widget is a larger cover, never a longer gap.
     */
    private fun portrait(c: Context, cv: Canvas, s: Surface, np: NowPlaying?): Scene {
        val k = (s.w / 255f).coerceIn(0.8f, 2.4f)
        val p = 18f * k
        val r = 25f * k
        val footTop = s.h - p - 2 * r
        val width = s.w - 2 * p
        val eyebrowH = 11f * k * 1.4f
        val colour = s.ink(s.w / 2, s.h * 0.7f)
        val artistP = c.meta(s.muted(s.w / 2, s.h * 0.75f), k, 13.5f)
        val text = titleOf(np)
        fun block(size: Float, n: Int) = eyebrowH + 10f * k + n * size * 1.2f + 6f * k + artistP.textSize * 1.2f
        // The artwork takes the full width; the title then grows, in whole words and up to four
        // lines, until the words fill the room between artwork and foot
        var a = width
        val room = footTop - 12f * k - (p + a + 12f * k)
        var chosen: Pair<android.text.TextPaint, List<String>>? = null
        for (size in listOf(32f, 30f, 28f, 26f, 24f, 22f, 20.5f, 19f, 17.5f).map { it * k }) {
            val tp = c.ink(size, colour, 600)
            if (fitsWhole(text, tp, width, 4)) {
                val lines = wrap(text, tp, width, 4)
                if (block(size, lines.size) <= room) { chosen = tp to lines; break }
            }
        }
        if (chosen == null) {
            // Too little room even at the smallest size: the artwork gives way to three lines
            chosen = c.title(text, colour, width, 3, listOf(19f, 17.5f).map { it * k }, 600)
            a = minOf(width, footTop - 12f * k - p - 12f * k - block(chosen.first.textSize, chosen.second.size)).coerceAtLeast(60f * k)
        }
        val (tp, lines) = chosen
        val lead = tp.textSize * 1.2f
        val art = RectF(p + (width - a) / 2, p, p + (width - a) / 2 + a, p + a)
        artwork(cv, art, np, s, 16f * k)
        val roomTop = art.bottom + 12f * k
        val roomBottom = footTop - 12f * k
        var y = roomTop + ((roomBottom - roomTop) - block(tp.textSize, lines.size)).coerceAtLeast(0f) / 2f
        eyebrow(c, cv, p, y, width, 11f * k, np, s)
        y += eyebrowH + 10f * k
        for (l in lines) { cv.textTop(l, p, y + (lead - tp.textSize) / 2, tp); y += lead }
        cv.textTop(fit(artistOf(c, np), artistP, width), p, y + 6f * k, artistP)
        return foot(c, cv, s, np, p, s.w - p, s.h - p - r, r, 1.6f * k, 18f * k)
    }

    /**
     * Large and landscape (main screen, 1 Nov: the poster's artwork sat small in a corner with an
     * empty band beside it). The artwork stands on the left as tall as the widget allows; the
     * player, the title (grown to fill its column) and the artist on the right, the foot beneath them.
     */
    private fun landscape(c: Context, cv: Canvas, s: Surface, np: NowPlaying?): Scene {
        val k = (s.h / 267f).coerceIn(0.8f, 2.4f)
        val p = 18f * k
        val r = 25f * k
        val a = minOf(s.h - 2 * p, s.w * 0.5f)
        val art = RectF(p, (s.h - a) / 2, p + a, (s.h + a) / 2)
        artwork(cv, art, np, s, 16f * k)
        val x0 = art.right + 20f * k
        val cw = s.w - x0 - p
        val top = art.top
        val eyebrowH = 11f * k * 1.4f
        eyebrow(c, cv, x0, top, cw, 11f * k, np, s)
        val footCy = art.bottom - r
        val artistP = c.meta(s.muted(x0 + cw / 2, s.h / 2), k, 13.5f)
        val titleTop = top + eyebrowH + 12f * k
        val room = footCy - r - 14f * k - titleTop - artistP.textSize * 1.3f - 6f * k
        val colour = s.ink(x0 + cw / 2, s.h / 2)
        val text = titleOf(np)
        var chosen: Pair<android.text.TextPaint, List<String>>? = null
        for (size in listOf(30f, 28f, 26f, 24f, 22f, 20.5f, 19f, 17.5f, 16f).map { it * k }) {
            val tp = c.ink(size, colour, 600)
            val most = (room / (size * 1.2f)).toInt().coerceIn(1, 4)
            if (fitsWhole(text, tp, cw, most)) { chosen = tp to wrap(text, tp, cw, most); break }
        }
        val (tp, lines) = chosen ?: c.title(text, colour, cw, (room / (16f * k * 1.2f)).toInt().coerceIn(1, 4), listOf(16f * k), 600)
        var y = titleTop
        for (l in lines) { cv.textTop(l, x0, y, tp); y += tp.textSize * 1.2f }
        cv.textTop(fit(artistOf(c, np), artistP, cw), x0, y + 6f * k, artistP)
        return foot(c, cv, s, np, x0, s.w - p, footCy, r, 1.6f * k, 18f * k)
    }

    private fun wide(c: Context, cv: Canvas, s: Surface, np: NowPlaying?): Scene {
        val k = (s.h / 110f).coerceIn(0.7f, 1.6f)
        val p = 12f * k
        val a = s.h - 2 * p
        val art = RectF(p, p, p + a, p + a)
        artwork(cv, art, np, s, 10f * k)
        val x0 = art.right + 14f * k
        val tw = s.w - x0 - p
        var y = p + 1f * k
        if (s.h >= 128f) { eyebrow(c, cv, x0, y, tw, 9.5f * k, np, s); y += 9.5f * k * 1.4f + 8f * k }
        val titleP = c.ink(15f * k, s.ink(x0 + tw / 2, y + 8f * k), 600)
        cv.textTop(fit(titleOf(np), titleP, tw), x0, y, titleP); y += titleP.textSize * 1.3f
        val artistP = c.meta(s.muted(x0 + tw / 2, y + 6f * k), k)
        cv.textTop(fit(artistOf(c, np), artistP, tw), x0, y, artistP); y += artistP.textSize * 1.2f

        val r = 18f * k
        return foot(c, cv, s, np, x0, s.w - p, s.h - p - r, r, 1.4f * k, 12f * k)
    }

    private fun compact(c: Context, cv: Canvas, s: Surface, np: NowPlaying?): Scene {
        val k = (minOf(s.w, s.h) / 130f).coerceIn(0.8f, 1.2f)
        val p = 12f * k
        val a = minOf(s.h * 0.5f, s.w * 0.44f)
        val art = RectF(p, p, p + a, p + a)
        artwork(cv, art, np, s, 9f * k)
        val r = minOf(s.w, s.h) * 0.17f
        val cx = s.w - p - r; val cy = s.h - p - r
        val titleW = s.w - 2 * p - (if (cy - r < art.bottom + 30f * k) 2 * r + 8f * k else 0f)
        val titleP = c.ink(13.5f * k, s.ink(p + titleW / 2, art.bottom + 16f * k), 600)
        var y = art.bottom + 10f * k
        val room = ((s.h - p - y) / (titleP.textSize * 1.2f)).toInt().coerceIn(1, 2)
        for (line in wrap(titleOf(np), titleP, titleW, room)) { cv.textTop(line, p, y, titleP); y += titleP.textSize * 1.2f }
        disc(cv, s, np, cx, cy, r, r * 0.78f)
        val hits = ArrayList<Hit>()
        Media.openAct(c, np)?.let { hits.add(Hit(RectF(0f, 0f, s.w, s.h), it, "Open ${np?.app ?: "player"}")) }
        hits += Hit(RectF(cx - r * 1.3f, cy - r * 1.3f, s.w, s.h), Act.Send(TapReceiver.ACTION_PLAY), if (np?.playing == true) "Pause" else "Play")
        return Scene(hits)
    }

    /**
     * Small and square (2x2, 1 Nov): the artwork is the widget, edge to edge; the title sits on a
     * soft shade at its foot, the disc in the corner. The flagship's signature, nothing else.
     */
    private fun cover(c: Context, cv: Canvas, s: Surface, np: NowPlaying?): Scene {
        val full = RectF(0f, 0f, s.w, s.h)
        val k = (minOf(s.w, s.h) / 150f).coerceIn(0.8f, 1.3f)
        val art = np?.art
        cv.save(); cv.clipPath(s.shape)
        if (art != null) cv.drawBitmap(art, null, cover(art, full), Paint(Paint.FILTER_BITMAP_FLAG))
        else Glyphs.record(cv, full, s.ink(s.w / 2, s.h / 2))
        cv.drawRect(full, Paint().apply {
            shader = LinearGradient(0f, s.h * 0.3f, 0f, s.h, 0x00000000, 0xB3000000.toInt(), Shader.TileMode.CLAMP)
        })
        cv.restore()
        val p = 12f * k
        val r = (minOf(s.w, s.h) * 0.125f).coerceIn(14f, 22f)
        val cx = s.w - p - r; val cy = s.h - p - r
        disc(cv, s, np, cx, cy, r, r * 0.78f)
        val tw = cx - r - 8f * k - p
        val titleP = c.ink(13.5f * k, Palette.IVORY, 600)
        val lines = wrap(titleOf(np), titleP, tw, 2)
        var y = s.h - p - lines.size * titleP.textSize * 1.18f + (titleP.textSize * 1.18f - capHeight(titleP)) / 2
        for (l in lines) { cv.textTop(l, p, y, titleP); y += titleP.textSize * 1.18f }
        val hits = ArrayList<Hit>()
        Media.openAct(c, np)?.let { hits.add(Hit(full, it, "Open ${np?.app ?: "player"}")) }
        hits += Hit(RectF(cx - r * 1.4f, cy - r * 1.4f, s.w, s.h), Act.Send(TapReceiver.ACTION_PLAY), if (np?.playing == true) "Pause" else "Play")
        return Scene(hits)
    }

    /**
     * Narrow and tall (two columns, three or more rows): the portrait in miniature. The artwork
     * across the width, the title and artist beneath, the line and the disc at the foot.
     */
    private fun slim(c: Context, cv: Canvas, s: Surface, np: NowPlaying?): Scene {
        val k = (s.w / 150f).coerceIn(0.75f, 1.4f)
        val p = 12f * k
        val r = 18f * k
        val width = s.w - 2 * p
        val footTop = s.h - p - 2 * r
        val artistP = c.meta(s.muted(s.w / 2, s.h * 0.7f), k, 12f)
        val colour = s.ink(s.w / 2, s.h * 0.65f)
        val text = titleOf(np)
        fun block(size: Float, n: Int) = n * size * 1.2f + 4f * k + artistP.textSize * 1.2f
        var a = width
        val room = footTop - 10f * k - (p + a + 10f * k)
        var chosen: Pair<android.text.TextPaint, List<String>>? = null
        // As many whole-word lines as the room holds (up to six), at the largest size that fits
        for (size in listOf(18f, 17f, 16f, 15f, 14f, 13f).map { it * k }) {
            val tp = c.ink(size, colour, 600)
            val most = ((room - 4f * k - artistP.textSize * 1.2f) / (size * 1.2f)).toInt().coerceIn(1, 6)
            if (fitsWhole(text, tp, width, most)) { val l = wrap(text, tp, width, most); if (block(size, l.size) <= room) { chosen = tp to l; break } }
        }
        if (chosen == null && room > 13f * k * 1.2f * 2) {
            val most = ((room - 4f * k - artistP.textSize * 1.2f) / (13f * k * 1.2f)).toInt().coerceIn(2, 6)
            chosen = c.title(text, colour, width, most, listOf(13f * k), 600)
        }
        if (chosen == null) {
            chosen = c.title(text, colour, width, 2, listOf(13f * k), 600)
            a = minOf(width, footTop - 10f * k - p - 10f * k - block(chosen.first.textSize, chosen.second.size)).coerceAtLeast(36f * k)
        }
        val (tp, lines) = chosen
        val art = RectF(p + (width - a) / 2, p, p + (width - a) / 2 + a, p + a)
        artwork(cv, art, np, s, 10f * k)
        val roomTop = art.bottom + 10f * k; val roomBottom = footTop - 10f * k
        var y = roomTop + ((roomBottom - roomTop) - block(tp.textSize, lines.size)).coerceAtLeast(0f) / 2f
        for (l in lines) { cv.textTop(l, p, y + (tp.textSize * 0.2f) / 2, tp); y += tp.textSize * 1.2f }
        cv.textTop(fit(artistOf(c, np), artistP, width), p, y + 4f * k, artistP)
        return foot(c, cv, s, np, p, s.w - p, s.h - p - r, r, 1.4f * k, 10f * k)
    }

    private fun tile(c: Context, cv: Canvas, s: Surface, np: NowPlaying?): Scene {
        val full = RectF(0f, 0f, s.w, s.h)
        val art = np?.art
        if (art != null) {
            cv.save(); cv.clipPath(s.shape)
            cv.drawBitmap(art, null, cover(art, full), Paint(Paint.FILTER_BITMAP_FLAG))
            // A soft fall of shade, so the disc reads on any artwork
            cv.drawRect(full, Paint().apply {
                shader = LinearGradient(0f, s.h * 0.35f, 0f, s.h, 0x00000000, 0x8C000000.toInt(), Shader.TileMode.CLAMP)
            })
            cv.restore()
        } else Glyphs.record(cv, full, s.ink(s.w / 2, s.h / 2))
        val r = minOf(s.w, s.h) * 0.19f
        val cx = s.w - r - minOf(s.w, s.h) * 0.1f
        val cy = s.h - r - minOf(s.w, s.h) * 0.1f
        disc(cv, s, np, cx, cy, r, r * 0.8f)
        return Scene(listOf(Hit(full, Act.Send(TapReceiver.ACTION_PLAY), if (np?.playing == true) "Pause" else "Play")))
    }

    private fun locked(c: Context, cv: Canvas, s: Surface): Scene {
        // Readable when turned sideways too (12 Nov): a short, wide widget no longer shrinks the note
        val k = (minOf(s.w, s.h) / 164f).coerceIn(0.85f, 1.6f)
        val title = c.statement(s.ink(s.w / 2, s.h / 2 - 10f * k), k).apply { textAlign = Paint.Align.CENTER }
        val note = c.ink(11.5f * k, s.muted(s.w / 2, s.h / 2 + 14f * k)).apply { textAlign = Paint.Align.CENTER }
        cv.drawText("Music", s.w / 2, s.h / 2 - 4f * k, title)
        wrap("Tap to let the widget follow your player", note, s.w - 32f * k, 2).forEachIndexed { i, l ->
            cv.drawText(l, s.w / 2, s.h / 2 + 18f * k + i * note.textSize * 1.3f, note)
        }
        val open = Intent(c, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return Scene(listOf(Hit(RectF(0f, 0f, s.w, s.h), Act.Open(intent = open), "Allow music access")))
    }

    // ---- Parts ----------------------------------------------------------------------------------

    private fun titleOf(np: NowPlaying?) = np?.title?.takeIf { it.isNotEmpty() } ?: "Nothing playing"

    private fun artistOf(c: Context, np: NowPlaying?): String {
        if (np != null) return np.artist.ifEmpty { np.album.ifEmpty { np.app } }
        val idle = Media.idlePlayer(c)
        val name = idle?.let { Media.label(c, it) }
        return when {
            name == null -> "Start any player"
            idle == Media.playWith(c) && idle != Media.lastPlayer(c) -> "Play to start $name"
            else -> "Play to resume $name"
        }
    }

    /** The player's icon and name, small and tracked: it says where the music comes from. */
    private fun eyebrow(c: Context, cv: Canvas, x: Float, top: Float, width: Float, size: Float, np: NowPlaying?, s: Surface) {
        val pkg = np?.pkg ?: Media.idlePlayer(c) ?: return
        val name = (np?.app ?: Media.label(c, pkg)).uppercase()
        val packed = Icons.of(c, pkg)
        val icon = if (packed == null) (np?.icon ?: Media.appIcon(c, pkg)) else null
        var tx = x
        val iconSize = size * 1.5f
        if (packed != null) {
            Icons.draw(cv, packed, RectF(x, top + size * 0.5f - iconSize / 2, x + iconSize, top + size * 0.5f + iconSize / 2))
            tx += iconSize + size * 0.55f
        } else if (icon != null) {
            val r = RectF(x, top + size * 0.5f - iconSize / 2, x + iconSize, top + size * 0.5f + iconSize / 2)
            cv.save(); cv.clipPath(Path().apply { addOval(r, Path.Direction.CW) })
            cv.drawBitmap(icon, null, r, Paint(Paint.FILTER_BITMAP_FLAG)); cv.restore()
            tx += iconSize + size * 0.55f
        }
        val p = c.label(s.muted(x + width / 2, top + size / 2), 1f, size)
        cv.textTop(fit(name, p, x + width - tx), tx, top + size * 0.5f - capHeight(p) / 2, p)
    }

    /** The artwork, or a record where there is none. */
    private fun artwork(cv: Canvas, r: RectF, np: NowPlaying?, s: Surface, corner: Float) {
        val path = Path().apply { addRoundRect(r, corner, corner, Path.Direction.CW) }
        val art = np?.art
        if (art != null) {
            cv.save(); cv.clipPath(path)
            cv.drawBitmap(art, null, r, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            cv.restore()
        } else {
            cv.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = s.faint(r.centerX(), r.centerY()) })
            Glyphs.record(cv, r, s.ink(r.centerX(), r.centerY()))
        }
        cv.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = s.hairline; color = s.faint(r.centerX(), r.centerY())
        })
    }

    /** The colour the artwork lends, tuned to the face it sits on; the finish's own light if none. */
    private fun accent(s: Surface, np: NowPlaying?, x: Float, y: Float): Int =
        np?.accent?.let { Art.against(it, s.darkInk(x, y)) } ?: s.accent(x, y)

    private fun progress(s: Surface, np: NowPlaying?, r: RectF): Line? {
        if (np == null || np.duration <= 0) return null
        return Line(r, np.progress(), s.faint(r.centerX(), r.centerY()), accent(s, np, r.centerX(), r.centerY()))
    }

    /** The play disc: the one filled shape, in the artwork's colour. */
    private fun disc(cv: Canvas, s: Surface, np: NowPlaying?, cx: Float, cy: Float, r: Float, glyph: Float) {
        val fill = accent(s, np, cx, cy)
        cv.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill })
        val mark = if (lum(fill) > 0.55f) 0xFF15191A.toInt() else Palette.IVORY
        if (np?.playing == true) Glyphs.pause(cv, cx, cy, glyph * 0.9f, mark) else Glyphs.play(cv, cx + glyph * 0.04f, cy, glyph, mark)
    }

    /**
     * The foot of the widget: the progress line running from [left] to the play disc, which sits at
     * [right] on the line's centre [cy]. The disc is the only control; every other tap opens the
     * player, where anything else can be done.
     */
    private fun foot(c: Context, cv: Canvas, s: Surface, np: NowPlaying?, left: Float, right: Float, cy: Float, r: Float,
                     thickness: Float, gap: Float): Scene {
        val cx = right - r
        val line = progress(s, np, RectF(left, cy - thickness, cx - r - gap, cy + thickness))
        disc(cv, s, np, cx, cy, r, r * 0.78f)
        val hits = ArrayList<Hit>()
        Media.openAct(c, np)?.let { hits.add(Hit(RectF(0f, 0f, s.w, s.h), it, "Open ${np?.app ?: "player"}")) }
        val reach = maxOf(r * 1.3f, 24f)
        hits += Hit(RectF(cx - reach, cy - reach, cx + reach, cy + reach), Act.Send(TapReceiver.ACTION_PLAY), if (np?.playing == true) "Pause" else "Play")
        return Scene(hits, line)
    }

    /** [b] scaled to cover [r], centred. */
    private fun cover(b: Bitmap, r: RectF): RectF {
        val sc = maxOf(r.width() / b.width, r.height() / b.height)
        val w = b.width * sc; val h = b.height * sc
        return RectF(r.centerX() - w / 2, r.centerY() - h / 2, r.centerX() + w / 2, r.centerY() + h / 2)
    }
}
