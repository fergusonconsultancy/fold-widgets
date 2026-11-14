package com.fergusonconsultancy.foldwidgets

import com.cerniera.kit.Finish
import com.cerniera.kit.contrast
import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast

/**
 * Settings, applying the doctrine to itself: one main action per row, a permission shows its state
 * and only asks when something is missing, choices you compare are tiles of the thing itself.
 */
class SettingsActivity : Activity() {

    private lateinit var column: LinearLayout

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        com.cerniera.kit.Crash.install(this, "Fold Widgets")
        if (com.cerniera.kit.Crash.show(this, "Fold Widgets")) return
        window.statusBarColor = Palette.GROUND
        window.navigationBarColor = Palette.GROUND
        val (scroll, col) = screen()
        column = col
        setContentView(scroll)
        // Debug builds only: every widget drawn as held upright and turned sideways, for the launch test
        if (intent.getStringExtra("open") == "preview" && (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) preview = true
    }

    private var preview = false

    /** Each kind at a cover-screen size upright, and the same cell span turned (an estimate of One UI's grid). */
    private fun buildPreview() {
        column.removeAllViews()
        column.addView(label("Upright and turned", 26f, Palette.IVORY, display = true))
        val d = Backdrop.density(this)
        val room = (column.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) - px(40)
        listOf(Kind.MUSIC to android.util.SizeF(256f, 269f), Kind.AGENDA to android.util.SizeF(256f, 269f),
            Kind.WEATHER to android.util.SizeF(190f, 164f), Kind.MONTH to android.util.SizeF(190f, 164f)).forEachIndexed { n, (kind, up) ->
            column.addView(eyebrow(kind.name.lowercase()))
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
            val turned = android.util.SizeF(up.width * 1.6f, up.height * 0.62f)
            val total = (up.width + turned.width) * d + px(12)
            val k = minOf(1f, room / total)
            listOf(up, turned).forEachIndexed { i, sz ->
                val bmp = runCatching { Widgets.draw(this, -100 - n, kind, Widgets.Frame(Math.round(sz.width * d), Math.round(sz.height * d), d),
                    Prefs.defaultFinish(this), Backdrop.flatGround(this)).first }.getOrNull() ?: return@forEachIndexed
                line.addView(android.widget.ImageView(this).apply { setImageBitmap(bmp); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER },
                    LinearLayout.LayoutParams(Math.round(sz.width * d * k), Math.round(sz.height * d * k)).apply { if (i > 0) leftMargin = px(12) })
            }
            column.addView(line)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::column.isInitialized) return
        if (preview) { column.post { buildPreview() }; return }
        build()
        Widgets.later { Widgets.renderAll(this) }
        RefreshJob.schedule(this)
        Clockwork.arm(this)
    }

    private fun build() {
        column.removeAllViews()
        column.addView(label("Fold Widgets", 36f, Palette.IVORY, display = true))
        column.addView(label("Music, agenda and weather, in the finishes of the taskbar", 14f, Palette.MUTED).apply { setPadding(0, px(8), 0, 0) })

        // ---- Access
        column.addView(eyebrow("Access"))
        column.addView(card().apply {
            val music = Media.allowed(this@SettingsActivity)
            addView(row("Music", "Follows Spotify, Poweramp or any player. Android files this under notification access; no notification is read.",
                if (music) status(true) else button("Allow") { startActivity(Media.accessIntent(this@SettingsActivity)) }))
            val cal = Agenda.allowed(this@SettingsActivity)
            addView(row("Calendar", "The agenda reads your calendars on the phone",
                if (cal) status(true) else button("Allow") { requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 1) }))
            val wall = Backdrop.canReadWallpaper()
            addView(row("Glass", "The glass reads your wallpaper. Android only lets an app read it with all-files access.",
                if (wall) status(true) else button("Allow") {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                }))
            // Live mode (2 Nov 2026): the widgets' eyes, as the taskbar has its own
            val live = LiveService.enabled(this@SettingsActivity)
            addView(row("Live mode", (if (live) Live.lastNote(this@SettingsActivity)?.let { "$it. " } ?: "" else "") +
                "Each time the home screen comes to rest, one screenshot shows where the launcher has the wallpaper, and each widget is redrawn against what is really behind it. Never saved, never sent, home screen only.",
                if (live) status(true) else button("Turn on") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }))
        })

        // ---- Music (5 Nov 2026): which apps count as players, and what Play starts when idle
        val players = Media.candidates(this)
        if (players.isNotEmpty()) {
            val me = this
            val redraw = { build(); Widgets.later { Widgets.renderAll(me, Kind.MUSIC) } }
            column.addView(eyebrow("Music"))
            column.addView(card().apply {
                addView(label("The widget follows these players. Apps that offer a music library are followed from the start; anything else holding a media session, such as the phone's call screen, is not.",
                    13f, Palette.MUTED).apply { setPadding(0, px(14), 0, px(6)) })
                players.forEach { pkg ->
                    val on = Media.follows(me, pkg)
                    addView(row(Media.label(me, pkg), if (on) "Followed" else "Ignored",
                        toggle(on, Media.label(me, pkg)) { v -> Media.setFollows(me, pkg, v); redraw() }))
                }
            })
            val followed = players.filter { Media.follows(me, it) }
            if (followed.isNotEmpty()) column.addView(card().apply {
                val choice = Media.playWith(me)
                val last = Media.lastPlayer(me)?.let { Media.label(me, it) }
                addView(row("When nothing is playing", "Play starts " + (choice?.let { Media.label(me, it) } ?: last?.let { "the last player used, now $it" } ?: "the last player used"), null))
                (listOf<String?>(null) + followed).forEach { pkg ->
                    val on = pkg == choice
                    addView(label(pkg?.let { Media.label(me, it) } ?: "The last player used", 15f, if (on) Palette.CHAMPAGNE else Palette.IVORY, if (on) 600 else 500).apply {
                        setPadding(0, px(12), 0, px(12))
                        setOnClickListener { Media.setPlayWith(me, pkg); redraw() }
                    })
                }
            })
        }

        // ---- Calendar (5 Nov 2026): for every agenda and month. Calendars and days are per widget.
        if (Agenda.allowed(this)) {
            val me = this
            val redraw = { build(); Widgets.later { Widgets.renderAll(me, Kind.AGENDA); Widgets.renderAll(me, Kind.MONTH) } }
            column.addView(eyebrow("Calendar"))
            column.addView(card().apply {
                val hide = AgendaOptions.hideDeclined(me)
                addView(row("Declined invitations", if (hide) "Left out of the agenda" else "Shown in the agenda",
                    toggle(!hide, "Show declined invitations") { v -> AgendaOptions.setHideDeclined(me, !v); redraw() }))
                val apps = listOfNotNull(null to "Default",
                    (AgendaOptions.SAMSUNG to "Samsung").takeIf { AgendaOptions.installed(me, AgendaOptions.SAMSUNG) },
                    (AgendaOptions.GOOGLE to "Google").takeIf { AgendaOptions.installed(me, AgendaOptions.GOOGLE) })
                val choice = AgendaOptions.app(me)
                addView(row("A tap opens", choice?.let { Media.label(me, it) } ?: "The phone's default calendar", null))
                if (apps.size > 1) addView(segments(apps.map { it.second }, apps.indexOfFirst { it.first == choice }.coerceAtLeast(0)) { i ->
                    AgendaOptions.setApp(me, apps[i].first); redraw()
                }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(14) })
                addView(label("Which calendars an agenda shows, and how far ahead, are set on each widget: hold it on the home screen and open its settings.",
                    13f, Palette.MUTED).apply { setPadding(0, 0, 0, px(14)) })
            })
        }

        // ---- Icons: the marks in the widgets come from the home screen's icon pack
        val packs = Icons.packs(this)
        if (packs.isNotEmpty()) {
            column.addView(eyebrow("Icons"))
            column.addView(card().apply {
                val inUse = Icons.pack(this@SettingsActivity)
                addView(label("The music widget's player icon is taken from the icon pack you use on the home screen.",
                    13f, Palette.MUTED).apply { setPadding(0, px(14), 0, px(6)) })
                (packs.toList() + (null to "Each app's own icon")).forEach { (pkg, name) ->
                    val on = pkg == inUse
                    addView(row(name, if (on) "In use" else "", if (on) status(true, "In use") else button("Use", primary = false) {
                        Icons.setPack(this@SettingsActivity, pkg); build(); Widgets.later { Widgets.renderAll(this@SettingsActivity) }
                    }))
                }
            })
        }

        // ---- Finish
        column.addView(eyebrow("Finish"))
        column.addView(finishGrid(Finish.entries.toList(), { it == Prefs.defaultFinish(this) }) { f ->
            if (f != null) { Prefs.setDefaultFinish(this, f); Prefs.followAll(this) }
            Widgets.later { Widgets.renderAll(this) }
        })
        column.addView(label("Choosing here dresses every widget. To give one widget its own finish afterwards, hold it on the home screen and open its settings.",
            13f, Palette.MUTED).apply { setPadding(px(4), 0, px(4), 0) })

        // ---- Corners
        column.addView(eyebrow("Corners"))
        val corners = listOf("Square" to 0, "Soft" to 14, "Round" to 24, "Full" to 32)
        column.addView(segments(corners.map { it.first }, corners.indexOfFirst { it.second == Prefs.radius(this) }.coerceAtLeast(0)) { i ->
            Prefs.setRadius(this@SettingsActivity, corners[i].second); Widgets.later { Widgets.renderAll(this@SettingsActivity) }
        })

        // ---- Weather
        column.addView(eyebrow("Weather"))
        column.addView(card().apply {
            val place = Weather.place(this@SettingsActivity)
            addView(row(place?.name ?: "No place yet", if (place == null) "Search below, or use where the phone is" else "Weather from Open-Meteo, every half hour",
                button("Use my location", primary = place == null) { useLocation() }))
            val field = EditText(this@SettingsActivity).apply {
                hint = "Search a town or city"
                setHintTextColor(Palette.MUTED); setTextColor(Palette.IVORY); textSize = 15f
                typeface = Type.sans(context, 400)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                imeOptions = EditorInfo.IME_ACTION_SEARCH
                background = rounded(Palette.RAISED, px(14).toFloat(), Palette.HAIRLINE, px(1))
                setPadding(px(14), px(12), px(14), px(12))
            }
            val results = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.VERTICAL }
            field.setOnEditorActionListener { v, _, _ -> search(v.text.toString(), results); true }
            addView(field, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(8) })
            addView(results)
        })

        // ---- What a tap opens (4 Nov): any weather app, offered only if it is on the phone
        column.addView(card().apply {
            val me = this@SettingsActivity
            val choice = Weather.opener(me)
            addView(row("A tap opens", Weather.openerName(me), null))
            val fixed = listOfNotNull(
                (Weather.CIELO to "Cielo").takeIf { Weather.installed(me, Weather.CIELO) },
                (Weather.SAMSUNG_WEATHER to "Samsung").takeIf { Weather.installed(me, Weather.SAMSUNG_WEATHER) },
                Weather.GOOGLE to "Google",
            )
            val other = choice !in fixed.map { it.first }
            val apps = LinearLayout(me).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
            val opts = fixed + ("" to "Other")
            addView(segments(opts.map { it.second }, if (other) opts.size - 1 else opts.indexOfFirst { it.first == choice }.coerceAtLeast(0)) { i ->
                val pkg = opts[i].first
                if (pkg.isEmpty()) apps.visibility = View.VISIBLE
                else { apps.visibility = View.GONE; Weather.setOpener(me, pkg); Widgets.later { Widgets.renderAll(me, Kind.WEATHER) } }
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(14) })
            // Other: every app with a launcher entry, by name
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(launcher, 0)
                .map { it.activityInfo.packageName }.distinct()
                .filter { it != packageName && it !in fixed.map { f -> f.first } }
                .map { it to Media.label(me, it) }.sortedBy { it.second.lowercase() }
                .forEach { (pkg, name) ->
                    apps.addView(label(name, 15f, if (pkg == choice) Palette.CHAMPAGNE else Palette.IVORY, 500).apply {
                        setPadding(0, px(12), 0, px(12))
                        setOnClickListener { Weather.setOpener(me, pkg); build(); Widgets.later { Widgets.renderAll(me, Kind.WEATHER) } }
                    })
                }
            addView(apps)
        })

        // ---- Glass
        column.addView(eyebrow("Glass"))
        column.addView(card().apply {
            val seats = Kind.entries.flatMap { Widgets.ids(this@SettingsActivity, it).toList() }
            val seated = seats.count { Backdrop.seat(this@SettingsActivity, it) != null }
            addView(row("Seating", if (seats.isEmpty()) "Add a widget to your home screen first"
                else "$seated of ${seats.size} widgets know where they sit. Tap any part of a widget once and its glass lines up with the wallpaper behind it.",
                if (seated > 0) button("Reset", primary = false) {
                    seats.forEach { Prefs.of(this@SettingsActivity).edit().remove("seat_$it").apply() }
                    build(); Widgets.later { Widgets.renderAll(this@SettingsActivity) }
                } else null))
            for ((key, name) in listOf("image_cover" to "Cover screen image", "image_main" to "Main screen image")) {
                val set = Prefs.of(this@SettingsActivity).getString(key, null) != null
                addView(row(name, if (set) "Your own image is used behind the glass on this screen"
                    else "Uses the system wallpaper. If the glass shows the wrong picture here, choose the same image you use as this screen's wallpaper.",
                    if (set) button("Clear", primary = false) { Prefs.of(this@SettingsActivity).edit().remove(key).apply(); Backdrop.invalidate(); Look.changed(this@SettingsActivity); build(); Widgets.later { Widgets.renderAll(this@SettingsActivity) } }
                    else button("Choose", primary = false) { pickImage(key) }))
            }
        })

        // ---- Sizes: what each widget was told and what it measured, for tuning on the phone
        val placed = Kind.entries.flatMap { Widgets.ids(this, it).toList() }
        if (placed.isNotEmpty()) {
            column.addView(eyebrow("Sharpness"))
            column.addView(card().apply {
                addView(label("Each widget is drawn to the exact pixels it occupies once you have tapped it. Until then it uses the size the launcher reports, which may be wrong and look soft.",
                    13f, Palette.MUTED).apply { setPadding(0, px(14), 0, px(6)) })
                placed.forEach { id ->
                    val d = Prefs.of(this@SettingsActivity).getString("diag_$id", null) ?: return@forEach
                    addView(label(d, 12.5f, if (d.contains("measured")) Palette.IVORY else Palette.WARN).apply { setPadding(0, px(8), 0, px(8)) })
                }
            })
        }

        column.addView(eyebrow("How it works"))
        column.addView(label(NOTES, 13.5f, Palette.MUTED).apply { setPadding(px(4), 0, px(4), 0) })
    }

    private fun search(name: String, into: LinearLayout) {
        if (name.isBlank()) return
        into.removeAllViews()
        into.addView(label("Searching…", 13f, Palette.MUTED).apply { setPadding(px(4), px(8), 0, px(8)) })
        Thread {
            val found = runCatching { Weather.search(name.trim()) }
            runOnUiThread {
                into.removeAllViews()
                val list = found.getOrNull()
                if (list.isNullOrEmpty()) {
                    into.addView(label(if (found.isFailure) "Couldn't reach Open-Meteo. Check the connection." else "No places called $name", 13f, Palette.MUTED)
                        .apply { setPadding(px(4), px(8), 0, px(8)) })
                    return@runOnUiThread
                }
                list.forEach { p ->
                    into.addView(row(p.name, p.detail, button("Use", primary = false) { choose(p) }))
                }
            }
        }.start()
    }

    private fun choose(p: Place) {
        Weather.setPlace(this, p)
        RefreshJob.schedule(this)
        Thread {
            runCatching { Weather.refresh(this) }
            Widgets.renderAll(this, Kind.WEATHER)
        }.start()
        build()
    }

    private fun useLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 2); return
        }
        Thread {
            val here = Weather.here(this)
            // Name the spot by the nearest town Open-Meteo knows
            val named = here?.let { h ->
                runCatching { reverseName(h) }.getOrNull()?.let { Place(it, h.lat, h.lon) } ?: Place("Here", h.lat, h.lon)
            }
            runOnUiThread {
                if (named == null) Toast.makeText(this, "No recent location. Open Maps once, then try again.", Toast.LENGTH_LONG).show()
                else choose(named)
            }
        }.start()
    }

    /**
     * Open-Meteo's geocoder searches by name only, so the spot is named from the phone's own
     * geocoder when it has one.
     */
    private fun reverseName(p: Place): String? {
        @Suppress("DEPRECATION")
        val list = android.location.Geocoder(this).getFromLocation(p.lat, p.lon, 1)
        return list?.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
    }

    private fun pickImage(key: String) {
        pendingImageKey = key
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), 10)
    }

    private var pendingImageKey: String? = null

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        val key = pendingImageKey
        if (requestCode == 10 && resultCode == RESULT_OK && uri != null && key != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            Prefs.of(this).edit().putString(key, uri.toString()).apply()
            Backdrop.invalidate()
            Look.changed(this)
            Widgets.later { Widgets.renderAll(this) }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2 && grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) useLocation()
        build()
        Clockwork.arm(this)
        Widgets.later { Widgets.renderAll(this) }
    }

    companion object {
        const val NOTES =
            "Each widget is drawn at its exact size, so the type, artwork and controls scale as you resize it. " +
            "The small and large defaults match the two widgets on your cover screen.\n\n" +
            "The adaptive finishes read what is behind each widget in a grid of zones, as the taskbar reads the screen: dark over light, " +
            "light over dark, with a trace of the colour beneath. Legibility comes first: each zone is checked at its lightest and darkest, " +
            "not its average, and if the type would not reach a contrast of 7 to 1 the finish flips, or its glass thickens just enough. A launcher never tells a widget where it sits, so each one learns it from " +
            "your first tap. Until then it reads the wallpaper's overall colour.\n\n" +
            "The glass is the wallpaper itself, thickened. Cristallo is clear: near its rim it bends in what lies just beyond the edge, " +
            "as a curved edge bends light. Vetro is frosted and takes the wallpaper's colour. Both carry one highlight at the upper left, " +
            "cool by day and warm and dim at night. Before a widget's first tap its glass is clear and flat, and its size is the launcher's guess: one tap measures it.\n\n" +
            "As a widget gets smaller, things fall away in a fixed order until only the vital thing is left: the play disc, today's date, " +
            "the temperature. Music has one control, play and pause; any other tap opens the player.\n\n" +
            "The music widget follows the player Android marks as active. Playing beats paused; among equals, the most recent wins."
    }
}

/**
 * Opened by the launcher when a widget is placed or reconfigured: that widget's own finish.
 * Placing works without it too, wearing the default.
 */
class ConfigureActivity : Activity() {
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        com.cerniera.kit.Crash.install(this, "Fold Widgets")
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        // Leaving without a choice keeps the widget, wearing whatever it wore
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        window.statusBarColor = Palette.GROUND
        window.navigationBarColor = Palette.GROUND
        val (scroll, column) = screen()
        column.addView(label("This widget", 32f, Palette.IVORY, display = true))
        Widgets.kindOf(this, id)?.let { k -> column.addView(label(Widgets.describe(this, id, k), 14f, Palette.CHAMPAGNE, 500).apply { setPadding(0, px(6), 0, 0) }) }
        column.addView(label("Its finish and what it shows. Follow app keeps this widget in the finish chosen for the whole app.", 14f, Palette.MUTED).apply { setPadding(0, px(8), 0, 0) })
        column.addView(eyebrow("Finish"))
        column.addView(finishGrid(listOf<Finish?>(null) + Finish.entries,
            { f -> if (f == null) !Prefs.hasOwnFinish(this, id) else Prefs.hasOwnFinish(this, id) && Prefs.finish(this, id) == f }) { f ->
            Prefs.setFinish(this, id, f)
            Widgets.later { Widgets.render(this, id) }
        })
        // Weather: what this widget shows beyond its minimum (31 Oct)
        if (Widgets.kindOf(this, id) == Kind.WEATHER) {
            column.addView(eyebrow("Shows"))
            column.addView(label("Always: the place, the temperature and the conditions. Then, as the widget's size allows, whichever of these you choose, in this order.",
                13f, Palette.MUTED).apply { setPadding(px(4), 0, px(4), px(10)) })
            column.addView(card().apply {
                WeatherOptions.Item.entries.forEach { item ->
                    val on = WeatherOptions.on(this@ConfigureActivity, id, item)
                    addView(row(item.label, item.note, toggle(on, item.label) { v ->
                        WeatherOptions.set(this@ConfigureActivity, id, item, v)
                        Widgets.later { Widgets.render(this@ConfigureActivity, id) }
                    }))
                }
            })
        }
        // Agenda: how far ahead, and which calendars (5 Nov)
        if (Widgets.kindOf(this, id) == Kind.AGENDA) {
            val me = this
            val redraw = { Widgets.later { Widgets.render(me, id) } }
            column.addView(eyebrow("Days ahead"))
            column.addView(segments(AgendaOptions.RANGES.map { it.second }, AgendaOptions.RANGES.indexOfFirst { it.first == AgendaOptions.days(me, id) }.coerceAtLeast(0)) { i ->
                AgendaOptions.setDays(me, id, AgendaOptions.RANGES[i].first); redraw()
            })
            val cals = Agenda.calendars(this)
            if (cals.isNotEmpty()) {
                column.addView(eyebrow("Calendars"))
                column.addView(label("This agenda shows the calendars marked Shown. New calendars appear while every calendar is shown.",
                    13f, Palette.MUTED).apply { setPadding(px(4), 0, px(4), px(10)) })
                column.addView(card().apply {
                    val chosen = AgendaOptions.calendars(me, id)
                    cals.forEach { cal ->
                        val on = chosen == null || cal.id in chosen
                        val name = android.text.SpannableString("●  " + cal.name).apply {
                            setSpan(android.text.style.ForegroundColorSpan(cal.colour), 0, 1, 0)
                        }
                        addView(row(cal.name, cal.account, toggle(on, cal.name) { v ->
                            AgendaOptions.setCalendar(me, id, cal.id, v); redraw()
                        }).also { r -> (r.getChildAt(0) as? LinearLayout)?.getChildAt(0)?.let { (it as? android.widget.TextView)?.text = name } })
                    }
                })
            }
        }
        column.addView(button("Done") { finish() }.apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10) })
        column.addView(View(this), LinearLayout.LayoutParams(-1, px(20)))
        setContentView(scroll)
        Widgets.later { Widgets.render(this, id) }
    }
}
