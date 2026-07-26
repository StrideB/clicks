package com.fran.teclas.spacetoday.data

import android.content.Context
import com.fran.teclas.brief.BriefCategory
import com.fran.teclas.brief.BriefItem
import com.fran.teclas.brief.BriefRepository
import com.fran.teclas.brief.CalendarSignal
import com.fran.teclas.brief.NotificationSignal
import com.fran.teclas.brief.Signal
import com.fran.teclas.predict.Space
import com.fran.teclas.predict.SpaceManager
import com.fran.teclas.spacetoday.model.Breakthrough
import com.fran.teclas.spacetoday.model.SpaceWorkload
import com.fran.teclas.spacetoday.model.Tier
import com.fran.teclas.spacetoday.model.WorkAction
import com.fran.teclas.spacetoday.model.WorkItem
import com.fran.teclas.spacetoday.model.Zone
import com.fran.teclas.spacetoday.rank.LearningStore
import com.fran.teclas.spacetoday.rank.LlmRanker
import com.fran.teclas.spacetoday.rank.PreScorer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * Transforms the existing Today brief into Space-scoped workloads. No notification re-collection
 * happens here; the source of truth remains [BriefRepository].
 */
class PriorityFeedRepository(
    private val context: Context,
    private val briefRepository: BriefRepository,
    private val zoneResolver: ZoneResolver,
    private val preScorer: PreScorer,
    private val llmRanker: LlmRanker,
    private val learningStore: LearningStore,
    private val cache: WorkItemCache,
    private val scope: CoroutineScope,
    private val activeSpaceProvider: () -> String
) {
    private val _workloads = MutableStateFlow(cache.load())
    val workloads: StateFlow<Map<String, SpaceWorkload>> = _workloads
    private var rankJob: Job? = null
    private var periodicJob: Job? = null

    init {
        scope.launch {
            briefRepository.brief.collect { rebuild(polish = true) }
        }
    }

    /**
     * Every Space gets its own board.
     *
     * This used to be `spaceId in WORKLOAD_SPACES` — a hardcoded set of work/travel/fitness/gym.
     * Nothing about the pipeline required that: [buildBaseWorkloads] scores the whole device signal
     * set *per Space* via toWorkItem(item, space, ...), so any Space could always have been ranked.
     * The set only decided who was allowed to.
     *
     * The effect was that a normal evening or weekend at home resolved to Home, Home was not in the
     * set, and the Today surface silently never opened — it read as "Spaces is broken". Work is also
     * weekdaysOnly, so the four permitted Spaces were all weekday-or-away contexts. Things happen
     * outside those, so the gate is gone.
     *
     * A Space with nothing to show now renders the designed EmptyWorkload state rather than being
     * unreachable.
     */
    fun hasWorkload(spaceId: String): Boolean = true

    fun breakthroughsFor(activeSpace: String): List<Breakthrough> =
        _workloads.value.values
            .filter { it.spaceId != activeSpace && it.hasWorkload }
            .flatMap { workload ->
                val threshold = learningStore.breakthroughThreshold(workload.spaceId)
                workload.items
                    .filter { it.tier == Tier.T1_CRITICAL && it.score >= threshold }
                    .map { item ->
                        Breakthrough(
                            fromSpace = workload.spaceId,
                            fromSpaceName = workload.spaceName,
                            who = item.who,
                            text = item.summary,
                            timeCritical = (item.startsInMin ?: Int.MAX_VALUE) < 10 || item.summary.contains("now", true),
                            signalRef = item.signalRef
                        )
                    }
            }
            .sortedByDescending { it.timeCritical }
            .take(3)

    fun refresh() {
        briefRepository.refresh()
        scope.launch { rebuild(polish = true) }
    }

    fun refreshDebounced() {
        briefRepository.refreshDebounced(DEBOUNCE_MS)
    }

    fun onAction(signalRef: String, actionKind: String, space: String) {
        val item = _workloads.value[space]?.items?.firstOrNull { it.signalRef == signalRef }
        if (item != null) learningStore.onActed(space, item.tier, actionKind, item.bursty)
        briefRepository.removeItem(signalRef)
        scope.launch {
            delay(RECONCILE_MS)
            rebuild(polish = false)
        }
    }

    fun onDismiss(item: WorkItem) {
        learningStore.onIgnored(item.space, item.tier, item.bursty)
        item.source?.let { briefRepository.dismissItem(it) } ?: briefRepository.removeItem(item.signalRef)
    }

    fun startPeriodic() {
        if (periodicJob?.isActive == true) return
        periodicJob = scope.launch {
            while (true) {
                delay(PERIODIC_MS)
                refresh()
            }
        }
    }

    fun stopPeriodic() {
        periodicJob?.cancel()
        periodicJob = null
    }

    private fun rebuild(polish: Boolean) {
        val active = activeSpaceProvider()
        val spaces = SpaceManager.spaces(context).filter { it.enabled }
        val base = buildBaseWorkloads(spaces, active)
        publish(base)
        if (!polish) return
        rankJob?.cancel()
        rankJob = scope.launch {
            val polished = polish(base)
            publish(polished)
        }
    }

    private fun buildBaseWorkloads(spaces: List<Space>, activeSpace: String): Map<String, SpaceWorkload> {
        val now = System.currentTimeMillis()
        val briefItems = briefRepository.brief.value.items.filterNot { it.category == BriefCategory.WEATHER || it.category == BriefCategory.MUSIC }
        // How many items in this batch share each source (package). Calendar/other non-notification
        // items get their own unique key, so they are never counted as a burst.
        val burstByKey = briefItems.groupingBy { burstKey(it) }.eachCount()
        return spaces.associate { space ->
            if (!hasWorkload(space.id)) {
                space.id to SpaceWorkload.ambient(space.id, space.name)
            } else {
                val raw = briefItems.mapNotNull { item -> toWorkItem(item, space, activeSpace, now, burstByKey[burstKey(item)] ?: 1) }
                    .sortedByDescending { it.score }
                    .take(MAX_ITEMS_PER_SPACE)
                space.id to SpaceWorkload(
                    spaceId = space.id,
                    spaceName = space.name,
                    hasWorkload = true,
                    hero = ruleHero(raw),
                    stat = stats(raw),
                    items = raw
                )
            }
        }
    }

    private suspend fun polish(base: Map<String, SpaceWorkload>): Map<String, SpaceWorkload> {
        val next = base.toMutableMap()
        base.values.filter { it.hasWorkload && it.items.isNotEmpty() }.forEach { workload ->
            val (t1, t2) = learningStore.tierCuts(workload.spaceId)
            val inputs = workload.items.take(10).map { item ->
                LlmRanker.RankInput(
                    ref = item.signalRef,
                    sender = item.who,
                    zone = item.zone.name.lowercase(Locale.US),
                    category = item.category.name.lowercase(Locale.US),
                    time = item.time,
                    body = sourceBody(item.source, item.summary),
                    contentHash = item.contentHash.ifBlank { item.signalRef },
                    score = item.score,
                    fallbackSummary = item.summary
                )
            }
            val result = llmRanker.rank(workload.spaceId, inputs, t1, t2)
            val ranked = result.perItem.associateBy { it.ref }
            val updated = workload.items.map { item ->
                ranked[item.signalRef]?.let { r ->
                    item.copy(
                        summary = r.summary.ifBlank { item.summary },
                        tier = r.tier,
                        primaryAction = item.primaryAction.copy(kind = r.action.ifBlank { item.primaryAction.kind })
                    )
                } ?: item
            }.sortedWith(compareByDescending<WorkItem> { it.tier.ordinal * -1 }.thenByDescending { it.score })
            next[workload.spaceId] = workload.copy(hero = result.hero.ifBlank { workload.hero }, stat = stats(updated), items = updated)
        }
        return next
    }

    private fun publish(map: Map<String, SpaceWorkload>) {
        // Every brief emission (i.e. every notification change) runs a rebuild, and a polished
        // rebuild publishes twice — so an unguarded save meant two JSON serializations + two disk
        // writes per notification, often for an identical result. Skip when nothing actually moved.
        if (_workloads.value == map) return
        _workloads.value = map
        cache.save(map)
    }

    private fun burstKey(item: BriefItem): String =
        (item.signal as? NotificationSignal)?.packageName ?: item.signalRef

    private fun toWorkItem(item: BriefItem, space: Space, activeSpace: String, now: Long, burstCount: Int = 1): WorkItem? {
        val signal = item.signal ?: return cachedRebound(item, space, activeSpace, now, burstCount)
        val zone = zoneFor(signal)
        val startsIn = (signal as? CalendarSignal)?.let { ((it.beginMillis - now) / 60_000L).toInt() }
        val score = preScorer.score(item, zone, activeSpace, space.id, now, burstCount)
        if (space.id != activeSpace && score < MIN_OTHER_SPACE_SCORE) return null
        val (t1, t2) = learningStore.tierCuts(space.id)
        return WorkItem(
            signalRef = item.signalRef,
            space = space.id,
            zone = zone,
            category = item.category,
            who = who(signal, item),
            summary = item.title.ifBlank { item.subtitle }.take(90),
            time = timeLabel(signal, now),
            startsInMin = startsIn,
            score = score,
            tier = LlmRanker.tierFromScore(score, t1, t2),
            primaryAction = WorkAction(item.primaryActionLabel.ifBlank { "Open" }, kindFrom(item.primaryActionLabel)),
            extraActions = signal.actions
                .map { WorkAction(it.label, kindFrom(it.label)) }
                .filterNot { it.label.equals(item.primaryActionLabel, ignoreCase = true) }
                .take(2),
            source = item,
            contentHash = contentHash(signal),
            bursty = burstCount > 2
        )
    }

    private fun cachedRebound(item: BriefItem, space: Space, activeSpace: String, now: Long, burstCount: Int = 1): WorkItem {
        val zone = Zone.SYSTEM
        val score = preScorer.score(item, zone, activeSpace, space.id, now, burstCount)
        val (t1, t2) = learningStore.tierCuts(space.id)
        return WorkItem(
            signalRef = item.signalRef,
            space = space.id,
            zone = zone,
            category = item.category,
            who = item.subtitle.substringBefore(" · ").ifBlank { item.title },
            summary = item.title,
            time = "cached",
            startsInMin = null,
            score = score,
            tier = LlmRanker.tierFromScore(score, t1, t2),
            primaryAction = WorkAction(item.primaryActionLabel.ifBlank { "Open" }, kindFrom(item.primaryActionLabel)),
            source = item,
            contentHash = item.signalRef,
            bursty = burstCount > 2
        )
    }

    private fun zoneFor(signal: Signal): Zone = when (signal) {
        is NotificationSignal -> zoneResolver.resolve(signal.packageName, accountEmail = null, appLabel = signal.appLabel)
        is CalendarSignal -> Zone.SYSTEM
        else -> Zone.SYSTEM
    }

    private fun who(signal: Signal, item: BriefItem): String = when (signal) {
        is NotificationSignal -> signal.personName ?: signal.title.ifBlank { signal.appLabel }
        is CalendarSignal -> signal.title
        else -> item.subtitle.ifBlank { item.title }
    }.take(36)

    private fun timeLabel(signal: Signal, now: Long): String = when (signal) {
        is CalendarSignal -> {
            val minutes = ((signal.beginMillis - now) / 60_000L).toInt()
            when {
                minutes in 0..10 -> "in ${max(1, minutes)} min"
                minutes in 11..90 -> "in $minutes min"
                else -> signal.timeLabel.ifBlank { TIME.format(Date(signal.beginMillis)) }
            }
        }
        else -> TIME.format(Date(signal.timestamp))
    }

    private fun contentHash(signal: Signal): String = when (signal) {
        is NotificationSignal -> signal.contentHash
        is CalendarSignal -> "${signal.title}|${signal.beginMillis}|${signal.location}".hashCode().toString(16)
        else -> "${signal.id}|${signal.timestamp}".hashCode().toString(16)
    }

    private fun sourceBody(item: BriefItem?, fallback: String): String {
        val signal = item?.signal
        return when (signal) {
            is NotificationSignal -> "${signal.title}: ${signal.text}".trim()
            is CalendarSignal -> "${signal.title} ${signal.timeLabel} ${signal.location.orEmpty()}".trim()
            else -> listOfNotNull(item?.title, item?.subtitle).joinToString(" ").ifBlank { fallback }
        }
    }

    private fun kindFrom(label: String): String = when {
        label.contains("reply", true) || label.contains("respond", true) -> "reply"
        label.contains("join", true) -> "join"
        label.contains("direction", true) || label.contains("map", true) -> "directions"
        label.contains("snooze", true) -> "snooze"
        label.contains("dismiss", true) -> "dismiss"
        else -> "open"
    }

    private fun stats(items: List<WorkItem>): SpaceWorkload.Stat =
        SpaceWorkload.Stat(
            needNow = items.count { it.tier == Tier.T1_CRITICAL },
            tasks = items.count { it.tier == Tier.T2_IMPORTANT },
            updates = items.count { it.tier == Tier.T3_AMBIENT }
        )

    private fun ruleHero(items: List<WorkItem>): String =
        items.firstOrNull()?.let { "${it.who}: ${it.summary}".take(160) } ?: "Nothing needs you right now."

    private companion object {
        const val MAX_ITEMS_PER_SPACE = 8
        const val MIN_OTHER_SPACE_SCORE = 26
        const val DEBOUNCE_MS = 900L
        const val RECONCILE_MS = 400L
        const val PERIODIC_MS = 45 * 60_000L
        val TIME = SimpleDateFormat("h:mm a", Locale.getDefault())
    }
}
