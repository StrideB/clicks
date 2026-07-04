package com.fran.clicks.keyboard.neural

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.fran.clicks.keyboard.neural.NeuralSwipeContract.DECODER_ASSET
import com.fran.clicks.keyboard.neural.NeuralSwipeContract.ENCODER_ASSET
import com.fran.clicks.keyboard.neural.NeuralSwipeContract.FEATURE_DIM
import com.fran.clicks.keyboard.neural.NeuralSwipeContract.IN_FEATURES
import com.fran.clicks.keyboard.neural.NeuralSwipeContract.IN_MEM_MASK
import com.fran.clicks.keyboard.neural.NeuralSwipeContract.IN_MEMORY
import com.fran.clicks.keyboard.neural.NeuralSwipeContract.IN_SRC_MASK
import com.fran.clicks.keyboard.neural.NeuralSwipeContract.IN_TGT
import com.fran.clicks.keyboard.neural.NeuralSwipeContract.MAX_TRAJ
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Thin, host-agnostic wrapper over the two ONNX Runtime sessions (encoder + decoder), using the
 * XNNPACK execution provider for speed. Original glue — it only marshals tensors in/out per the
 * [NeuralSwipeContract]; the model weights and the beam search live elsewhere.
 *
 * Loading is safe with no model present: [load] simply returns false if the asset files are missing,
 * so shipping without a model keeps [isReady] false and every caller falls back to the existing
 * statistical/geometric decoder — no behavior change.
 *
 * All methods are blocking and must be called off the main thread (the facade does this via
 * coroutines on [kotlinx.coroutines.Dispatchers.Default]).
 */
class OnnxSwipeModel(private val context: Context) {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var encoder: OrtSession? = null
    private var decoder: OrtSession? = null

    val isReady: Boolean get() = encoder != null && decoder != null

    fun load(): Boolean {
        if (isReady) return true
        return runCatching {
            val assets = context.assets.list("")?.toSet() ?: emptySet()
            if (ENCODER_ASSET !in assets || DECODER_ASSET !in assets) return false
            val enc = env.createSession(context.assets.open(ENCODER_ASSET).readBytes(), buildOptions())
            val dec = env.createSession(context.assets.open(DECODER_ASSET).readBytes(), buildOptions())
            // Fail fast (and clearly) if the exported graph drifted from the contract.
            require(IN_FEATURES in enc.inputNames && IN_SRC_MASK in enc.inputNames) {
                "encoder inputs ${enc.inputNames} must contain [$IN_FEATURES, $IN_SRC_MASK]"
            }
            require(IN_MEMORY in dec.inputNames && IN_TGT in dec.inputNames) {
                "decoder inputs ${dec.inputNames} must contain [$IN_MEMORY, $IN_TGT]"
            }
            encoder = enc
            decoder = dec
            true
        }.getOrElse {
            Log.e(TAG, "neural swipe model load failed", it)
            close()
            false
        }
    }

    private fun buildOptions(): OrtSession.SessionOptions {
        val o = OrtSession.SessionOptions()
        o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        // Prefer XNNPACK; fall back to plain CPU if this build/device lacks the provider.
        runCatching {
            o.setIntraOpNumThreads(1)
            o.addXnnpack(mapOf("intra_op_num_threads" to "2"))
        }.onFailure {
            Log.w(TAG, "XNNPACK unavailable, using default CPU EP: ${it.message}")
            runCatching { o.setIntraOpNumThreads(2) }
        }
        return o
    }

    /** Encoder memory tiled across [beam] rows, plus the tiled padding mask. Close when decoding ends. */
    class Memory(
        val memTensor: OnnxTensor,
        val maskTensor: OnnxTensor,
        val beam: Int,
        val dModel: Int
    ) {
        fun close() {
            runCatching { memTensor.close() }
            runCatching { maskTensor.close() }
        }
    }

    /**
     * Runs the encoder once (batch 1) and tiles its memory across [beam] rows so the decoder can be
     * driven as a single batched call per step during beam search.
     */
    fun encode(features: FloatArray, mask: LongArray, beam: Int): Memory? {
        val enc = encoder ?: return null
        val t = MAX_TRAJ
        val featT = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(features), longArrayOf(1, t.toLong(), FEATURE_DIM.toLong())
        )
        val maskT = OnnxTensor.createTensor(env, LongBuffer.wrap(mask), longArrayOf(1, t.toLong()))
        val memFlat: FloatArray
        val d: Int
        try {
            enc.run(mapOf(IN_FEATURES to featT, IN_SRC_MASK to maskT)).use { res ->
                @Suppress("UNCHECKED_CAST")
                val mem = res[0].value as Array<Array<FloatArray>>   // [1, T, D]
                d = mem[0][0].size
                memFlat = FloatArray(t * d)
                for (step in 0 until t) System.arraycopy(mem[0][step], 0, memFlat, step * d, d)
            }
        } finally {
            featT.close(); maskT.close()
        }

        val tiledMem = FloatArray(beam * t * d)
        val tiledMask = LongArray(beam * t)
        for (b in 0 until beam) {
            System.arraycopy(memFlat, 0, tiledMem, b * t * d, t * d)
            System.arraycopy(mask, 0, tiledMask, b * t, t)
        }
        val memTensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(tiledMem), longArrayOf(beam.toLong(), t.toLong(), d.toLong())
        )
        val maskTensor = OnnxTensor.createTensor(
            env, LongBuffer.wrap(tiledMask), longArrayOf(beam.toLong(), t.toLong())
        )
        return Memory(memTensor, maskTensor, beam, d)
    }

    /**
     * One decoder step over all [Memory.beam] rows. [tgtFlat] is row-major [beam, len]; returns the
     * last-position logits for each beam row, shape [beam][VOCAB_SIZE].
     */
    fun decodeStep(mem: Memory, tgtFlat: LongArray, len: Int): Array<FloatArray>? {
        val dec = decoder ?: return null
        val b = mem.beam
        val tgtT = OnnxTensor.createTensor(env, LongBuffer.wrap(tgtFlat), longArrayOf(b.toLong(), len.toLong()))
        try {
            dec.run(mapOf(IN_MEMORY to mem.memTensor, IN_MEM_MASK to mem.maskTensor, IN_TGT to tgtT)).use { res ->
                @Suppress("UNCHECKED_CAST")
                val logits = res[0].value as Array<Array<FloatArray>>   // [B, L, V]
                return Array(b) { row -> logits[row][len - 1] }
            }
        } finally {
            tgtT.close()
        }
    }

    fun close() {
        runCatching { encoder?.close() }
        runCatching { decoder?.close() }
        encoder = null
        decoder = null
    }

    private companion object {
        const val TAG = "NeuralSwipe"
    }
}
