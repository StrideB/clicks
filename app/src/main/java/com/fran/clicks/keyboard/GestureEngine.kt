package com.fran.clicks.keyboard

import kotlin.math.hypot

data class SwipePoint(val x: Float, val y: Float, val timestamp: Long)

/**
 * Extracts anchor letters from a swipe path using velocity local-minima detection.
 * Where a finger naturally slows/pauses over a key is far more reliable than
 * sampling every pixel crossed.
 */
class GestureEngine {

    /** Minimum time delta to avoid division by zero (microseconds → we use ms here). */
    private val minDt = 1L

    /**
     * Returns the subset of [path] points where the finger clearly paused —
     * i.e., where instantaneous velocity drops below 40% of the prior segment's velocity.
     * These are the intended letter targets.
     */
    fun extractAnchorPoints(path: List<SwipePoint>): List<SwipePoint> {
        if (path.size < 3) return path
        val anchors = mutableListOf<SwipePoint>()
        // Always include first point
        anchors.add(path.first())

        for (i in 1 until path.size - 1) {
            val prev = path[i - 1]; val curr = path[i]; val next = path[i + 1]
            val dt1 = maxOf(curr.timestamp - prev.timestamp, minDt)
            val dt2 = maxOf(next.timestamp - curr.timestamp, minDt)
            val v1 = hypot((curr.x - prev.x).toDouble(), (curr.y - prev.y).toDouble()) / dt1
            val v2 = hypot((next.x - curr.x).toDouble(), (next.y - curr.y).toDouble()) / dt2
            if (v1 > 0.01 && v2 < v1 * 0.40) anchors.add(curr)
        }
        anchors.add(path.last())
        return anchors
    }

    /**
     * Converts anchor points to a deduplicated list of key labels using the provided
     * lookup function. Adjacent duplicates are collapsed (same as key-crossing approach).
     */
    fun anchorKeysFromPath(
        path: List<SwipePoint>,
        keyAtPoint: (Float, Float) -> String?
    ): List<String> {
        val anchors = extractAnchorPoints(path)
        return anchors.mapNotNull { keyAtPoint(it.x, it.y) }
            .filter { it.length == 1 }
            .fold(mutableListOf()) { acc, k -> if (acc.lastOrNull() != k) acc.apply { add(k) } else acc }
    }
}
