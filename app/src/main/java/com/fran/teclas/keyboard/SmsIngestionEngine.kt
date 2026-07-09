package com.fran.teclas.keyboard

import android.content.Context
import android.net.Uri
import android.provider.Telephony

private val URL_RE = Regex("""https?://\S+""")
private val JUNK_RE = Regex("""[^a-zA-Z\s']""")
private val SPACE_RE = Regex("""\s+""")

/**
 * Reads up to [limit] sent SMS messages and extracts:
 *   - bigrams: consecutive-word pairs (prevWord → word) with occurrence counts
 *   - wordFrequencies: per-word frequency across all messages
 *
 * Runs on whatever dispatcher the caller provides (intended: Dispatchers.IO).
 * Returns empty maps gracefully on SecurityException or missing URI.
 */
class SmsIngestionEngine(private val context: Context) {

    data class SmsAnalysis(
        val bigrams: Map<Pair<String, String>, Int>,
        val wordFrequencies: Map<String, Int>
    )

    fun analyze(limit: Int = 1000): SmsAnalysis {
        val bigramCounts = mutableMapOf<Pair<String, String>, Int>()
        val wordCounts = mutableMapOf<String, Int>()

        try {
            val uri: Uri = Telephony.Sms.Sent.CONTENT_URI
            context.contentResolver.query(
                uri, arrayOf(Telephony.Sms.BODY), null, null,
                "${Telephony.Sms.DATE} DESC"
            )?.use { cursor ->
                val bodyIdx = cursor.getColumnIndex(Telephony.Sms.BODY)
                if (bodyIdx < 0) return@use
                var processed = 0
                while (cursor.moveToNext() && processed < limit) {
                    val raw = cursor.getString(bodyIdx) ?: continue
                    processed++
                    val cleaned = raw.lowercase()
                        .replace(URL_RE, "")
                        .replace(JUNK_RE, "")
                        .trim()
                    val words = SPACE_RE.split(cleaned)
                        .filter { it.length in 2..20 }
                    for (w in words) wordCounts[w] = (wordCounts[w] ?: 0) + 1
                    for (i in 1 until words.size) {
                        val pair = words[i - 1] to words[i]
                        bigramCounts[pair] = (bigramCounts[pair] ?: 0) + 1
                    }
                }
            }
        } catch (_: SecurityException) {}
        catch (_: Exception) {}

        return SmsAnalysis(bigramCounts, wordCounts)
    }
}
