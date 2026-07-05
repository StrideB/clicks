package com.fran.clicks.brief

/**
 * The single source of truth for routing a notification:
 *
 *  - **Actionable** → the Today brief. Something the user needs to DO or respond to: a person
 *    messaging, an email, a missed call, a to-do/reminder, or any notification carrying a real
 *    response action (reply, approve, RSVP, pay, snooze…).
 *  - **Informational** (everything else) → the homescreen widget stack, as ambient notifications.
 *    Video uploads, news, promos, social feed noise, delivery pings, app chatter.
 */
object BriefClassifier {

    private val TODO_PACKAGES = setOf(
        "com.google.android.apps.tasks",
        "com.google.android.keep",
        "com.todoist",
        "com.ticktick.task",
        "com.anydo",
        "com.microsoft.todos",
        "com.samsung.android.app.reminder"
    )

    // Substrings that mark an action button as "you're expected to respond / act".
    private val ACTIONABLE_LABEL_HINTS = listOf(
        "reply", "answer", "call back", "respond", "mark as read", "mark read",
        "mark done", "mark as done", "complete", "approve", "accept", "decline",
        "confirm", "rsvp", "pay", "snooze", "archive"
    )

    fun isActionable(record: NotificationRecord): Boolean {
        // Direct human communication is always actionable.
        when (record.category) {
            "message", "email", "call" -> return true
        }
        // To-do / reminder apps.
        if (record.packageName in TODO_PACKAGES) return true
        // A free-text reply target means someone expects a response.
        if (record.actions.any { it.remoteInput != null }) return true
        // An explicit act-on-it button.
        if (record.actions.any { a -> ACTIONABLE_LABEL_HINTS.any { a.label.contains(it, ignoreCase = true) } }) return true
        return false
    }
}
