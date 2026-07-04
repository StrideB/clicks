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
