package com.fergusonconsultancy.foldwidgets

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.RectF
import android.util.SizeF
import com.cerniera.kit.Ground
import com.cerniera.kit.Backdrop as KitBackdrop

/**
 * What is behind a widget: now the Kit's Backdrop (14 Nov 2026), given Fold Widgets' own store of
 * seats and chosen images and, when live mode is on, where the launcher really has the wallpaper.
 * The calls are the same as before, so the widgets didn't change.
 */
object Backdrop {
    fun seat(c: Context, id: Int): KitBackdrop.Seat? = KitBackdrop.seat(Prefs.of(c), id)

    fun record(c: Context, intent: Intent, id: Int, hit: RectF?, sizeDp: SizeF?, root: Boolean, reported: SizeF): Boolean =
        KitBackdrop.record(c, Prefs.of(c), intent, id, hit, sizeDp, root, reported)

    fun density(c: Context): Float = KitBackdrop.density(c)
    fun screenSize(c: Context): Point = KitBackdrop.screenSize(c)
    fun invalidate() = KitBackdrop.invalidate()
    fun canReadWallpaper(): Boolean = KitBackdrop.canReadWallpaper()
    fun wallpaper(c: Context, seat: KitBackdrop.Seat?): Bitmap? = KitBackdrop.wallpaper(c, Prefs.of(c), seat)
    fun flatGround(c: Context): Ground = KitBackdrop.flatGround(c)

    fun ground(c: Context, id: Int): Ground {
        val live = if (LiveService.enabled(c)) Live.viewOf(c, id)?.let { KitBackdrop.LiveView(it.ox, it.oy, it.gain, it.screenW, it.screenH) } else null
        return KitBackdrop.ground(c, Prefs.of(c), id, live)
    }
}
