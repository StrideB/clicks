package com.fran.teclas.brief

/**
 * Local first-pass triage for Today.
 *
 * Today is not a notification drawer. It only admits notifications that imply the user should do
 * something: reply, decide, pay, attend, review, investigate, or respond. Ambiguous/truncated text is
 * treated as FYI so the surface does not fill with generic app noise while the AI pass is unavailable.
 */
object BriefClassifier {
    enum class Kind { ACTION, FYI, IGNORE }
    data class Result(val kind: Kind, val task: String? = null)

    private val TODO_PACKAGES = setOf(
        "com.google.android.apps.tasks",
        "com.google.android.keep",
        "com.todoist",
        "com.ticktick.task",
        "com.anydo",
        "com.microsoft.todos",
        "com.samsung.android.app.reminder"
    )

    private val ACTIONABLE_LABEL_HINTS = listOf(
        "reply", "answer", "call back", "respond", "mark done", "mark as done", "complete",
        "approve", "accept", "decline", "confirm", "rsvp", "pay", "review", "send"
    )

    private val ACTION_TEXT_HINTS = listOf(
        "can you", "could you", "please", "pls", "need you", "do you want", "are you", "will you",
        "let me know", "reply", "respond", "send", "review", "revise", "approve", "confirm",
        "rsvp", "pay", "due", "deadline", "alert", "pagerduty", "@you", "assigned", "invited"
    )

    private val IGNORE_HINTS = listOf(
        "is typing", "typing…", "typing...", "syncing", "running in the background",
        "backup complete", "connected", "updated", "new login", "rate us"
    )

    private val FYI_HINTS = listOf(
        "stories for you", "newsletter", "daily digest", "receipt", "order shipped", "delivered",
        "liked your", "started following", "posted", "watch now", "sale", "discount", "promo"
    )

    fun isActionable(record: NotificationRecord): Boolean = classify(record).kind == Kind.ACTION

    fun classify(record: NotificationRecord): Result {
        val title = record.title.trim()
        val text = record.text.trim()
        val combined = "$title $text".trim()
        val lower = combined.lowercase()

        if (combined.isBlank()) return Result(Kind.IGNORE)
        if (IGNORE_HINTS.any { lower.contains(it) }) return Result(Kind.IGNORE)
        if (looksTruncated(text) && !hasStrongActionSignal(record, lower)) return Result(Kind.FYI)
        if (FYI_HINTS.any { lower.contains(it) } && !hasStrongActionSignal(record, lower)) return Result(Kind.FYI)

        val actionLabel = record.actions.firstOrNull { a ->
            a.remoteInput != null || ACTIONABLE_LABEL_HINTS.any { hint -> a.label.contains(hint, ignoreCase = true) }
        }?.label.orEmpty()

        val strong = hasStrongActionSignal(record, lower) || record.packageName in TODO_PACKAGES
        if (!strong) return Result(Kind.FYI)

        val task = draftTask(record, actionLabel)
        return if (task.isBlank()) Result(Kind.FYI) else Result(Kind.ACTION, task)
    }

    private fun hasStrongActionSignal(record: NotificationRecord, lower: String): Boolean {
        if (record.packageName in TODO_PACKAGES) return true
        if (record.actions.any { it.remoteInput != null }) {
            if ("?" in record.text || ACTION_TEXT_HINTS.any { lower.contains(it) }) return true
        }
        if (record.actions.any { a -> ACTIONABLE_LABEL_HINTS.any { a.label.contains(it, ignoreCase = true) } }) return true
        if (ACTION_TEXT_HINTS.any { lower.contains(it) }) return true
        return false
    }

    private fun looksTruncated(text: String): Boolean {
        val t = text.trim()
        return t.endsWith("…") || t.endsWith("...") || t.length >= 180
    }

    private fun draftTask(record: NotificationRecord, actionLabel: String): String {
        val sender = cleanSender(record)
        val text = record.text.trim()
        val lower = text.lowercase()
        val subject = subjectPhrase(text)
        val time = timePhrase(text)

        val raw = when {
            "pagerduty" in lower || "alert" in lower ->
                "Investigate ${subject.ifBlank { "alert" }}"
            "revise" in lower ->
                "Revise ${subject.ifBlank { "request" }}${sender?.let { " for $it" }.orEmpty()}"
            "review" in lower ->
                "Review ${subject.ifBlank { "request" }}${sender?.let { " from $it" }.orEmpty()}"
            "send" in lower ->
                "Send ${subject.ifBlank { "requested item" }}${sender?.let { " to $it" }.orEmpty()}${time?.let { " $it" }.orEmpty()}"
            "pay" in lower ->
                "Pay ${subject.ifBlank { "request" }}${time?.let { " $it" }.orEmpty()}"
            "approve" in lower ->
                "Approve ${subject.ifBlank { "request" }}${sender?.let { " from $it" }.orEmpty()}"
            "confirm" in lower || "rsvp" in lower ->
                "Confirm ${subject.ifBlank { "plans" }}${sender?.let { " with $it" }.orEmpty()}"
            actionLabel.contains("call", ignoreCase = true) ->
                "Call ${sender ?: "back"}"
            else ->
                "Reply to ${sender ?: record.appLabel}${subject.takeIf { it.isNotBlank() }?.let { " about $it" }.orEmpty()}${time?.let { " $it" }.orEmpty()}"
        }
        return raw.trimWords(10)
    }

    private fun cleanSender(record: NotificationRecord): String? {
        val sender = (record.personName ?: record.title).trim()
        if (sender.isBlank()) return null
        if (sender.equals(record.appLabel, ignoreCase = true)) return null
        if (sender.equals(record.packageName.substringAfterLast('.'), ignoreCase = true)) return null
        return sender.take(32)
    }

    private fun subjectPhrase(text: String): String {
        val cleaned = text
            .replace(Regex("\\b(can|could) you\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\b(please|pls|let me know|regarding|about)\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[?!.]+$"), "")
            .trim()
        val patterns = listOf(
            Regex("\\b(send|review|revise|approve|confirm|pay|investigate)\\s+(?:the\\s+|a\\s+|an\\s+)?(.+?)(?:\\s+(before|by|tonight|today|tomorrow|at)\\b|$)", RegexOption.IGNORE_CASE),
            Regex("\\b(?:dinner|lunch|meeting|call|deck|invoice|email|alert|latency|q[1-4])\\b.*", RegexOption.IGNORE_CASE)
        )
        patterns.forEach { re ->
            val m = re.find(cleaned) ?: return@forEach
            // The second pattern has no capturing groups, so guard on group count before reading group 2.
            val captured = if (m.groups.size > 2) m.groups[2]?.value else null
            val group = (captured ?: m.value).trim()
            if (group.isNotBlank()) return group.trimWords(5)
        }
        return cleaned.split(Regex("\\s+")).filter { it.length > 2 }.take(4).joinToString(" ")
    }

    private fun timePhrase(text: String): String? {
        val time = Regex("\\b(before|by|at)\\s+\\d{1,2}(:\\d{2})?\\s*(am|pm)?\\b", RegexOption.IGNORE_CASE)
            .find(text)?.value
        if (!time.isNullOrBlank()) return time
        return Regex("\\b(today|tonight|tomorrow)\\b", RegexOption.IGNORE_CASE).find(text)?.value
    }

    private fun String.trimWords(max: Int): String {
        val words = trim().replace(Regex("\\s+"), " ").split(" ").filter { it.isNotBlank() }
        return words.take(max).joinToString(" ")
    }
}
