package com.fran.teclas.llm

/**
 * Turning shorthand into a sendable reply — "tell him ill be there but grab coffee first" becomes
 * "I'll be there, but I'm grabbing a coffee first."
 *
 * The direct-reply plumbing already exists (`TeclasNotificationListener.sendReply`), so the only
 * new thing needed is the rewrite. Almost all of the code here is [sanitize], because a model
 * rewriting a message that a real person will receive has failure modes that a model answering a
 * question does not:
 *
 *  - It chats back. "Sure! Here's a polished version:" is a perfectly good LLM response and a
 *    catastrophic text message.
 *  - It pads. Three words of shorthand become a four-sentence email nobody would have written.
 *  - It leaves placeholders. "[Name]" or "<time>" sent verbatim to a colleague.
 *  - It refuses, and the refusal is what gets sent.
 *
 * So the contract is: return a clean single-line reply, or null. Null is a completely fine answer —
 * the caller keeps what the user typed, which was always sendable. Nothing here ever sends;
 * the result is put in front of the user to edit and send themselves.
 */
object ReplyPolish {

    /** A reply is one message. Anything longer than this is the model writing an essay. */
    const val MAX_CHARS = 320

    /** Growth beyond this multiple of the shorthand means invention, not tidying. */
    const val MAX_GROWTH = 6

    fun prompt(shorthand: String, incoming: String, sender: String): String = buildString {
        append("Rewrite a shorthand reply as one natural, sendable message.\n")
        if (sender.isNotBlank()) append("Replying to: ").append(sender).append('\n')
        if (incoming.isNotBlank()) append("Their message: \"").append(incoming.take(400)).append("\"\n")
        append("Shorthand reply: \"").append(shorthand.take(400)).append("\"\n")
        append(
            "Keep the meaning and every fact exactly as given. Add nothing that is not in the " +
                "shorthand. Match a casual texting tone. Reply with ONLY the message text, on one " +
                "line, with no quotes, no preamble and no placeholders."
        )
    }

    // Openers that mean the model is talking to us rather than writing the message.
    private val PREAMBLE = listOf(
        "sure", "certainly", "of course", "here's", "here is", "here you go", "okay", "ok,",
        "absolutely", "got it", "understood", "i've", "i have rewritten", "rewritten", "polished",
        "sounds good! here", "as an ai", "i'm sorry", "i cannot", "i can't", "i am unable",
    )

    /**
     * Clean the model's output into something sendable, or null to keep what the user typed.
     *
     * [shorthand] is the user's own text — the yardstick for whether the rewrite grew into
     * something they did not say.
     */
    fun sanitize(raw: String?, shorthand: String): String? {
        val firstLine = raw?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.isNotEmpty() }
            ?: return null

        var s = firstLine
        // Models love wrapping the answer in quotes; strip one balanced layer, not stray ones.
        while (s.length >= 2 &&
            ((s.first() == '"' && s.last() == '"') || (s.first() == '\'' && s.last() == '\'') ||
                (s.first() == '“' && s.last() == '”'))
        ) {
            s = s.substring(1, s.length - 1).trim()
        }
        if (s.isEmpty()) return null

        val lower = s.lowercase()
        if (PREAMBLE.any { lower.startsWith(it) }) return null
        // A colon in the first few words is the other shape of "Here's your message:".
        if (s.substringBefore(':', "").let { it.isNotEmpty() && it.length < 24 && !it.contains(' ') }) return null
        // Unfilled placeholders must never reach a real person.
        if (PLACEHOLDER.containsMatchIn(s)) return null
        if (s.length > MAX_CHARS) return null
        if (shorthand.isNotBlank() && s.length > shorthand.trim().length * MAX_GROWTH) return null
        // No change is not an improvement worth showing.
        if (s.equals(shorthand.trim(), ignoreCase = true)) return null
        return s
    }

    // "[name]", "<time>", "{place}" — any bracketed slot the model left for someone else to fill.
    private val PLACEHOLDER = Regex("""[\[<{][A-Za-z _/|-]{2,20}[]>}]""")
}
