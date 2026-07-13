package com.fran.teclas.semantic

import android.content.Context
import com.fran.teclas.llm.EmbedEngine
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One item the semantic index knows about: a stable key ("app:com.foo" / "setting:Glass effects")
 *  plus the natural-language text that gets embedded ("Maps. navigation, directions, gps"). */
internal data class SemanticItem(val key: String, val text: String)

/** A semantic hit: the item key and its cosine similarity to the query (0..1, higher = closer). */
internal data class SemanticHit(val key: String, val score: Float)

/**
 * On-device semantic search over launcher entities, powered by nomic-embed-text-v1.5 through the
 * SAME llama.cpp runtime as the generative model ([EmbedEngine]). Ungated + auto-downloaded, no
 * tokenizer file, no second SDK. Vectors live in a small binary file; queries embed per
 * keystroke-pause. No network at query time, ever.
 */
internal object SemanticSearchEngine {

    private const val DIR = "semantic"
    private const val INDEX_FILE = "index.bin"
    private const val INDEX_MAGIC = 0x54534D49 // "TSMI" — Teclas semantic index
    private const val INDEX_VERSION = 2         // bumped for the nomic embedding space (≠ Gemma's)

    private val indexMutex = Mutex()

    // key -> (hash of source text, L2-normalized vector). Volatile snapshot for lock-free reads.
    @Volatile private var vectors: Map<String, Pair<Int, FloatArray>> = emptyMap()
    private var indexLoaded = false

    private fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }
    private fun indexFile(context: Context) = File(dir(context), INDEX_FILE)

    /** Installed = the ungated nomic GGUF is downloaded. No import step anymore. */
    fun modelInstalled(context: Context): Boolean = EmbedEngine.modelInstalled(context)

    /** Brings the index in line with [items]: embeds new/changed ones, drops removed ones.
     *  Cheap when nothing changed (hash comparison only). Call from a background scope. */
    suspend fun ensureIndex(context: Context, items: List<SemanticItem>) = withContext(Dispatchers.IO) {
        if (!EmbedEngine.modelInstalled(context)) return@withContext
        indexMutex.withLock {
            loadIndexLocked(context)
            val current = vectors
            val wanted = items.associateBy { it.key }
            val stale = wanted.values.filter { current[it.key]?.first != it.text.hashCode() }
            val removed = current.keys - wanted.keys
            if (stale.isEmpty() && removed.isEmpty()) return@withLock

            val updated = current.toMutableMap()
            removed.forEach { updated.remove(it) }
            stale.forEach { item ->
                EmbedEngine.embed(context, item.text, isQuery = false)?.let { vec ->
                    updated[item.key] = item.text.hashCode() to vec
                }
            }
            vectors = updated
            saveIndexLocked(context)
        }
    }

    /** Embeds [query] and returns the closest items above [minScore], best first. */
    suspend fun search(context: Context, query: String, topK: Int, minScore: Float): List<SemanticHit> =
        withContext(Dispatchers.IO) {
            val snapshot = vectors
            if (snapshot.isEmpty()) return@withContext emptyList()
            val q = EmbedEngine.embed(context, query, isQuery = true) ?: return@withContext emptyList()
            snapshot.entries
                .map { (key, entry) -> SemanticHit(key, dot(q, entry.second)) }
                .filter { it.score >= minScore }
                .sortedByDescending { it.score }
                .take(topK)
        }

    /** Warm the persisted index into memory without embedding anything (fast, safe anywhere). */
    suspend fun warmUp(context: Context) = withContext(Dispatchers.IO) {
        indexMutex.withLock { loadIndexLocked(context) }
    }

    /**
     * Cosine affinity of every indexed item under [keyPrefix] (e.g. "app:") to a free-text
     * [description]. Powers Space cold-start priors: "Fitness. workouts, running, health"
     * ↔ each installed app's vector. One query embedding per call — cheap; cache results.
     */
    suspend fun affinity(context: Context, description: String, keyPrefix: String, minScore: Float): Map<String, Float> =
        withContext(Dispatchers.IO) {
            val snapshot = vectors
            if (snapshot.isEmpty()) return@withContext emptyMap()
            val q = EmbedEngine.embed(context, description, isQuery = true) ?: return@withContext emptyMap()
            buildMap {
                snapshot.forEach { (key, entry) ->
                    if (!key.startsWith(keyPrefix)) return@forEach
                    val score = dot(q, entry.second)
                    if (score >= minScore) put(key.removePrefix(keyPrefix), score)
                }
            }
        }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    private fun loadIndexLocked(context: Context) {
        if (indexLoaded) return
        indexLoaded = true
        val file = indexFile(context)
        if (!file.exists()) return
        runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != INDEX_MAGIC || input.readInt() != INDEX_VERSION) return
                val count = input.readInt()
                val loaded = HashMap<String, Pair<Int, FloatArray>>(count)
                repeat(count) {
                    val key = input.readUTF()
                    val hash = input.readInt()
                    val dim = input.readInt()
                    val vec = FloatArray(dim) { input.readFloat() }
                    loaded[key] = hash to vec
                }
                vectors = loaded
            }
        }.onFailure { file.delete() }
    }

    private fun saveIndexLocked(context: Context) {
        val snapshot = vectors
        val tmp = File(dir(context), "$INDEX_FILE.tmp")
        runCatching {
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.writeInt(INDEX_MAGIC)
                out.writeInt(INDEX_VERSION)
                out.writeInt(snapshot.size)
                snapshot.forEach { (key, entry) ->
                    out.writeUTF(key)
                    out.writeInt(entry.first)
                    out.writeInt(entry.second.size)
                    entry.second.forEach { out.writeFloat(it) }
                }
            }
            tmp.renameTo(indexFile(context))
        }.onFailure { tmp.delete() }
    }
}
