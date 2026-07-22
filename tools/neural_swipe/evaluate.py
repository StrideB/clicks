"""
Beam-search accuracy of a swipe model on HELD-OUT REAL swipes (FUTO dev split).

    python evaluate.py <model_dir> [<baseline_dir>]

Reports top-1 / top-3 exact-match. With a second dir, prints both side by side so you can tell
whether a freshly trained candidate actually beats the shipped model before copying it into assets.
Uses dev.jsonl (never the train split), so it's a fair test. Requires: onnxruntime, numpy.
"""
import sys, os, json, numpy as np, onnxruntime as ort
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import export_seq2seq as M
import futo_data as F

WORDLIST = os.path.join(os.path.dirname(__file__), "../../app/src/main/assets/dict/en_wordlist.txt")
DEV = os.path.join(os.path.dirname(__file__), "futo_data/dev.jsonl")


def _prep():
    words = M.load_words(WORDLIST, 15000)
    wordset = set(words)
    pref = set()
    for w in words:
        for i in range(1, len(w) + 1):
            pref.add(w[:i])
    return wordset, pref


def _lsm(x):
    x = x - x.max(); e = np.exp(x); return np.log(e / e.sum())


def beam(enc, dec, f, m, wordset, pref, bw=8, ml=24, topk=3):
    mem = enc.run(None, {"features": f[None], "src_mask": m[None].astype(np.int64)})[0]
    beams = [([M.SOS], "", 0.0)]; fin = []
    for _ in range(ml):
        if not beams: break
        nx = []
        for tk, let, sc in beams:
            lg = dec.run(None, {"memory": mem, "src_mask": m[None].astype(np.int64),
                                "tgt": np.array([tk], np.int64)})[0][0, -1]
            lp = _lsm(lg)
            if let and let in wordset:
                fin.append((let, (sc + lp[M.EOS]) / len(let)))
            for li in range(26):
                if (let + chr(97 + li)) in pref:
                    nx.append((tk + [M.FIRST_LETTER + li], let + chr(97 + li), sc + lp[M.FIRST_LETTER + li]))
        nx.sort(key=lambda x: -x[2]); beams = nx[:bw]
    best = {}
    for w, s in fin:
        if w not in best or s > best[w]: best[w] = s
    return [w for w, _ in sorted(best.items(), key=lambda x: -x[1])[:topk]]


def load(d):
    return (ort.InferenceSession(os.path.join(d, "swipe_encoder.onnx")),
            ort.InferenceSession(os.path.join(d, "swipe_decoder.onnx")))


# Words for the device-space gate: common vocabulary weighted toward bottom-row letters
# (z x c v b n m) — the row the "google"→"good" miscalibration silently destroyed.
GATE_WORDS = [
    "google", "back", "become", "number", "move", "name", "change", "much", "black",
    "common", "member", "voice", "magic", "begin", "chance", "zone", "zero", "crazy",
    "exact", "every", "never", "women", "money", "combine", "minimum", "maximum",
    "there", "water", "people", "think", "right", "house", "world", "great", "should", "little",
]


def _space_gate(model_dir, wordset, pref, path_fn, per_word, min_top1, min_top3, seed):
    """Decode SYNTHETIC gestures generated DIRECTLY in device key-box space — no affine, no FUTO
    coordinates anywhere. [path_fn](word, rng) picks the gesture shape (swipe vs discrete tap)."""
    enc, dec = load(model_dir)
    words = [w for w in GATE_WORDS if w in wordset]
    rng = np.random.default_rng(seed)
    t1 = t3 = n = 0
    misses = []
    for w in words:
        for _ in range(per_word):
            path, times = path_fn(w, rng)
            if path is None:
                continue
            f, m = M.features_from_path(path, times)
            cand = beam(enc, dec, f, m, wordset, pref)
            n += 1
            if cand[:1] == [w]:
                t1 += 1
            if w in cand:
                t3 += 1
            elif len(misses) < 10:
                misses.append(f"{w}→{cand[0] if cand else '?'}")
    r1, r3 = (t1 / n, t3 / n) if n else (0.0, 0.0)
    ok = n >= 20 and r1 >= min_top1 and r3 >= min_top3
    return ok, r1, r3, n, misses


def device_gate(model_dir, wordset, pref, per_word=2, min_top1=0.70, min_top3=0.85):
    """
    Gate #2: SWIPE decode in device key-box space. This is the check the dev-split eval structurally
    cannot do: train and dev share the canvas→key-box mapping, so a misfit mapping scores 95%+ there
    while the model misdecodes every real device swipe (the shipped-then-reverted 'google'→'good'
    bug). A model that can't read clean device-space swipes must never ship.
    """
    ok, r1, r3, n, misses = _space_gate(
        model_dir, wordset, pref, M.synth_path, per_word, min_top1, min_top3, seed=7)
    print(f"  device-space synthetic decode: top-1 {100 * r1:.1f}%  top-3 {100 * r3:.1f}%  ({n} swipes)")
    if misses:
        print(f"    top-3 misses: {', '.join(misses)}")
    if ok:
        print("  DEVICE-SPACE GATE: PASS")
    else:
        print("  DEVICE-SPACE GATE: FAIL — model cannot decode swipes in the device's own coordinate "
              "space. DO NOT SHIP: this is the calibration-misfit failure mode the dev-split eval "
              "cannot see (it evaluates in the same, possibly wrong, mapped space).")
    return ok


def tap_gate(model_dir, wordset, pref, per_word=3, min_top1=0.65, min_top3=0.82):
    """
    Gate #3 (Stage 1b): decode SYNTHETIC discrete TAP traces in device key-box space — one point
    per letter at tap cadence, the shape a *tap* typist produces. A swipe-only model is
    out-of-distribution here and will FAIL; a tap-aware model (trained with --tap-mix) must decode
    taps as well as swipes before it ships, since tapping is the primary on-device input path.
    """
    ok, r1, r3, n, misses = _space_gate(
        model_dir, wordset, pref, M.synth_tap_path, per_word, min_top1, min_top3, seed=11)
    print(f"  device-space synthetic TAP decode: top-1 {100 * r1:.1f}%  top-3 {100 * r3:.1f}%  ({n} taps)")
    if misses:
        print(f"    top-3 misses: {', '.join(misses)}")
    if ok:
        print("  TAP-SPACE GATE: PASS")
    else:
        print("  TAP-SPACE GATE: FAIL — model cannot decode discrete tap traces. A tap-aware model "
              "(trained with --tap-mix) must pass this; a swipe-only model is expected to fail it and "
              "should not be shipped as the on-device tap decoder.")
    return ok


def score(model_dir, rows, aff, wordset, pref):
    enc, dec = load(model_dir)
    t1 = t3 = n = 0
    for r in rows:
        s = F.row_to_sample(r, aff)
        if s is None: continue
        f, m, _ = s; w = r["word"].lower(); n += 1
        cand = beam(enc, dec, f, m, wordset, pref)
        t1 += 1 if cand[:1] == [w] else 0
        t3 += 1 if w in cand else 0
    return 100 * t1 / n, 100 * t3 / n, n


def main():
    if len(sys.argv) < 2:
        print(__doc__); return
    wordset, pref = _prep()
    rows = []
    with open(DEV) as fh:
        for line in fh:
            if len(rows) >= 1500: break
            r = json.loads(line)
            if not r.get("potentially_invalid_sentence") and r.get("word", "").isalpha() \
                    and r["word"].lower() in wordset:
                rows.append(r)
    aff = F.calibrate_affine(rows)
    print(f"held-out real swipes: {len(rows)}")
    c1, c3, n = score(sys.argv[1], rows, aff, wordset, pref)
    print(f"  candidate {sys.argv[1]}: top-1 {c1:.1f}%  top-3 {c3:.1f}%")
    if len(sys.argv) > 2:
        b1, b3, _ = score(sys.argv[2], rows, aff, wordset, pref)
        print(f"  baseline  {sys.argv[2]}: top-1 {b1:.1f}%  top-3 {b3:.1f}%")
        print(f"  => candidate {'WINS' if c1 > b1 else 'does NOT beat baseline'} (top-1 {c1 - b1:+.1f} pts)")
    # Shipping requires ALL: beating the baseline above AND passing both device-space gates below
    # (swipe decode, and — for a tap-aware Stage 1b model — tap decode).
    device_gate(sys.argv[1], wordset, pref)
    tap_gate(sys.argv[1], wordset, pref)


if __name__ == "__main__":
    main()
