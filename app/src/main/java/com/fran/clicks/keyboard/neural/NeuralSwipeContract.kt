package com.fran.clicks.keyboard.neural

/**
 * The single source of truth for the on-device neural glide model's I/O contract.
 *
 * This is an ORIGINAL, self-defined contract (not copied from any GPL / Source-First project). The
 * PyTorch → ONNX export in `tools/neural_swipe/` MUST match these names, shapes, dtypes, and the
 * feature/character layout below exactly, or inference will fail fast at load time (see
 * [OnnxSwipeModel]). Treat this file and the exporter as two ends of one wire — change both together.
 *
 * Architecture: encoder-decoder transformer.
 *   encoder(features, src_mask) -> memory                 (run once, batch 1)
 *   decoder(memory, src_mask, tgt) -> logits              (autoregressive; batch 1 per beam)
 *
 * The decoder runs at batch 1, once per beam per step — NOT batched across beams. This is
 * intentional: the transformer ONNX exporters constant-fold the batch axis, so a fixed batch of 1
 * (only the sequence length dynamic) is what exports and runs reliably. Beam width stays a free
 * runtime loop count.
 *
 * Everything is normalized/tokenized on the device so a single trained model works identically in
 * both the Docked (IME) and Widget (launcher) placements regardless of on-screen keyboard size.
 */
object NeuralSwipeContract {

    // ── Asset file names (place these in app/src/main/assets/) ──
    const val ENCODER_ASSET = "swipe_encoder.onnx"
    const val DECODER_ASSET = "swipe_decoder.onnx"

    // ── Encoder tensor names ──
    const val IN_FEATURES = "features"   // float32 [1, T, FEATURE_DIM]
    const val IN_SRC_MASK = "src_mask"   // int64   [1, T]  (1 = real point, 0 = padding)
    // Encoder output read positionally (index 0): "memory" float32 [1, T, D]

    // ── Decoder tensor names ── (all at batch 1; only L is dynamic)
    const val IN_MEMORY = "memory"       // float32 [1, T, D]  (encoder memory)
    const val IN_MEM_MASK = "src_mask"   // int64   [1, T]     (same padding mask)
    const val IN_TGT = "tgt"             // int64   [1, L]     (tokens generated so far)
    // Decoder output read positionally (index 0): "logits" float32 [1, L, VOCAB_SIZE]

    // ── Trajectory / feature layout ──
    /** Max trajectory steps fed to the encoder; longer paths are uniformly subsampled, shorter padded. */
    const val MAX_TRAJ = 200

    /**
     * Per-step feature vector, in this exact order:
     *   [0] x            normalized to [0,1] across the letter-key bounding box
     *   [1] y            normalized to [0,1]
     *   [2] vx           d(x)/dt   (normalized units per second)
     *   [3] vy           d(y)/dt
     *   [4] ax           d(vx)/dt  (normalized units per second^2)
     *   [5] ay           d(vy)/dt
     *   [6 .. 6+25] nearest-key one-hot over 'a'..'z' (1.0 on the letter whose center is closest)
     */
    const val BASE_FEATURES = 6
    const val KEY_COUNT = 26                     // 'a'..'z'
    const val FEATURE_DIM = BASE_FEATURES + KEY_COUNT   // 32

    // ── Character vocabulary for the decoder (index == logit column) ──
    // 0 = <pad>, 1 = <sos>, 2 = <eos>, 3..28 = 'a'..'z'
    const val PAD = 0
    const val SOS = 1
    const val EOS = 2
    const val FIRST_LETTER = 3
    const val VOCAB_SIZE = FIRST_LETTER + KEY_COUNT   // 29

    /** Token id for a lowercase letter, or -1 if not 'a'..'z'. */
    fun tokenFor(c: Char): Int {
        val lc = c.lowercaseChar()
        return if (lc in 'a'..'z') FIRST_LETTER + (lc - 'a') else -1
    }

    /** Inverse of [tokenFor]: letter for a token id, or null for special tokens. */
    fun letterFor(token: Int): Char? =
        if (token in FIRST_LETTER until VOCAB_SIZE) ('a' + (token - FIRST_LETTER)) else null

    // ── Decoding defaults (tunable at call sites) ──
    const val DEFAULT_BEAM_WIDTH = 8
    const val DEFAULT_TOP_K = 5
    const val MAX_DECODE_LEN = 24        // longest word the decoder will emit
}

/** A raw captured touch sample: screen-space position plus its event timestamp (ms). */
data class TimedPoint(val x: Float, val y: Float, val t: Long)

/** A decoded word with its combined model+frequency score (higher is better). */
data class ScoredWord(val word: String, val score: Float)
