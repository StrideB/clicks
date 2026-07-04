"""
QWERTY key geometry, normalized to a [0,1] bounding box.

This MUST stay consistent with what the device sends at inference time:
NeuralSwipeEngine.kt normalizes the raw swipe against the letter-key bounding box, so here we
place key centers in that same [0,1] space. Row offsets mirror the app's layout (10 / 9 / 7 keys).
"""

ROWS = ["qwertyuiop", "asdfghjkl", "zxcvbnm"]

# Horizontal offset of each row's first key, in key-widths (matches a standard staggered QWERTY).
ROW_OFFSET = [0.0, 0.5, 1.5]

# Derived: a grid of 10 columns x 3 rows inside the [0,1] box.
_COLS = 10
_KEY_W = 1.0 / _COLS
_KEY_H = 1.0 / 3.0

KEY_CENTERS = {}
for r, row in enumerate(ROWS):
    for c, ch in enumerate(row):
        cx = (ROW_OFFSET[r] + c + 0.5) * _KEY_W
        cy = (r + 0.5) * _KEY_H
        KEY_CENTERS[ch] = (cx, cy)

KEY_W = _KEY_W
KEY_H = _KEY_H


def word_key_path(word):
    """Ideal control points (key centers) for a lowercase word; skips non-letters."""
    pts = []
    for ch in word.lower():
        if ch in KEY_CENTERS:
            pts.append(KEY_CENTERS[ch])
    return pts
