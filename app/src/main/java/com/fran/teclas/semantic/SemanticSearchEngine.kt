package com.fran.teclas.semantic

import android.content.Context
import android.net.Uri
import com.google.ai.edge.localagents.rag.models.EmbedData
import com.google.ai.edge.localagents.rag.models.Embedder
import com.google.ai.edge.localagents.rag.models.EmbeddingRequest
import com.google.ai.edge.localagents.rag.models.GemmaEmbeddingModel
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One item the semantic index knows about: a stable key ("app:com.foo" / "setting:Glass effects")
 *  plus the natural-language text that gets embedded ("Maps. navigation, directions, gps"). */
internal data class SemanticItem(val key: String, val text: String)

/** A semantic hit: the item key and its cosine similarity to the query (0..1, higher = closer). */
internal data class SemanticHit(val key: String, val score: Float)

/**
 * On-device semantic search over launcher entities, powered by EmbeddingGemma through the
 * AI Edge RAG SDK. Everything runs locally: the user imports the model + tokenizer files once
 * (gated download from HuggingFace/Kaggle), vectors live in a small binary file, and queries
 * are embedded per keystroke-pause. No network, ever.
 *
 * Files under filesDir/semantic/:
 *   embedder.tflite      — EmbeddingGemma LiteRT model (any seq-length variant)
 *   sentencepiece.model  — its tokenizer
 *   index.bin            — persisted item vectors (rebuilt incrementally)
 */
internal object SemanticSearchEngine {

    private const val DIR = "semantic"
    private const val MODEL_FILE = "embedder.tflite"
    private const val TOKENIZER_FILE = "sentencepiece.model"
    private const val INDEX_FILE = "index.bin"
    private const val INDEX_MAGIC = 0x54534D49 // "TSMI" — Teclas semantic index
    private const val INDEX_VERSION = 1
    private const val EMBED_BATCH = 12
    private const val USE_GPU = false // CPU is reliable everywhere; GPU delegate can be flaky on sideload targets

    private var embedder: Embedder<String>? = null
    private val indexMutex = Mutex()

    // key -> (hash of source text, L2-normalized vector). Volatile snapshot for lock-free reads.
    @Volatile private var vectors: Map<String, Pair<Int, FloatArray>> = emptyMap()
    private var indexLoaded = false

    private fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }
    private fun modelFile(context: Context) = File(dir(context), MODEL_FILE)
    private fun tokenizerFile(context: Context) = File(dir(context), TOKENIZER_FILE)
    private fun indexFile(context: Context) = File(dir(context), INDEX_FILE)

    fun modelInstalled(context: Context): Boolean =
        modelFile(context).length() > 0L && tokenizerFile(context).length() > 0L

    /** Copies a user-picked document into the model dir. Classifies by file name: anything
     *  sentencepiece-ish is the tokenizer, .tflite/.litertlm is the embedder.
     *  Returns a human-readable label of what was imported, or null if unrecognized. */
    fun importModelFile(context: Context, uri: Uri): String? {
        val name = queryDisplayName(context, uri)?.lowercase() ?: return null
        val target = when {
            name.contains("sentencepiece") || name.endsWith(".spm") ||
                (name.endsWith(".model") && !name.contains("embed")) -> tokenizerFile(context)
            name.endsWith(".tflite") || name.endsWith(".litertlm") -> modelFile(context)
            else -> return null
        }
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val tmp = File(target.parentFile, target.name + ".tmp")
                tmp.outputStream().use { output -> input.copyTo(output) }
                tmp.renameTo(target)
            } ?: return null
        }.getOrElse { return null }
        // Model changed → stale vectors would mix embedding spaces. Start clean.
        if (target.name == MODEL_FILE) {
            indexFile(context).delete()
            vectors = emptyMap()
            indexLoaded = false
            embedder = null
        }
        return if (target.name == MODEL_FILE) "embedding model" else "tokenizer"
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment

    @Synchronized
    private fun embedderOrNull(context: Context): Embedder<String>? {
        embedder?.let { return it }
        if (!modelInstalled(context)) return null
        return runCatching {
            GemmaEmbeddingModel(modelFile(context).absolutePath, tokenizerFile(context).absolutePath, USE_GPU)
        }.getOrNull()?.also { embedder = it }
    }

    /** Brings the index in line with [items]: embeds new/changed ones, drops removed ones.
     *  Cheap when nothing changed (hash comparison only). Call from a background scope. */
    suspend fun ensureIndex(context: Context, items: List<SemanticItem>) = withContext(Dispatchers.IO) {
        val model = embedderOrNull(context) ?: return@withContext
        indexMutex.withLock {
            loadIndexLocked(context)
            val current = vectors
            val wanted = items.associateBy { it.key }
            val stale = wanted.values.filter { current[it.key]?.first != it.text.hashCode() }
            val removed = current.keys - wanted.keys
            if (stale.isEmpty() && removed.isEmpty()) return@withLock

            val updated = current.toMutableMap()
            removed.forEach { updated.remove(it) }
            stale.chunked(EMBED_BATCH).forEach { chunk ->
                val request = EmbeddingRequest.create(chunk.map {
                    EmbedData.create(it.text, EmbedData.TaskType.RETRIEVAL_DOCUMENT, false)
                })
                val embeddings = runCatching { model.getBatchEmbeddings(request).await() }.getOrNull() ?: return@forEach
                chunk.forEachIndexed { i, item ->
                    embeddings.getOrNull(i)?.let { vec ->
                        updated[item.key] = item.text.hashCode() to normalized(vec.toFloatArray())
                    }
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
            val model = embedderOrNull(context) ?: return@withContext emptyList()
            val request = EmbeddingRequest.create(
                listOf(EmbedData.create(query, EmbedData.TaskType.RETRIEVAL_QUERY, true))
            )
            val raw = runCatching { model.getEmbeddings(request).await() }.getOrNull() ?: return@withContext emptyList()
            val q = normalized(raw.toFloatArray())
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
            val model = embedderOrNull(context) ?: return@withContext emptyMap()
            val request = EmbeddingRequest.create(
                listOf(EmbedData.create(description, EmbedData.TaskType.RETRIEVAL_QUERY, true))
            )
            val raw = runCatching { model.getEmbeddings(request).await() }.getOrNull() ?: return@withContext emptyMap()
            val q = normalized(raw.toFloatArray())
            buildMap {
                snapshot.forEach { (key, entry) ->
                    if (!key.startsWith(keyPrefix)) return@forEach
                    val score = dot(q, entry.second)
                    if (score >= minScore) put(key.removePrefix(keyPrefix), score)
                }
            }
        }

    private fun normalized(v: FloatArray): FloatArray {
        var sum = 0f
        for (x in v) sum += x * x
        val norm = kotlin.math.sqrt(sum)
        if (norm <= 0f) return v
        return FloatArray(v.size) { v[it] / norm }
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
