package com.fran.clicks.keyboard.neural

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
