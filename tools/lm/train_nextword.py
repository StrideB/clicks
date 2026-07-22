#!/usr/bin/env python3
"""
Stage 2b — train + export the NEURAL next-word language model for the Teclas keyboard.

Stage 2a is a compact bigram table (build_bigrams.py → en_bigrams.txt): fast, but it only ever sees
ONE previous word and can't generalize past pairs it literally counted. Stage 2b learns a small
neural model over a few words of context, so it predicts the next word from the *sentence so far* and
generalizes to contexts it never saw verbatim. On-device it feeds the decoder's language prior
(TapLatticeDecoder.lmProb / contextNextWords) so decode-at-space picks the word the sentence wants —
the last piece of Gboard-style "types what you meant, not what you tapped".

It produces two assets the app loads (wiring is a follow-up, exactly like the swipe model):

    nextword.onnx         context(int64 [1,K]) -> logits(float [1,V])   (one matmul per word commit)
    nextword_vocab.txt    line N (0-based) = the word for id N; ids 0=<pad> 1=<unk>

Architecture is deliberately tiny (embedding + GRU + tied-ish projection) so it's a few MB of ONNX
and runs in well under a millisecond per committed word — next-word runs once per space, never per
keystroke.

Corpus: running text. Either point --futo at the swipe corpus (its `sentence` field is real typed
English) and/or pass --corpus plain.txt. Vocab is the app wordlist's top --vocab-size words, so every
predicted id maps to a word the dictionary already knows.

One-time setup (same venv as the swipe model):
    pip install torch numpy onnx onnxruntime

Run:
    python train_nextword.py \
        --wordlist ../../app/src/main/assets/dict/en_wordlist.txt \
        --futo ../neural_swipe/futo_data/train.jsonl \
        --out ../../app/src/main/assets/dict

Self-test (no data, no network — proves the graph trains + exports + reloads):
    python train_nextword.py --selftest
"""

import argparse
import json
import math
import os
import re
import sys

import numpy as np
import torch
import torch.nn as nn

# ── vocab layout — keep in lockstep with the on-device loader (NextWordModel.kt, to be added) ──
PAD, UNK = 0, 1
FIRST_WORD = 2               # real vocab ids start here
WORD_RE = re.compile(r"[a-z']+")


# ─────────────────────────────── vocab + corpus ───────────────────────────────

def load_vocab(wordlist_path, vocab_size):
    """Top-[vocab_size] wordlist words → id maps. id 0=<pad>, 1=<unk>, 2.. = words."""
    words = []
    with open(wordlist_path, encoding="utf-8") as f:
        for line in f:
            w = line.strip().split()[0].lower() if line.strip() else ""
            if w and all("a" <= c <= "z" or c == "'" for c in w):
                words.append(w)
            if len(words) >= vocab_size:
                break
    stoi = {"<pad>": PAD, "<unk>": UNK}
    for w in words:
        if w not in stoi:
            stoi[w] = len(stoi)
    itos = [None] * len(stoi)
    for w, i in stoi.items():
        itos[i] = w
    return stoi, itos


def _sentences_from_futo(path, limit):
    """Yield the deduped `sentence` strings from a FUTO jsonl file/dir — real typed English."""
    seen = set()
    files = []
    if os.path.isdir(path):
        for root, _, names in os.walk(path):
            files += [os.path.join(root, n) for n in names if n.endswith(".jsonl")]
    else:
        files = [path]
    for fp in files:
        with open(fp, encoding="utf-8", errors="ignore") as f:
            for line in f:
                if limit and len(seen) >= limit:
                    return
                try:
                    r = json.loads(line)
                except json.JSONDecodeError:
                    continue
                s = (r.get("sentence") or "").strip().lower()
                if s and s not in seen:
                    seen.add(s)
                    yield s


def build_examples(stoi, k, futo=None, futo_sent_limit=None, corpus=None):
    """Every sentence → sliding (K-word context → next word) id examples. Context is left-padded with
    <pad>; out-of-vocab words become <unk> so the model still learns to predict *around* them."""
    ctxs, tgts = [], []

    def add_sentence(text):
        ids = [stoi.get(w, UNK) for w in WORD_RE.findall(text)]
        if len(ids) < 2:
            return
        for i in range(1, len(ids)):
            if ids[i] == UNK:
                continue                        # don't train the model to *emit* <unk>
            window = ids[max(0, i - k):i]
            window = [PAD] * (k - len(window)) + window
            ctxs.append(window)
            tgts.append(ids[i])

    if futo:
        for s in _sentences_from_futo(futo, futo_sent_limit):
            add_sentence(s)
    if corpus:
        with open(corpus, encoding="utf-8", errors="ignore") as f:
            for line in f:
                add_sentence(line.lower())
    if not ctxs:
        return None, None
    return np.asarray(ctxs, dtype=np.int64), np.asarray(tgts, dtype=np.int64)


# ─────────────────────────────── model ───────────────────────────────

class NextWordModel(nn.Module):
    """Embed K context words → GRU → project to the vocab. Small on purpose."""

    def __init__(self, vocab, d_model=128, hidden=192):
        super().__init__()
        self.emb = nn.Embedding(vocab, d_model, padding_idx=PAD)
        self.gru = nn.GRU(d_model, hidden, batch_first=True)
        self.out = nn.Linear(hidden, vocab)

    def forward(self, context):                 # context: [B, K] int64
        e = self.emb(context)                   # [B, K, d]
        _, h = self.gru(e)                       # h: [1, B, hidden]
        return self.out(h[-1])                   # [B, vocab] logits for the NEXT word


# ─────────────────────────────── train + export ───────────────────────────────

def train(model, ctxs, tgts, device, steps, batch, lr, seed, log_every=200):
    rng = np.random.default_rng(seed)
    opt = torch.optim.AdamW(model.parameters(), lr=lr)
    loss_fn = nn.CrossEntropyLoss()
    n = len(ctxs)
    model.train()
    for step in range(1, steps + 1):
        idx = rng.integers(0, n, size=min(batch, n))
        cb = torch.from_numpy(ctxs[idx]).to(device)
        tb = torch.from_numpy(tgts[idx]).to(device)
        logits = model(cb)
        loss = loss_fn(logits, tb)
        opt.zero_grad(); loss.backward()
        nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        opt.step()
        if step % log_every == 0 or step == 1:
            print(f"step {step:5d}/{steps}  loss {loss.item():.4f}  ppl {math.exp(min(loss.item(), 20)):.1f}")


@torch.no_grad()
def evaluate(model, ctxs, tgts, device, topk=3, sample=20000):
    """Held-out top-1 / top-k next-word accuracy — the honest 'does context help' number."""
    model.eval()
    n = len(ctxs)
    take = min(sample, n)
    cb = torch.from_numpy(ctxs[:take]).to(device)
    logits = model(cb).cpu().numpy()
    top = np.argsort(-logits, axis=1)[:, :topk]
    t = tgts[:take]
    t1 = float((top[:, 0] == t).mean())
    tk = float((top == t[:, None]).any(axis=1).mean())
    return t1, tk, take


def export_onnx(model, k, out_dir, itos, opset=17):
    os.makedirs(out_dir, exist_ok=True)
    onnx_path = os.path.join(out_dir, "nextword.onnx")
    vocab_path = os.path.join(out_dir, "nextword_vocab.txt")
    model.eval().cpu()
    ex = torch.zeros(1, k, dtype=torch.int64)
    import inspect
    legacy = {"dynamo": False} if "dynamo" in inspect.signature(torch.onnx.export).parameters else {}
    torch.onnx.export(
        model, (ex,), onnx_path,
        input_names=["context"], output_names=["logits"],
        opset_version=opset, **legacy,
    )
    # Vocab written atomically: line i == word for id i (0=<pad>, 1=<unk>).
    tmp = vocab_path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write("\n".join(itos) + "\n")
    os.replace(tmp, vocab_path)
    return onnx_path, vocab_path


def verify_onnx(onnx_path, model, k):
    """Prove the exported graph reproduces the torch model's top-1 on random contexts."""
    import onnxruntime as ort
    sess = ort.InferenceSession(onnx_path)
    rng = np.random.default_rng(3)
    ctx = rng.integers(0, model.emb.num_embeddings, size=(1, k)).astype(np.int64)
    onnx_logits = sess.run(None, {"context": ctx})[0]
    with torch.no_grad():
        torch_logits = model.cpu()(torch.from_numpy(ctx)).numpy()
    return int(onnx_logits.argmax()) == int(torch_logits.argmax())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--wordlist")
    ap.add_argument("--out", help="assets dir to write nextword.onnx + nextword_vocab.txt into")
    ap.add_argument("--futo", default=None, help="FUTO jsonl file/dir — trains on its `sentence` text")
    ap.add_argument("--futo-sent-limit", type=int, default=None, help="cap distinct FUTO sentences")
    ap.add_argument("--corpus", default=None, help="extra plain-text corpus (one doc/line-ish)")
    ap.add_argument("--vocab-size", type=int, default=20000)
    ap.add_argument("--context", type=int, default=3, help="K previous words the model conditions on")
    ap.add_argument("--d-model", type=int, default=128)
    ap.add_argument("--hidden", type=int, default=192)
    ap.add_argument("--steps", type=int, default=20000)
    ap.add_argument("--batch", type=int, default=512)
    ap.add_argument("--lr", type=float, default=3e-4)
    ap.add_argument("--dev-frac", type=float, default=0.02, help="held-out fraction for the eval")
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--opset", type=int, default=17)
    ap.add_argument("--selftest", action="store_true",
                    help="train a tiny model on a built-in toy corpus, export, reload — no data/network")
    args = ap.parse_args()

    torch.manual_seed(args.seed)
    device = ("cuda" if torch.cuda.is_available()
              else "mps" if getattr(torch.backends, "mps", None) and torch.backends.mps.is_available()
              else "cpu")

    if args.selftest:
        return selftest(device, args.opset)

    if not args.wordlist or not args.out:
        ap.error("--wordlist and --out are required (or use --selftest)")
    if not (args.futo or args.corpus):
        ap.error("give a corpus: --futo <jsonl> and/or --corpus <text>")

    stoi, itos = load_vocab(args.wordlist, args.vocab_size)
    print(f"vocab: {len(itos)} words (+<pad>,<unk>); context K={args.context}; device={device}")
    ctxs, tgts = build_examples(stoi, args.context, futo=args.futo,
                                futo_sent_limit=args.futo_sent_limit, corpus=args.corpus)
    if ctxs is None:
        raise SystemExit("no training examples — check --futo/--corpus paths")
    # Shuffle once, carve a held-out dev tail the training never samples from.
    perm = np.random.default_rng(args.seed).permutation(len(ctxs))
    ctxs, tgts = ctxs[perm], tgts[perm]
    n_dev = max(1, int(len(ctxs) * args.dev_frac))
    dev_c, dev_t = ctxs[:n_dev], tgts[:n_dev]
    tr_c, tr_t = ctxs[n_dev:], tgts[n_dev:]
    print(f"examples: {len(tr_c)} train / {len(dev_c)} dev")

    model = NextWordModel(len(itos), args.d_model, args.hidden).to(device)
    train(model, tr_c, tr_t, device, args.steps, args.batch, args.lr, args.seed)

    t1, tk, n = evaluate(model, dev_c, dev_t, device, topk=3)
    print(f"held-out next-word: top-1 {100 * t1:.1f}%  top-3 {100 * tk:.1f}%  ({n} contexts)")

    onnx_path, vocab_path = export_onnx(model, args.context, args.out, itos, args.opset)
    ok = verify_onnx(onnx_path, model, args.context)
    print(f"wrote {onnx_path}\nwrote {vocab_path}")
    print(f"ONNX round-trip: {'PASS' if ok else 'FAIL — exported graph disagrees with torch, do NOT ship'}")
    if not ok:
        raise SystemExit(1)
    print("Rebuild the app once the on-device NextWordModel loader is wired (Stage 2b app-side).")


def selftest(device, opset):
    """No data, no network: a toy corpus proves the whole train→export→reload path is sound."""
    print(f"selftest on device={device}")
    toy = (["the cat sat on the mat"] * 40 + ["i want to go home now"] * 40 +
           ["please call me back later today"] * 40 + ["let me know what you think"] * 40)
    stoi = {"<pad>": PAD, "<unk>": UNK}
    for s in toy:
        for w in WORD_RE.findall(s):
            stoi.setdefault(w, len(stoi))
    itos = [None] * len(stoi)
    for w, i in stoi.items():
        itos[i] = w
    k = 3
    # build_examples reads from files; inline the same windowing over the in-memory toy strings.
    ctxs, tgts = [], []
    for s in toy:
        ids = [stoi[w] for w in WORD_RE.findall(s)]
        for i in range(1, len(ids)):
            window = ids[max(0, i - k):i]
            window = [PAD] * (k - len(window)) + window
            ctxs.append(window); tgts.append(ids[i])
    ctxs = np.asarray(ctxs, dtype=np.int64); tgts = np.asarray(tgts, dtype=np.int64)
    model = NextWordModel(len(itos), 32, 48).to(device)
    train(model, ctxs, tgts, device, steps=600, batch=64, lr=3e-3, seed=0, log_every=200)
    t1, tk, n = evaluate(model, ctxs, tgts, device, topk=3)
    print(f"toy fit (train=eval): top-1 {100 * t1:.1f}%  top-3 {100 * tk:.1f}%  ({n})")
    out_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_selftest_out")
    onnx_path, vocab_path = export_onnx(model, k, out_dir, itos, opset)
    ok = verify_onnx(onnx_path, model, k)
    print(f"export + ONNX round-trip: {'PASS' if ok else 'FAIL'}")
    # The model should have clearly learned the toy continuations.
    assert t1 > 0.8, f"toy top-1 too low ({t1:.2f}) — training path is broken"
    assert ok, "ONNX graph disagrees with torch"
    print("SELFTEST PASS")


if __name__ == "__main__":
    main()
