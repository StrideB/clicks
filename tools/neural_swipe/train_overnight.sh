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
# Add YOUR OWN captured swipes (from the collector web app) before training — they personalize it:
#   cat ~/Downloads/clicks_swipes_*.jsonl >> futo_data/train.jsonl
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
OUT="model_candidate"

# 1. Get the real data (once).
if [ ! -f futo_data/train.jsonl ]; then
  echo "== downloading FUTO corpus (MIT, one-time) =="
  python futo_data.py --download
fi

# 2. Train on the GPU. Bigger than the CPU proof run (d_model 192, 4 layers).
echo "== training on real swipes: steps=$STEPS limit=$LIMIT batch=$BATCH =="
mkdir -p "$OUT"
python export_seq2seq.py \
  --wordlist ../../app/src/main/assets/dict/en_wordlist.txt \
  --futo futo_data/train.jsonl --futo-limit "$LIMIT" \
  --steps "$STEPS" --batch "$BATCH" --d-model "$DMODEL" --nhead "$NHEAD" --layers "$LAYERS" --ff "$FF" \
  --out "$OUT"

# 3. Prove it beats what's shipped (held-out real swipes) BEFORE you ship it.
echo "== evaluating candidate vs the shipped model =="
python evaluate.py "$OUT" ../../app/src/main/assets

cat <<EOF

Done. If the candidate WINS above, ship it:
  cp $OUT/swipe_encoder.onnx $OUT/swipe_decoder.onnx ../../app/src/main/assets/
  # then rebuild + install the app
If it did not beat the baseline, keep the current model (or train longer with more STEPS).
EOF
