"""
Synthetic swipe generator with a human-motor-noise model.

This is the crux of Track B: there is no large, permissively-licensed English-QWERTY swipe corpus,
so — exactly as IndicSwipe did — we synthesize millions of realistic gestures from the app's own
lexicon and key geometry. Realism (not volume) is what makes the trained model beat the geometric
decoder, so the noise model matters:

  * corner cutting     — humans round corners; we smooth control points with a Catmull-Rom spline.
  * anchor jitter      — the finger doesn't hit key centers; add Gaussian scatter at each key.
  * path jitter        — small tremor along the whole stroke.
  * endpoint slop      — start/end land imprecisely (late trigger, early lift).
  * speed variation    — vary how much corners are cut per-sample.

Output features are IDENTICAL to NeuralSwipeEngine.kt: arc-length resample to N=50, normalize into
[0,1] (already in that space here), features [x, y, dx, dy].
"""

import numpy as np
from layout import word_key_path, KEY_W, KEY_H

SEQ_LEN = 50


def _catmull_rom(points, samples_per_seg=12):
    """Smooth control points into a rounded path (models corner-cutting)."""
    pts = np.asarray(points, dtype=np.float64)
    if len(pts) == 1:
        return np.repeat(pts, 2, axis=0)
    # Pad ends so the spline reaches the first/last control point.
    p = np.vstack([pts[0], pts, pts[-1]])
    out = []
    for i in range(1, len(p) - 2):
        p0, p1, p2, p3 = p[i - 1], p[i], p[i + 1], p[i + 2]
        for t in np.linspace(0, 1, samples_per_seg, endpoint=False):
            t2, t3 = t * t, t * t * t
            out.append(0.5 * ((2 * p1) + (-p0 + p2) * t +
                              (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 +
                              (-p0 + 3 * p1 - 3 * p2 + p3) * t3))
    out.append(pts[-1])
    return np.asarray(out)


def _resample_uniform(path, n=SEQ_LEN):
    """Arc-length uniform resampling to n points — matches the Kotlin resampleUniform()."""
    d = np.sqrt(((path[1:] - path[:-1]) ** 2).sum(axis=1))
    cum = np.concatenate([[0], np.cumsum(d)])
    total = cum[-1]
    if total <= 0:
        return np.repeat(path[:1], n, axis=0)
    targets = np.linspace(0, total, n)
    xs = np.interp(targets, cum, path[:, 0])
    ys = np.interp(targets, cum, path[:, 1])
    return np.stack([xs, ys], axis=1)


def synth_gesture(word, rng):
    """Return an (SEQ_LEN, 4) float32 feature array for one noisy swipe of `word`, or None."""
    ctrl = word_key_path(word)
    if len(ctrl) < 1:
        return None
    ctrl = np.asarray(ctrl, dtype=np.float64)

    # Anchor jitter: scatter each key target within a fraction of the key size.
    sx, sy = KEY_W * rng.uniform(0.10, 0.28), KEY_H * rng.uniform(0.10, 0.28)
    ctrl = ctrl + rng.normal(0, [sx, sy], size=ctrl.shape)
    # Endpoint slop.
    ctrl[0] += rng.normal(0, [sx * 1.4, sy * 1.4], size=2)
    ctrl[-1] += rng.normal(0, [sx * 1.4, sy * 1.4], size=2)

    path = _catmull_rom(ctrl, samples_per_seg=rng.integers(8, 16))
    # Path tremor.
    path = path + rng.normal(0, KEY_W * 0.03, size=path.shape)

    path = _resample_uniform(path, SEQ_LEN)
    path = np.clip(path, 0.0, 1.0)

    feats = np.zeros((SEQ_LEN, 4), dtype=np.float32)
    feats[:, 0] = path[:, 0]
    feats[:, 1] = path[:, 1]
    feats[1:, 2] = path[1:, 0] - path[:-1, 0]
    feats[1:, 3] = path[1:, 1] - path[:-1, 1]
    return feats


if __name__ == "__main__":
    rng = np.random.default_rng(0)
    g = synth_gesture("hello", rng)
    print("shape:", g.shape, "x-range:", g[:, 0].min(), g[:, 0].max())
