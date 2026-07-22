"""
Train + export the ENCODER-DECODER neural swipe model (Track C) for the Teclas keyboard.

This is separate from the legacy encoder-only pipeline (synth.py / train.py, Track B). It produces the
two ONNX graphs that `app/src/main/java/com/fran/teclas/keyboard/neural/` loads:

    swipe_encoder.onnx   encoder(features, src_mask) -> memory
    swipe_decoder.onnx   decoder(memory, src_mask, tgt) -> logits   (autoregressive; beam search on device)

The feature layout, tensor names, shapes, and character vocabulary MUST match
`NeuralSwipeContract.kt` EXACTLY — that file and this script are the two ends of one wire. The
constants below are copied from it; if you change one, change both.

Why synthetic data: there is no large, permissively-licensed English-QWERTY swipe corpus with real
coordinates, so we synthesize realistic gestures from the app's own lexicon + key geometry (same
approach as IndicSwipe). To train on the MIT-licensed FUTO corpus instead, replace `iter_batch()`
with a loader that yields the same (features, src_mask, tgt) tensors — the model/export are unchanged.

Run (CPU is fine for a small model; GPU speeds it up):

    pip install torch numpy onnx
    python export_seq2seq.py \
        --wordlist ../../app/src/main/assets/dict/en_wordlist.txt \
        --vocab-size 20000 --steps 4000 --batch 128 \
        --out ../../app/src/main/assets

Then rebuild the app. `NeuralGlideEngine.isReady` flips true automatically and glide routes through
the neural decoder (statistical decoder stays as fallback). No app code changes are needed.
"""

import argparse
import math
import os
import numpy as np
import torch
import torch.nn as nn

from layout import word_key_path, KEY_CENTERS, KEY_W, KEY_H

# ── Contract constants — keep in lockstep with NeuralSwipeContract.kt ──
MAX_TRAJ = 200
BASE_FEATURES = 6
KEY_COUNT = 26
FEATURE_DIM = BASE_FEATURES + KEY_COUNT      # 32
PAD, SOS, EOS = 0, 1, 2
FIRST_LETTER = 3
VOCAB_SIZE = FIRST_LETTER + KEY_COUNT        # 29
MAX_DECODE_LEN = 24

# Ordered letters for the nearest-key one-hot (index 0..25 == 'a'..'z').
_LETTERS = [chr(ord("a") + i) for i in range(KEY_COUNT)]
_KEY_XY = np.array([KEY_CENTERS[c] for c in _LETTERS], dtype=np.float32)  # (26, 2), already in [0,1]


# ─────────────────────────────── synthetic gestures ───────────────────────────────

def _catmull_rom(points, samples_per_seg):
    pts = np.asarray(points, dtype=np.float64)
    if len(pts) == 1:
        return np.repeat(pts, 2, axis=0)
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


def synth_path(word, rng):
    """A noisy [N,2] swipe path in [0,1] with per-point timestamps [N] (ms). None if unusable."""
    ctrl = word_key_path(word)
    if len(ctrl) < 1:
        return None, None
    ctrl = np.asarray(ctrl, dtype=np.float64)
    sx, sy = KEY_W * rng.uniform(0.10, 0.28), KEY_H * rng.uniform(0.10, 0.28)
    ctrl = ctrl + rng.normal(0, [sx, sy], size=ctrl.shape)
    ctrl[0] += rng.normal(0, [sx * 1.4, sy * 1.4], size=2)
    ctrl[-1] += rng.normal(0, [sx * 1.4, sy * 1.4], size=2)
    path = _catmull_rom(ctrl, samples_per_seg=int(rng.integers(8, 16)))
    path = path + rng.normal(0, KEY_W * 0.03, size=path.shape)
    path = np.clip(path, 0.0, 1.0)

    # Cap length like the device (uniform subsample, keep endpoints).
    if len(path) > MAX_TRAJ:
        idx = np.round(np.linspace(0, len(path) - 1, MAX_TRAJ)).astype(int)
        path = path[idx]

    # Timestamps: randomize the sampling interval so the model sees a range of velocity scales
    # (real devices sample anywhere from ~60Hz to ~120Hz; dt jitter mimics human speed variation).
    dt = rng.uniform(6.0, 18.0)
    times = np.cumsum(rng.normal(dt, dt * 0.15, size=len(path))).astype(np.float32)
    times[0] = 0.0
    return path.astype(np.float32), times


def synth_tap_path(word, rng):
    """A DISCRETE tap sequence for [word]: one point per letter near its key center, with
    tap-cadence timestamps — the shape a *tap* typist produces, not a continuous swipe.

    Stage 1b makes the neural decoder tap-aware. A swipe-only model is out-of-distribution on taps:
    a tap trace is N well-separated points (one per letter) sampled ~90–220 ms apart, so the
    per-point velocity/acceleration features are an order of magnitude smaller than a swipe's, and
    there is no interpolated arc between keys. Feeding the model this exact shape at train time is
    what lets the same encoder/decoder decode both gestures on-device. None if the word is unusable.
    """
    ctrl = word_key_path(word)
    if len(ctrl) < 1:
        return None, None
    ctrl = np.asarray(ctrl, dtype=np.float64)
    # Per-tap scatter around the key center — real fingers land off-center but rarely leave the key.
    # Independent draw per letter (no shared arc), matching how a device buffers one point per press.
    sx, sy = KEY_W * rng.uniform(0.12, 0.32), KEY_H * rng.uniform(0.12, 0.32)
    path = ctrl + rng.normal(0, [sx, sy], size=ctrl.shape)
    path = np.clip(path, 0.0, 1.0)

    # Tap cadence: a fresh press every ~90–220 ms (vs a swipe's 6–18 ms sample interval). The wide
    # gaps are the signal — they drive velocity toward zero, telling the model "these are taps".
    dt = rng.uniform(90.0, 220.0)
    times = np.cumsum(rng.normal(dt, dt * 0.25, size=len(path))).astype(np.float32)
    times[0] = 0.0
    return path.astype(np.float32), times


def features_from_path(path, times):
    """[MAX_TRAJ, FEATURE_DIM] features + [MAX_TRAJ] int mask. IDENTICAL math to SwipeFeaturizer.kt."""
    n = len(path)
    feats = np.zeros((MAX_TRAJ, FEATURE_DIM), dtype=np.float32)
    mask = np.zeros((MAX_TRAJ,), dtype=np.int64)
    x, y = path[:, 0], path[:, 1]
    vx = np.zeros(n, dtype=np.float32); vy = np.zeros(n, dtype=np.float32)
    for i in range(1, n):
        dt_sec = max((times[i] - times[i - 1]), 1.0) / 1000.0
        vx[i] = (x[i] - x[i - 1]) / dt_sec
        vy[i] = (y[i] - y[i - 1]) / dt_sec
    for i in range(n):
        dt_sec = (max((times[i] - times[i - 1]), 1.0) / 1000.0) if i > 0 else 1.0
        feats[i, 0] = x[i]
        feats[i, 1] = y[i]
        feats[i, 2] = vx[i]
        feats[i, 3] = vy[i]
        feats[i, 4] = (vx[i] - vx[i - 1]) / dt_sec if i > 0 else 0.0
        feats[i, 5] = (vy[i] - vy[i - 1]) / dt_sec if i > 0 else 0.0
        nearest = int(np.argmin(((_KEY_XY[:, 0] - x[i]) ** 2) + ((_KEY_XY[:, 1] - y[i]) ** 2)))
        feats[i, BASE_FEATURES + nearest] = 1.0
        mask[i] = 1
    return feats, mask


def word_tokens(word):
    """[SOS, chars..., EOS] token ids; None if the word has a non 'a'..'z' char."""
    toks = [SOS]
    for c in word.lower():
        if "a" <= c <= "z":
            toks.append(FIRST_LETTER + (ord(c) - ord("a")))
        else:
            return None
    toks.append(EOS)
    return toks


def iter_batch(words, rng, batch, max_word_len, tap_prob=0.0):
    """Synthetic batch. Each sample is a swipe by default, or a discrete TAP trace with probability
    [tap_prob] (Stage 1b) — so one synthetic stream teaches the model both gestures at once."""
    feats, masks, tgts = [], [], []
    while len(feats) < batch:
        w = words[int(rng.integers(0, len(words)))]
        if not (1 <= len(w) <= max_word_len):
            continue
        toks = word_tokens(w)
        if toks is None:
            continue
        if tap_prob > 0.0 and rng.random() < tap_prob:
            path, times = synth_tap_path(w, rng)
            # A 1-letter tap word is a single point; that's a legitimate trace, keep it.
            if path is None or len(path) < 1:
                continue
        else:
            path, times = synth_path(w, rng)
            if path is None or len(path) < 2:
                continue
        f, m = features_from_path(path, times)
        feats.append(f); masks.append(m); tgts.append(toks)
    tlen = max(len(t) for t in tgts)
    tgt = np.full((batch, tlen), PAD, dtype=np.int64)
    for i, t in enumerate(tgts):
        tgt[i, : len(t)] = t
    return (torch.from_numpy(np.stack(feats)),
            torch.from_numpy(np.stack(masks)),
            torch.from_numpy(tgt))


# ─────────────────────────────── model ───────────────────────────────

class PositionalEncoding(nn.Module):
    def __init__(self, d_model, max_len=MAX_TRAJ + MAX_DECODE_LEN):
        super().__init__()
        pe = torch.zeros(max_len, d_model)
        pos = torch.arange(0, max_len).unsqueeze(1).float()
        div = torch.exp(torch.arange(0, d_model, 2).float() * (-math.log(10000.0) / d_model))
        pe[:, 0::2] = torch.sin(pos * div)
        pe[:, 1::2] = torch.cos(pos * div)
        self.register_buffer("pe", pe.unsqueeze(0))

    def forward(self, x):
        return x + self.pe[:, : x.size(1)]


# We implement attention by hand rather than using nn.MultiheadAttention: the stock module's
# internal reshapes constant-fold the batch AND sequence-length dims from the export example, which
# then fails at any other length. Splitting heads via reshape(B, L, H, Dh) with B/L read symbolically
# from `x.shape` keeps the sequence length dynamic in the exported ONNX graph.
NEG_INF = -1e9


class MultiHeadAttention(nn.Module):
    def __init__(self, d_model, nhead):
        super().__init__()
        assert d_model % nhead == 0
        self.h = nhead
        self.dh = d_model // nhead
        self.q = nn.Linear(d_model, d_model)
        self.k = nn.Linear(d_model, d_model)
        self.v = nn.Linear(d_model, d_model)
        self.o = nn.Linear(d_model, d_model)

    def _split(self, x):                                  # [B,L,D] -> [B,H,L,Dh]
        b, l = x.shape[0], x.shape[1]
        return x.reshape(b, l, self.h, self.dh).transpose(1, 2)

    def forward(self, q, k, v, key_padding=None, causal=False):
        b = q.shape[0]
        qs, ks, vs = self._split(self.q(q)), self._split(self.k(k)), self._split(self.v(v))
        scores = (qs @ ks.transpose(-2, -1)) / math.sqrt(self.dh)   # [B,H,Lq,Lk]
        if causal:
            lq = q.shape[1]
            i = torch.arange(lq, device=q.device)
            fut = (i[None, :] > i[:, None])               # [Lq,Lk] True = future key
            scores = scores.masked_fill(fut[None, None, :, :], NEG_INF)
        if key_padding is not None:                       # [B,Lk] True = pad
            scores = scores.masked_fill(key_padding[:, None, None, :], NEG_INF)
        attn = scores.softmax(dim=-1)
        out = attn @ vs                                   # [B,H,Lq,Dh]
        lq = out.shape[2]
        out = out.transpose(1, 2).reshape(b, lq, self.h * self.dh)
        return self.o(out)


class FeedForward(nn.Module):
    def __init__(self, d_model, ff):
        super().__init__()
        self.net = nn.Sequential(nn.Linear(d_model, ff), nn.ReLU(), nn.Linear(ff, d_model))

    def forward(self, x):
        return self.net(x)


class EncoderLayer(nn.Module):
    def __init__(self, d_model, nhead, ff):
        super().__init__()
        self.attn = MultiHeadAttention(d_model, nhead)
        self.ff = FeedForward(d_model, ff)
        self.n1 = nn.LayerNorm(d_model)
        self.n2 = nn.LayerNorm(d_model)

    def forward(self, x, key_padding):
        x = self.n1(x + self.attn(x, x, x, key_padding=key_padding))
        return self.n2(x + self.ff(x))


class DecoderLayer(nn.Module):
    def __init__(self, d_model, nhead, ff):
        super().__init__()
        self.self_attn = MultiHeadAttention(d_model, nhead)
        self.cross_attn = MultiHeadAttention(d_model, nhead)
        self.ff = FeedForward(d_model, ff)
        self.n1 = nn.LayerNorm(d_model)
        self.n2 = nn.LayerNorm(d_model)
        self.n3 = nn.LayerNorm(d_model)

    def forward(self, y, memory, mem_key_padding):
        y = self.n1(y + self.self_attn(y, y, y, causal=True))
        y = self.n2(y + self.cross_attn(y, memory, memory, key_padding=mem_key_padding))
        return self.n3(y + self.ff(y))


class SwipeEncoder(nn.Module):
    def __init__(self, d_model, nhead, layers, ff):
        super().__init__()
        self.proj = nn.Linear(FEATURE_DIM, d_model)
        self.pos = PositionalEncoding(d_model)
        self.layers = nn.ModuleList([EncoderLayer(d_model, nhead, ff) for _ in range(layers)])

    def forward(self, features, src_mask):
        key_padding = src_mask == 0                       # True = padding
        x = self.pos(self.proj(features))
        for layer in self.layers:
            x = layer(x, key_padding)
        return x


class SwipeDecoder(nn.Module):
    def __init__(self, d_model, nhead, layers, ff):
        super().__init__()
        self.emb = nn.Embedding(VOCAB_SIZE, d_model, padding_idx=PAD)
        self.pos = PositionalEncoding(d_model)
        self.layers = nn.ModuleList([DecoderLayer(d_model, nhead, ff) for _ in range(layers)])
        self.out = nn.Linear(d_model, VOCAB_SIZE)

    def forward(self, memory, src_mask, tgt):
        mem_key_padding = src_mask == 0
        y = self.pos(self.emb(tgt))
        for layer in self.layers:
            y = layer(y, memory, mem_key_padding)
        return self.out(y)


# ─────────────────────────────── train + export ───────────────────────────────

def load_words(path, cap):
    words = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            w = line.strip().split()[0].lower() if line.strip() else ""
            if w and all("a" <= c <= "z" for c in w):
                words.append(w)
            if len(words) >= cap:
                break
    return words


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--wordlist", required=True)
    ap.add_argument("--out", required=True, help="assets dir to write the two .onnx files into")
    ap.add_argument("--vocab-size", type=int, default=20000)
    ap.add_argument("--steps", type=int, default=4000)
    ap.add_argument("--batch", type=int, default=128)
    ap.add_argument("--d-model", type=int, default=128)
    ap.add_argument("--nhead", type=int, default=4)
    ap.add_argument("--layers", type=int, default=3)
    ap.add_argument("--ff", type=int, default=256)
    ap.add_argument("--lr", type=float, default=3e-4)
    ap.add_argument("--max-word-len", type=int, default=MAX_DECODE_LEN - 2)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--opset", type=int, default=17)
    ap.add_argument("--futo", metavar="PATH", default=None,
                    help="train on REAL FUTO swipes (jsonl file/dir) instead of synthetic")
    ap.add_argument("--futo-limit", type=int, default=None, help="cap FUTO rows loaded (CPU memory)")
    ap.add_argument("--exclude-every", type=int, default=None,
                    help="skip every Nth FUTO line — pair with `futo_data.py --make-dev` so the "
                         "held-out dev.jsonl never leaks into training")
    ap.add_argument("--synth-mix", type=float, default=0.25,
                    help="fraction of batches drawn from the synthetic generator when --futo is set "
                         "(real swipes cover ~12k donors' common words; synthetic covers the full "
                         "wordlist's long tail — mixing keeps rare-word decoding from regressing)")
    ap.add_argument("--tap-mix", type=float, default=0.0,
                    help="Stage 1b: fraction of batches that are synthetic DISCRETE TAP traces "
                         "(one point per letter, tap cadence) instead of swipes. Makes the model "
                         "tap-aware so the same encoder/decoder decodes tap typing on-device, not "
                         "just glides. 0 = swipe-only (legacy). ~0.35 is a good tap+swipe balance.")
    ap.add_argument("--own", metavar="GLOB", default=None,
                    help="your own collected swipes (jsonl, collector-app or GlideLearningStore "
                         "export) — oversampled via --own-prob, not drowned in the corpus")
    ap.add_argument("--own-prob", type=float, default=0.10,
                    help="probability each REAL-batch sample is drawn from --own rows")
    ap.add_argument("--checkpoint-every", type=int, default=2000,
                    help="save a resumable checkpoint to <out>/checkpoint.pt every N steps — a "
                         "crash, reboot, or file mishap can only ever cost N steps of progress")
    ap.add_argument("--resume", action="store_true",
                    help="continue from <out>/checkpoint.pt if present (config must match)")
    args = ap.parse_args()

    rng = np.random.default_rng(args.seed)
    torch.manual_seed(args.seed)
    # Use a GPU when there is one: NVIDIA CUDA, else Apple-Silicon MPS (set PYTORCH_ENABLE_MPS_FALLBACK=1
    # so the few ops MPS lacks fall back to CPU instead of erroring), else CPU.
    device = ("cuda" if torch.cuda.is_available()
              else "mps" if getattr(torch.backends, "mps", None) and torch.backends.mps.is_available()
              else "cpu")

    words = load_words(args.wordlist, args.vocab_size)
    # Real-data path: load the FUTO corpus + calibrate the coordinate transform once (see futo_data.py).
    futo_rows, futo_affine, own_rows = None, None, None
    if args.futo:
        import futo_data
        futo_rows = futo_data.load_futo_rows(args.futo, limit=args.futo_limit, exclude_every=args.exclude_every)
        futo_affine = futo_data.calibrate_affine(futo_rows)
        futo_data.check_affine(futo_affine, KEY_W, KEY_H)   # refuse to train through a misfit map
        print(f"FUTO: {len(futo_rows)} real swipes; {futo_affine}")
        if args.own:
            own_rows = futo_data.load_jsonl_rows(args.own)
            print(f"own swipes: {len(own_rows)} rows, drawn with p={args.own_prob} per real sample")
        print(f"mix: {int((1 - args.synth_mix) * 100)}% real batches / {int(args.synth_mix * 100)}% synthetic")
    if args.tap_mix > 0.0:
        print(f"tap-aware (Stage 1b): {int(args.tap_mix * 100)}% of batches are synthetic TAP traces")
    print(f"loaded {len(words)} words; device={device}; data={'FUTO-real+synth' if args.futo else 'synthetic'}")

    encoder = SwipeEncoder(args.d_model, args.nhead, args.layers, args.ff).to(device)
    decoder = SwipeDecoder(args.d_model, args.nhead, args.layers, args.ff).to(device)
    opt = torch.optim.AdamW(list(encoder.parameters()) + list(decoder.parameters()), lr=args.lr)
    loss_fn = nn.CrossEntropyLoss(ignore_index=PAD)

    # Resumable checkpointing: days of GPU time must never hinge on one file surviving. The
    # checkpoint is written atomically (tmp + rename) so a crash mid-save can't corrupt it.
    os.makedirs(args.out, exist_ok=True)
    ckpt_path = os.path.join(args.out, "checkpoint.pt")
    config = {"d_model": args.d_model, "nhead": args.nhead, "layers": args.layers,
              "ff": args.ff, "vocab_size": args.vocab_size, "max_word_len": args.max_word_len}
    start_step = 1
    if args.resume and os.path.exists(ckpt_path):
        ck = torch.load(ckpt_path, map_location=device)
        if ck.get("config") != config:
            raise SystemExit(
                f"REFUSING TO RESUME: {ckpt_path} was trained with a different config\n"
                f"  checkpoint: {ck.get('config')}\n  requested : {config}\n"
                f"Either rerun with the checkpoint's config, or move/delete the checkpoint "
                f"to deliberately start fresh.")
        encoder.load_state_dict(ck["encoder"]); decoder.load_state_dict(ck["decoder"])
        opt.load_state_dict(ck["opt"])
        start_step = ck["step"] + 1
        print(f"resumed from {ckpt_path}: step {ck['step']} of {args.steps} already done")

    def save_ckpt(step):
        tmp = ckpt_path + ".tmp"
        torch.save({"step": step, "config": config,
                    "encoder": encoder.state_dict(), "decoder": decoder.state_dict(),
                    "opt": opt.state_dict()}, tmp)
        os.replace(tmp, ckpt_path)

    encoder.train(); decoder.train()
    for step in range(start_step, args.steps + 1):
        # Stage 1b: carve out a tap-trace fraction first, then split the rest real/synthetic. Tap
        # batches are always synthetic (the FUTO corpus is swipes only) — one point per letter.
        if args.tap_mix > 0.0 and rng.random() < args.tap_mix:
            feats, masks, tgt = iter_batch(words, rng, args.batch, args.max_word_len, tap_prob=1.0)
        elif futo_rows is not None and rng.random() >= args.synth_mix:
            import futo_data
            feats, masks, tgt = futo_data.iter_futo_batch(
                futo_rows, rng, args.batch, affine=futo_affine,
                own_rows=own_rows, own_prob=args.own_prob)
        else:
            feats, masks, tgt = iter_batch(words, rng, args.batch, args.max_word_len)
        feats, masks, tgt = feats.to(device), masks.to(device), tgt.to(device)
        memory = encoder(feats, masks)
        logits = decoder(memory, masks, tgt[:, :-1])            # teacher forcing
        loss = loss_fn(logits.reshape(-1, VOCAB_SIZE), tgt[:, 1:].reshape(-1))
        opt.zero_grad(); loss.backward()
        nn.utils.clip_grad_norm_(list(encoder.parameters()) + list(decoder.parameters()), 1.0)
        opt.step()
        if step % 200 == 0 or step == 1:
            print(f"step {step:5d}/{args.steps}  loss {loss.item():.4f}")
        if step % args.checkpoint_every == 0:
            save_ckpt(step)

    save_ckpt(max(args.steps, start_step - 1))   # final state — a crash during export costs nothing on rerun
    encoder.eval(); decoder.eval()

    # Export. Both graphs run at BATCH 1 on-device (the beam search calls the decoder once per beam),
    # so only the decoder's sequence length L is dynamic. This deliberately avoids a dynamic batch
    # axis: PyTorch's ONNX exporters constant-fold nn.MultiheadAttention's internal reshapes from the
    # example batch, which then fails at batch > 1. Fixed batch = 1 sidesteps that entirely and keeps
    # beam width freely tunable on-device (it's just a loop count).
    ex_feats = torch.zeros(1, MAX_TRAJ, FEATURE_DIM)
    ex_mask = torch.ones(1, MAX_TRAJ, dtype=torch.int64)
    with torch.no_grad():
        ex_memory = encoder(ex_feats.to(device), ex_mask.to(device)).cpu()
    ex_tgt = torch.tensor([[SOS, FIRST_LETTER]], dtype=torch.int64)   # batch 1, L 2

    enc_path = f"{args.out}/swipe_encoder.onnx"
    dec_path = f"{args.out}/swipe_decoder.onnx"
    # torch >= 2.6 defaults to the dynamo exporter, whose shape inference rejects our dynamic_axes
    # contract (it traces memory's length as static 200). These graphs were built for the legacy
    # TorchScript exporter (see the MultiHeadAttention notes above) — request it explicitly on torch
    # versions that have the flag, older versions use it anyway.
    import inspect
    legacy = {"dynamo": False} if "dynamo" in inspect.signature(torch.onnx.export).parameters else {}
    torch.onnx.export(
        encoder.cpu(), (ex_feats, ex_mask), enc_path,
        input_names=["features", "src_mask"], output_names=["memory"],
        opset_version=args.opset, **legacy,
    )
    torch.onnx.export(
        decoder.cpu(), (ex_memory, ex_mask, ex_tgt), dec_path,
        input_names=["memory", "src_mask", "tgt"], output_names=["logits"],
        dynamic_axes={"tgt": {1: "L"}, "logits": {1: "L"}},
        opset_version=args.opset, **legacy,
    )
    print(f"wrote {enc_path}\nwrote {dec_path}")
    print("Rebuild the app; NeuralGlideEngine.isReady will flip true automatically.")


if __name__ == "__main__":
    main()
