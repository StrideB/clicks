package com.fran.teclas.keyboard

import android.content.Context
import android.util.Log
import com.fran.teclas.keyboard.unified.PhoneticPatterns

/**
 * Loads the bulk misspelling lexicon from a bundled asset into [PhoneticPatterns], so the table can
 * grow to thousands of entries as DATA rather than code. Two reasons it can't just be a bigger map
 * literal: Kotlin builds `mapOf(...)` in a static initializer (thousands of pairs risk the JVM's
 * 64 KB per-method bytecode limit), and a data refresh shouldn't require a code change.
 *
 * Asset: `dict/en_typos.txt`, one entry per line, in the format used by Wikipedia's machine-readable
 * common-misspellings list:
 *
 *     abandonned->abandoned
 *     accension->accession, ascension
 *
 * **Lines offering more than one correction are skipped on purpose.** If the source itself can't say
 * which word was meant, neither can we, and silently picking the first would be exactly the
 * confident-wrong-rewrite this keyboard tries to avoid. Only single-correction lines are installed.
 *
 * The asset is optional: when absent, the curated built-in table is used alone and nothing changes.
 */
object TypoLexiconLoader {

    private const val ASSET = "dict/en_typos.txt"
    private const val TAG = "TypoLexicon"

    @Volatile private var loaded = false

    /** Parses and installs the asset once per process. Safe to call from any startup path. */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loaded = true          // set first: a missing/broken asset must not retry every keystroke
            val entries = runCatching { parse(context) }.getOrElse {
                Log.w(TAG, "typo lexicon load failed: ${it.message}"); return
            }
            if (entries.isNotEmpty()) {
                PhoneticPatterns.installBulk(entries)
                Log.i(TAG, "installed ${entries.size} bulk typo entries")
            }
        }
    }

    private fun parse(context: Context): Map<String, String> {
        val assets = context.applicationContext.assets
        if (assets.list("dict")?.contains("en_typos.txt") != true) return emptyMap()
        val out = HashMap<String, String>(4096)
        assets.open(ASSET).bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val arrow = line.indexOf("->")
                if (arrow <= 0) continue
                val typo = line.substring(0, arrow).trim().lowercase()
                val fix = line.substring(arrow + 2).trim()
                // Ambiguous source entry ("a, b") — skip rather than guess. See the class doc.
                if (fix.contains(',')) continue
                val target = fix.lowercase()
                if (typo.isEmpty() || target.isEmpty() || typo == target) continue
                // Word-level only: the phrase layer (PhrasePatterns) owns anything with a space.
                if (typo.any { !it.isLetter() } || target.any { !it.isLetter() }) continue
                out[typo] = target
            }
        }
        return out
    }
}
