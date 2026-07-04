# Neural Swipe — training pipeline (Track B)

Trains the on-device neural glide decoder for `clicks`. The device side
(`NeuralSwipeEngine.kt`) is already wired and falls back to the geometric decoder until the two
output files below exist in `app/src/main/assets/`.

## Why synthetic data
There is no large, permissively-licensed **English QWERTY** swipe corpus with real coordinates.
The public sets are the wrong layout/language (Yandex Neuroswipe = Russian) or already synthetic
(IndicSwipe). So we generate realistic gestures from *this app's* lexicon and key geometry, with a
human-motor-noise model (corner-cutting, anchor jitter, endpoint slop, tremor). Realism is what
makes the model beat the geometric decoder — tune the noise in `synth.py` if accuracy plateaus.

## Files
- `layout.py` — QWERTY key centers in the same `[0,1]` box the device normalizes into.
- `synth.py` — motor-noise gesture generator. Featurization (arc-length resample to 50, `[x,y,dx,dy]`)
  is **identical** to `NeuralSwipeEngine.kt`; keep them in sync.
- `train.py` — Transformer encoder → mean-pool → word head; trains on-the-fly synthetic data and
  exports `neural_swipe_engine.onnx` + `neural_swipe_vocab.txt`.

## Run (GPU recommended)
```bash
pip install torch numpy
python train.py \
  --wordlist ../../app/src/main/assets/dict/en_wordlist.txt \
  --vocab-size 12000 --samples-per-word 60 --epochs 6 \
  --out ../../app/src/main/assets
```
Then rebuild the app — `NeuralSwipeEngine.isReady` flips true automatically and glide decoding
routes through the model (with the statistical decoder still as fallback).

## Contract with the device (do not drift)
| | value |
|---|---|
| input name | `src` |
| input shape | `[1, 50, 4]` = `[x, y, dx, dy]`, arc-length resampled, normalized `[0,1]` |
| output | word logits `[1, vocab]` |
| vocab file | one word per line; **line index == logit index** |

## Tuning for reliability
- More `--samples-per-word` and more noise variety > a bigger network.
- Multi-language: pass a merged wordlist (or train per-language models and pick by active dict).
- Validate cold-start load time on a low-end device; drop `d_model`/`num_layers` if needed.
- Consider training on a **real** dataset later (collect accepted glides on-device) to fine-tune.

---

# Track C — encoder-decoder + beam search (`export_seq2seq.py`)

This is the newer engine consumed by `app/src/main/java/com/fran/clicks/keyboard/neural/`
(`NeuralGlideEngine` and friends). It is an **encoder-decoder** transformer that emits the word one
character at a time, decoded on-device with a **dictionary-constrained beam search** — a different,
richer contract than Track B's single-shot word classifier. Both engines coexist; the device prefers
this one when its model files are present and the `kbd_neural_glide` preference is on (default on).

## What to obtain / produce
There is **no drop-in, verified permissively-licensed pre-trained model** to point you at (the
CleverKeys reference implementation is GPL-3.0 and its weights' license is unclear — do not vendor
it). So produce your own from the MIT-licensed lexicon/corpus:

```bash
pip install torch numpy onnx
python export_seq2seq.py \
  --wordlist ../../app/src/main/assets/dict/en_wordlist.txt \
  --vocab-size 20000 --steps 4000 --batch 128 \
  --out ../../app/src/main/assets
```

This writes exactly two files into `app/src/main/assets/`:

| file | what |
|---|---|
| `swipe_encoder.onnx` | `encoder(features, src_mask) -> memory` |
| `swipe_decoder.onnx` | `decoder(memory, src_mask, tgt) -> logits` (autoregressive) |

Rebuild the app — `NeuralGlideEngine.isReady` flips true automatically and glide routes through the
neural decoder, with the statistical decoder still the fallback. No app code changes are needed.

To train on FUTO's MIT swipe corpus instead of synthetic data, replace `iter_batch()` with a loader
that yields the same `(features, src_mask, tgt)` tensors; the model and export are unchanged.

## Contract with the device (`NeuralSwipeContract.kt` — do not drift)
| | encoder | decoder |
|---|---|---|
| input `features` | float32 `[1, 200, 32]` | — |
| input `src_mask` | int64 `[1, 200]` (1=real, 0=pad) | int64 `[B, 200]` |
| input `memory` | — | float32 `[B, 200, D]` |
| input `tgt` | — | int64 `[B, L]` |
| output | `memory` float32 `[1, 200, D]` | `logits` float32 `[B, L, 29]` |

- **Per-step feature vector (32):** `[x, y, vx, vy, ax, ay,` then `26-dim nearest-key one-hot]`.
  `x,y` normalized to `[0,1]` over the letter-key box; `v`/`a` are per-second derivatives from real
  event timestamps. The math lives in `SwipeFeaturizer.kt` and is mirrored in
  `features_from_path()` here — keep them identical.
- **Character vocab (29):** `0=<pad> 1=<sos> 2=<eos> 3..28='a'..'z'` (index == logit column).
- Batch dim `B` is dynamic (encoder runs at B=1; decoder at B=beamWidth). Trajectory length is fixed
  at `MAX_TRAJ=200`; decoder length `L` is dynamic.

## Tuning
- The synthetic `dt` range (6–18 ms/sample) sets the velocity scale the model learns. If on-device
  predictions feel off, widen it or fine-tune on real captured glides.
- Beam width is tunable at runtime (`NeuralGlideEngine.beamWidth`, default 8); higher = more
  accurate, slower. Validate the sub-200 ms budget on a low-end device and drop `d_model`/`layers`
  if needed (defaults `d_model=128, layers=3` export to roughly a few-MB pair).
