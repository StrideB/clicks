package com.fran.teclas.cue

import android.graphics.Color
import com.fran.teclas.Neu

/**
 * Card model for Cue ABA records. Mirrors `NativeSearchCard` in
 * cue/src/lib/native/search.ts — deliberately generic so a new record type on
 * the server renders here without an app release.
 */
internal data class CueCard(
    val type: String,
    val id: String,
    val glyph: String,
    val title: String,
    val subtitle: String,
    val sortValue: Int,
    /** True when the card carries PHI; blurred while the launcher is locked. */
    val phi: Boolean,
    val badgeText: String?,
    val badgeTone: String?,
    val meter: CueMeter?,
    val fields: List<CueField>,
    /** Full field set, shown when this card is the only result. */
    val detail: List<CueField>,
    val imageUrl: String?,
    val actions: List<CueAction>,
)

internal data class CueField(val label: String, val value: String)

internal data class CueMeter(val label: String, val used: Int, val total: Int, val tone: String) {
    val percent: Int get() = if (total <= 0) 0 else ((used.toLong() * 100) / total).toInt()
}

internal data class CueAction(
    val label: String,
    val deeplink: String?,
    val tel: String?,
    val geo: String?,
    val primary: Boolean,
)

internal data class CueBucket(val label: String, val count: Int, val tone: String)

internal data class CueSummary(val count: Int, val headline: String, val distribution: List<CueBucket>)

/** One search response. [mode] mirrors the server's: collection / detail / mixed / denied / empty. */
internal data class CueResults(
    val query: String,
    val mode: String,
    val type: String?,
    val sortedBy: String?,
    val summary: CueSummary?,
    val cards: List<CueCard>,
    /** Permission the caller is missing; only set when mode == "denied". */
    val gate: String?,
    val loading: Boolean = false,
    val error: String? = null,
) {
    val isEmpty: Boolean get() = cards.isEmpty() && gate == null && error == null && !loading

    companion object {
        fun loading(query: String) = CueResults(query, "empty", null, null, null, emptyList(), null, loading = true)
        fun error(query: String, message: String) =
            CueResults(query, "empty", null, null, null, emptyList(), null, error = message)

        /**
         * Signed out, but the user typed something only Cue could answer. This is
         * how the integration is discovered: there is no settings entry to hunt
         * for — type "auths" or "cue" and the way in appears where you already are.
         */
        fun signInPrompt(query: String) = CueResults(query, "signin", null, null, null, emptyList(), null)

        /** Signed in and asking about Cue itself: who am I, and can I sign out. */
        fun account(query: String) = CueResults(query, "account", null, null, null, emptyList(), null)

        val NONE = CueResults("", "empty", null, null, null, emptyList(), null)
    }
}

/** One organization this user holds an active staff row in. */
internal data class CueOrganization(
    val id: String,
    val name: String,
    val role: String,
    val active: Boolean,
)

/**
 * Identity from GET /api/native/v1/me.
 *
 * The launcher never invents an organization — it can only choose among the
 * ones Cue says this person belongs to. [ambiguous] means Cue picked a default
 * because nobody said which, and the user must resolve it before the records on
 * screen can be trusted.
 */
internal data class CueIdentity(
    val organizationId: String,
    val organizationName: String,
    val staffId: String,
    val displayName: String,
    val role: String,
    val permissions: Set<String>,
    val organizations: List<CueOrganization>,
    val ambiguous: Boolean,
)

/**
 * Per-record-type accent, drawn from the launcher's existing Neu palette so Cue
 * cards sit inside Teclas' visual language rather than importing Cue's web one.
 * The spine color is how you read the record type before reading any text.
 */
internal object CueTone {
    fun forType(type: String): Int = when (type) {
        "organization" -> Color.parseColor("#C9A7FF")  // go-key violet: the clinic itself
        "kiddo"        -> Neu.TEAL
        "staff"        -> Neu.BLUE
        "session"      -> Neu.GREEN
        "auth"         -> Neu.AMBER
        "claim"        -> Neu.ORANGE
        "incident"     -> Neu.ACCENT
        "document"     -> Neu.PURPLE
        "candidate"    -> Color.parseColor("#C9A7FF")
        "task"         -> Color.parseColor("#8FA3B8")
        else           -> Neu.BLUE
    }

    /** Semantic state color, independent of the record type's spine. */
    fun forTone(tone: String?): Int = when (tone) {
        "good" -> Neu.GREEN
        "warn" -> Neu.AMBER
        "bad"  -> Neu.ACCENT
        "info" -> Neu.BLUE
        else   -> Neu.TEAL
    }

    fun tagFor(type: String): String = when (type) {
        "organization" -> "COMPANY"
        "kiddo"        -> "KIDDO"
        "staff"        -> "STAFF"
        "session"      -> "SESSION"
        "auth"         -> "AUTH"
        "claim"        -> "CLAIM"
        "incident"     -> "INCIDENT"
        "document"     -> "DOC"
        "candidate"    -> "RECRUIT"
        "task"         -> "TASK"
        else           -> type.uppercase()
    }
}
