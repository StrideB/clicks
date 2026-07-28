package com.fran.teclas.predict

/**
 * A dock position that holds a *role* rather than an app.
 *
 * The existing context dock reranks the whole row, which is honest about what it knows and
 * terrible for the thumb: the app you want moves, so you have to look. A semantic slot inverts
 * that — position three is always "audio", and what audio *means* changes with context. Buds at
 * 8am on a weekday is a podcast; wired headphones at 9pm is something to fall asleep to. Same
 * place on screen either way, so muscle memory survives the adaptation.
 *
 * Pure decision logic over the [ContextSnapshot] the predictor already builds, with no Android
 * types and no model. There is nothing here a language model would do better: these are rules
 * over signals the launcher already has, and they need to resolve in the time it takes to draw a
 * frame, not in the seconds a 4B generation costs.
 */
object SemanticSlots {

    /** What a slot is *for*. The user assigns candidate apps per role, per context bucket. */
    enum class Role { AUDIO, COMMUTE, MESSAGING, WORK, CAMERA }

    /**
     * The context buckets a role resolves within. Coarse on purpose: a bucket the user cannot
     * predict is a bucket they cannot build a habit in, and the entire point is habit.
     */
    enum class Bucket { COMMUTE, WORK, EVENING, TRAVEL, DEFAULT }

    /** A dock position bound to a role, with the user's chosen app per bucket. */
    data class Slot(
        val role: Role,
        /** bucket → package. Missing buckets fall back to [fallback]. */
        val byBucket: Map<Bucket, String> = emptyMap(),
        /** The app shown when nothing more specific applies. Never null: a slot is never empty. */
        val fallback: String,
    )

    /**
     * Which bucket the current context falls in.
     *
     * Ordered most-specific first, and each rule leans on a signal the user can feel. Someone who
     * cannot explain why the dock changed will experience it as the dock being broken, so a rule
     * that needs a paragraph to justify does not belong here.
     */
    fun bucket(c: ContextSnapshot): Bucket = when {
        // Actually moving, or in the car. The strongest signal available and unambiguous.
        c.driving || c.btDevice == BtDevice.CAR -> Bucket.COMMUTE

        // Genuinely elsewhere. Distance says travel more reliably than a calendar entry does.
        c.distanceBand == DistanceBand.REGIONAL || c.distanceBand == DistanceBand.FAR -> Bucket.TRAVEL

        // At work, or about to be in a meeting — on a workday.
        !c.isWeekend && (c.placeKind == PlaceKind.WORK ||
            c.calendar == CalendarProximity.IN_MEETING ||
            c.calendar == CalendarProximity.MEETING_SOON) -> Bucket.WORK

        // Winding down: evening or night, at home. Weekends included, unlike WORK.
        c.hourBucket >= EVENING_HOUR_BUCKET || c.hourBucket == NIGHT_HOUR_BUCKET -> Bucket.EVENING

        else -> Bucket.DEFAULT
    }

    /**
     * The package this slot should show right now.
     *
     * Falls back rather than blanking: an empty dock position is worse than a slightly stale one,
     * because the position is the thing the user aims at.
     */
    fun resolve(slot: Slot, c: ContextSnapshot): String =
        slot.byBucket[bucket(c)] ?: slot.fallback

    /**
     * Resolve a whole dock row, keeping every slot in its declared position.
     *
     * Deduplication is deliberate and order-preserving: if two roles resolve to the same app, the
     * earlier slot keeps it and the later one shows its fallback instead. Two identical icons side
     * by side reads as a bug, and it also wastes the scarcest thing on the screen.
     */
    fun resolveRow(slots: List<Slot>, c: ContextSnapshot): List<String> {
        val used = HashSet<String>()
        return slots.map { slot ->
            val first = resolve(slot, c)
            val pick = if (used.add(first)) first
            else slot.fallback.takeIf { used.add(it) } ?: first
            pick
        }
    }

    // hourBucket: 0 night / 1 earlyAM / 2 AM / 3 midday / 4 PM / 5 evening
    private const val EVENING_HOUR_BUCKET = 5
    private const val NIGHT_HOUR_BUCKET = 0
}
