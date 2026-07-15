#!/usr/bin/env bash
#
# Leave this running overnight on a machine with a GPU. It trains the neural swipe model on the full
# real FUTO corpus (+ any swipes you've collected), on your GPU, then tells you whether the result
# beats the currently-shipped model — so you only ship a real improvement.
#
#   Apple-Silicon Mac : uses the GPU via Metal (MPS) automatically.
#   NVIDIA machine     : uses CUDA automatically.
#
# One-time setup:
#   python3 -m venv venv && source venv/bin/activate
#   pip install torch numpy onnx onnxruntime pyarrow huggingface_hub
#
# Add YOUR OWN captured swipes (collector web app export or GlideLearningStore pull) — they are
# OVERSAMPLED into training (~10% of samples) rather than drowned in the million-row corpus:
#   OWN="~/Downloads/teclas_swipes_*.jsonl" ./train_overnight.sh
#
# Run:
#   ./train_overnight.sh                 # sensible big config
#   STEPS=120000 LIMIT=800000 ./train_overnight.sh   # go bigger if you have time/VRAM
#
set -euo pipefail
cd "$(dirname "$0")"
export PYTORCH_ENABLE_MPS_FALLBACK=1   # let the few ops MPS lacks fall back to CPU instead of erroring

# Defaults are a strong run; override any of these for a bigger "max accuracy" pass, e.g.:
#   STEPS=200000 LIMIT=1000000 DMODEL=256 LAYERS=6 NHEAD=8 FF=512 ./train_overnight.sh
STEPS="${STEPS:-80000}"
LIMIT="${LIMIT:-500000}"     # real swipes loaded (RAM/VRAM bound; raise toward the full ~1M if you can)
BATCH="${BATCH:-256}"
DMODEL="${DMODEL:-192}"
LAYERS="${LAYERS:-4}"
NHEAD="${NHEAD:-6}"
FF="${FF:-384}"
SYNTH_MIX="${SYNTH_MIX:-0.25}"   # fraction of synthetic batches (long-tail vocab coverage)
OWN="${OWN:-}"                   # optional glob of your own collected swipes (jsonl)
DEV_EVERY=200                    # every Nth corpus line is held out for evaluation
OUT="model_candidate"

# 1. Get the real data (once).
if [ ! -f futo_data/train.jsonl ]; then
  echo "== downloading FUTO corpus (MIT, one-time) =="
  python futo_data.py --download
fi

# 2. Carve the held-out dev split (once) — training excludes these exact rows (--exclude-every),
#    so evaluate.py measures on swipes the model has never seen.
if [ ! -f futo_data/dev.jsonl ]; then
  echo "== creating held-out dev split (every ${DEV_EVERY}th swipe) =="
  python futo_data.py --make-dev futo_data/train.jsonl --dev-every "$DEV_EVERY"
fi

# 3. Train on the GPU: real swipes (minus the dev split) + synthetic long-tail mix (+ your own
#    swipes oversampled, if provided).
echo "== training: steps=$STEPS limit=$LIMIT batch=$BATCH synth-mix=$SYNTH_MIX own='${OWN}' =="
mkdir -p "$OUT"
python export_seq2seq.py \
  --wordlist ../../app/src/main/assets/dict/en_wordlist.txt \
  --futo futo_data/train.jsonl --futo-limit "$LIMIT" --exclude-every "$DEV_EVERY" \
  --synth-mix "$SYNTH_MIX" \
  ${OWN:+--own "$OWN"} \
  --steps "$STEPS" --batch "$BATCH" --d-model "$DMODEL" --nhead "$NHEAD" --layers "$LAYERS" --ff "$FF" \
  --out "$OUT"

# 4. Prove it beats what's shipped (held-out real swipes) BEFORE you ship it.
echo "== evaluating candidate vs the shipped model =="
python evaluate.py "$OUT" ../../app/src/main/assets

cat <<EOF

Done. If the candidate WINS above, ship it:
  cp $OUT/swipe_encoder.onnx $OUT/swipe_decoder.onnx ../../app/src/main/assets/
  # then rebuild + install the app
If it did not beat the baseline, keep the current model (or train longer with more STEPS).
EOF
