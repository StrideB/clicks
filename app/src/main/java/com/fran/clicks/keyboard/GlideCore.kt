package com.fran.clicks.keyboard

import com.fran.clicks.db.NgramRepository
import java.util.Locale

/**
 * Shared glide-typing decision logic for both keyboards, driven through [KeyboardHost]. The touch
 * capture and trail rendering stay in each host's keyboard view (they're View code, moved later);
 * this owns the parts that decide *which word* and *how it's placed*, so both keyboards agree.
 */
class GlideCore(
    private val host: KeyboardHost,
    private val ngram: NgramRepository
) {
    private fun previousWord(): String {
        val before = host.textBeforeCursor(96)
        val tokens = Regex("[A-Za-z]+").findAll(before).map { it.value }.toList()
        val endsLetter = before.isNotEmpty() && before.last().isLetter()
        return if (endsLetter) tokens.getOrElse(tokens.size - 2) { "" } else tokens.lastOrNull().orEmpty()
    }

    /** Per-word boost from what the user types after the previous word — fed into the decoder so the
     *  glide result agrees with sentence context. */
    fun contextBoost(): Map<String, Float> {
        val prev = previousWord()
        if (prev.length < 2) return emptyMap()
        val next = ngram.cachedNextWords(prev)
        if (next.isEmpty()) { ngram.prefetchNextWords(prev); return emptyMap() }
        return next.mapIndexed { i, w -> w.lowercase(Locale.US) to (1f - i * 0.12f).coerceAtLeast(0.2f) }.toMap()
    }

    /** Among the shape-vetted candidates, promote the one the user most often types after the
     *  previous word — breaks close calls the way you actually type. */
    fun rerank(results: List<String>): String {
        if (results.isEmpty()) return ""
        if (results.size < 2) return results.first()
        val ctx = ngram.cachedNextWords(previousWord()).map { it.lowercase(Locale.US) }
        if (ctx.isEmpty()) return results.first()
        val best = results.take(3).minByOrNull {
            val i = ctx.indexOf(it.lowercase(Locale.US)); if (i < 0) Int.MAX_VALUE else i
        }
        return if (best != null && ctx.contains(best.lowercase(Locale.US))) best else results.first()
    }

    /** Place a glided word: append after a completed word, or replace the in-progress partial word. */
    fun commitWord(word: String) = WordEditing.commitGlideWord(host, word)
}
