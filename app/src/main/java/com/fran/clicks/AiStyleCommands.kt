package com.fran.clicks

/**
 * Inline "//command" AI style transforms (e.g. "see you soon //formal" + space). Shared by the
 * launcher keyboard and the IME so both stay in parity. Only KNOWN commands fire, so URLs and code
 * containing "//" never trigger a transform.
 */
object AiStyleCommands {
    val instructions = mapOf(
        "polish" to "Rewrite this message so it reads clearly and naturally, with correct grammar and punctuation.",
        "fix" to "Fix the spelling, grammar, and punctuation of this message.",
        "formal" to "Rewrite this message in a polished, professional, formal tone.",
        "casual" to "Rewrite this message in a relaxed, friendly, casual tone.",
        "short" to "Rewrite this message to be shorter and more concise.",
        "shorter" to "Rewrite this message to be shorter and more concise.",
        "funny" to "Rewrite this message to be witty and lightly humorous.",
        "expand" to "Expand this brief message into a fuller, more detailed one.",
        // Humanize Text — style levels
        "human" to "Rewrite this message so it sounds like a real person wrote it: natural, warm, and " +
            "unforced, never robotic or corporate. Keep the original meaning.",
        "humanize" to "Rewrite this message so it sounds like a real person wrote it: natural, warm, and " +
            "unforced, never robotic or corporate. Keep the original meaning.",
        "mid" to "Rewrite this message in a mildly casual, everyday tone — relaxed but still clear, " +
            "not formal and not heavy slang. Keep the meaning.",
        "genz" to "Rewrite this message in casual Gen Z social-media style: lowercase, current slang, " +
            "light and expressive, keeping the original meaning.",
        // Reply modes — treat the input as something to respond to, not rewrite
        "reply" to "Write a natural, friendly reply to this message. Reply as if you are responding in a chat.",
        "midreply" to "Write a reply to this message in a mildly casual, everyday tone. Respond, don't rewrite.",
        "genzreply" to "Write a reply to this message in casual Gen Z social style (lowercase, slang). Respond, don't rewrite.",
        // Text to Emoji
        "emoji" to "Convert this message into a fun, emoji-only version that expresses the same idea. " +
            "Reply with ONLY emoji — no words, no explanation."
    )

    private val pattern = Regex("^(.*\\S)\\s*//(\\w+)\\s*$", RegexOption.DOT_MATCHES_ALL)

    /**
     * If [before] ends with "<content> //<known-command>", return (content, instruction);
     * otherwise null. [content] must be at least 2 chars so a lone "//fix" doesn't fire.
     */
    fun match(before: String): Pair<String, String>? {
        val m = pattern.find(before) ?: return null
        val instruction = instructions[m.groupValues[2].lowercase()] ?: return null
        val content = m.groupValues[1].trim()
        if (content.length < 2) return null
        return content to instruction
    }
}
