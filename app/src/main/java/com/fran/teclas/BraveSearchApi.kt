package com.fran.teclas

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Rich Data Enrichments via the Brave Search API — instant-answer cards ONLY (weather, stocks,
 * crypto, currency, calculator, definitions, conversions, plus local POI lookups). Brave is
 * deliberately NOT used for plain web result rows: those come from the Google Custom Search
 * fallback when no rich representation exists for a query.
 *
 * Connect: create a key at api-dashboard.search.brave.com (Search plan). The recurring $5/month
 * credit covers ~1,000 requests — keep the Brave/provider attribution visible on cards to
 * retain it. A rich answer costs TWO requests (search + callback), so callers gate rich lookups
 * on query intent instead of firing per keystroke.
 *
 * Rich flow per Brave's API: a web search with enable_rich_callback=1 returns
 * rich.hint.callback_key when the query has rich intent, then /res/v1/web/rich resolves it into
 * the typed payload. When there is no rich hint but the response carries a `locations` block
 * ("nearest best buy"), the top POI becomes a card that opens Google Maps directly.
 * Brave doesn't publish per-vertical schemas, so parsing is tolerant: known field names first,
 * generic title/description scan as fallback. Blocking; call off main thread.
 */
object BraveSearchApi {
    const val KEY_PREF = "brave_search_key"

    /** One day in the weather card's forecast strip. Temps are short-form ("82°"). */
    data class DayForecast(val day: String, val glyph: String, val hi: String, val lo: String)

    /** One instant answer, structured for a widget-style card in search results.
     *  [provider] must stay visible somewhere on the card — Brave's data partners require credit. */
    data class RichAnswer(
        val vertical: String,
        val headline: String,        // the answer itself, rendered big: "64,061 USD" / "61° Clear sky"
        val label: String,           // card header: "Bitcoin · BTC" / "Weather · Bogotá"
        val detail: String,          // secondary line: "H:82° L:57°" / "Rate 0.874" / definition text
        val delta: String?,          // change chip: "▲2.48% · 24h"; null when not applicable
        val deltaUp: Boolean,        // colors the chip and sparkline (gain vs loss)
        val provider: String,        // attribution: "CoinGecko", "OpenWeatherMap", …
        val spark: List<Float>,      // timeseries for the sparkline; empty = no chart
        val glyph: String = "✦",     // header icon: condition emoji for weather, ✦ otherwise
        val forecast: List<DayForecast> = emptyList(),  // weather only: next-days strip
        val url: String? = null      // tap destination override (Maps for local POIs)
    )

    fun isConfigured(prefs: SharedPreferences): Boolean =
        !prefs.getString(KEY_PREF, null).isNullOrBlank()

    // Bare terms that deserve a rich card without qualifier words: callers rewrite "bitcoin" →
    // "bitcoin price" / "tsla" → "tsla stock" so Brave's intent detector reliably hints.
    // Conservative on purpose — common English words as tickers ("coin", "dis") stay out.
    val CRYPTO_TERMS = setOf(
        "bitcoin", "btc", "ethereum", "eth", "solana", "dogecoin", "doge", "xrp", "ripple",
        "litecoin", "ltc", "bnb", "cardano", "avax", "avalanche", "monero", "xmr", "tron",
        "shiba", "pepe", "tether", "usdt"
    )
    val STOCK_TERMS = setOf(
        "aapl", "tsla", "nvda", "msft", "googl", "goog", "amzn", "meta", "nflx", "amd", "intc",
        "spy", "qqq", "uber", "abnb", "pltr", "gme", "amc", "hood", "sofi", "jpm", "baba",
        "orcl", "crm", "avgo", "smci", "mstr", "nke",
        "apple", "tesla", "nvidia", "microsoft", "google", "alphabet", "amazon", "netflix",
        "intel", "oracle", "palantir", "gamestop", "robinhood", "broadcom", "alibaba",
        "coinbase", "disney", "nike"
    )

    // "Main tourist locations" — a curated set so landmark cards fire on intent, not on every
    // two-word query. Matched as a substring of the (lowercased) query, so "the eiffel tower"
    // and "eiffel tower paris" both hit. Brave's infobox confirms it's actually a place.
    val LANDMARK_TERMS = setOf(
        "eiffel tower", "louvre", "arc de triomphe", "notre dame", "sacre coeur", "versailles",
        "big ben", "tower of london", "buckingham palace", "london eye", "stonehenge",
        "colosseum", "trevi fountain", "vatican", "st peter", "sistine chapel", "pantheon",
        "leaning tower", "pisa", "duomo", "st mark", "rialto",
        "sagrada familia", "park guell", "alhambra", "prado",
        "brandenburg gate", "reichstag", "neuschwanstein",
        "acropolis", "parthenon", "santorini",
        "statue of liberty", "empire state", "times square", "central park", "brooklyn bridge",
        "golden gate", "alcatraz", "hollywood sign", "space needle", "willis tower",
        "grand canyon", "niagara falls", "mount rushmore", "yellowstone",
        "christ the redeemer", "machu picchu", "chichen itza", "teotihuacan",
        "cn tower", "banff",
        "burj khalifa", "burj al arab", "petra", "pyramids", "giza", "sphinx",
        "taj mahal", "gateway of india", "red fort", "angkor wat", "grand palace",
        "marina bay sands", "merlion", "petronas towers", "great wall", "forbidden city",
        "tokyo tower", "senso-ji", "fushimi inari", "mount fuji", "shibuya crossing",
        "sydney opera house", "harbour bridge", "uluru",
        "table mountain", "victoria falls",
        "santa monica pier", "walt disney world", "disneyland", "universal studios"
    )

    fun matchesLandmark(lowerQuery: String): Boolean =
        LANDMARK_TERMS.any { lowerQuery.contains(it) }

    fun fetchRich(query: String, apiKey: String, lat: Double? = null, long: Double? = null): RichAnswer? {
        if (query.isBlank() || apiKey.isBlank()) return null
        // Location headers make Brave return the `locations` POI block ("best buy" → the actual
        // nearest store) — verified live: absent without them.
        val locHeaders = if (lat != null && long != null)
            mapOf("X-Loc-Lat" to lat.toString(), "X-Loc-Long" to long.toString()) else emptyMap()
        val search = get(
            "https://api.search.brave.com/res/v1/web/search?q=${URLEncoder.encode(query, "UTF-8")}" +
                "&enable_rich_callback=1&count=1",
            apiKey, locHeaders
        ) ?: return null
        val root = runCatching { JSONObject(search) }.getOrNull() ?: return null
        val hint = root.optJSONObject("rich")?.optJSONObject("hint")
        val callbackKey = hint?.optString("callback_key")?.trim().orEmpty()
        if (callbackKey.isBlank()) {
            // No rich vertical — a named landmark ("eiffel tower") is in the infobox; a "nearest X"
            // POI is in `locations`. Landmark wins: it's the "learn about it" answer.
            return parseInfoboxLandmark(root) ?: parseLocations(root)
        }
        val vertical = hint?.optString("vertical")?.trim().orEmpty()

        val rich = get(
            "https://api.search.brave.com/res/v1/web/rich?callback_key=${URLEncoder.encode(callbackKey, "UTF-8")}",
            apiKey
        ) ?: return parseInfoboxLandmark(root) ?: parseLocations(root)
        val results = runCatching { JSONObject(rich).optJSONArray("results") }.getOrNull()
            ?: return parseInfoboxLandmark(root) ?: parseLocations(root)
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            parse(item, vertical)?.let { return it }
        }
        return parseInfoboxLandmark(root) ?: parseLocations(root)
    }

    /** A place-category infobox ("eiffel tower", "colosseum") → a landmark card: name, a short
     *  encyclopedic blurb, tap-through to Wikipedia. The caller pairs it with a ride offer when
     *  the user is actually in that city. */
    private fun parseInfoboxLandmark(root: JSONObject): RichAnswer? {
        val ib = root.optJSONObject("infobox")?.optJSONArray("results")?.optJSONObject(0) ?: return null
        if (ib.optString("category").lowercase() !in setOf("place", "location", "landmark", "tourist attraction"))
            return null
        val name = ib.optString("title").trim().ifBlank { return null }
        val desc = ib.optString("description").trim()
        val long = stripHtml(ib.optString("long_desc")).trim()
        val body = when {
            long.length > desc.length -> long
            desc.isNotBlank() -> desc
            else -> return null
        }
        return RichAnswer(
            vertical = "landmark",
            headline = name,
            label = listOf("Landmark", desc.substringAfterLast(" in ", "").trim().trimEnd('.'))
                .filter { it.isNotBlank() }.joinToString(" · "),
            detail = body.take(220),
            delta = null, deltaUp = true,
            provider = "Wikipedia",
            spark = emptyList(),
            glyph = "🏛️",
            url = ib.optString("url").trim().ifBlank { null }
        )
    }

    private fun stripHtml(s: String): String =
        s.replace(Regex("<[^>]*>"), "").replace("&amp;", "&").replace("&quot;", "\"")
            .replace("&#39;", "'").replace(Regex("^\\)\\s*"), "").trim()

    /** Top POI from the web response's `locations` block → a card that opens Google Maps
     *  directly. No search-results page in between. */
    private fun parseLocations(root: JSONObject): RichAnswer? {
        val items = root.optJSONObject("locations")?.optJSONArray("results") ?: return null
        val top = items.optJSONObject(0) ?: return null
        val name = top.optString("title").trim().ifBlank { return null }
        val address = top.optJSONObject("postal_address")?.let { pa ->
            pa.optString("displayAddress").trim().ifBlank {
                listOf(pa.optString("streetAddress"), pa.optString("addressLocality"))
                    .map { it.trim() }.filter { it.isNotBlank() }.joinToString(", ")
            }
        }.orEmpty()
        val more = (items.length() - 1).takeIf { it > 0 }?.let { "  ·  +$it more on Maps" }.orEmpty()
        return RichAnswer(
            vertical = "local",
            headline = name,
            label = "Nearby",
            detail = "$address$more".trim(),
            delta = null, deltaUp = true,
            provider = "Brave Search",
            spark = emptyList(),
            glyph = "📍",
            url = "https://www.google.com/maps/search/?api=1&query=" +
                URLEncoder.encode(listOf(name, address).filter { it.isNotBlank() }.joinToString(" "), "UTF-8")
        )
    }

    // Field paths verified against live responses (July 2026): each result carries
    // {subtype, provider: {name}, <payload key named after the vertical>: {...}}.
    private fun parse(item: JSONObject, hintVertical: String): RichAnswer? {
        val subtype = item.optString("subtype").trim().ifBlank { hintVertical }.ifBlank { "answer" }
        val provider = item.optJSONObject("provider")?.optString("name")?.trim().orEmpty()
        return when (subtype) {
            "weather" -> {
                val w = item.optJSONObject("weather") ?: return null
                val current = w.optJSONObject("current_weather") ?: return null
                val temp = current.optString("temp").toDoubleOrNull() ?: return null
                val condition = current.optJSONObject("weather")
                val sky = condition?.optString("description").orEmpty()
                    .replaceFirstChar { it.uppercase() }
                val today = w.optJSONArray("daily")?.optJSONObject(0)?.optJSONObject("temperature")
                val hiLo = today?.let { t ->
                    val hi = t.optString("max").toDoubleOrNull()
                    val lo = t.optString("min").toDoubleOrNull()
                    if (hi != null && lo != null) "H:${localTemp(hi)}  L:${localTemp(lo)}" else null
                }
                val feels = current.optString("feels_like").toDoubleOrNull()?.let { "Feels ${localTemp(it)}" }
                val humidity = current.optString("humidity").takeIf { it.isNotBlank() }?.let { "Humidity $it%" }
                val loc = w.optJSONObject("location")
                val place = listOfNotNull(
                    loc?.optString("name")?.takeIf { it.isNotBlank() },
                    loc?.optString("state")?.takeIf { it.isNotBlank() }
                        ?: loc?.optString("country")?.takeIf { it.isNotBlank() }
                ).joinToString(", ")
                // Next ~24h temperature curve from the 3-hourly forecast.
                val curve = w.optJSONArray("hours3")?.let { hours ->
                    (0 until minOf(hours.length(), 9)).mapNotNull {
                        hours.optJSONObject(it)?.optJSONObject("temperature")?.optString("temp")?.toFloatOrNull()
                    }
                }.orEmpty()
                // Forecast strip: today + next days, each with day label, condition glyph, hi/lo.
                val days = w.optJSONArray("daily")?.let { daily ->
                    (0 until minOf(daily.length(), 5)).mapNotNull { i ->
                        val d = daily.optJSONObject(i) ?: return@mapNotNull null
                        val t = d.optJSONObject("temperature") ?: return@mapNotNull null
                        val hi = t.optString("max").toDoubleOrNull() ?: return@mapNotNull null
                        val lo = t.optString("min").toDoubleOrNull() ?: return@mapNotNull null
                        DayForecast(
                            day = if (i == 0) "NOW" else d.optString("date_i18n").take(3).uppercase(java.util.Locale.US),
                            glyph = weatherGlyph(d.optJSONObject("weather")?.optString("main").orEmpty()),
                            hi = shortTemp(hi), lo = shortTemp(lo)
                        )
                    }
                }.orEmpty()
                RichAnswer(subtype, "${localTemp(temp)} $sky",
                    listOfNotNull("Weather", place.takeIf { it.isNotBlank() }).joinToString(" · "),
                    listOfNotNull(hiLo, feels, humidity).joinToString("  ·  "),
                    null, true, provider, curve,
                    glyph = weatherGlyph(condition?.optString("main").orEmpty()), forecast = days)
            }
            "stocks", "stock" -> {
                val s = item.optJSONObject("stock") ?: return null
                val quote = s.optJSONObject("quote") ?: return null
                val price = quote.optString("latest_price").toDoubleOrNull() ?: return null
                val symbol = quote.optString("symbol").ifBlank { return null }
                val changePct = quote.optString("change_percent").toDoubleOrNull()
                val range = s.optString("time_range").ifBlank { "1d" }
                val spark = s.optJSONObject("timeseries")?.optJSONArray("timeseries")?.let { ts ->
                    (0 until ts.length()).mapNotNull { ts.optJSONObject(it)?.optString("close")?.toFloatOrNull() }
                }.orEmpty()
                RichAnswer(subtype, "${num(price)} ${quote.optString("currency").ifBlank { "USD" }}",
                    listOfNotNull(quote.optString("company_name").takeIf { it.isNotBlank() } ?: symbol,
                        symbol.takeIf { quote.optString("company_name").isNotBlank() }).joinToString(" · "),
                    listOfNotNull(quote.optString("primary_exchange").takeIf { it.isNotBlank() },
                        quote.optString("week_52_low").toDoubleOrNull()?.let { lo ->
                            quote.optString("week_52_high").toDoubleOrNull()?.let { hi -> "52w ${num(lo)}–${num(hi)}" }
                        }).joinToString("  ·  "),
                    changePct?.let { "${if (it >= 0) "▲" else "▼"}${num(kotlin.math.abs(it))}% · $range" },
                    (changePct ?: 0.0) >= 0, provider, sample(spark))
            }
            "cryptocurrency" -> {
                val c = item.optJSONObject("cryptocurrency") ?: return null
                val quote = c.optJSONObject("quote") ?: return null
                val price = quote.optString("current_price").toDoubleOrNull() ?: return null
                val unit = c.optString("vs_currency").uppercase(java.util.Locale.US).ifBlank { "USD" }
                val changePct = quote.optString("price_change_percentage_24h").toDoubleOrNull()
                val spark = c.optJSONObject("timeseries")?.optJSONArray("ts_price")?.let { ts ->
                    (0 until ts.length()).mapNotNull { ts.optJSONArray(it)?.optString(1)?.toFloatOrNull() }
                }.orEmpty()
                RichAnswer(subtype, "${num(price)} $unit",
                    listOfNotNull(quote.optString("name").takeIf { it.isNotBlank() },
                        quote.optString("symbol").uppercase(java.util.Locale.US).takeIf { it.isNotBlank() })
                        .joinToString(" · "),
                    listOfNotNull(quote.optString("high_24h").toDoubleOrNull()?.let { hi ->
                        quote.optString("low_24h").toDoubleOrNull()?.let { lo -> "24h ${num(lo)}–${num(hi)}" }
                    }, quote.optString("market_cap_rank").takeIf { it.isNotBlank() }?.let { "Rank #$it" })
                        .joinToString("  ·  "),
                    changePct?.let { "${if (it >= 0) "▲" else "▼"}${num(kotlin.math.abs(it))}% · 24h" },
                    (changePct ?: 0.0) >= 0, provider, sample(spark))
            }
            "currency" -> {
                val cur = item.optJSONObject("currency") ?: return null
                val conv = cur.optJSONObject("conversion") ?: return null
                val result = conv.optString("result").toDoubleOrNull() ?: return null
                val query = conv.optJSONObject("query")
                val from = query?.optJSONObject("from_currency")?.optString("code").orEmpty()
                val to = query?.optJSONObject("to_currency")?.optString("code").orEmpty()
                val amount = conv.optString("amount").toDoubleOrNull()
                val spark = cur.optJSONObject("timeseries")?.optJSONArray("ts_exchange_rate")?.let { ts ->
                    (0 until ts.length()).mapNotNull { ts.optJSONArray(it)?.optString(1)?.toFloatOrNull() }
                }.orEmpty()
                RichAnswer(subtype, "${num(result)} $to",
                    "${amount?.let { num(it) } ?: ""} $from → $to".trim(),
                    conv.optJSONObject("info")?.optString("rate")?.toDoubleOrNull()
                        ?.let { "Rate ${num(it, 4)}" }.orEmpty(),
                    null, (spark.size < 2 || spark.last() >= spark.first()), provider, sample(spark))
            }
            "definitions" -> {
                val d = item.optJSONObject("definitions") ?: return null
                val word = d.optString("word").ifBlank { return null }
                val group = d.optJSONArray("definitions")?.optJSONObject(0)
                val text = group?.optJSONArray("definitions")?.opt(0)?.let { first ->
                    when (first) {
                        is String -> first
                        is JSONObject -> find(first, "definition", "text", "meaning")
                        else -> null
                    }
                } ?: return null
                RichAnswer(subtype, word,
                    listOfNotNull("Definition", group?.optString("part_of_speech")?.takeIf { it.isNotBlank() })
                        .joinToString(" · "),
                    text, null, true, provider, emptyList())
            }
            "calculator", "unit_conversion", "unix_timestamp" -> {
                val payload = item.optJSONObject(subtype) ?: item
                val result = find(payload, "answer", "result", "value", "output") ?: return null
                val expression = find(payload, "expression", "input", "query")
                RichAnswer(subtype, num(result.toDoubleOrNull()) ?: result,
                    if (subtype == "calculator") "Calculator" else "Conversion",
                    expression?.let { "$it =" }.orEmpty(), null, true, provider, emptyList())
            }
            else -> {
                // Sports and anything Brave adds later: surface whatever headline the payload has.
                val title = find(item, "title", "answer", "result", "name", "text") ?: return null
                RichAnswer(subtype, title, subtype.replaceFirstChar { it.uppercase() },
                    find(item, "description", "subtitle", "summary", "status").orEmpty(),
                    null, true, provider, emptyList())
            }
        }
    }

    /** Downsample a timeseries to ≤48 points — plenty for a card-width sparkline. */
    private fun sample(points: List<Float>, max: Int = 48): List<Float> {
        if (points.size <= max) return points
        val step = points.size.toFloat() / max
        return (0 until max).map { points[(it * step).toInt().coerceAtMost(points.size - 1)] }
    }

    private fun num(value: Double?, decimals: Int = 2): String? = value?.let {
        val s = String.format(java.util.Locale.US, "%,.${decimals}f", it)
        s.trimEnd('0').trimEnd('.').ifBlank { "0" }
    }

    /** Brave's weather payload is metric; show °F in Fahrenheit locales (US &c.), °C elsewhere. */
    private fun localTemp(celsius: Double): String {
        val country = java.util.Locale.getDefault().country
        return if (country in setOf("US", "BS", "BZ", "KY", "PW", "LR", "MM"))
            "${(celsius * 9 / 5 + 32).toInt()}°F" else "${celsius.toInt()}°C"
    }

    /** Unit-less variant for the tight forecast cells: "82°". */
    private fun shortTemp(celsius: Double): String = localTemp(celsius).dropLast(1)

    /** OpenWeatherMap condition group → glyph for the card header and forecast strip. */
    private fun weatherGlyph(main: String): String = when (main.lowercase(java.util.Locale.US)) {
        "clear" -> "☀️"
        "clouds" -> "☁️"
        "rain" -> "🌧️"
        "drizzle" -> "🌦️"
        "thunderstorm" -> "⛈️"
        "snow" -> "❄️"
        "" -> "✦"
        else -> "🌫️"   // mist, fog, haze, smoke, dust…
    }

    /** Depth-limited search for the first non-blank scalar under any of [keys] — the payload
     *  schemas are undocumented, so values may sit at the top level or one object down. */
    private fun find(obj: JSONObject, vararg keys: String, depth: Int = 0): String? {
        for (key in keys) {
            val value = obj.opt(key)
            when (value) {
                is String -> if (value.isNotBlank()) return value.trim()
                is Number, is Boolean -> return value.toString()
                is JSONObject -> find(value, "value", "text", "label", "name")?.let { return it }
                else -> {}
            }
        }
        if (depth >= 2) return null
        val names = obj.keys()
        while (names.hasNext()) {
            val child = obj.opt(names.next())
            val nested = when (child) {
                is JSONObject -> find(child, *keys, depth = depth + 1)
                is JSONArray -> child.optJSONObject(0)?.let { find(it, *keys, depth = depth + 1) }
                else -> null
            }
            if (nested != null) return nested
        }
        return null
    }

    private fun get(url: String, apiKey: String, headers: Map<String, String> = emptyMap()): String? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000; readTimeout = 8_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("x-subscription-token", apiKey)
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    } catch (_: Exception) { null }
}
