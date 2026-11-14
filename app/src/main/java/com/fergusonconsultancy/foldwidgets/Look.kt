package com.fergusonconsultancy.foldwidgets

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor

/**
 * Fold Widgets' look, shared with the rest of the family (14 Nov 2026): the finish chosen for the
 * whole app, the corners, and the images chosen for the glass on each screen. Nodo's widget follows
 * it, so one choice here dresses every widget on the home screen, Nodo's included.
 *
 * Read-only, and appearance only: nothing personal passes through it. The chosen images are opened
 * here, where the permission to read them was granted, and handed over as a stream.
 */
class LookProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        val c = context ?: return null
        val p = Prefs.of(c)
        return MatrixCursor(arrayOf("finish", "radius", "image_cover", "image_main")).apply {
            addRow(arrayOf<Any>(Prefs.defaultFinish(c).name, Prefs.radius(c),
                if (p.getString("image_cover", null) != null) 1 else 0, if (p.getString("image_main", null) != null) 1 else 0))
        }
    }

    /** content://…look/image/main or /image/cover: the image chosen for that screen's glass. */
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val c = context ?: return null
        if (mode != "r") throw SecurityException("Read only")
        val key = when (uri.lastPathSegment) { "main" -> "image_main"; "cover" -> "image_cover"; else -> return null }
        val chosen = Prefs.of(c).getString(key, null) ?: return null
        return c.contentResolver.openFileDescriptor(Uri.parse(chosen), "r")
    }

    override fun getType(uri: Uri): String? = if (uri.pathSegments.firstOrNull() == "image") "image/*" else null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}

object Look {
    const val CHANGED = "com.cerniera.LOOK_CHANGED"
    /** The apps that follow this look. */
    private val FOLLOWERS = listOf("com.cerniera.nodo")

    /** Tells the followers the look (or the wallpaper behind it) changed, so they redraw. */
    fun changed(c: Context) {
        FOLLOWERS.forEach { pkg -> runCatching { c.sendBroadcast(Intent(CHANGED).setPackage(pkg)) } }
    }
}
