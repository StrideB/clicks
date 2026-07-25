package com.fran.teclas.spacetoday.rank

import com.fran.teclas.brief.BriefCategory
import com.fran.teclas.brief.BriefItem
import com.fran.teclas.brief.CalendarSignal
import com.fran.teclas.brief.NotificationSignal
import com.fran.teclas.spacetoday.model.Zone
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Stage A: deterministic local score. It is deliberately explainable and cheap, so the UI can
 * publish immediately before the LLM polish pass returns.
 */
class PreScorer(private val learning: LearningStore) {
    fun score(item: BriefItem, zone: Zone, activeSpace: String, targetSpace: String, now: Long = System.currentTimeMillis()): Int {
        val weights = learning.weights(targetSpace)
        val signal = item.signal
        val text = listOf(item.title, item.subtitle, (signal as? NotificationSignal)?.text.orEmpty()).joinToString(" ").lowercase(Locale.US)
        var score = when (item.category) {
            BriefCategory.CALL -> 46
            BriefCategory.CALENDAR -> 42
            BriefCategory.MESSAGE -> 34
            BriefCategory.EMAIL -> 28
            BriefCategory.MUSIC -> 16
            BriefCategory.WEATHER -> 10
            BriefCategory.OTHER -> 18
        }.toDouble()

        val senderUseful = (signal as? NotificationSignal)?.let { it.personName.orEmpty().isNotBlank() || it.title != it.appLabel } == true
        if (senderUseful) score += 9.0 * weights.sender
        if ((signal as? NotificationSignal)?.actions?.any { it.label.contains("reply", true) } == true) score += 10.0 * weights.channel
        if ("@you" in text || "dm" in text || "direct" in text) score += 8.0 * weights.channel
        val keywords = INTENT_KEYWORDS.count { text.contains(it) }
        score += (keywords * 5.0).coerceAtMost(18.0) * weights.keyword
        if ("?" in text || "can you" in text || "please" in text) score += 8.0 * weights.keyword

        val startsIn = (signal as? CalendarSignal)?.let { ((it.beginMillis - now) / 60_000L).toInt() }
        when {
            startsIn != null && startsIn < 0 -> score -= 6.0
            startsIn != null && startsIn <= 10 -> score += 24.0
            startsIn != null && startsIn <= 30 -> score += 18.0
            startsIn != null && startsIn <= 90 -> score += 9.0
        }

        score += zoneSpaceBoost(zone, targetSpace, activeSpace) * weights.zone
        if (activeSpace == targetSpace) score += 4.0
        return score.roundToInt().coerceIn(0, 100)
    }

    private fun zoneSpaceBoost(zone: Zone, targetSpace: String, activeSpace: String): Double = when {
        targetSpace == "work" && zone == Zone.WORK -> 12.0
        targetSpace == "home" && zone == Zone.PERSONAL -> 10.0
        targetSpace == "night" && zone == Zone.PERSONAL -> 9.0
        targetSpace == "travel" && zone == Zone.SYSTEM -> 8.0
        targetSpace == "fitness" && zone == Zone.PERSONAL -> 5.0
        targetSpace != activeSpace && zone == Zone.SYSTEM -> 2.0
        else -> -3.0
    }

    private companion object {
        val INTENT_KEYWORDS = listOf(
            "urgent", "asap", "eod", "today", "tonight", "now", "review", "send", "revise",
            "approve", "pay", "due", "join", "meeting", "alert", "latency", "late", "dinner"
        )
    }
}
