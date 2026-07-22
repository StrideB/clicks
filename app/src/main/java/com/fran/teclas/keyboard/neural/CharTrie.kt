package com.fran.teclas.keyboard.neural

/**
 * A compact 'a'..'z' prefix trie used to constrain beam search to real words.
 *
 * During decoding, a beam may only extend by a letter that keeps its prefix on a path toward some
 * dictionary word, and may only emit <eos> when its prefix is itself a complete word. This is what
 * makes the neural decoder produce dictionary words instead of plausible-looking non-words, and it
 * prunes the search space hard (most of the 26 branches are dead at any node).
 */
class CharTrie {

    private class Node {
        val children = arrayOfNulls<Node>(26)
        var isWord = false
        // Original (possibly accented) surface forms that fold to this a–z path. null when the word
        // equals its path (plain a–z) — callers then reconstruct from the path as before.
        var forms: ArrayList<String>? = null
    }

    private val root = Node()
    var size = 0
        private set

    /** Adds a lowercase word; non-'a'..'z' words are ignored. */
    fun add(word: String) {
        if (word.isEmpty()) return
        var node = root
        for (c in word) {
            val lc = c.lowercaseChar()
            if (lc !in 'a'..'z') return
            val i = lc - 'a'
            node = node.children[i] ?: Node().also { node.children[i] = it }
        }
        if (!node.isWord) { node.isWord = true; size++ }
    }

    fun addAll(words: Iterable<String>) = words.forEach(::add)

    /**
     * Adds a word by its ACCENT-FOLDED a–z path (á→a, ñ→n, …) while remembering the original surface
     * form. Lets the beam search walk plain a–z keys (which is all the physical keyboard has) yet
     * emit the correctly-accented word ("como" taps → "cómo"). Words with non-foldable characters are
     * ignored. Several accented words can share one folded path; all are kept.
     */
    fun addAccentFolded(word: String) {
        if (word.isEmpty()) return
        var node = root
        for (c in word) {
            val folded = foldAccent(c.lowercaseChar())
            if (folded !in 'a'..'z') return
            node = node.children[folded - 'a'] ?: Node().also { node.children[folded - 'a'] = it }
        }
        if (!node.isWord) { node.isWord = true; size++ }
        val list = node.forms ?: ArrayList<String>(1).also { node.forms = it }
        if (word !in list) list.add(word)
    }

    fun addAllAccentFolded(words: Iterable<String>) = words.forEach(::addAccentFolded)

    /** Surface forms stored at [cursor]'s word node (accented originals), or null to use the path. */
    fun formsAt(cursor: Any?): List<String>? = (cursor as? Node)?.forms

    companion object {
        fun foldAccent(c: Char): Char = when (c) {
            'á', 'à', 'ä', 'â', 'ã' -> 'a'
            'é', 'è', 'ë', 'ê' -> 'e'
            'í', 'ì', 'ï', 'î' -> 'i'
            'ó', 'ò', 'ö', 'ô', 'õ' -> 'o'
            'ú', 'ù', 'ü', 'û' -> 'u'
            'ñ' -> 'n'
            'ç' -> 'c'
            else -> c
        }
    }

    /** Opaque cursor into the trie for a beam's current prefix. Null means the prefix left the trie. */
    fun rootCursor(): Any = root

    /** Advances [cursor] by letter index [letterIdx] (0..25); null if no such continuation exists. */
    fun advance(cursor: Any?, letterIdx: Int): Any? =
        (cursor as? Node)?.children?.getOrNull(letterIdx)

    /** True if the letter index is a valid continuation from [cursor]. */
    fun canExtend(cursor: Any?, letterIdx: Int): Boolean =
        (cursor as? Node)?.children?.getOrNull(letterIdx) != null

    /** True if the prefix at [cursor] is a complete dictionary word (so <eos> is allowed here). */
    fun isWord(cursor: Any?): Boolean = (cursor as? Node)?.isWord == true
}
