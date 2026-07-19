"""
FUTO swipe-dataset loader — train the neural swipe model on REAL human swipes instead of synthetic.

The FUTO corpus (https://huggingface.co/datasets/futo-org/swipe.futo.org, MIT-licensed, ~1M real
English/QWERTY swipes) is the missing piece: our architecture and export are done, but the model is
trained on synthetic gestures, which is why it's weak on real-finger swipes (double letters). Point
this loader at the FUTO parquet files and it yields the exact same (features, src_mask, tgt) tensors
`export_seq2seq.py` already trains on — so training on real data is a drop-in swap for the synthetic
`iter_batch()`.

FUTO row schema (verified against the dataset viewer):
    word            target word (may be capitalized)
    canvas_width/height   keyboard aspect (x,y are already normalized to [0,1] over the canvas)
    data            list of {t (ms), x in [0,1], y in [0,1]}
    orientation, sentence, potentially_invalid_sentence, distance, ...

Coordinate alignment: FUTO normalizes to the full keyboard CANVAS; our device (and `layout.py`)
normalize to the letter-KEY-BOX. Those differ by an axis-aligned affine (the key box is a sub-rect of
the canvas). We fit that affine ONCE by regression — a swipe's first/last points should land on the
first/last letter's key center — then reuse the identical feature math as the device
(`export_seq2seq.features_from_path`), so training and on-device inference see the same inputs.

Usage (once you have GPU time) — or just run ../train_overnight.sh, which does all of this:
    pip install torch numpy onnx onnxscript huggingface_hub
    python futo_data.py --download                        # fetch the JSONL corpus (once)
    python futo_data.py --make-dev futo_data/train.jsonl  # carve held-out dev.jsonl (once)
    python export_seq2seq.py --futo futo_data/train.jsonl --exclude-every 200 \
        --synth-mix 0.25 --own "my_swipes_*.jsonl" ...    # real + synthetic + your own swipes

Self-test (no network):  python futo_data.py --selftest
"""

import argparse
import glob
import os
import numpy as np

import export_seq2seq as M
from layout import KEY_CENTERS


# ─────────────────────────── coordinate calibration ───────────────────────────

class Affine:
    """Axis-aligned canvas→key-box map: box = scale * canvas + offset, per axis."""
    def __init__(self, sx, ox, sy, oy):
        self.sx, self.ox, self.sy, self.oy = sx, ox, sy, oy

    def apply(self, xy):
        out = np.empty_like(xy, dtype=np.float32)
        out[:, 0] = self.sx * xy[:, 0] + self.ox
        out[:, 1] = self.sy * xy[:, 1] + self.oy
        return out

    def __repr__(self):
        return f"Affine(x={self.sx:.3f}*c+{self.ox:.3f}, y={self.sy:.3f}*c+{self.oy:.3f})"


def calibrate_affine(rows, max_rows=40000):
    """
    Fit the canvas→key-box affine from PER-LETTER MEDIANS: group every swipe's first point by its
    first letter (and last point by last letter), take the median canvas position of each group,
    and least-squares fit those ~26-52 anchor medians onto the letters' key centers.

    Why medians per letter, not raw endpoint regression (the old approach): endpoints carry
    corner-cutting and slop that BIAS a raw fit — the y-scale came out wrong, bottom-row letters
    mapped onto the middle row, and models decoded 'google' as 'good' on-device while evaluating
    at 95%+ in their own (identically misfit) space. Medians cancel that noise, and the residuals
    of the fit become a checkable quantity: [check_affine] refuses a fit whose anchors don't land
    on their keys, so a miscalibrated pipeline now fails loudly instead of training confidently.
    """
    from collections import defaultdict
    first, last = defaultdict(list), defaultdict(list)
    for r in rows[:max_rows]:
        w = (r.get("word") or "").lower()
        data = r.get("data") or []
        if len(data) < 2 or not w:
            continue
        if w[0] in KEY_CENTERS:
            first[w[0]].append((data[0]["x"], data[0]["y"]))
        if w[-1] in KEY_CENTERS:
            last[w[-1]].append((data[-1]["x"], data[-1]["y"]))
    cx, tx, cy, ty = [], [], [], []
    for grp in (first, last):
        for ch, pts in grp.items():
            if len(pts) < 3:
                continue
            xs = sorted(p[0] for p in pts); ys = sorted(p[1] for p in pts)
            k = KEY_CENTERS[ch]
            cx.append(xs[len(xs) // 2]); tx.append(k[0])
            cy.append(ys[len(ys) // 2]); ty.append(k[1])
    if len(cx) < 10:
        # Not enough signal — fall back to a sensible default (letters span full width, upper ~70%).
        return Affine(1.0, 0.0, 1.0 / 0.7, 0.0)
    sx, ox = np.polyfit(np.asarray(cx), np.asarray(tx), 1)
    sy, oy = np.polyfit(np.asarray(cy), np.asarray(ty), 1)
    aff = Affine(float(sx), float(ox), float(sy), float(oy))
    aff.residual = (
        float(np.mean(np.abs(np.asarray(cx) * sx + ox - np.asarray(tx)))),
        float(np.mean(np.abs(np.asarray(cy) * sy + oy - np.asarray(ty)))),
    )
    return aff


def check_affine(aff, key_w, key_h, max_frac=0.35):
    """Device-space gate #1: the calibration's anchor residuals must land within [max_frac] of a
    key's size on both axes. Raises with a clear message otherwise — do NOT train through this."""
    res = getattr(aff, "residual", None)
    if res is None:
        return
    rx, ry = res
    if rx > key_w * max_frac or ry > key_h * max_frac:
        raise RuntimeError(
            f"CALIBRATION GATE FAIL: per-letter anchors miss their keys "
            f"(x residual {rx:.3f} vs key_w {key_w:.3f}, y residual {ry:.3f} vs key_h {key_h:.3f}). "
            f"The canvas->key-box mapping is wrong; a model trained through it will misdecode on-device."
        )


# ─────────────────────────── row → training sample ───────────────────────────

def row_to_sample(row, affine):
    """FUTO row → (features [MAX_TRAJ, FEATURE_DIM], mask [MAX_TRAJ], tgt tokens) or None if unusable."""
    word = (row.get("word") or "").lower()
    toks = M.word_tokens(word)
    if toks is None or not (1 <= len(word) <= M.MAX_DECODE_LEN - 2):
        return None
    data = row.get("data") or []
    if len(data) < 2:
        return None
    xy = np.array([[p["x"], p["y"]] for p in data], dtype=np.float32)
    xy = np.clip(affine.apply(xy), 0.0, 1.0)              # canvas → key-box, matches the device
    t0 = data[0]["t"]
    times = np.array([p["t"] - t0 for p in data], dtype=np.float32)
    # Cap length like the device (uniform subsample keeping endpoints).
    if len(xy) > M.MAX_TRAJ:
        idx = np.round(np.linspace(0, len(xy) - 1, M.MAX_TRAJ)).astype(int)
        xy, times = xy[idx], times[idx]
    feats, mask = M.features_from_path(xy, times)          # IDENTICAL math to SwipeFeaturizer.kt
    return feats, mask, toks


def iter_futo_batch(rows, rng, batch, calibrate=True, affine=None, own_rows=None, own_prob=0.0):
    """Drop-in replacement for export_seq2seq.iter_batch: yields (features, masks, tgt) torch tensors.
    [own_rows]/[own_prob]: oversample the user's OWN collected swipes — a few hundred personal rows
    would statistically never be drawn from a million-row corpus, yet they're the highest-value
    samples (this user's finger, this user's device). Each sample comes from own_rows with
    probability own_prob (e.g. 0.1 → ~10% of training is personal data regardless of corpus size)."""
    import torch
    if affine is None and calibrate:
        affine = calibrate_affine(rows)
    elif affine is None:
        affine = Affine(1.0, 0.0, 1.0 / 0.7, 0.0)
    feats, masks, tgts = [], [], []
    n = len(rows)
    n_own = len(own_rows) if own_rows else 0
    while len(feats) < batch:
        if n_own > 0 and rng.random() < own_prob:
            s = row_to_sample(own_rows[int(rng.integers(0, n_own))], affine)
        else:
            s = row_to_sample(rows[int(rng.integers(0, n))], affine)
        if s is None:
            continue
        f, m, t = s
        feats.append(f); masks.append(m); tgts.append(t)
    tlen = max(len(t) for t in tgts)
    tgt = np.full((batch, tlen), M.PAD, dtype=np.int64)
    for i, t in enumerate(tgts):
        tgt[i, : len(t)] = t
    return (torch.from_numpy(np.stack(feats)),
            torch.from_numpy(np.stack(masks)),
            torch.from_numpy(tgt))


# ─────────────────────────── parquet loading (real training) ───────────────────────────

def load_futo_rows(path_or_dir, limit=None, exclude_every=None):
    """Load FUTO rows. FUTO ships as JSONL (train.jsonl + swipe-2..5/*.jsonl). Accepts a single file,
    a glob, or the downloaded directory (loads every *.jsonl under it). Skips flagged-invalid rows.
    [limit] caps how many valid rows are loaded — the full train.jsonl is ~5 GB, so cap it on CPU.
    [exclude_every]: skip every Nth line (by global line index across files) — the SAME rows
    [write_dev_split] carves out for dev.jsonl, so training never sees the held-out set."""
    import json
    if os.path.isdir(path_or_dir):
        files = sorted(glob.glob(os.path.join(path_or_dir, "**", "*.jsonl"), recursive=True))
    else:
        files = glob.glob(path_or_dir) if any(c in path_or_dir for c in "*?[") else [path_or_dir]
    if not files:
        raise FileNotFoundError(f"no .jsonl under {path_or_dir} (run: python futo_data.py --download)")
    rows = []
    idx = -1
    for fp in files:
        with open(fp, encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if not line:
                    continue
                idx += 1
                if exclude_every and idx % exclude_every == 0:
                    continue
                r = json.loads(line)
                if not r.get("potentially_invalid_sentence"):
                    rows.append(r)
                    if limit and len(rows) >= limit:
                        return rows
    return rows


def write_dev_split(path_or_dir, dev_out, every=200):
    """Carve a deterministic held-out dev set: every Nth line (global index across the same file
    order [load_futo_rows] uses) goes to [dev_out]. Train with exclude_every=<same N> and the two
    sets can never overlap — evaluate.py then measures on swipes the model has never seen."""
    import json
    if os.path.isdir(path_or_dir):
        files = sorted(glob.glob(os.path.join(path_or_dir, "**", "*.jsonl"), recursive=True))
    else:
        files = glob.glob(path_or_dir) if any(c in path_or_dir for c in "*?[") else [path_or_dir]
    n = 0
    idx = -1
    with open(dev_out, "w", encoding="utf-8") as out:
        for fp in files:
            with open(fp, encoding="utf-8") as fh:
                for line in fh:
                    line = line.strip()
                    if not line:
                        continue
                    idx += 1
                    if idx % every != 0:
                        continue
                    r = json.loads(line)
                    if not r.get("potentially_invalid_sentence"):
                        out.write(line + "\n")
                        n += 1
    print(f"wrote {n} held-out swipes to {dev_out} (every {every}th line)")
    return n


def load_jsonl_rows(*paths):
    """Load rows from JSONL files (e.g. exported by the Teclas swipe collector web app). Same schema
    as FUTO — so your own captured swipes train through the identical path. Mix with FUTO rows freely."""
    import json
    rows = []
    for p in paths:
        for fp in (glob.glob(p) if any(c in p for c in "*?[") else [p]):
            with open(fp, encoding="utf-8") as fh:
                for line in fh:
                    line = line.strip()
                    if line:
                        rows.append(json.loads(line))
    return rows


def download(out="futo_data"):
    """Download the FUTO JSONL swipe files locally via huggingface_hub (one-time, MIT-licensed)."""
    from huggingface_hub import snapshot_download
    path = snapshot_download(repo_id="futo-org/swipe.futo.org", repo_type="dataset",
                             local_dir=out, allow_patterns=["*.jsonl", "*/*.jsonl"])
    print(f"downloaded FUTO JSONL to {path}")
    return path


# ─────────────────────────── self-test (no network) ───────────────────────────

def _selftest():
    rng = np.random.default_rng(0)
    # Build synthetic FUTO-format rows with a KNOWN canvas→box affine, so calibration should recover it.
    true = Affine(sx=0.9, ox=0.05, sy=1.0 / 0.7, oy=-0.02)
    inv = lambda bx, by: ((bx - true.ox) / true.sx, (by - true.oy) / true.sy)   # box → canvas
    rows = []
    vocab = ["the", "hello", "google", "allow", "swipe", "keyboard", "little", "coffee",
             "because", "people", "world", "letter", "quick", "brown", "jumps", "over", "lazy",
             "typing", "message", "phone", "water", "seven", "eight"]
    for w in vocab:
        for _ in range(6):   # repeats per word: per-letter anchor groups need >=3 samples to count
            pts = []
            t = 1_000_000
            for ch in w:
                k = KEY_CENTERS[ch]
                cx, cy = inv(k[0], k[1])
                for _ in range(3):
                    t += 12
                    pts.append({"t": t, "x": float(np.clip(cx + rng.normal(0, 0.01), 0, 1)),
                                "y": float(np.clip(cy + rng.normal(0, 0.01), 0, 1))})
            rows.append({"word": w.capitalize(), "data": pts, "canvas_width": 422.0, "canvas_height": 170.0,
                         "potentially_invalid_sentence": False})

    aff = calibrate_affine(rows)
    print("calibrated:", aff, "\n     truth:", true)
    assert getattr(aff, "residual", None) is not None, "calibration fell back to default — fit path not exercised"
    assert abs(aff.sx - true.sx) < 0.05 and abs(aff.sy - true.sy) < 0.08, "affine not recovered"
    assert abs(aff.ox - true.ox) < 0.03 and abs(aff.oy - true.oy) < 0.03, "affine offset not recovered"
    from layout import KEY_W, KEY_H
    check_affine(aff, KEY_W, KEY_H)   # a correct fit must pass gate #1...
    bad = Affine(1.0, 0.0, 1.0, 0.0)  # ...and a misfit map (identity y, like the shipped bug) must fail it
    bad.residual = (0.01, KEY_H * 0.5)
    try:
        check_affine(bad, KEY_W, KEY_H)
        raise AssertionError("check_affine accepted a misfit calibration")
    except RuntimeError:
        pass

    grow = next(r for r in rows if r["word"].lower() == "google")
    s = row_to_sample(grow, aff)
    assert s is not None
    feats, mask, toks = s
    assert feats.shape == (M.MAX_TRAJ, M.FEATURE_DIM), feats.shape
    assert mask.shape == (M.MAX_TRAJ,) and int(mask.sum()) == min(len(grow["data"]), M.MAX_TRAJ)
    assert toks[0] == M.SOS and toks[-1] == M.EOS
    assert [chr(ord('a') + t - M.FIRST_LETTER) for t in toks[1:-1]] == list("google")

    tf, tm, tt = iter_futo_batch(rows, rng, batch=8, affine=aff)
    assert tuple(tf.shape) == (8, M.MAX_TRAJ, M.FEATURE_DIM), tf.shape
    assert tuple(tm.shape) == (8, M.MAX_TRAJ)
    print(f"OK — feats {tuple(tf.shape)}, masks {tuple(tm.shape)}, tgt {tuple(tt.shape)}")
    print("self-test passed: loader produces device-matching tensors from FUTO-format rows.")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--selftest", action="store_true")
    ap.add_argument("--download", action="store_true")
    ap.add_argument("--out", default="futo_data")
    ap.add_argument("--make-dev", metavar="SRC", default=None,
                    help="write futo_data/dev.jsonl as every Nth line of SRC (train with the same --exclude-every)")
    ap.add_argument("--dev-every", type=int, default=200)
    args = ap.parse_args()
    if args.download:
        download(out=args.out)
    elif args.make_dev:
        write_dev_split(args.make_dev, os.path.join(args.out, "dev.jsonl"), every=args.dev_every)
    else:
        _selftest()
