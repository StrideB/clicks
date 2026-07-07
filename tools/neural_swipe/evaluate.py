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


if __name__ == "__main__":
    main()
