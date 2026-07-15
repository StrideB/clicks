package com.fran.teclas

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Crypto prices via CoinGecko's public API — free, no key, no signup, so the card works on a
 * fresh install. Replaces the Brave rich lookup for this vertical, which cost two metered
 * requests (and a key) for data a free endpoint serves directly.
 *
 * CoinGecko's free tier is rate-limited (~10-30 req/min), which is fine here: the lookup is
 * debounced and only fires on an actual coin term. Blocking; call off the main thread.
 */
object CryptoApi {

    // Query term → CoinGecko coin id. Kept as a static map so resolution is instant and we never
    // pay a /coins/list round-trip just to turn "btc" into "bitcoin".
    private val IDS = mapOf(
        "bitcoin" to "bitcoin", "btc" to "bitcoin",
        "ethereum" to "ethereum", "eth" to "ethereum",
        "solana" to "solana", "sol" to "solana",
        "dogecoin" to "dogecoin", "doge" to "dogecoin",
        "xrp" to "ripple", "ripple" to "ripple",
        "litecoin" to "litecoin", "ltc" to "litecoin",
        "bnb" to "binancecoin",
        "cardano" to "cardano", "ada" to "cardano",
        "avalanche" to "avalanche-2", "avax" to "avalanche-2",
        "monero" to "monero", "xmr" to "monero",
        "tron" to "tron", "trx" to "tron",
        "shiba" to "shiba-inu", "pepe" to "pepe",
        "tether" to "tether", "usdt" to "tether",
        "polkadot" to "polkadot", "dot" to "polkadot",
        "chainlink" to "chainlink", "link" to "chainlink"
    )

    fun idFor(term: String): String? = IDS[term.trim().lowercase(java.util.Locale.US)]

    /** Coin terms — shielded from autocorrect so "btc"/"eth" survive being typed. */
    val TERMS: Set<String> get() = IDS.keys

    /** Price card for [term] ("btc", "bitcoin"), or null. */
    fun card(term: String): BraveSearchApi.RichAnswer? {
        val id = idFor(term) ?: return null
        val body = get("https://api.coingecko.com/api/v3/coins/$id" +
            "?localization=false&tickers=false&community_data=false&developer_data=false&sparkline=false")
            ?: return null
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val market = root.optJSONObject("market_data") ?: return null
        val price = market.optJSONObject("current_price")?.optDouble("usd", Double.NaN)
            ?.takeIf { !it.isNaN() } ?: return null
        val change = market.optDouble("price_change_percentage_24h", Double.NaN).takeIf { !it.isNaN() }
        val hi = market.optJSONObject("high_24h")?.optDouble("usd", Double.NaN)?.takeIf { !it.isNaN() }
        val lo = market.optJSONObject("low_24h")?.optDouble("usd", Double.NaN)?.takeIf { !it.isNaN() }
        val rank = root.optInt("market_cap_rank", 0).takeIf { it > 0 }
        val name = root.optString("name").ifBlank { id }
        val symbol = root.optString("symbol").uppercase(java.util.Locale.US)

        // 24h curve for the sparkline (second call — still free, still keyless).
        val spark = get("https://api.coingecko.com/api/v3/coins/$id/market_chart?vs_currency=usd&days=1")
            ?.let { chart ->
                runCatching { JSONObject(chart).optJSONArray("prices") }.getOrNull()?.let { arr ->
                    sample((0 until arr.length()).mapNotNull { i ->
                        (arr.opt(i) as? JSONArray)?.optDouble(1)?.takeIf { !it.isNaN() }?.toFloat()
                    })
                }
            }.orEmpty()

        return BraveSearchApi.RichAnswer(
            vertical = "cryptocurrency",
            headline = "${num(price)} USD",
            label = listOf(name, symbol).filter { it.isNotBlank() }.joinToString(" · "),
            detail = listOfNotNull(
                if (hi != null && lo != null) "24h ${num(lo)}–${num(hi)}" else null,
                rank?.let { "Rank #$it" }
            ).joinToString("  ·  "),
            delta = change?.let { "${if (it >= 0) "▲" else "▼"}${num(kotlin.math.abs(it))}% · 24h" },
            deltaUp = (change ?: 0.0) >= 0,
            provider = "CoinGecko",
            spark = spark,
            glyph = "₿"
        )
    }

    private fun sample(points: List<Float>, max: Int = 48): List<Float> {
        if (points.size <= max) return points
        val step = points.size.toFloat() / max
        return (0 until max).map { points[(it * step).toInt().coerceAtMost(points.size - 1)] }
    }

    private fun num(v: Double, decimals: Int = 2): String =
        String.format(java.util.Locale.US, "%,.${decimals}f", v).trimEnd('0').trimEnd('.').ifBlank { "0" }

    private fun get(url: String): String? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 5_000; readTimeout = 8_000
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    } catch (_: Exception) { null }
}
