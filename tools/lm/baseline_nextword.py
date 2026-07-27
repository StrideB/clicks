#!/usr/bin/env python3
"""
Score the CURRENT on-device next-word approach on the SAME held-out contexts the neural LM was
evaluated on, so "is the model actually better?" is a number instead of a judgment call.

The app predicts the next word from a bigram table (ContextModel: counts of prev -> next, normalized
per prev) plus a personal n-gram store. The personal half is per-user and has no offline analogue,
so this measures the bigram — the part that ships to everyone on day one and the part the neural LM
would be layered on top of.

Fairness is the whole point, so this reuses train_nextword.py's own vocab loader, example builder,
seed, permutation and dev fraction. Same corpus in, same split, same metric out. Change nothing here
without changing it there.

    python baseline_nextword.py --wordlist ../../app/src/main/assets/dict/en_wordlist.txt \
        --futo ../neural_swipe/futo_data/train.jsonl

Prints top-1 / top-3 exactly like the trainer, plus a verdict against --neural-top1 if you pass the
number the trainer reported.
"""
import argparse
import sys
from collections import Counter, defaultdict

import numpy as np

# Import the trainer's own data path so the split is provably identical.
sys.path.insert(0, __import__("os").path.dirname(__import__("os").path.abspath(__file__)))
from train_nextword import PAD, UNK, load_vocab, build_examples  # noqa: E402


def fit_bigram(ctxs, tgts):
    """Counts of (previous word -> next word) from the TRAINING half only.

    The context window is left-padded, so the immediately-previous word is the last column. That is
    exactly what ContextModel keys on. PAD contexts (sentence starts) are kept as their own bucket,
    which is also what the app does — it has a start-of-input state.
    """
    counts = defaultdict(Counter)
    for prev, tgt in zip(ctxs[:, -1], tgts):
        counts[int(prev)][int(tgt)] += 1
    return counts


def fit_unigram(tgts):
    """Global frequency — the fallback for a previous word the bigram never saw. The app falls back
    to dictionary frequency in the same situation, so leaving it out would flatter the baseline's
    failure cases into zeros and overstate the neural win."""
    return Counter(int(t) for t in tgts)


def evaluate_bigram(counts, unigram, ctxs, tgts, topk=3, sample=20000):
    take = min(sample, len(ctxs))
    uni_top = [w for w, _ in unigram.most_common(topk)]
    hit1 = hitk = 0
    backoffs = 0
    for prev, tgt in zip(ctxs[:take, -1], tgts[:take]):
        row = counts.get(int(prev))
        if row:
            top = [w for w, _ in row.most_common(topk)]
            if len(top) < topk:                      # pad thin rows with global frequency
                top += [w for w in uni_top if w not in top][: topk - len(top)]
        else:
            top = uni_top
            backoffs += 1
        t = int(tgt)
        if top and top[0] == t:
            hit1 += 1
        if t in top:
            hitk += 1
    return hit1 / take, hitk / take, take, backoffs / take


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--wordlist", required=True)
    ap.add_argument("--futo", default=None)
    ap.add_argument("--futo-sent-limit", type=int, default=None)
    ap.add_argument("--corpus", default=None)
    # These four MUST match the train_nextword.py run being compared against.
    ap.add_argument("--vocab-size", type=int, default=20000)
    ap.add_argument("--context", type=int, default=3)
    ap.add_argument("--dev-frac", type=float, default=0.02)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--neural-top1", type=float, default=None,
                    help="top-1 %% the trainer reported, e.g. 18.1 — prints a verdict")
    ap.add_argument("--neural-top3", type=float, default=None)
    args = ap.parse_args()

    stoi, itos = load_vocab(args.wordlist, args.vocab_size)
    ctxs, tgts = build_examples(stoi, args.context, args.futo, args.futo_sent_limit, args.corpus)
    if len(ctxs) == 0:
        raise SystemExit("no examples — check --futo/--corpus paths")

    # Identical shuffle + carve to train_nextword.py.
    perm = np.random.default_rng(args.seed).permutation(len(ctxs))
    ctxs, tgts = ctxs[perm], tgts[perm]
    n_dev = max(1, int(len(ctxs) * args.dev_frac))
    dev_c, dev_t = ctxs[:n_dev], tgts[:n_dev]
    tr_c, tr_t = ctxs[n_dev:], tgts[n_dev:]
    print(f"examples: {len(tr_c)} train / {len(dev_c)} dev   (vocab {len(itos)}, K={args.context})")

    counts = fit_bigram(tr_c, tr_t)
    unigram = fit_unigram(tr_t)
    t1, tk, n, backoff = evaluate_bigram(counts, unigram, dev_c, dev_t)

    print(f"held-out next-word: top-1 {100 * t1:.1f}%  top-3 {100 * tk:.1f}%  ({n} contexts)   [BIGRAM BASELINE]")
    print(f"  bigram covered {100 * (1 - backoff):.1f}% of contexts; {100 * backoff:.1f}% fell back to global frequency")
    print(f"  distinct previous-words learned: {len(counts)}")

    if args.neural_top1 is not None:
        d1 = args.neural_top1 - 100 * t1
        print()
        print(f"  neural top-1 {args.neural_top1:.1f}%  vs  bigram {100 * t1:.1f}%   ->  {d1:+.1f} pts")
        if args.neural_top3 is not None:
            d3 = args.neural_top3 - 100 * tk
            print(f"  neural top-3 {args.neural_top3:.1f}%  vs  bigram {100 * tk:.1f}%   ->  {d3:+.1f} pts")
        print()
        if d1 >= 3.0:
            print("  VERDICT: ship it — a clear win, worth the ONNX session.")
        elif d1 >= 1.0:
            print("  VERDICT: marginal. Worth wiring as an ADDED ranking signal at low weight,")
            print("           not as a replacement for the bigram.")
        elif d1 > -1.0:
            print("  VERDICT: no meaningful difference. Skip it — the memory and load cost buy nothing.")
        else:
            print("  VERDICT: the bigram is BETTER. Do not ship the model; retrain or drop it.")


if __name__ == "__main__":
    main()
