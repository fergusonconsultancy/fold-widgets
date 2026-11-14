package com.fergusonconsultancy.foldwidgets

import android.content.Context
import android.content.SharedPreferences
import com.cerniera.kit.Finish

/*
 * The finishes themselves, their faces and their legibility moved to the Kit (com.cerniera.kit:
 * Finish.kt, Surface.kt, Glass.kt, Backdrop.kt) on 14 Nov 2026, so Nodo's widget wears them too.
 * What stays here is Fold Widgets' own: its settings.
 */

/** Every setting the widgets keep. */
object Prefs {
    private const val FILE = "fold_widgets"
    fun of(c: Context): SharedPreferences = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun defaultFinish(c: Context): Finish =
        runCatching { Finish.valueOf(of(c).getString("finish", Finish.VETRO.name)!!) }.getOrDefault(Finish.VETRO)
    fun setDefaultFinish(c: Context, f: Finish) = of(c).edit().putString("finish", f.name).apply()

    /** A widget's own finish, or the default if it has none. */
    fun finish(c: Context, id: Int): Finish = of(c).getString("finish_$id", null)
        ?.let { runCatching { Finish.valueOf(it) }.getOrNull() } ?: defaultFinish(c)
    fun setFinish(c: Context, id: Int, f: Finish?) = of(c).edit().apply {
        if (f == null) remove("finish_$id") else putString("finish_$id", f.name)
    }.apply()
    fun hasOwnFinish(c: Context, id: Int) = of(c).contains("finish_$id")

    /**
     * Every widget back to following the app's finish (5 Nov 2026). An app-wide choice that
     * silently skips widgets with their own finish reads as broken, so choosing one in the app
     * dresses every widget; a widget can be given its own again afterwards.
     */
    fun followAll(c: Context) = of(c).edit().apply {
        of(c).all.keys.filter { it.startsWith("finish_") }.forEach { remove(it) }
    }.apply()

    /**
     * The mail widget was retired on 5 Nov 2026. Its inbox addresses, sealed passwords and tokens,
     * kept emails and tap log are wiped once from this phone, with the key that sealed them.
     */
    fun retireMail(c: Context) {
        val p = of(c)
        if (p.getBoolean("mail_retired", false)) return
        val gone = listOf("vault_", "mb_", "ms_", "mail_", "gmail_ids_error", "probe_taps")
        p.edit().apply {
            p.all.keys.filter { k -> gone.any { k.startsWith(it) } }.forEach { remove(it) }
            putBoolean("mail_retired", true)
        }.apply()
        runCatching { java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("foldwidgets_mail") }
    }

    fun forget(c: Context, id: Int) = of(c).edit().remove("finish_$id").remove("seat_$id")
        .remove("agenda_cals_$id").remove("agenda_days_$id").apply()

    /** Corner radius in dp. 24 matches Android's own widget corners. */
    fun radius(c: Context): Int = of(c).getInt("radius", 24)
    fun setRadius(c: Context, dp: Int) = of(c).edit().putInt("radius", dp).apply()
}
