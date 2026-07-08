package com.fran.clicks.predict

import android.content.Context
import com.fran.clicks.GeminiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Optional AI layer over the bandit: when the user has switched it on in Spaces settings
 * (and Gemini is configured), re-rank the bandit's top candidates using only coarse
 * context labels — never raw locations, calendar contents or history. Hard 400ms budget;
 * any failure/timeout silently keeps the bandit's order. Enhancement, never a dependency.
 */
object GeminiReRanker {

    private const val TIMEOUT_MS = 400L

    fun enabled(context: Context): Boolean {
        if (!SpaceManager.aiLayerEnabled(context)) return false
        val prefs = context.getSharedPreferences("clicks", Context.MODE_PRIVATE)
        return GeminiClient.configured(prefs)
    }

    /**
     * Returns [candidates] reordered by the model, or null to keep the bandit ranking.
     * [labels] are app display names in the same order as [candidates] (packages are
     * never sent).
     */
    suspend fun rerank(
        context: Context,
        spaceName: String,
        snapshot: ContextSnapshot,
        candidates: List<String>,
        labels: List<String>,
    ): List<String>? {
        if (!enabled(context) || candidates.size < 2 || candidates.size != labels.size) return null
        val prefs = context.getSharedPreferences("clicks", Context.MODE_PRIVATE)
        val coarse = buildList {
            add("space=$spaceName")
            add("timeOfDay=${listOf("night", "earlyMorning", "morning", "midday", "afternoon", "evening")[snapshot.hourBucket]}")
            if (snapshot.isWeekend) add("weekend")
            if (snapshot.driving) add("driving")
            if (snapshot.headphones) add("headphonesOn")
            if (snapshot.mediaPlaying) add("musicPlaying")
            if (snapshot.calendar != CalendarProximity.FREE) add("meeting=${snapshot.calendar.name}")
        }
        val reply = withTimeoutOrNull(TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                GeminiClient.generate(
                    apiKey = GeminiClient.apiKey(prefs),
                    model = GeminiClient.model(prefs),
                    prompt = "Context: ${coarse.joinToString(", ")}. Rank these apps by how likely " +
                        "the user opens them next. Reply with only the numbers, best first, " +
                        "comma-separated.\n" +
                        labels.mapIndexed { i, l -> "${i + 1}. $l" }.joinToString("\n"),
                    maxTokens = 40,
                    temperature = 0.0,
                )
            }
        } ?: return null
        val order = Regex("\\d+").findAll(reply).map { it.value.toInt() - 1 }
            .filter { it in candidates.indices }.distinct().toList()
        if (order.size < candidates.size / 2) return null
        val rest = candidates.indices.filter { it !in order }
        return (order + rest).map { candidates[it] }
    }
}
