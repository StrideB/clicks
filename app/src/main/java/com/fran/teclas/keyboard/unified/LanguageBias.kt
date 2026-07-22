package com.fran.teclas.keyboard.unified

/**
 * Contextual language detection for multilingual typing. When the user enables more than one
 * language, the dictionary is a union of both, so a sloppy tap can resolve to the wrong-language
 * word ("mixing"). This watches which language the recent, UNAMBIGUOUS words belong to and biases
 * word scoring toward it — while a clear run of the other language shifts the balance. No hard
 * switch, and a single shared cognate ("no", "hotel") never flips it.
 */
class LanguageBias(private val perLangWords: Map<String, Set<String>>) {

    private val langs = perLangWords.keys.toList()
    val active: Boolean = langs.size > 1

    // Running preference per language (sums to 1). Starts even.
    private val score = HashMap<String, Double>().apply {
        val even = if (langs.isEmpty()) 0.0 else 1.0 / langs.size
        langs.forEach { put(it, even) }
    }

    private fun langsOf(word: String): List<String> {
        val w = word.lowercase()
        return langs.filter { perLangWords[it]?.contains(w) == true }
    }

    /** Update the running preference from a committed word. Only unambiguous words move it. */
    fun observe(word: String) {
        if (!active) return
        val inLangs = langsOf(word)
        if (inLangs.size != 1) return   // ambiguous (cognate) or unknown → no signal
        val winner = inLangs[0]
        for (l in langs) {
            val target = if (l == winner) 1.0 else 0.0
            score[l] = (score[l] ?: 0.0) * (1 - RATE) + target * RATE
        }
        val sum = score.values.sum().coerceAtLeast(1e-6)
        for (l in langs) score[l] = (score[l] ?: 0.0) / sum
    }

    /** Multiplier for a candidate word by current language preference; 1.0 = neutral. */
    fun preference(word: String): Double {
        if (!active) return 1.0
        val inLangs = langsOf(word)
        if (inLangs.isEmpty() || inLangs.size == langs.size) return 1.0   // unknown or shared cognate
        val best = inLangs.maxOf { score[it] ?: 0.0 }
        val even = 1.0 / langs.size
        return (1.0 + STRENGTH * ((best - even) / even)).coerceIn(1 - STRENGTH, 1 + STRENGTH)
    }

    private companion object {
        const val RATE = 0.25       // how fast the active language shifts toward the words being typed
        const val STRENGTH = 0.35   // max ± bias applied to a candidate word's score
    }
}
