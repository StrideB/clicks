#!/usr/bin/env bash
#
# Unattended, self-improving training loop. Leave it running (e.g. on a 4060 for a few days). Each
# round it trains the swipe model a bit further, then evaluates. It only ever PROMOTES a model to the
# champion slot when that model:
#   1. beats the currently-shipped model on held-out REAL swipes ("candidate WINS"),
#   2. passes the DEVICE-SPACE GATE (decodes swipes in the device's own coordinate space),
#   3. passes the TAP-SPACE GATE  (decodes discrete taps — tap-aware),
#   4. AND scores higher (held-out top-1) than any previous champion this run.
# So the champion in runs/champion/ is always the single best *shippable* model found — overtraining
# or a bad round can never replace a good champion. Come back, check STATUS.txt, ship the champion.
#
# Resilient: the model checkpoints every 2000 steps and the step target is persisted, so a crash or
# reboot loses at most ~2000 steps — just re-run this same script and it continues.
#
# Run (from tools/neural_swipe, venv active):
#   ./train_forever.sh
# Tuning (all optional):
#   STEP_INCREMENT=40000   # steps to train each round (default 40000)
#   MAX_HOURS=72           # stop after this many hours (default 72 = ~3 days)
#   TAP_MIX=0.35           # fraction of tap-trace batches (tap-awareness)
#   LIMIT=1000000          # real swipes loaded (raise toward full corpus if RAM allows)
#   OWN="~/Downloads/teclas_swipes_*.jsonl"   # your own collected swipes, oversampled ~10%
# Stop early: create a file named STOP in this folder (`touch STOP`), or Ctrl-C.
#
set -uo pipefail            # NOT -e: one bad round must log and continue, never kill the loop
cd "$(dirname "$0")"
export PYTORCH_ENABLE_MPS_FALLBACK=1

LIMIT="${LIMIT:-1000000}"
BATCH="${BATCH:-256}"
DMODEL="${DMODEL:-192}"; LAYERS="${LAYERS:-4}"; NHEAD="${NHEAD:-6}"; FF="${FF:-384}"
SYNTH_MIX="${SYNTH_MIX:-0.25}"; TAP_MIX="${TAP_MIX:-0.35}"
STEP_INCREMENT="${STEP_INCREMENT:-40000}"
MAX_HOURS="${MAX_HOURS:-72}"
OWN="${OWN:-}"
DEV_EVERY=200
OUT="model_candidate"
LOG="train_forever.log"
STATUS="STATUS.txt"

start=$(date +%s)
log(){ echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*" | tee -a "$LOG"; }

# 1. Data (once).
[ -f futo_data/train.jsonl ] || { log "downloading FUTO corpus (one-time)"; python futo_data.py --download 2>&1 | tee -a "$LOG"; }
[ -f futo_data/dev.jsonl ]   || { log "carving held-out dev split (one-time)"; python futo_data.py --make-dev futo_data/train.jsonl --dev-every "$DEV_EVERY" 2>&1 | tee -a "$LOG"; }

mkdir -p "$OUT" runs runs/champion
# Resume state across reboots.
target=0;      [ -f "$OUT/.target" ]        && target=$(cat "$OUT/.target")
best_top1=0.0; [ -f runs/champion/.top1 ]   && best_top1=$(cat runs/champion/.top1)

log "=== train_forever start: increment=$STEP_INCREMENT max_hours=$MAX_HOURS tap_mix=$TAP_MIX limit=$LIMIT resume_target=$target best_top1=$best_top1 ==="

round=0
while :; do
  elapsed_h=$(( ( $(date +%s) - start ) / 3600 ))
  [ "$elapsed_h" -ge "$MAX_HOURS" ] && { log "reached MAX_HOURS=$MAX_HOURS — stopping."; break; }
  [ -f STOP ] && { log "STOP file found — stopping."; rm -f STOP; break; }

  round=$((round+1))
  target=$((target + STEP_INCREMENT))
  echo "$target" > "$OUT/.target"
  log "--- round $round: train to $target steps (elapsed ${elapsed_h}h, best_top1=$best_top1) ---"

  python export_seq2seq.py \
    --wordlist ../../app/src/main/assets/dict/en_wordlist.txt \
    --futo futo_data/train.jsonl --futo-limit "$LIMIT" --exclude-every "$DEV_EVERY" \
    --synth-mix "$SYNTH_MIX" --tap-mix "$TAP_MIX" \
    ${OWN:+--own "$OWN"} \
    --steps "$target" --batch "$BATCH" --d-model "$DMODEL" --nhead "$NHEAD" --layers "$LAYERS" --ff "$FF" \
    --checkpoint-every 2000 --resume \
    --out "$OUT" 2>&1 | tee -a "$LOG"

  evalout=$(python evaluate.py "$OUT" ../../app/src/main/assets 2>&1)
  echo "$evalout" | tee -a "$LOG"

  cand_top1=$(echo "$evalout" | grep -E 'candidate .*: top-1' | grep -oE 'top-1 [0-9.]+' | grep -oE '[0-9.]+' | head -1)
  wins=$(echo   "$evalout" | grep -c 'candidate WINS')
  dgate=$(echo  "$evalout" | grep -c 'DEVICE-SPACE GATE: PASS')
  tgate=$(echo  "$evalout" | grep -c 'TAP-SPACE GATE: PASS')
  cand_top1="${cand_top1:-0}"
  log "round $round: top1=${cand_top1}%  wins=$wins  device_gate=$dgate  tap_gate=$tgate"

  if [ "$wins" -ge 1 ] && [ "$dgate" -ge 1 ] && [ "$tgate" -ge 1 ]; then
    better=$(python -c "print(1 if float('$cand_top1') > float('$best_top1') else 0)" 2>/dev/null || echo 0)
    if [ "$better" = "1" ]; then
      best_top1="$cand_top1"
      cp "$OUT/swipe_encoder.onnx" "$OUT/swipe_decoder.onnx" runs/champion/
      echo "$best_top1" > runs/champion/.top1
      echo "$evalout"   > runs/champion/eval.txt
      log "*** NEW CHAMPION: top-1 ${best_top1}% at $target steps — archived to runs/champion/ ***"
    else
      log "passes all gates but not better than champion (${best_top1}%) — keep training."
    fi
  else
    log "round $round did not pass all ship checks — keep training (champion unchanged)."
  fi

  {
    echo "Teclas swipe training — STATUS"
    echo "updated:            $(date)"
    echo "elapsed:            ${elapsed_h}h / ${MAX_HOURS}h"
    echo "rounds completed:   $round"
    echo "current step target:$target"
    echo "best shippable top-1: ${best_top1}%"
    echo ""
    if python -c "exit(0 if float('$best_top1')>0 else 1)" 2>/dev/null; then
      echo "SHIP-READY: yes — models in runs/champion/ (passed WINS + both gates)."
      echo "To ship:  copy runs\\champion\\swipe_encoder.onnx runs\\champion\\swipe_decoder.onnx ..\\..\\app\\src\\main\\assets\\"
    else
      echo "SHIP-READY: not yet — no round has passed all three checks. Still training."
    fi
  } > "$STATUS"
done

# Next-word LM (Stage 2b) — fast; refresh it at the end so both models are current.
log "=== training next-word LM (Stage 2b) ==="
python ../lm/train_nextword.py --wordlist ../../app/src/main/assets/dict/en_wordlist.txt \
  --futo futo_data/train.jsonl --out ../../app/src/main/assets/dict 2>&1 | tee -a "$LOG"

log "=== DONE. Best shippable swipe model: top-1 ${best_top1}% in runs/champion/. See $STATUS. ==="
