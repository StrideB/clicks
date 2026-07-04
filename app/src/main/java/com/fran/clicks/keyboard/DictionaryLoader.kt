package com.fran.clicks.keyboard

import android.content.Context
import android.os.Build

/**
 * Multi-language without switching.
 *
 * Loads the bundled per-language word lists and MERGES them into a single union dictionary, so the
 * keyboard corrects and predicts in every enabled language at the same time — the user never taps a
 * "change language" key. A word that's valid in any enabled language is therefore in the dictionary
 * and never gets autocorrected away; completions and glide candidates are drawn from all of them.
 *
 * Enabled languages come from the in-app language picker when set; otherwise Clicks uses just the
 * phone's PRIMARY system language (intersected with the lists we ship). Multilingual typing is an
 * explicit opt-in — the user picks the extra languages in Clicks settings — so a secondary phone
 * locale never silently starts rewriting the primary language. Falls back to English when nothing
 * matches.
 *
 * Per language, raw counts are normalized to [0,1] by that language's own most-frequent word before
 * merging, so no single language's larger counts drown out another's ranking. For words shared
 * across languages we keep the highest normalized frequency.
 */
object DictionaryLoader {

    // 2-letter language code -> bundled asset. Latin-script languages that share the QWERTY layout,
    // which is what makes "without switching" possible (same keys, different words).
    private val BUNDLED = linkedMapOf(
        "en" to "dict/en_wordlist.txt",
        "es" to "dict/es_wordlist.txt",
        "fr" to "dict/fr_wordlist.txt",
        "de" to "dict/de_wordlist.txt",
        "pt" to "dict/pt_wordlist.txt",
        "it" to "dict/it_wordlist.txt"
    )

    // Cap the glide shape-matching set so gesture typing stays snappy over the union (correction and
    // completion use cheap hash/first-letter filtering, so they keep the full union).
    private const val GLIDE_WORD_CAP = 26000

    // In-app language selection (comma-separated codes), stored in the shared "clicks" prefs. When
    // set it overrides the system locales, so the user can type multilingually on a single-locale
    // phone without changing Android's language settings.
    const val LANGUAGES_PREF = "kbd_languages"
    private const val PREFS = "clicks"

    /** The bundled languages the user could enable, in display order. */
    fun available(): List<String> = BUNDLED.keys.toList()

    data class Loaded(
        val words: List<String>,        // glide classifier input (frequency-capped)
        val freqs: Map<String, Float>,  // PredictionEngine input (full union)
        val languages: List<String>     // enabled language codes, primary first
    )

    /** In-app selection if set, else the system's enabled locales; ["en"] if none match. */
    fun enabledLanguages(context: Context): List<String> {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(LANGUAGES_PREF, null)
            ?.split(",")?.map { it.trim().lowercase() }?.filter { it in BUNDLED }
        if (!saved.isNullOrEmpty()) return saved
        // Zero-config uses ONLY the primary system language, not every secondary locale. Pulling in
        // every phone locale meant a user with, say, Spanish as a secondary language got their
        // English silently "corrected" into Spanish they never asked Clicks for. Multilingual typing
        // is still supported — it's just an explicit opt-in via the in-app language picker
        // (LANGUAGES_PREF), which is handled above and overrides this.
        val out = LinkedHashSet<String>()
        val cfg = context.resources.configuration
        if (Build.VERSION.SDK_INT >= 24) {
            val locales = cfg.locales
            for (i in 0 until locales.size()) {
                val lang = locales.get(i).language.lowercase()
                if (lang in BUNDLED) { out.add(lang); break }   // primary bundled language only
            }
        } else {
            @Suppress("DEPRECATION")
            val lang = cfg.locale.language.lowercase()
            if (lang in BUNDLED) out.add(lang)
        }
        if (out.isEmpty()) out.add("en")
        return out.toList()
    }

    /** Load + merge the enabled languages into one union dictionary. Call off the main thread. */
    fun load(context: Context): Loaded {
        val langs = enabledLanguages(context)
        val freqs = merge(context, langs)
        return Loaded(capWords(freqs), freqs, langs)
    }

    /**
     * Active primary dictionary plus an [extendedFreqs] superset that folds in the phone's secondary
     * bundled languages. Lets the keyboard type in the primary language by default and only switch a
     * latent language ON once the user actually writes a couple of its words — so a secondary phone
     * locale never silently rewrites the primary language. [latentLangs] is empty when the user has
     * explicitly picked languages in-app (their choice is honored as-is) or has no secondary locale.
     */
    data class Adaptive(
        val primaryFreqs: Map<String, Float>,
        val extendedFreqs: Map<String, Float>,
        val extendedWords: List<String>,
        val activeLangs: List<String>,
        val latentLangs: List<String>
    )

    fun loadAdaptive(context: Context): Adaptive {
        val active = enabledLanguages(context)
        val primaryFreqs = merge(context, active)
        val latent = systemBundledLanguages(context).filter { it !in active }
        if (latent.isEmpty()) {
            return Adaptive(primaryFreqs, primaryFreqs, capWords(primaryFreqs), active, emptyList())
        }
        val extendedFreqs = merge(context, active + latent)
        return Adaptive(primaryFreqs, extendedFreqs, capWords(extendedFreqs), active, latent)
    }

    /** Every bundled language present in the phone's locale list, primary first. */
    fun systemBundledLanguages(context: Context): List<String> {
        val out = LinkedHashSet<String>()
        val cfg = context.resources.configuration
        if (Build.VERSION.SDK_INT >= 24) {
            val locales = cfg.locales
            for (i in 0 until locales.size()) {
                val lang = locales.get(i).language.lowercase()
                if (lang in BUNDLED) out.add(lang)
            }
        } else {
            @Suppress("DEPRECATION")
            val lang = cfg.locale.language.lowercase()
            if (lang in BUNDLED) out.add(lang)
        }
        return out.toList()
    }

    private fun merge(context: Context, langs: List<String>): Map<String, Float> {
        val assets = context.assets
        val merged = HashMap<String, Float>(langs.size * 22000)
        for (lang in langs) {
            val path = BUNDLED[lang] ?: continue
            val counts = ArrayList<Pair<String, Long>>(22000)
            var maxC = 1L
            runCatching {
                assets.open(path).bufferedReader().forEachLine { line ->
                    val sp = line.trim().split(" ")
                    if (sp.size >= 2) {
                        val w = sp[0].lowercase()
                        val c = sp[1].toLongOrNull() ?: return@forEachLine
                        if (w.length in 2..20 && w.all { it.isLetter() }) {
                            counts.add(w to c)
                            if (c > maxC) maxC = c
                        }
                    }
                }
            }
            for ((w, c) in counts) {
                val f = c.toFloat() / maxC
                val prev = merged[w]
                if (prev == null || f > prev) merged[w] = f
            }
        }
        return merged
    }

    private fun capWords(freqs: Map<String, Float>): List<String> =
        if (freqs.size <= GLIDE_WORD_CAP) freqs.keys.toList()
        else freqs.entries.sortedByDescending { it.value }.take(GLIDE_WORD_CAP).map { it.key }
}
