"""
Train the neural swipe decoder and export it for the `teclas` launcher.

Produces exactly what NeuralSwipeEngine.kt loads:
  neural_swipe_engine.onnx   input "src" [1,50,4]  ->  output word logits [1, vocab]
  neural_swipe_vocab.txt      one word per line, line index == model vocab index

The architecture mirrors the Neuroswipe/IndicSwipe blueprint: a Transformer encoder over the
[x,y,dx,dy] sequence, mean-pooled, projected to a whole-word vocabulary head.

Usage:
    pip install torch numpy
    python train.py --wordlist ../../app/src/main/assets/dict/en_wordlist.txt \
                    --vocab-size 12000 --samples-per-word 60 --epochs 6 --out ../../app/src/main/assets
"""

import argparse
import math
import os
import numpy as np
import torch
import torch.nn as nn
from torch.utils.data import Dataset, DataLoader

from synth import synth_gesture, SEQ_LEN


# ── Model ────────────────────────────────────────────────────────────────────

class SpatialPositionalEncoding(nn.Module):
    def __init__(self, d_model, max_len=SEQ_LEN):
        super().__init__()
        self.pe = nn.Parameter(torch.randn(1, max_len, d_model) * 0.02)

    def forward(self, x):
        return x + self.pe[:, : x.size(1)]


class NeuralSwipeTransformer(nn.Module):
    def __init__(self, vocab_size, d_model=128, nhead=4, num_layers=3):
        super().__init__()
        self.input_projection = nn.Linear(4, d_model)
        self.pos_encoder = SpatialPositionalEncoding(d_model)
        layer = nn.TransformerEncoderLayer(d_model=d_model, nhead=nhead,
                                           dim_feedforward=256, batch_first=True)
        self.transformer_encoder = nn.TransformerEncoder(layer, num_layers=num_layers)
        self.char_classifier = nn.Linear(d_model, vocab_size)

    def forward(self, src):
        x = self.input_projection(src)
        x = self.pos_encoder(x)
        x = self.transformer_encoder(x)
        pooled = torch.mean(x, dim=1)
        return self.char_classifier(pooled)


# ── Data ─────────────────────────────────────────────────────────────────────

class SwipeDataset(Dataset):
    """Generates noisy synthetic gestures on the fly — effectively unlimited data."""
    def __init__(self, words, samples_per_word, seed=0):
        self.words = words
        self.n = len(words) * samples_per_word
        self.spw = samples_per_word
        self.seed = seed

    def __len__(self):
        return self.n

    def __getitem__(self, i):
        widx = i // self.spw
        rng = np.random.default_rng(self.seed * 1_000_003 + i)
        feats = None
        while feats is None:
            feats = synth_gesture(self.words[widx], rng)
            if feats is None:
                widx = (widx + 1) % len(self.words)
        return torch.from_numpy(feats), widx


def load_words(path, vocab_size):
    words = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            parts = line.split()
            if not parts:
                continue
            w = parts[0].strip().lower()
            if w.isalpha() and 2 <= len(w) <= 18:
                words.append(w)
            if len(words) >= vocab_size:
                break
    return words


# ── Train + export ───────────────────────────────────────────────────────────

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--wordlist", required=True)
    ap.add_argument("--vocab-size", type=int, default=12000)
    ap.add_argument("--samples-per-word", type=int, default=60)
    ap.add_argument("--epochs", type=int, default=6)
    ap.add_argument("--batch-size", type=int, default=256)
    ap.add_argument("--lr", type=float, default=3e-4)
    ap.add_argument("--out", default="../../app/src/main/assets")
    args = ap.parse_args()

    device = "cuda" if torch.cuda.is_available() else "cpu"
    words = load_words(args.wordlist, args.vocab_size)
    print(f"vocab: {len(words)} words on {device}")

    ds = SwipeDataset(words, args.samples_per_word)
    dl = DataLoader(ds, batch_size=args.batch_size, shuffle=True, num_workers=4, drop_last=True)

    model = NeuralSwipeTransformer(len(words)).to(device)
    opt = torch.optim.AdamW(model.parameters(), lr=args.lr)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=args.lr,
                                                steps_per_epoch=len(dl), epochs=args.epochs)
    loss_fn = nn.CrossEntropyLoss()

    for epoch in range(args.epochs):
        model.train()
        running, correct, seen = 0.0, 0, 0
        for feats, target in dl:
            feats, target = feats.to(device), target.to(device)
            opt.zero_grad()
            logits = model(feats)
            loss = loss_fn(logits, target)
            loss.backward()
            opt.step(); sched.step()
            running += loss.item() * feats.size(0)
            correct += (logits.argmax(1) == target).sum().item()
            seen += feats.size(0)
        print(f"epoch {epoch+1}/{args.epochs}  loss={running/seen:.4f}  top1={correct/seen:.3f}")

    os.makedirs(args.out, exist_ok=True)
    vocab_path = os.path.join(args.out, "neural_swipe_vocab.txt")
    with open(vocab_path, "w", encoding="utf-8") as f:
        f.write("\n".join(words))
    print("wrote", vocab_path)

    model.eval().to("cpu")
    dummy = torch.randn(1, SEQ_LEN, 4)
    onnx_path = os.path.join(args.out, "neural_swipe_engine.onnx")
    torch.onnx.export(
        model, dummy, onnx_path,
        input_names=["src"], output_names=["logits"],
        dynamic_axes={"src": {0: "batch"}, "logits": {0: "batch"}},
        opset_version=17,
    )
    print("wrote", onnx_path)
    print("Rebuild the app — NeuralSwipeEngine.isReady flips true automatically.")


if __name__ == "__main__":
    main()
