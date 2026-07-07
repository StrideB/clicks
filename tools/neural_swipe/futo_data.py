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

Usage (once you have GPU time):
    pip install torch numpy onnx pyarrow huggingface_hub
    python futo_data.py --download            # fetch parquet files locally (once)
    # then in export_seq2seq.py, swap iter_batch(...) for iter_futo_batch(rows, ...)
    # or: python export_seq2seq.py --futo <dir>   (wire the flag as noted at the bottom)

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


def calibrate_affine(rows, max_rows=20000):
    """
    Fit the canvas→key-box affine by least squares: a swipe's FIRST point should sit on its first
    letter's key center and its LAST point on its last letter's (both in layout.py's [0,1] box space).
    Robust with a few thousand swipes; returns an [Affine].
    """
    cx, tx, cy, ty = [], [], [], []   # canvas coord, target (key-center) coord — per axis
    for r in rows[:max_rows]:
        w = (r.get("word") or "").lower()
        data = r.get("data") or []
        if len(data) < 2 or not w:
            continue
        for pt, ch in ((data[0], w[0]), (data[-1], w[-1])):
            k = KEY_CENTERS.get(ch)
            if k is None:
                continue
            cx.append(pt["x"]); tx.append(k[0])
            cy.append(pt["y"]); ty.append(k[1])
    if len(cx) < 20:
        # Not enough signal — fall back to a sensible default (letters span full width, upper ~70%).
        return Affine(1.0, 0.0, 1.0 / 0.7, 0.0)
    sx, ox = np.polyfit(np.asarray(cx), np.asarray(tx), 1)
    sy, oy = np.polyfit(np.asarray(cy), np.asarray(ty), 1)
    return Affine(float(sx), float(ox), float(sy), float(oy))


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


def iter_futo_batch(rows, rng, batch, calibrate=True, affine=None):
    """Drop-in replacement for export_seq2seq.iter_batch: yields (features, masks, tgt) torch tensors."""
    import torch
    if affine is None and calibrate:
        affine = calibrate_affine(rows)
    elif affine is None:
        affine = Affine(1.0, 0.0, 1.0 / 0.7, 0.0)
    feats, masks, tgts = [], [], []
    n = len(rows)
    while len(feats) < batch:
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

def load_parquet_rows(data_dir, split="train"):
    """Load FUTO rows from downloaded parquet files. Returns a list of dict rows."""
    import pyarrow.parquet as pq
    files = sorted(glob.glob(os.path.join(data_dir, "**", f"*{split}*.parquet"), recursive=True)) \
        or sorted(glob.glob(os.path.join(data_dir, "**", "*.parquet"), recursive=True))
    if not files:
        raise FileNotFoundError(f"no parquet files under {data_dir} (run --download first)")
    rows = []
    for fp in files:
        tbl = pq.read_table(fp, columns=["word", "data", "canvas_width", "canvas_height",
                                         "potentially_invalid_sentence"])
        for rec in tbl.to_pylist():
            if not rec.get("potentially_invalid_sentence"):
                rows.append(rec)
    return rows


def download(configs=("swipe-1", "swipe-2", "swipe-3", "swipe-4", "swipe-5"), out="futo_data"):
    """Download the FUTO parquet files locally via huggingface_hub (one-time)."""
    from huggingface_hub import snapshot_download
    path = snapshot_download(repo_id="futo-org/swipe.futo.org", repo_type="dataset",
                             local_dir=out, allow_patterns=["*.parquet", "*/*.parquet"])
    print(f"downloaded to {path}")
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
    assert abs(aff.sx - true.sx) < 0.15 and abs(aff.sy - true.sy) < 0.25, "affine not recovered"

    s = row_to_sample(rows[2], aff)   # "google"
    assert s is not None
    feats, mask, toks = s
    assert feats.shape == (M.MAX_TRAJ, M.FEATURE_DIM), feats.shape
    assert mask.shape == (M.MAX_TRAJ,) and int(mask.sum()) == min(len(rows[2]["data"]), M.MAX_TRAJ)
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
    args = ap.parse_args()
    if args.download:
        download(out=args.out)
    else:
        _selftest()
