package com.fran.teclas

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Currency conversion via Frankfurter (ECB reference rates) — free, no key, no signup. Replaces
 * the Brave rich lookup for this vertical: same answer, no metered request, works on a fresh
 * install. Blocking; call off the main thread.
 */
object CurrencyApi {

    // ISO codes we accept, plus the common symbols/names people actually type.
    private val ALIASES = mapOf(
        "dollar" to "USD", "dollars" to "USD", "usd" to "USD", "$" to "USD", "bucks" to "USD",
        "euro" to "EUR", "euros" to "EUR", "eur" to "EUR", "€" to "EUR",
        "pound" to "GBP", "pounds" to "GBP", "gbp" to "GBP", "£" to "GBP", "sterling" to "GBP",
        "yen" to "JPY", "jpy" to "JPY", "¥" to "JPY",
        "peso" to "MXN", "pesos" to "MXN", "mxn" to "MXN",
        "cop" to "COP", "real" to "BRL", "reais" to "BRL", "brl" to "BRL",
        "franc" to "CHF", "chf" to "CHF", "yuan" to "CNY", "cny" to "CNY", "rmb" to "CNY",
        "rupee" to "INR", "rupees" to "INR", "inr" to "INR",
        "cad" to "CAD", "aud" to "AUD", "nzd" to "NZD", "sek" to "SEK", "nok" to "NOK",
        "dkk" to "DKK", "pln" to "PLN", "czk" to "CZK", "try" to "TRY", "zar" to "ZAR",
        "krw" to "KRW", "won" to "KRW", "hkd" to "HKD", "sgd" to "SGD", "ars" to "ARS"
    )

    /** Every term that names a currency — used to shield them from autocorrect ("usd" → "use"). */
    val TERMS: Set<String> get() = ALIASES.keys

    data class Parsed(val amount: Double, val from: String, val to: String)

    /** "100 usd to eur", "50 euros in dollars", "usd to cop" → a conversion request, or null. */
    fun parse(rawQuery: String): Parsed? {
        val q = rawQuery.trim().lowercase(Locale.US)
        if (!q.contains(" to ") && !q.contains(" in ")) return null
        val sep = if (q.contains(" to ")) " to " else " in "
        val left = q.substringBefore(sep).trim()
        val right = q.substringAfter(sep).trim()
        val to = code(right.split(' ').firstOrNull().orEmpty()) ?: code(right) ?: return null
        val leftWords = left.split(' ').filter { it.isNotBlank() }
        // Amount is optional: "usd to eur" means 1.
        val amount = leftWords.firstOrNull()?.replace(",", "")?.toDoubleOrNull() ?: 1.0
        val fromWord = leftWords.lastOrNull() ?: return null
        val from = code(fromWord) ?: return null
        if (from == to) return null
        return Parsed(amount, from, to)
    }

    private fun code(word: String): String? {
        val w = word.trim().lowercase(Locale.US)
        if (w.isBlank()) return null
        ALIASES[w]?.let { return it }
        // Bare ISO code the alias table doesn't list.
        return if (w.length == 3 && w.all { it.isLetter() }) w.uppercase(Locale.US) else null
    }

    fun card(p: Parsed): BraveSearchApi.RichAnswer? {
        val body = get("https://api.frankfurter.dev/v1/latest?base=${p.from}&symbols=${p.to}") ?: return null
        val rate = runCatching { JSONObject(body).optJSONObject("rates")?.optDouble(p.to, Double.NaN) }
            .getOrNull()?.takeIf { !it.isNaN() } ?: return null
        val result = p.amount * rate

        // 30-day rate history for the sparkline (free, keyless).
        val since = java.time.LocalDate.now().minusDays(30).toString()
        val spark = get("https://api.frankfurter.dev/v1/$since..?base=${p.from}&symbols=${p.to}")?.let { hist ->
            runCatching { JSONObject(hist).optJSONObject("rates") }.getOrNull()?.let { days ->
                days.keys().asSequence().sorted()
                    .mapNotNull { d -> days.optJSONObject(d)?.optDouble(p.to, Double.NaN)?.takeIf { !it.isNaN() }?.toFloat() }
                    .toList()
            }
        }.orEmpty()

        return BraveSearchApi.RichAnswer(
            vertical = "currency",
            headline = "${num(result)} ${p.to}",
            label = "${num(p.amount)} ${p.from} → ${p.to}",
            detail = "Rate ${num(rate, 4)}",
            delta = null,
            deltaUp = spark.size < 2 || spark.last() >= spark.first(),
            provider = "Frankfurter · ECB",
            spark = spark,
            glyph = "$"
        )
    }

    private fun num(v: Double, decimals: Int = 2): String =
        String.format(Locale.US, "%,.${decimals}f", v).trimEnd('0').trimEnd('.').ifBlank { "0" }

    private fun get(url: String): String? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 5_000; readTimeout = 8_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    } catch (_: Exception) { null }
}
