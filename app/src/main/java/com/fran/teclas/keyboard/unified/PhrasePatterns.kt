package com.fran.teclas.keyboard.unified

import java.util.Locale

/**
 * Multi-word and real-word error fixes — the class of mistake word-level autocorrect structurally
 * CANNOT catch.
 *
 * [PhoneticPatterns] only ever runs on a token that is NOT a dictionary word, and its targets must
 * each be a single dictionary word. That makes it blind to:
 *  - contractions missing an apostrophe ("dont", "wouldnt") — the target isn't a plain dict word
 *  - a misplaced apostrophe ("do'nt", "could'nt")
 *  - modal + "of" ("should of") — every word is individually valid
 *  - run-together compounds ("alot", "aswell") — the fix is TWO words
 *  - fixed idioms typed wrong ("sneak peak") — every word is individually valid
 *
 * Entries here are deliberately limited to the **lexically unambiguous**: the typed form is
 * essentially never valid English, so the fix can be applied without asking. Context-dependent
 * pairs (its/it's, your/you're before a noun, to/too, their/there) are intentionally ABSENT — they
 * need sentence understanding, which is what the on-device grammar model handles. Adding them here
 * would trade a real typo fix for a confident wrong rewrite, which is the worst outcome.
 */
object PhrasePatterns {

    /** Longest key length in words — bounds how far back a matcher has to look. */
    val maxWords: Int by lazy { fixes.keys.maxOf { key -> key.count { it == ' ' } + 1 } }

    /** The corrected form of [phrase] (already lowercased, single-spaced), or null. */
    fun fix(phrase: String): String? = fixes[phrase]

    private val fixes: Map<String, String> = mapOf(
        // ── Contractions missing the apostrophe ────────────────────────────────────────────────
        // Only forms with no real-word collision. Deliberately EXCLUDED because the bare spelling
        // is itself a valid word: its, were, wont, cant, ill, id, shed, hell, wed, lets, whos.
        "dont" to "don't", "doesnt" to "doesn't", "didnt" to "didn't",
        "isnt" to "isn't", "arent" to "aren't", "wasnt" to "wasn't", "werent" to "weren't",
        "hasnt" to "hasn't", "havent" to "haven't", "hadnt" to "hadn't",
        "wouldnt" to "wouldn't", "couldnt" to "couldn't", "shouldnt" to "shouldn't",
        "mustnt" to "mustn't", "neednt" to "needn't", "mightnt" to "mightn't",
        "youre" to "you're", "theyre" to "they're",
        "ive" to "I've", "youve" to "you've", "weve" to "we've", "theyve" to "they've",
        "youll" to "you'll", "theyll" to "they'll", "itll" to "it'll", "thatll" to "that'll",
        "wouldve" to "would've", "couldve" to "could've", "shouldve" to "should've",
        "mustve" to "must've", "mightve" to "might've",
        "thats" to "that's", "whats" to "what's", "theres" to "there's",
        "heres" to "here's", "wheres" to "where's", "hows" to "how's",
        "im" to "I'm", "aint" to "ain't", "yall" to "y'all", "oclock" to "o'clock",

        // ── Apostrophe in the wrong place ──────────────────────────────────────────────────────
        "do'nt" to "don't", "does'nt" to "doesn't", "did'nt" to "didn't",
        "is'nt" to "isn't", "are'nt" to "aren't", "was'nt" to "wasn't", "were'nt" to "weren't",
        "has'nt" to "hasn't", "have'nt" to "haven't", "had'nt" to "hadn't",
        "would'nt" to "wouldn't", "could'nt" to "couldn't", "should'nt" to "shouldn't",
        "ca'nt" to "can't", "wo'nt" to "won't", "must'nt" to "mustn't",

        // ── Modal + "of" → modal + "have" ──────────────────────────────────────────────────────
        // "of" after a modal is never grammatical, so these are safe despite every word being real.
        "should of" to "should have", "could of" to "could have", "would of" to "would have",
        "must of" to "must have", "might of" to "might have", "may of" to "may have",
        "shouldnt of" to "shouldn't have", "couldnt of" to "couldn't have",
        "wouldnt of" to "wouldn't have",
        "should of been" to "should have been", "could of been" to "could have been",
        "would of been" to "would have been",

        // ── Run-together compounds (the fix is two words) ──────────────────────────────────────
        "alot" to "a lot", "alot of" to "a lot of", "aswell" to "as well",
        "atleast" to "at least", "abit" to "a bit", "infact" to "in fact",
        "incase" to "in case", "ofcourse" to "of course", "thankyou" to "thank you",
        "eachother" to "each other", "everytime" to "every time", "inspite" to "in spite",
        "noone" to "no one", "aslong" to "as long", "atall" to "at all",
        "sortof" to "sort of", "kindof" to "kind of", "outof" to "out of",
        "no where" to "nowhere",

        // ── Fixed idioms typed wrong (every word real, the phrase is not) ──────────────────────
        "for all intensive purposes" to "for all intents and purposes",
        "sneak peak" to "sneak peek", "free reign" to "free rein",
        "deep seeded" to "deep seated", "case and point" to "case in point",
        "one in the same" to "one and the same", "make due" to "make do",
        "tow the line" to "toe the line", "baited breath" to "bated breath",
        "mute point" to "moot point", "beckon call" to "beck and call",
        "statue of limitations" to "statute of limitations",
        "escape goat" to "scapegoat",
        "wet your appetite" to "whet your appetite",
        "nip it in the butt" to "nip it in the bud",
        "peaked my interest" to "piqued my interest",
        "all of the sudden" to "all of a sudden",
        "worse case scenario" to "worst case scenario",
        "by in large" to "by and large", "first come first serve" to "first come first served",
        "would of thought" to "would have thought",

        // ── Single-word real-word errors with no valid reading ─────────────────────────────────
        "irregardless" to "regardless", "supposably" to "supposedly",
        "altho" to "although", "expresso" to "espresso"
    )

    /**
     * Re-applies [typed]'s capitalization to [corrected]. ALL-CAPS input stays all-caps; a leading
     * capital stays capitalized. Any capital the replacement itself carries (the "I" in "I'm") is
     * preserved when the input gives no signal.
     */
    fun preserveCase(typed: String, corrected: String): String = when {
        typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> corrected.uppercase(Locale.US)
        typed.firstOrNull()?.isUpperCase() == true -> corrected.replaceFirstChar { it.titlecase(Locale.US) }
        else -> corrected
    }
}
