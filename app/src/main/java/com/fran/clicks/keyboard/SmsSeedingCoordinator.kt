package com.fran.clicks.keyboard

import android.content.Context
import com.fran.clicks.db.NgramDatabase
import com.fran.clicks.db.NgramEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val PREFS_KEY = "sms_seeded_v1"
private const val CHUNK_SIZE = 100

/**
 * One-shot pipeline: reads the user's sent SMS history, builds bigrams, and
 * seeds the NgramDatabase. Runs entirely on Dispatchers.IO.
 *
 * A SharedPreferences flag prevents re-running after the first successful seed.
 * Call [runIfNeeded] from a coroutine launched on any dispatcher.
 */
class SmsSeedingCoordinator(private val context: Context) {

    private val prefs = context.getSharedPreferences("clicks_prefs", Context.MODE_PRIVATE)

    /** Returns true if seeding has not yet run on this installation. */
    fun needsSeeding(): Boolean = !prefs.getBoolean(PREFS_KEY, false)

    suspend fun runIfNeeded(
        onFrequenciesReady: (wordFrequencies: Map<String, Float>) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        if (!needsSeeding()) return@withContext

        val analysis = SmsIngestionEngine(context).analyze(limit = 1000)
        if (analysis.bigrams.isEmpty()) {
            // Permission denied or no sent messages — mark done so we don't retry every launch
            prefs.edit().putBoolean(PREFS_KEY, true).apply()
            return@withContext
        }

        val dao = NgramDatabase.get(context).ngramDao()
        val now = System.currentTimeMillis()

        // Build NgramEntry list from bigrams
        val entries = analysis.bigrams.map { (pair, count) ->
            NgramEntry(
                prefix = pair.first,
                word = pair.second,
                count = count,
                lastUsed = now
            )
        }

        // Seed in chunks to avoid holding the DB lock for too long
        for (chunk in entries.chunked(CHUNK_SIZE)) {
            dao.seedBatch(chunk)
        }

        prefs.edit().putBoolean(PREFS_KEY, true).apply()

        // Normalize SMS word frequencies (0..1) and surface them to the caller
        // so PredictionEngine can merge them with the wordlist frequencies
        val maxCount = analysis.wordFrequencies.values.maxOrNull()?.toFloat() ?: 1f
        val normalised = analysis.wordFrequencies
            .mapValues { it.value.toFloat() / maxCount }
        withContext(Dispatchers.Main) { onFrequenciesReady(normalised) }
    }
}
