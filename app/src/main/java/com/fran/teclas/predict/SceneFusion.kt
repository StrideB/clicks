package com.fran.teclas.predict

import android.content.Context
import com.fran.teclas.GeminiClient
import org.json.JSONObject

/**
 * LLM scene fusion: when rule triggers can't decide the active Space (no strong signal), an
 * on-device model reads the full signal bundle — time, upcoming calendar, recent notifications,
 * device state — and nominates a Space with a confidence. Catches scenes rules never encode:
 * a boarding-pass email + morning flight → Travel before you reach the airport; a Meet event
 * in 10 minutes → Work.
 *
 * NEVER in the detection hot path: [maybeRefresh] runs debounced on a background thread and
 * caches a verdict; [current] is a lock-free read consulted by [SpaceManager.detect].
 * Gated by the same AI-layer toggle as the reranker.
 */
object SceneFusion {

    private const val MIN_INTERVAL_MS = 5 * 60_000L
    private const val VERDICT_TTL_MS = 12 * 60_000L
    private const val MIN_CONFIDENCE = 0.75f

    data class Verdict(val spaceId: String, val confidence: Float, val reason: String, val at: Long)

    @Volatile private var verdict: Verdict? = null
    @Volatile private var lastRunMs = 0L
    @Volatile private var lastSignalHash = 0
    @Volatile private var running = false

    /** The cached verdict when fresh and confident, else null. Safe from any thread. */
    fun current(): Verdict? {
        val v = verdict ?: return null
        if (System.currentTimeMillis() - v.at > VERDICT_TTL_MS) return null
        if (v.confidence < MIN_CONFIDENCE) return null
        return v
    }

    /**
     * Re-evaluate if the signals changed and we haven't run recently. [calendarLines] and
     * [notificationLines] are short human-readable summaries (title + time / sender + preview).
     * Runs the model on a private thread; returns immediately.
     */
    fun maybeRefresh(
        context: Context,
        snapshot: ContextSnapshot,
        calendarLines: List<String>,
        notificationLines: List<String>,
    ) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences("teclas", Context.MODE_PRIVATE)
        if (!prefs.getBoolean(SpaceManager.AI_LAYER_KEY, false)) return
        val spaces = SpaceManager.spaces(appContext).filter { it.enabled && it.autoSwitch }
        if (spaces.isEmpty()) return

        val hash = listOf(
            snapshot.hourBucket, snapshot.driving, snapshot.headphones, snapshot.awayFromHome,
            calendarLines.take(3), notificationLines.take(5),
        ).hashCode()
        val now = System.currentTimeMillis()
        if (running || (hash == lastSignalHash && now - lastRunMs < VERDICT_TTL_MS) ||
            now - lastRunMs < MIN_INTERVAL_MS) return
        running = true
        lastSignalHash = hash
        lastRunMs = now

        Thread {
            try {
                val prompt = buildString {
                    append("You infer which context an Android launcher should switch to. Reply ONLY compact JSON: ")
                    append("{\"space\":\"<one id from the list or NONE>\",\"confidence\":<0.0-1.0>,\"reason\":\"<max 8 words>\"}\n")
                    append("Only pick a space when the evidence clearly points to it; otherwise NONE.\n")
                    append("Spaces: ${spaces.joinToString(", ") { "${it.id} (${it.name})" }}\n")
                    append("Now: hourBucket=${snapshot.hourBucket}(0=night,2=AM,3=midday,5=evening) weekend=${snapshot.isWeekend} driving=${snapshot.driving} ")
                    append("headphones=${snapshot.headphones} awayFromHome=${snapshot.awayFromHome}\n")
                    if (calendarLines.isNotEmpty()) append("Upcoming calendar: ${calendarLines.take(3).joinToString("; ")}\n")
                    if (notificationLines.isNotEmpty()) append("Recent notifications: ${notificationLines.take(5).joinToString("; ")}\n")
                }
                val out = GeminiClient.generate(
                    GeminiClient.apiKey(prefs), GeminiClient.model(prefs), prompt,
                    maxTokens = 60, temperature = 0.0, json = true,
                ) ?: return@Thread
                val inner = out.substringAfter('{', "").substringBeforeLast('}')
                if (inner.isBlank()) return@Thread
                val obj = runCatching { JSONObject("{$inner}") }.getOrNull() ?: return@Thread
                val id = obj.optString("space").trim()
                val confidence = obj.optDouble("confidence", 0.0).toFloat()
                if (id.isBlank() || id.equals("NONE", ignoreCase = true)) { verdict = null; return@Thread }
                if (spaces.none { it.id == id }) return@Thread
                verdict = Verdict(id, confidence, obj.optString("reason"), System.currentTimeMillis())
            } finally {
                running = false
            }
        }.start()
    }
}
