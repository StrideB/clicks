package com.fran.teclas

import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Agentic emoji + smart symbols for the suggestion strip.
 *
 * Emoji: type a word ("love", "pizza") and relevant emoji appear as tappable chips. Picks are
 * learned per-user on-device (a word->emoji count map in prefs) and re-rank future suggestions —
 * no network, no data leaves the phone. A shared "learn from everybody" layer can be added later.
 *
 * Symbols: contextual punctuation without switching to the symbols layout — after a digit you get
 * currency/percent, and spelling a symbol's name ("dollar", "percent", "degree") offers the glyph.
 */
object SmartChips {

    private const val LEARN_PREF = "emoji_learn_v1"

    // Keyword -> emoji options (first is the default; learning re-ranks). Kept to common, unambiguous
    // words so chips are useful, not noisy.
    private val EMOJI: Map<String, List<String>> = mapOf(
        "love" to listOf("❤️", "😍", "🥰", "😘"),
        "loved" to listOf("❤️", "🥰"),
        "heart" to listOf("❤️", "💜", "💙", "🧡"),
        "happy" to listOf("😀", "😄", "😊", "🙂"),
        "sad" to listOf("😢", "😭", "☹️", "🥺"),
        "laugh" to listOf("😂", "🤣", "😆"),
        "lol" to listOf("😂", "🤣"),
        "fire" to listOf("🔥"),
        "lit" to listOf("🔥", "💯"),
        "cool" to listOf("😎", "🆒"),
        "ok" to listOf("👌", "🆗", "👍"),
        "okay" to listOf("👌", "👍"),
        "yes" to listOf("✅", "👍", "🙌"),
        "no" to listOf("❌", "🙅", "👎"),
        "thanks" to listOf("🙏", "😊"),
        "thank" to listOf("🙏"),
        "please" to listOf("🙏"),
        "pizza" to listOf("🍕"),
        "food" to listOf("🍔", "🍟", "🍕", "🌮"),
        "coffee" to listOf("☕"),
        "beer" to listOf("🍺", "🍻"),
        "wine" to listOf("🍷"),
        "party" to listOf("🎉", "🥳", "🎊"),
        "birthday" to listOf("🎂", "🥳", "🎉"),
        "cake" to listOf("🍰", "🎂"),
        "money" to listOf("💰", "🤑", "💵"),
        "dog" to listOf("🐶", "🐕"),
        "cat" to listOf("🐱", "🐈"),
        "sun" to listOf("☀️", "🌞"),
        "rain" to listOf("🌧️", "☔"),
        "snow" to listOf("❄️", "☃️"),
        "star" to listOf("⭐", "🌟"),
        "music" to listOf("🎵", "🎶"),
        "hi" to listOf("👋", "🙂"),
        "hey" to listOf("👋"),
        "hello" to listOf("👋", "🙂"),
        "bye" to listOf("👋"),
        "sleep" to listOf("😴", "💤"),
        "tired" to listOf("😩", "😴"),
        "sick" to listOf("🤒", "🤢"),
        "cry" to listOf("😭", "😢"),
        "angry" to listOf("😠", "😡"),
        "mad" to listOf("😠", "😡"),
        "kiss" to listOf("😘", "💋"),
        "hug" to listOf("🤗"),
        "think" to listOf("🤔"),
        "eyes" to listOf("👀"),
        "clap" to listOf("👏"),
        "pray" to listOf("🙏"),
        "run" to listOf("🏃", "💨"),
        "gym" to listOf("💪", "🏋️"),
        "strong" to listOf("💪"),
        "sorry" to listOf("😔", "🙏"),
        "wow" to listOf("😮", "🤩"),
        "omg" to listOf("😱", "😲"),
        "win" to listOf("🏆", "🥇", "🙌"),
        "game" to listOf("🎮"),
        "car" to listOf("🚗"),
        "plane" to listOf("✈️"),
        "home" to listOf("🏠"),
        "work" to listOf("💼"),
        "phone" to listOf("📱"),
        "email" to listOf("📧"),
        "time" to listOf("⏰"),
        "cold" to listOf("🥶", "❄️"),
        "hot" to listOf("🥵", "🔥"),
        "flower" to listOf("🌸", "🌹", "💐"),
        "rose" to listOf("🌹"),
        "moon" to listOf("🌙"),
        "ghost" to listOf("👻"),
        "skull" to listOf("💀"),
        "peace" to listOf("✌️"),
        "wave" to listOf("👋"),
        "hundred" to listOf("💯")
    )

    // Spelling a symbol's name offers the glyph. Kept to words rarely meant literally, to avoid noise.
    private val SYMBOL_WORDS: Map<String, String> = mapOf(
        "dollar" to "$", "dollars" to "$", "percent" to "%", "hashtag" to "#",
        "degree" to "°", "degrees" to "°", "euro" to "€", "pound" to "£",
        "copyright" to "©", "trademark" to "™", "bullet" to "•", "arrow" to "→",
        "infinity" to "∞", "bullseye" to "◎"
    )

    /** Emoji options for [word], re-ranked by this user's learned picks. Empty if no match. */
    fun emojiFor(prefs: SharedPreferences, word: String): List<String> {
        val base = EMOJI[word].orEmpty()
        if (base.isEmpty()) return emptyList()
        val learned = learnedCounts(prefs, word)
        if (learned.isEmpty()) return base
        return base.sortedByDescending { learned[it] ?: 0 }
    }

    /** Contextual symbol chips for the text [before] the cursor and the current [word]. */
    fun symbolsFor(before: String, word: String): List<String> {
        val out = LinkedHashSet<String>()
        SYMBOL_WORDS[word.lowercase()]?.let { out.add(it) }
        val last = before.trimEnd().lastOrNull()
        if (last != null && last.isDigit()) {
            out.add("%"); out.add("$"); out.add("."); out.add(",")
        }
        return out.toList()
    }

    /** Remember that this user picked [emoji] for [word], so it ranks higher next time. */
    fun recordEmojiPick(prefs: SharedPreferences, word: String, emoji: String) {
        if (word.isEmpty()) return
        runCatching {
            val json = JSONObject(prefs.getString(LEARN_PREF, "{}") ?: "{}")
            val wObj = json.optJSONObject(word) ?: JSONObject().also { json.put(word, it) }
            wObj.put(emoji, wObj.optInt(emoji, 0) + 1)
            prefs.edit().putString(LEARN_PREF, json.toString()).apply()
        }
    }

    private fun learnedCounts(prefs: SharedPreferences, word: String): Map<String, Int> =
        runCatching {
            val wObj = JSONObject(prefs.getString(LEARN_PREF, "{}") ?: "{}").optJSONObject(word)
                ?: return emptyMap()
            val out = HashMap<String, Int>()
            val keys = wObj.keys()
            while (keys.hasNext()) { val k = keys.next(); out[k] = wObj.optInt(k) }
            out
        }.getOrDefault(emptyMap())
}
