package com.fran.clicks.glide

import android.util.LruCache
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

data class KeyInfo(
    val char: Char,
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float
) {
    val code: Int get() = char.lowercaseChar().code
}

class StatisticalGlideTypingClassifier {

    companion object {
        private const val PRUNING_LENGTH_THRESHOLD = 8.42
        private const val SAMPLING_POINTS = 200
        private const val SHAPE_STD = 22.08f
        private const val LOCATION_STD_FACTOR = 0.5109f
    }

    private val gesture = Gesture()
    private var keysByCode: HashMap<Int, KeyInfo> = HashMap()
    private var keys: List<KeyInfo> = emptyList()
    private var words: List<String> = emptyList()
    private var wordFrequencies: Map<String, Float> = emptyMap()
    private var pruner: Pruner? = null
    private var distanceThresholdSquared = 0f
    private var keyRadius = 1f
    private val suggestionCache = LruCache<Int, List<String>>(8)

    fun setLayout(keyList: List<KeyInfo>) {
        keysByCode.clear()
        keys = keyList
        keyList.forEach { keysByCode[it.code] = it }
        val avgW = keyList.map { it.width }.average().toFloat()
        val avgH = keyList.map { it.height }.average().toFloat()
        keyRadius = min(avgW, avgH)
        distanceThresholdSquared = (avgW / 4f).pow(2)
        rebuildPruner()
    }

    fun setWordData(wordList: List<String>, frequencies: Map<String, Float>) {
        words = wordList
        wordFrequencies = frequencies
        rebuildPruner()
    }

    private fun rebuildPruner() {
        if (keys.isEmpty() || words.isEmpty()) return
        pruner = Pruner(PRUNING_LENGTH_THRESHOLD, words, keysByCode)
        suggestionCache.evictAll()
    }

    fun addGesturePoint(x: Float, y: Float) {
        if (!gesture.isEmpty) {
            val dx = gesture.lastX() - x
            val dy = gesture.lastY() - y
            if (dx * dx + dy * dy < distanceThresholdSquared) return
        }
        gesture.addPoint(x, y)
    }

    val hasEnoughPoints: Boolean get() = gesture.size >= 6

    fun getSuggestions(maxCount: Int): List<String> {
        val cacheKey = gesture.hashCode() * 31 + maxCount
        return suggestionCache.get(cacheKey) ?: run {
            val r = computeSuggestions(maxCount)
            suggestionCache.put(cacheKey, r)
            r
        }
    }

    private fun computeSuggestions(maxCount: Int): List<String> {
        val p = pruner ?: return emptyList()
        if (gesture.size < 2) return emptyList()

        var candidates = p.pruneByExtremities(gesture, keys)
        val userResampled = gesture.resample(SAMPLING_POINTS)
        val userNormalized = userResampled.normalizeByBoxSide()
        candidates = p.pruneByLength(gesture, candidates, keysByCode, keys)

        val results = ArrayList<String>(maxCount + 1)
        val weights = ArrayList<Float>(maxCount + 1)

        for (word in candidates) {
            val idealGestures = Gesture.generateIdealGestures(word, keysByCode)
            for (ideal in idealGestures) {
                val wordResampled = ideal.resample(SAMPLING_POINTS)
                val wordNormalized = wordResampled.normalizeByBoxSide()
                val shapeProb = gaussianProb(calcShapeDistance(wordNormalized, userNormalized), 0f, SHAPE_STD)
                val locProb = gaussianProb(calcLocationDistance(wordResampled, userResampled), 0f, LOCATION_STD_FACTOR * keyRadius)
                val freq = 255f * (wordFrequencies[word] ?: 0.005f)
                val confidence = 1f / (shapeProb * locProb * freq + 1e-10f)

                var pos = 0
                var dupAt = Int.MAX_VALUE
                while (pos < weights.size && weights[pos] <= confidence) {
                    if (results[pos] == word) dupAt = pos
                    pos++
                }
                if (pos < maxCount && pos <= dupAt) {
                    if (dupAt < Int.MAX_VALUE) { weights.removeAt(dupAt); results.removeAt(dupAt) }
                    weights.add(pos, confidence)
                    results.add(pos, word)
                    if (weights.size > maxCount) { weights.removeAt(maxCount); results.removeAt(maxCount) }
                }
            }
        }
        return results
    }

    fun clear() {
        gesture.clear()
        suggestionCache.evictAll()
    }

    private fun gaussianProb(value: Float, mean: Float, std: Float): Float {
        val factor = 1.0 / (std * sqrt(2 * PI))
        val exp = ((value - mean) / std).toDouble().pow(2.0)
        return (factor * kotlin.math.exp(-0.5 * exp)).toFloat()
    }

    private fun calcShapeDistance(g1: Gesture, g2: Gesture): Float {
        var total = 0f
        for (i in 0 until SAMPLING_POINTS) total += Gesture.dist(g1.x(i), g1.y(i), g2.x(i), g2.y(i))
        return total
    }

    private fun calcLocationDistance(g1: Gesture, g2: Gesture): Float {
        var total = 0f
        for (i in 0 until SAMPLING_POINTS) total += abs(g1.x(i) - g2.x(i)) + abs(g1.y(i) - g2.y(i))
        return total / SAMPLING_POINTS / 2
    }

    // ── Pruner ───────────────────────────────────────────────────────────────

    class Pruner(
        private val lengthThreshold: Double,
        words: List<String>,
        keys: HashMap<Int, KeyInfo>
    ) {
        private val wordTree = HashMap<Long, ArrayList<String>>()

        init {
            for (word in words) {
                firstLastKey(word, keys)?.let { pair ->
                    wordTree.getOrPut(pair) { arrayListOf() }.add(word)
                }
            }
        }

        fun pruneByExtremities(gesture: Gesture, allKeys: List<KeyInfo>): ArrayList<String> {
            val result = ArrayList<String>()
            val startKeys = nClosest(gesture.firstX(), gesture.firstY(), 2, allKeys)
            val endKeys = nClosest(gesture.lastX(), gesture.lastY(), 2, allKeys)
            for (sk in startKeys) for (ek in endKeys) {
                wordTree[packKey(sk, ek)]?.let { result.addAll(it) }
            }
            return result
        }

        fun pruneByLength(
            gesture: Gesture,
            words: ArrayList<String>,
            keys: HashMap<Int, KeyInfo>,
            allKeys: List<KeyInfo>
        ): ArrayList<String> {
            val result = ArrayList<String>()
            val r = min(allKeys.firstOrNull()?.width ?: 1f, allKeys.firstOrNull()?.height ?: 1f)
            val userLen = gesture.getLength()
            for (word in words) {
                for (ideal in Gesture.generateIdealGestures(word, keys)) {
                    if (abs(userLen - ideal.getLength()) < lengthThreshold * r) { result.add(word); break }
                }
            }
            return result
        }

        companion object {
            private fun packKey(a: Int, b: Int): Long = a.toLong() shl 32 or b.toLong()

            fun firstLastKey(word: String, keys: HashMap<Int, KeyInfo>): Long? {
                if (word.isEmpty()) return null
                val fc = baseCode(word.first().lowercaseChar())
                val lc = baseCode(word.last().lowercaseChar())
                val fk = keys[fc] ?: return null
                val lk = keys[lc] ?: return null
                return packKey(fk.code, lk.code)
            }

            private fun baseCode(c: Char): Int {
                val s = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
                return s[0].code
            }

            fun nClosest(x: Float, y: Float, n: Int, keys: List<KeyInfo>): List<Int> =
                keys.sortedBy { Gesture.dist(it.centerX, it.centerY, x, y) }.take(n).map { it.code }
        }
    }

    // ── Gesture ──────────────────────────────────────────────────────────────

    class Gesture {
        companion object {
            private const val MAX_SIZE = 500

            fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float =
                sqrt((x1 - x2).pow(2) + (y1 - y2).pow(2))

            fun generateIdealGestures(word: String, keys: HashMap<Int, KeyInfo>): List<Gesture> {
                val straight = Gesture()
                val loops = Gesture()
                var prev = ' '
                var hasLoops = false

                for (c in word) {
                    val lc = c.lowercaseChar()
                    val code = Normalizer.normalize(lc.toString(), Normalizer.Form.NFD)[0].code
                    val key = keys[code] ?: continue
                    val cx = key.centerX; val cy = key.centerY
                    val hw = key.width / 4f; val hh = key.height / 4f

                    if (prev == lc) {
                        loops.addPoint(cx + hw, cy + hh)
                        loops.addPoint(cx + hw, cy - hh)
                        loops.addPoint(cx - hw, cy - hh)
                        loops.addPoint(cx - hw, cy + hh)
                        hasLoops = true
                        straight.addPoint(cx, cy)
                    } else {
                        straight.addPoint(cx, cy)
                        loops.addPoint(cx, cy)
                    }
                    prev = lc
                }
                return if (hasLoops) listOf(straight, loops) else listOf(straight)
            }
        }

        private val xs = FloatArray(MAX_SIZE)
        private val ys = FloatArray(MAX_SIZE)
        var size = 0; private set
        val isEmpty get() = size == 0

        fun addPoint(x: Float, y: Float) { if (size < MAX_SIZE) { xs[size] = x; ys[size] = y; size++ } }
        fun x(i: Int) = if (i < size) xs[i] else xs[size - 1]
        fun y(i: Int) = if (i < size) ys[i] else ys[size - 1]
        fun firstX() = xs[0]; fun firstY() = ys[0]
        fun lastX() = xs[size - 1]; fun lastY() = ys[size - 1]

        fun getLength(): Float {
            var len = 0f
            for (i in 1 until size) len += dist(xs[i - 1], ys[i - 1], xs[i], ys[i])
            return len
        }

        fun resample(n: Int): Gesture {
            val out = Gesture()
            if (size == 0) return out
            out.addPoint(xs[0], ys[0])
            if (size == 1 || n <= 1) { repeat(n - 1) { out.addPoint(xs[0], ys[0]) }; return out }
            val totalLen = getLength()
            if (totalLen < 0.001f) { repeat(n - 1) { out.addPoint(xs[0], ys[0]) }; return out }
            val step = totalLen / n
            var accumulated = 0f
            var prevX = xs[0]; var prevY = ys[0]
            var i = 1
            while (out.size < n && i < size) {
                val segLen = dist(prevX, prevY, xs[i], ys[i])
                if (accumulated + segLen >= step) {
                    val t = (step - accumulated) / segLen
                    val nx = prevX + t * (xs[i] - prevX)
                    val ny = prevY + t * (ys[i] - prevY)
                    out.addPoint(nx, ny)
                    prevX = nx; prevY = ny
                    accumulated = 0f
                } else {
                    accumulated += segLen
                    prevX = xs[i]; prevY = ys[i]
                    i++
                }
            }
            while (out.size < n) out.addPoint(xs[size - 1], ys[size - 1])
            return out
        }

        fun normalizeByBoxSide(): Gesture {
            val out = Gesture()
            if (size == 0) return out
            var minX = xs[0]; var maxX = xs[0]; var minY = ys[0]; var maxY = ys[0]
            for (i in 1 until size) {
                if (xs[i] < minX) minX = xs[i]; if (xs[i] > maxX) maxX = xs[i]
                if (ys[i] < minY) minY = ys[i]; if (ys[i] > maxY) maxY = ys[i]
            }
            val side = max(max(maxX - minX, maxY - minY), 0.00001f)
            val cx = (maxX + minX) / 2f; val cy = (maxY + minY) / 2f
            for (i in 0 until size) out.addPoint((xs[i] - cx) / side, (ys[i] - cy) / side)
            return out
        }

        fun clear() { size = 0 }

        override fun hashCode(): Int {
            var r = size
            for (i in 0 until size) { r = 31 * r + xs[i].hashCode(); r = 31 * r + ys[i].hashCode() }
            return r
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Gesture || size != other.size) return false
            for (i in 0 until size) if (xs[i] != other.xs[i] || ys[i] != other.ys[i]) return false
            return true
        }
    }
}
