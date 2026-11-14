package com.fergusonconsultancy.foldwidgets

import com.cerniera.kit.Surface
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.location.LocationManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A place for the weather. */
class Place(val name: String, val lat: Double, val lon: Double, val detail: String = "")

/**
 * The weather as last fetched. Everything beyond the temperature, the sky and today's range is
 * optional: Open-Meteo may not give it, and the widget shows only what it has.
 */
class Forecast(
    val temp: Double, val code: Int, val day: Boolean, val high: Double, val low: Double,
    val hours: List<Hour>, val fetched: Long,
    val days: List<Day> = emptyList(),
    val feels: Double? = null, val humidity: Int? = null, val wind: Double? = null, val windDir: Int? = null,
    val pressure: Double? = null, val uv: Double? = null, val visibility: Double? = null, val dew: Double? = null,
    val sunrise: String? = null, val sunset: String? = null, val nextSunrise: String? = null,
    val aqi: Int? = null, val pollen: Pair<String, Double>? = null,
)
/** [hour] is the hour of day in the place's own time, 0 to 23. */
class Hour(val label: String, val temp: Double, val code: Int, val day: Boolean, val hour: Int = 0, val rain: Int? = null)
class Day(val date: String, val code: Int, val max: Double, val min: Double, val rain: Int? = null)

/**
 * Weather from Open-Meteo: free, no account, no key. The place is chosen in settings, by name or
 * from the phone's location; the widget refreshes about every half hour when there is a network,
 * and at once when tapped.
 */
object Weather {

    fun place(c: Context): Place? {
        val p = Prefs.of(c)
        val name = p.getString("place_name", null) ?: return null
        return Place(name, p.getFloat("place_lat", 0f).toDouble(), p.getFloat("place_lon", 0f).toDouble())
    }

    fun setPlace(c: Context, place: Place) = Prefs.of(c).edit()
        .putString("place_name", place.name).putFloat("place_lat", place.lat.toFloat()).putFloat("place_lon", place.lon.toFloat())
        .remove("forecast").apply()

    fun forecast(c: Context): Forecast? = runCatching { parse(JSONObject(Prefs.of(c).getString("forecast", null)!!)) }.getOrNull()

    /**
     * Fetches and keeps the forecast. Network work: never on the main thread. Asks for everything
     * the widget can show; if Open-Meteo refuses any of it, falls back to the forecast that has
     * always worked, so the weather is never lost for want of a detail.
     */
    fun refresh(c: Context) {
        val place = place(c) ?: return
        val base = "https://api.open-meteo.com/v1/forecast?latitude=${place.lat}&longitude=${place.lon}&timezone=auto"
        val full = base +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,is_day,wind_speed_10m," +
            "wind_direction_10m,pressure_msl,uv_index,dew_point_2m,visibility" +
            "&hourly=temperature_2m,weather_code,is_day,precipitation_probability" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,precipitation_probability_max" +
            "&forecast_days=8&wind_speed_unit=mph"
        val minimal = base + "&current=temperature_2m,weather_code,is_day&hourly=temperature_2m,weather_code,is_day" +
            "&daily=temperature_2m_max,temperature_2m_min&forecast_days=2"
        val json = runCatching { JSONObject(get(full)).also { parse(it) } }.getOrNull() ?: JSONObject(get(minimal))
        // Air quality and pollen come from a separate service; without them the rest still shows
        runCatching {
            val aq = JSONObject(get("https://air-quality-api.open-meteo.com/v1/air-quality?latitude=${place.lat}&longitude=${place.lon}" +
                "&current=european_aqi,grass_pollen,birch_pollen,alder_pollen&timezone=auto"))
            json.put("aq", aq.getJSONObject("current"))
        }
        json.put("fetched", System.currentTimeMillis())
        parse(json)  // only keep what reads back cleanly
        Prefs.of(c).edit().putString("forecast", json.toString()).apply()
    }

    /** Places matching [name], for the settings screen. */
    fun search(name: String): List<Place> {
        val json = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=6&language=en&format=json&name=" + URLEncoder.encode(name, "UTF-8")))
        val results = json.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).map { i ->
            val r = results.getJSONObject(i)
            Place(r.getString("name"), r.getDouble("latitude"), r.getDouble("longitude"),
                listOfNotNull(r.optString("admin1").takeIf { it.isNotEmpty() }, r.optString("country").takeIf { it.isNotEmpty() }).joinToString(", "))
        }
    }

    /** The phone's last known position, named by the nearest place Open-Meteo knows. */
    fun here(c: Context): Place? {
        if (c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val lm = c.getSystemService(LocationManager::class.java)
        val loc = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time } ?: return null
        return Place("Here", loc.latitude, loc.longitude)
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000; conn.readTimeout = 10_000
        conn.setRequestProperty("User-Agent", "FoldWidgets/1.0")
        try {
            if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    }

    private fun parse(j: JSONObject): Forecast {
        val cur = j.getJSONObject("current")
        val daily = j.getJSONObject("daily")
        val hourly = j.getJSONObject("hourly")
        val times = hourly.getJSONArray("time")
        val nowIso = cur.getString("time").take(13)  // yyyy-MM-ddTHH, in the place's own time
        var start = (0 until times.length()).firstOrNull { times.getString(it).take(13) >= nowIso } ?: 0
        start += 1
        val rainH = hourly.optJSONArray("precipitation_probability")
        val hours = (start until minOf(start + 10, times.length())).map { i ->
            val hr = times.getString(i).substring(11, 13).toInt()
            Hour(times.getString(i).substring(11, 13), hourly.getJSONArray("temperature_2m").getDouble(i),
                hourly.getJSONArray("weather_code").getInt(i), hourly.getJSONArray("is_day").getInt(i) == 1, hr,
                rainH?.let { if (it.isNull(i)) null else it.optInt(i) })
        }
        fun num(o: JSONObject, k: String) = if (o.has(k) && !o.isNull(k)) o.getDouble(k) else null
        val dTimes = daily.optJSONArray("time")
        val dCode = daily.optJSONArray("weather_code")
        val dRain = daily.optJSONArray("precipitation_probability_max")
        val days = if (dTimes == null || dCode == null) emptyList() else (0 until dTimes.length()).map { i ->
            Day(dTimes.getString(i), dCode.optInt(i), daily.getJSONArray("temperature_2m_max").getDouble(i),
                daily.getJSONArray("temperature_2m_min").getDouble(i), dRain?.let { if (it.isNull(i)) null else it.optInt(i) })
        }
        val rise = daily.optJSONArray("sunrise"); val set = daily.optJSONArray("sunset")
        val aq = j.optJSONObject("aq")
        val pollen = aq?.let { a ->
            listOf("grass_pollen" to "grass", "birch_pollen" to "birch", "alder_pollen" to "alder")
                .mapNotNull { (k, name) -> num(a, k)?.let { name to it } }.maxByOrNull { it.second }
        }
        return Forecast(
            cur.getDouble("temperature_2m"), cur.getInt("weather_code"), cur.getInt("is_day") == 1,
            daily.getJSONArray("temperature_2m_max").getDouble(0), daily.getJSONArray("temperature_2m_min").getDouble(0),
            hours, j.optLong("fetched", 0L), days,
            feels = num(cur, "apparent_temperature"), humidity = num(cur, "relative_humidity_2m")?.toInt(),
            wind = num(cur, "wind_speed_10m"), windDir = num(cur, "wind_direction_10m")?.toInt(),
            pressure = num(cur, "pressure_msl"), uv = num(cur, "uv_index"), visibility = num(cur, "visibility"),
            dew = num(cur, "dew_point_2m"),
            sunrise = rise?.optString(0)?.takeIf { it.length >= 16 }, sunset = set?.optString(0)?.takeIf { it.length >= 16 },
            nextSunrise = rise?.optString(1)?.takeIf { it.length >= 16 },
            aqi = aq?.let { num(it, "european_aqi")?.toInt() }, pollen = pollen,
        )
    }

    /** The WMO weather code as words and a sky to draw. */
    fun describe(code: Int): Pair<String, Glyphs.Sky> = when (code) {
        0 -> "Clear" to Glyphs.Sky.CLEAR
        1 -> "Mainly clear" to Glyphs.Sky.CLEAR
        2 -> "Partly cloudy" to Glyphs.Sky.PARTLY
        3 -> "Overcast" to Glyphs.Sky.CLOUD
        45, 48 -> "Fog" to Glyphs.Sky.FOG
        51, 53, 55, 56, 57 -> "Drizzle" to Glyphs.Sky.DRIZZLE
        61, 63, 66 -> "Rain" to Glyphs.Sky.RAIN
        65, 67 -> "Heavy rain" to Glyphs.Sky.RAIN
        71, 73, 75, 77 -> "Snow" to Glyphs.Sky.SNOW
        80, 81 -> "Showers" to Glyphs.Sky.RAIN
        82 -> "Heavy showers" to Glyphs.Sky.RAIN
        85, 86 -> "Snow showers" to Glyphs.Sky.SNOW
        95 -> "Thunderstorm" to Glyphs.Sky.STORM
        96, 99 -> "Thunder and hail" to Glyphs.Sky.STORM
        else -> "Unsettled" to Glyphs.Sky.CLOUD
    }

    fun deg(t: Double) = "${Math.round(t)}°"

    /** Samsung Weather's package (verified against its APK listings). */
    const val SAMSUNG_WEATHER = "com.sec.android.daemonapp"
    const val CIELO = "com.fergusonconsultancy.cielo"
    /** Not an app: the weather in Google search. */
    const val GOOGLE = "google"

    /** What a tap on a weather widget opens: an app's package, or [GOOGLE]. Samsung Weather until chosen. */
    fun opener(c: Context): String = Prefs.of(c).getString("weather_app", null) ?: SAMSUNG_WEATHER
    fun setOpener(c: Context, v: String) = Prefs.of(c).edit().putString("weather_app", v).apply()

    fun openerName(c: Context, v: String = opener(c)): String = when (v) {
        GOOGLE -> "Google"
        SAMSUNG_WEATHER -> "Samsung Weather"
        else -> Media.label(c, v)
    }

    fun installed(c: Context, pkg: String) = runCatching { c.packageManager.getApplicationInfo(pkg, 0); true }.getOrDefault(false)

    /**
     * A tap opens Samsung Weather. Which way in exists is decided at the moment of the tap, not when
     * the widget was drawn: Samsung Weather normally has no Apps-screen icon (it is opened from its
     * own widget), so the usual way in may be missing until it is added there.
     */
    fun openApp(c: Context): Act = Act.Open(intent = Intent(c, TapActivity::class.java)
        .putExtra(TapActivity.EXTRA_RESOLVE, TapActivity.RESOLVE_WEATHER))

    /**
     * Every way into Samsung Weather Android offers this app, best first: its Apps-screen entry,
     * then any main screen it exposes. Null if it shows none.
     */
    fun launchIntent(c: Context): Intent? {
        val pm = c.packageManager
        pm.getLaunchIntentForPackage(SAMSUNG_WEATHER)?.let { return it }
        val main = Intent(Intent.ACTION_MAIN).setPackage(SAMSUNG_WEATHER)
        @Suppress("DEPRECATION")
        val found = pm.queryIntentActivities(main, 0).firstOrNull { it.activityInfo?.exported == true } ?: return null
        return Intent(Intent.ACTION_MAIN).setClassName(SAMSUNG_WEATHER, found.activityInfo.name)
    }
}

/**
 * What a weather widget may show beyond its minimum, chosen per widget in its settings (31 Oct).
 * Every widget shows the place, the temperature and the conditions; these come in as space allows.
 */
object WeatherOptions {
    enum class Item(val label: String, val note: String) {
        HOURLY("Next hours", "Temperature, sky and chance of rain hour by hour"),
        WEEK("Week ahead", "Each day's sky, chance of rain and range"),
        RANGE("Today's high and low", "Beneath the temperature"),
        FEELS("Feels like", "The temperature as it feels"),
        HUMIDITY("Humidity", "Relative humidity"),
        WIND("Wind", "Direction and speed in mph"),
        UV("UV index", "With its level"),
        SUN("Sunrise and sunset", "Whichever comes next"),
        AIR("Air quality", "European index, with its level"),
        POLLEN("Pollen", "The highest of grass, birch and alder"),
        PRESSURE("Pressure", "At sea level, in millibars"),
        VISIBILITY("Visibility", "In kilometres"),
        DEW("Dew point", "Where the air would turn to dew"),
    }

    /** The details, the tiles at the foot of a large widget, in the order they are offered. */
    val DETAILS = listOf(Item.FEELS, Item.HUMIDITY, Item.WIND, Item.UV, Item.SUN, Item.AIR, Item.POLLEN, Item.PRESSURE, Item.VISIBILITY, Item.DEW)

    /**
     * Weather · Today keeps to today: the hours and the details, no week unless switched on.
     * Weather · Forecast leads with the week (1 Nov: the two looked identical once placed).
     */
    fun on(c: Context, id: Int, item: Item): Boolean =
        Prefs.of(c).getBoolean("wx_${id}_${item.name}", item != Item.WEEK || Widgets.forecast(c, id))
    fun set(c: Context, id: Int, item: Item, on: Boolean) = Prefs.of(c).edit().putBoolean("wx_${id}_${item.name}", on).apply()
}

/**
 * The weather. The minimum, at every size: the place, the temperature in Italiana, the conditions
 * and the sky drawn as Bauhaus geometry. Beyond that the widget fills with what it has room for,
 * in this order: today's range and how it feels, the next hours, the week ahead, then details as
 * tiles. A short wide widget lays the minimum out in a row and runs the hours along it; a tall
 * narrow one stacks the week beneath. Which of these appear is chosen per widget.
 */
object WeatherPainter : Painter {

    override fun paint(c: Context, id: Int, canvas: Canvas, s: Surface): Scene {
        val place = Weather.place(c)
        val fc = Weather.forecast(c)
        val all = RectF(0f, 0f, s.w, s.h)
        val p = if (minOf(s.w, s.h) >= 220f) 18f else if (minOf(s.w, s.h) >= 120f) 14f else 10f

        if (place == null || fc == null) {
            val titleP = c.statement(s.ink(s.w / 2, s.h / 2))
            canvas.textTop("Weather", p, p, titleP)
            val noteP = c.ink(12.5f, s.muted(s.w / 2, s.h / 2), 450)
            val note = if (place == null) "Tap to choose a place" else "Fetching ${place.name}…"
            wrap(note, noteP, s.w - 2 * p, 2).forEachIndexed { i, l -> canvas.textTop(l, p, p + capHeight(titleP) + 12f + i * noteP.textSize * 1.3f, noteP) }
            val act = if (place == null) Act.Open(intent = Intent(c, SettingsActivity::class.java)) else Weather.openApp(c)
            return Scene(listOf(Hit(all, act, "Weather")))
        }
        fun on(i: WeatherOptions.Item) = WeatherOptions.on(c, id, i)

        if (s.h < 190f && s.w >= s.h * 1.5f) strip(c, canvas, s, fc, place, p, ::on, Widgets.forecast(c, id)) else column(c, canvas, s, fc, place, p, ::on)
        return Scene(listOf(Hit(all, Weather.openApp(c), "Open " + Weather.openerName(c))))
    }

    // ---- A short, wide widget: the minimum in a row, then the hours ----------------------------

    private fun strip(c: Context, cv: Canvas, s: Surface, fc: Forecast, place: Place, p: Float, on: (WeatherOptions.Item) -> Boolean, weekFirst: Boolean) {
        val (words, sky) = Weather.describe(fc.code)
        val tempP = c.ink(minOf(s.h * 0.52f, 64f), s.ink(p + 30f, s.h / 2), display = true)
        val temp = Weather.deg(fc.temp)
        val capT = capHeight(tempP)
        val base = (s.h + capT) / 2f
        cv.drawText(temp, p - 1f, base, tempP)
        var x = p + tempP.measureText(temp) + 14f
        // Place, conditions and the range, stacked beside the temperature
        val placeP = c.ink(14.5f, s.ink(x + 40f, s.h / 2 - 12f), 600)
        val wordsP = c.meta(s.muted(x + 40f, s.h / 2 + 6f))
        val rangeP = c.ink(11.5f, s.muted(x + 40f, s.h / 2 + 20f), 500)
        val showRange = on(WeatherOptions.Item.RANGE) && s.h >= 100f
        val lines = if (showRange) 3 else 2
        val rangeText = "H ${Weather.deg(fc.high)}   L ${Weather.deg(fc.low)}"
        // The block is as wide as its words, so the hours get the rest
        val blockW = minOf(maxOf(placeP.measureText(place.name), wordsP.measureText(words), if (showRange) rangeP.measureText(rangeText) else 0f),
            150f, s.w - x - p)
        var y = s.h / 2f - (lines * 17f) / 2f
        cv.textTop(fit(place.name, placeP, blockW), x, y, placeP); y += 18f
        cv.textTop(fit(words, wordsP, blockW), x, y, wordsP); y += 17f
        if (showRange) cv.textTop(fit(rangeText, rangeP, blockW), x, y, rangeP)
        x += blockW + 12f
        val g = minOf(s.h * 0.42f, 46f)
        // The sky gives way to the hours when there is room for one but not both
        val rest = s.w - p - x
        val hoursWanted = on(WeatherOptions.Item.HOURLY) || on(WeatherOptions.Item.WEEK)
        if (rest >= g && !(hoursWanted && rest >= 2 * 46f && rest - g - 18f < 2 * 46f)) {
            Glyphs.sky(cv, sky, fc.day, x + g / 2, s.h / 2, g, s.ink(x + g / 2, s.h / 2), s.accent(x + g / 2, s.h / 2))
            x += g + 18f
        }
        // The hours, as many as fit; else the week
        val room = s.w - p - x
        if (room >= 2 * 46f) {
            cv.drawLine(x - 9f, p + 4f, x - 9f, s.h - p - 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = s.hairline; color = s.faint(x, s.h / 2) })
            // Forecast runs the week along the strip; Today, the hours
            if (weekFirst && on(WeatherOptions.Item.WEEK)) weekColumns(c, cv, s, fc, x, p, s.w - p, s.h - p)
            else if (on(WeatherOptions.Item.HOURLY)) hours(c, cv, s, fc, x, p, s.w - p, s.h - p)
            else if (on(WeatherOptions.Item.WEEK)) weekColumns(c, cv, s, fc, x, p, s.w - p, s.h - p)
        }
    }

    // ---- Everything else: the minimum at the top, then modules down the widget ------------------

    private fun column(c: Context, cv: Canvas, s: Surface, fc: Forecast, place: Place, p: Float, on: (WeatherOptions.Item) -> Boolean) {
        // Wide and tall enough: the minimum in a left column, the modules beside it (31 Oct)
        val split = s.w >= 360f && s.h >= 200f && s.w >= s.h * 1.4f
        val headRight = if (split) s.w * 0.42f else s.w - p
        val headBottom = header(c, cv, s, fc, place, p, p, headRight, null, on)
        if (headBottom < 0f) return
        if (split) {
            val x = headRight + 10f
            val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = s.hairline; color = s.faint(x, s.h / 2) }
            cv.drawLine(x, p + 4f, x, s.h - p - 4f, rule)
            // The hours and the week beside the minimum; the details beneath it
            modules(c, cv, s, fc, x + 14f, s.w - p, p, s.h - p, on, divideFirst = false, allowed = setOf(Module.HOURS, Module.WEEK))
            modules(c, cv, s, fc, p, headRight, headBottom + 6f, s.h - p, on, divideFirst = true, allowed = setOf(Module.DETAILS))
        } else modules(c, cv, s, fc, p, s.w - p, headBottom + 6f, s.h - p, on, divideFirst = true)
    }

    /**
     * The minimum: place and conditions top left, the sky top right, the temperature beneath with
     * today's range and how it feels. Returns where it ends, or -1 when the widget is too small for
     * anything more. With [fillTo], the temperature sits at the foot of a column that tall.
     */
    private fun header(c: Context, cv: Canvas, s: Surface, fc: Forecast, place: Place, p: Float, left: Float, right: Float,
                       fillTo: Float?, on: (WeatherOptions.Item) -> Boolean): Float {
        val (words, sky) = Weather.describe(fc.code)
        val w = right - left
        if (w < 150f) return narrow(c, cv, s, fc, place, p, left, right, words, sky, on)
        val tiny = minOf(s.w, s.h) < 120f
        val g = (minOf(w, s.h) * 0.24f).coerceIn(26f, 64f)
        val gx = right - g / 2; val gy = p + g / 2 - 2f
        Glyphs.sky(cv, sky, fc.day, gx, gy, g, s.ink(gx, gy), s.accent(gx, gy))
        val textW = w - g - 6f
        val placeP = c.ink(if (tiny) 12.5f else 15f, s.ink(left + textW / 2, p + 6f), 600)
        val wordsP = c.ink(if (tiny) 11.5f else 13f, s.muted(left + textW / 2, p + 24f), 450)
        cv.textTop(fit(place.name, placeP, textW), left, p, placeP)
        cv.textTop(fit(words, wordsP, textW), left, p + placeP.textSize * 1.4f, wordsP)
        val topBlock = p + maxOf(placeP.textSize * 1.4f + wordsP.textSize * 1.2f, g)

        val tempSize = if (tiny) minOf(s.h * 0.34f, w * 0.3f).coerceAtLeast(24f) else minOf(w * 0.30f, s.h * 0.2f, 84f).coerceAtLeast(44f)
        val tempP = c.ink(tempSize, s.ink(left + 40f, topBlock + tempSize / 2), display = true)
        val capT = capHeight(tempP)
        val showRange = on(WeatherOptions.Item.RANGE) && !tiny
        val showFeels = on(WeatherOptions.Item.FEELS) && fc.feels != null && !tiny && w >= 200f
        val lineH = if (showRange || showFeels) 22f else 0f
        // In a column of its own the temperature sits at the foot; otherwise just under the place
        var base = if (fillTo != null) fillTo - lineH - 4f else topBlock + 14f + capT
        cv.drawText(Weather.deg(fc.temp), left - 2f, base, tempP)
        if (lineH > 0f) {
            val parts = ArrayList<String>()
            if (showRange) parts += "H ${Weather.deg(fc.high)}   L ${Weather.deg(fc.low)}"
            if (showFeels) parts += "Feels like ${Weather.deg(fc.feels!!)}"
            val lineP = c.meta(s.muted(left + 60f, base + 14f))
            cv.drawText(fit(parts.joinToString("   ·   "), lineP, w), left, base + 10f + capHeight(lineP) + 4f, lineP)
        }
        return if (tiny) -1f else topBlock + 14f + capT + 10f + lineH
    }

    /**
     * Two columns wide (1 Nov: the sky beside the place left the name no room, and the temperature
     * ran off the foot). The words take the full width, place then conditions; the temperature
     * beneath, as large as the room allows, with the sky beside it. Every size keeps the minimum:
     * place, temperature, conditions. Returns where it ends, so a tall one can add the week.
     */
    private fun narrow(c: Context, cv: Canvas, s: Surface, fc: Forecast, place: Place, p: Float, left: Float, right: Float,
                       words: String, sky: Glyphs.Sky, on: (WeatherOptions.Item) -> Boolean): Float {
        val w = right - left
        val placeP = c.ink(if (w < 100f) 12.5f else 14f, s.ink(left + w / 2, p + 6f), 600)
        val wordsP = c.meta(s.muted(left + w / 2, p + 24f), 1f, if (w < 100f) 11.5f else 12.5f)
        cv.textTop(fit(place.name, placeP, w), left, p, placeP)
        cv.textTop(fit(words, wordsP, w), left, p + placeP.textSize * 1.35f, wordsP)
        val top = p + placeP.textSize * 1.35f + wordsP.textSize * 1.3f + 6f
        val bottom = s.h - p
        val showRange = on(WeatherOptions.Item.RANGE) && bottom - top >= 80f
        val lineH = if (showRange) 20f else 0f
        val g = (w * 0.28f).coerceIn(20f, 34f)
        // The temperature fills what is left, never past the foot
        var size = minOf(w * 0.5f, (bottom - top - lineH) * 0.9f, if (s.h >= 230f) 64f else 56f).coerceAtLeast(18f)
        var tempP = c.ink(size, s.ink(left + w * 0.3f, top + size / 2), display = true)
        while (capHeight(tempP) + 4f > bottom - top - lineH && size > 18f) { size -= 2f; tempP = c.ink(size, tempP.color, display = true) }
        val capT = capHeight(tempP)
        val base = top + 4f + capT
        cv.drawText(Weather.deg(fc.temp), left - 1f, base, tempP)
        val tw = tempP.measureText(Weather.deg(fc.temp))
        if (tw + g + 6f <= w) {
            val gx = right - g / 2; val gy = base - capT / 2
            Glyphs.sky(cv, sky, fc.day, gx, gy, g, s.ink(gx, gy), s.accent(gx, gy))
        }
        if (showRange) {
            val lineP = c.meta(s.muted(left + w / 2, base + 14f), 1f, 12f)
            cv.drawText(fit("H ${Weather.deg(fc.high)}  L ${Weather.deg(fc.low)}", lineP, w), left, base + 8f + capHeight(lineP), lineP)
        }
        val end = base + lineH + 10f
        return if (bottom - end >= 3 * 26f + 14f) end else -1f
    }

    private enum class Module { HOURS, WEEK, DETAILS }

    /**
     * The modules between [top] and [bottom], in the order that suits the width: hours first where
     * there is room for at least three of them, the week first in a narrow widget. Each is sized
     * first, then any room left over is shared out between them, so nothing is crowded at the top
     * with a gap at the foot.
     */
    private fun modules(c: Context, cv: Canvas, s: Surface, fc: Forecast, left: Float, right: Float, top0: Float, bottom: Float,
                        on: (WeatherOptions.Item) -> Boolean, divideFirst: Boolean, allowed: Set<Module> = Module.entries.toSet()) {
        val w = right - left
        val tiles = WeatherOptions.DETAILS.filter { on(it) }.mapNotNull { item -> value(c, fc, item)?.let { item to it } }
        val cols = when { w >= 330f -> 3; w >= 180f -> 2; else -> 1 }
        val order = if (w < 3 * 46f) listOf(Module.WEEK, Module.DETAILS, Module.HOURS) else listOf(Module.HOURS, Module.WEEK, Module.DETAILS)
        val plan = ArrayList<Pair<Module, Int>>()   // module and its rows (hours: 1)
        var room = bottom - top0
        val dividerH = 14f
        for (m in order.filter { it in allowed }) {
            when (m) {
                Module.HOURS -> if (on(WeatherOptions.Item.HOURLY) && fc.hours.isNotEmpty() && w >= 3 * 46f && room >= 58f + dividerH) {
                    plan += m to 1; room -= 58f + dividerH
                }
                Module.WEEK -> if (on(WeatherOptions.Item.WEEK) && fc.days.size > 1) {
                    // Leave a row of details if they are wanted and there is plenty of room
                    val reserve = if (tiles.isNotEmpty() && room > 7 * 26f + dividerH + 60f) 46f + dividerH else 0f
                    val rows = minOf(fc.days.size - 1, 7, ((room - dividerH - reserve) / 26f).toInt())
                    if (rows >= 3) { plan += m to rows; room -= rows * 26f + dividerH }
                }
                Module.DETAILS -> if (tiles.isNotEmpty()) {
                    val rows = minOf(((room - dividerH + 6f) / 46f).toInt(), (tiles.size + cols - 1) / cols)
                    if (rows >= 1) { plan += m to rows; room -= rows * 46f - 6f + dividerH }
                }
            }
        }
        if (plan.isEmpty()) return
        // Room left once every wanted module is placed (1 Nov: the tallest widget ended in an empty
        // band). The modules breathe first: hour columns, week rows and detail tiles all grow by
        // the same proportion, up to a third; only then is any remainder shared between the gaps.
        val base = plan.sumOf { (m, rows) -> when (m) { Module.HOURS -> 58.0; Module.WEEK -> rows * 26.0; Module.DETAILS -> rows * 46.0 } }.toFloat()
        val f = (1f + room / base).coerceIn(1f, 1.35f)
        room -= base * (f - 1f)
        val hourH = 58f * f; val weekH = 26f * f; val tileH = 46f * f
        val extra = (room / plan.size).coerceIn(0f, 40f)
        val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = s.hairline }
        var y = top0
        for ((i, step) in plan.withIndex()) {
            val (m, rows) = step
            y += extra / 2f
            if (i > 0 || divideFirst) { rule.color = s.faint((left + right) / 2, y); cv.drawLine(left, y, right, y, rule) }
            y += 10f
            when (m) {
                Module.HOURS -> { hours(c, cv, s, fc, left, y, right, y + hourH); y += hourH }
                Module.WEEK -> { week(c, cv, s, fc, left, y, right, rows, weekH); y += rows * weekH }
                Module.DETAILS -> {
                    val colW = w / cols
                    val labelP = c.label(s.muted(left + w / 2, y), 1f, 10.5f)
                    val valueP = c.ink(15f, s.ink(left + w / 2, y), 600)
                    for ((k, t) in tiles.take(rows * cols).withIndex()) {
                        val x = left + colW * (k % cols); val ty = y + tileH * (k / cols)
                        labelP.color = s.muted(x + colW / 2, ty); valueP.color = s.ink(x + colW / 2, ty + 20f)
                        cv.textTop(fit(t.second.first.uppercase(), labelP, colW - 8f), x, ty, labelP)
                        cv.textTop(fit(t.second.second, valueP, colW - 8f), x, ty + 16f, valueP)
                    }
                    y += rows * tileH - 6f
                }
            }
            y += 4f + extra / 2f
        }
    }

    // ---- Modules -------------------------------------------------------------------------------

    /** The next hours as columns between [left] and [right]: time, sky, temperature, chance of rain. */
    private fun hours(c: Context, cv: Canvas, s: Surface, fc: Forecast, left: Float, top: Float, right: Float, bottom: Float) {
        val n = ((right - left) / 46f).toInt().coerceIn(1, minOf(8, fc.hours.size))
        val colW = (right - left) / n
        val h = bottom - top
        val twelve = !android.text.format.DateFormat.is24HourFormat(c)
        for (i in 0 until n) {
            val hr = fc.hours[i]
            val cx = left + colW * (i + 0.5f)
            val label = if (twelve) "${if (hr.hour % 12 == 0) 12 else hr.hour % 12}${if (hr.hour < 12) "am" else "pm"}" else hr.label
            val labelP = c.ink(11f, s.muted(cx, top + 6f), 500).apply { textAlign = Paint.Align.CENTER }
            cv.textTop(label, cx, top, labelP)
            val gy = top + h * 0.45f
            Glyphs.sky(cv, Weather.describe(hr.code).second, hr.day, cx, gy, minOf(20f, h * 0.34f), s.ink(cx, gy), s.accent(cx, gy))
            val tP = c.ink(13f, s.ink(cx, top + h * 0.8f), 600).apply { textAlign = Paint.Align.CENTER }
            val rain = hr.rain?.takeIf { it >= 10 }
            val tBase = if (rain != null && h >= 64f) top + h * 0.82f else top + h
            cv.drawText(Weather.deg(hr.temp), cx + 2f, tBase, tP)
            if (rain != null && h >= 64f) {
                val rP = c.ink(10.5f, s.accent(cx, top + h), 500).apply { textAlign = Paint.Align.CENTER }
                cv.drawText("$rain%", cx, top + h, rP)
            }
        }
    }

    /** The week as rows: day, sky, chance of rain, the low, the day's range against the week's, the high. */
    private fun week(c: Context, cv: Canvas, s: Surface, fc: Forecast, left: Float, top: Float, right: Float, rows: Int, rowH: Float = 26f) {
        val days = fc.days.drop(1).take(rows)
        if (days.isEmpty()) return
        val weekMin = days.minOf { it.min }; val weekMax = days.maxOf { it.max }
        val w = right - left
        val dayP = c.ink(13.5f, s.ink(left + 20f, top), 500)
        val lowP = c.ink(13f, s.muted(right - 60f, top), 500).apply { textAlign = Paint.Align.RIGHT }
        val highP = c.ink(13f, s.ink(right - 10f, top), 600).apply { textAlign = Paint.Align.RIGHT }
        val rainP = c.ink(11f, s.accent(left + 80f, top), 500)
        val withRain = w >= 220f
        val withBar = w >= 270f
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val dayName = java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault())
        for ((i, d) in days.withIndex()) {
            val mid = top + rowH * i + rowH * 0.42f
            val name = runCatching { dayName.format(fmt.parse(d.date)!!) }.getOrDefault(d.date.takeLast(2))
            dayP.color = s.ink(left + 20f, mid)
            cv.drawText(name, left, mid + capHeight(dayP) / 2, dayP)
            val gx = left + 52f
            Glyphs.sky(cv, Weather.describe(d.code).second, true, gx, mid, 18f, s.ink(gx, mid), s.accent(gx, mid))
            if (withRain) d.rain?.takeIf { it >= 10 }?.let { cv.drawText("$it%", gx + 18f, mid + capHeight(rainP) / 2, rainP) }
            highP.color = s.ink(right - 10f, mid); lowP.color = s.muted(right - 60f, mid)
            cv.drawText(Weather.deg(d.max), right, mid + capHeight(highP) / 2, highP)
            if (withBar) {
                // The day's range as a short line on the week's scale: information, not ornament
                val barL = left + w * 0.52f; val barR = right - 40f
                val span = (weekMax - weekMin).coerceAtLeast(1.0)
                val a = barL + ((d.min - weekMin) / span * (barR - barL)).toFloat()
                val b = barL + ((d.max - weekMin) / span * (barR - barL)).toFloat()
                cv.drawText(Weather.deg(d.min), barL - 8f, mid + capHeight(lowP) / 2, lowP)
                val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 4f; strokeCap = Paint.Cap.ROUND; color = s.faint(barL, mid) }
                cv.drawLine(barL, mid, barR, mid, track)
                track.color = s.accent(barL, mid)
                cv.drawLine(a, mid, maxOf(b, a + 1f), mid, track)
            } else cv.drawText(Weather.deg(d.min), right - 40f, mid + capHeight(lowP) / 2, lowP)
        }
    }

    /** The week as columns, for the short wide widget when the hours are switched off. */
    private fun weekColumns(c: Context, cv: Canvas, s: Surface, fc: Forecast, left: Float, top: Float, right: Float, bottom: Float) {
        val days = fc.days.drop(1)
        val n = ((right - left) / 52f).toInt().coerceIn(1, minOf(7, days.size))
        val colW = (right - left) / n
        val h = bottom - top
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val dayName = java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault())
        for (i in 0 until n) {
            val d = days[i]; val cx = left + colW * (i + 0.5f)
            val lp = c.ink(11f, s.muted(cx, top), 500).apply { textAlign = Paint.Align.CENTER }
            cv.textTop(runCatching { dayName.format(fmt.parse(d.date)!!) }.getOrDefault(""), cx, top, lp)
            Glyphs.sky(cv, Weather.describe(d.code).second, true, cx, top + h * 0.45f, minOf(20f, h * 0.34f), s.ink(cx, top + h / 2), s.accent(cx, top + h / 2))
            val tp = c.ink(12.5f, s.ink(cx, top + h), 600).apply { textAlign = Paint.Align.CENTER }
            cv.drawText("${Weather.deg(d.max)} ${Weather.deg(d.min)}", cx, top + h, tp)
        }
    }

    /** A detail's label and value, or null if the forecast does not have it. */
    fun value(c: Context, fc: Forecast, item: WeatherOptions.Item): Pair<String, String>? = when (item) {
        WeatherOptions.Item.FEELS -> fc.feels?.let { "Feels like" to Weather.deg(it) }
        WeatherOptions.Item.HUMIDITY -> fc.humidity?.let { "Humidity" to "$it%" }
        WeatherOptions.Item.WIND -> fc.wind?.let { "Wind" to ((fc.windDir?.let { d -> compass(d) + " " } ?: "") + "${Math.round(it)} mph") }
        WeatherOptions.Item.UV -> fc.uv?.let { "UV index" to "${uvLevel(it)} · ${Math.round(it)}" }
        WeatherOptions.Item.SUN -> sun(c, fc)
        WeatherOptions.Item.AIR -> fc.aqi?.let { "Air quality" to "${aqiLevel(it)} · $it" }
        WeatherOptions.Item.POLLEN -> fc.pollen?.let { "Pollen" to "${pollenLevel(it.second)} · ${it.first}" }
        WeatherOptions.Item.PRESSURE -> fc.pressure?.let { "Pressure" to "${Math.round(it)} mb" }
        WeatherOptions.Item.VISIBILITY -> fc.visibility?.let { "Visibility" to (if (it >= 10_000) "${Math.round(it / 1000)} km" else "%.1f km".format(it / 1000)) }
        WeatherOptions.Item.DEW -> fc.dew?.let { "Dew point" to Weather.deg(it) }
        else -> null
    }

    private fun compass(deg: Int): String {
        val points = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        return points[(((deg % 360) + 360) % 360 * 16 + 180) / 360 % 16]
    }

    /** WHO's UV bands. */
    private fun uvLevel(uv: Double) = when { uv < 3 -> "Low"; uv < 6 -> "Moderate"; uv < 8 -> "High"; uv < 11 -> "Very high"; else -> "Extreme" }

    /** The European Air Quality Index's bands. */
    private fun aqiLevel(a: Int) = when { a <= 20 -> "Good"; a <= 40 -> "Fair"; a <= 60 -> "Moderate"; a <= 80 -> "Poor"; a <= 100 -> "Very poor"; else -> "Extremely poor" }

    /** Grains per cubic metre, in rough bands; an approximation, not an official scale. */
    private fun pollenLevel(g: Double) = when { g < 1 -> "None"; g < 10 -> "Low"; g < 50 -> "Moderate"; g < 150 -> "High"; else -> "Very high" }

    /** Whichever comes next, sunset today or sunrise tomorrow, in the phone's clock. */
    private fun sun(c: Context, fc: Forecast): Pair<String, String>? {
        val nowHm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date())
        fun hm(iso: String) = iso.substring(11, 16)
        fun show(hm: String): String {
            val h = hm.take(2).toInt(); val m = hm.takeLast(2)
            return if (android.text.format.DateFormat.is24HourFormat(c)) hm else "${if (h % 12 == 0) 12 else h % 12}:$m ${if (h < 12) "am" else "pm"}"
        }
        val rise = fc.sunrise?.let { hm(it) }; val set = fc.sunset?.let { hm(it) }
        return when {
            rise != null && nowHm < rise -> "Sunrise" to show(rise)
            set != null && nowHm < set -> "Sunset" to show(set)
            fc.nextSunrise != null -> "Sunrise" to show(hm(fc.nextSunrise))
            else -> null
        }
    }
}
