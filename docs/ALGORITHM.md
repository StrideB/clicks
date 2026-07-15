# The Teclas Unified Keyboard Algorithm

The owned, on-device intelligence stack behind both Teclas keyboards (IME + launcher).
Everything here runs locally, learns locally, and is proprietary to this codebase — the only
external ingredients are permissively-licensed data (wordlists, and optionally the MIT-licensed
FUTO swipe corpus for decoder training).

## Architecture: score fusion

Rather than running decode → spell-correct → language-model in sequence (each stage guessing
alone), all evidence is fused into ONE composite score per candidate word — the same shape as
Gboard's decoder (spatial model + language model scores fused during search) and FUTO's
three-model stack (gesture encoder + decoder + context LM).

```
                 ┌────────────────────────────────────────────────────┐
 taps ──────────▶│               CANDIDATE GENERATION                 │
                 │ PredictionEngine: first-letter buckets, budget-    │
 glide path ────▶│ capped keyboard-aware edit distance (400 evals);   │
                 │ HybridDecoder: neural ONNX + statistical shape     │
                 └───────────────────────┬────────────────────────────┘
                                         ▼
                 ┌────────────────────────────────────────────────────┐
                 │            UNIFIED RANKER (score fusion)           │
                 │  Σ wᵢ · signalᵢ over 8 signals (below); weights    │
                 │  adapted per user, hard-bounded to ±50% defaults   │
                 └───────────────────────┬────────────────────────────┘
                                         ▼
                 suggestion strip · autocorrect · glide commit
```

### The 8 signals (each 0..1, weighted)

| # | Signal | Default weight | Source | What it captures |
|---|--------|----------------|--------|------------------|
| 1 | Edit distance | 0.36 | PredictionEngine (Damerau–Levenshtein; adjacent-key sub 0.4, transposition 0.5) | What the fingers actually did (the *spatial* leg) |
| 2 | Global frequency | 0.22 | wordlist, normalized within candidate set | How common the word is |
| 3 | Personal usage | 0.12 | PersonalFrequencyStore (capped 400, halving decay) | This user's vocabulary |
| 4 | N-gram context | 0.09 | NgramRepository (personal bigrams) | What THIS user says after the previous word |
| 5 | Language model | 0.08 | ContextModel (compact bigram LM asset) | What the LANGUAGE says follows the previous word |
| 6 | Completion | 0.08 | prefix match | Prediction vs rewrite |
| 7 | Phonetic | 0.03 | curated misspelling table (~45 entries) | Classic typos whose fix isn't nearest-by-distance |
| 8 | Morphology | 0.02 | affix-repair rules (doubling, y→i, silent-e, ie/ei) | Systematic word-formation errors |

Deterministic tiers answer before scoring: a dictionary-validated phonetic-table hit or
morphological repair IS the correction (immune to the frequency trap). The scored tier keeps the
engine's safety guards: never rewrite a dictionary word, refuse ties where the runner-up is the
more common word, never repeat a rejected correction.

## The learning loop (all on-device, shared by both keyboards via "teclas" prefs)

| Event | Store | Effect |
|---|---|---|
| Word committed (typed / pick / glide) | PersonalFrequencyStore | word rises in ranking (saturating boost; can't overturn a clear typo fix) |
| Word committed after another | NgramRepository (Room) | personal next-word predictions + context signal |
| Autocorrect backspaced | RejectedCorrectionsStore | that (typed→fix) pair never fires again, anywhere, persistently |
| Suggestion #2/#3 picked over #1 | AdaptiveWeights | signals favoring the user's pick gain weight (LR 0.02, ±50% hard bounds, mass-conserving, clamp-last) |
| Suggestion #1 picked | AdaptiveWeights | weights decay toward defaults (stale adaptations fade) |
| Glide accepted / corrected | GlideLearningStore | personal frequency + on-device gesture corpus for decoder fine-tuning |
| Confident tap | SpatialScorer | per-key touch-offset means + global prior (see below) |

Safety rails everywhere: caps, decay, hard bounds, reset paths, and 100% local storage.

## Spatial personalization (already ahead of the curve)

`SpatialScorer` implements the technique published by Google for Gboard (Sivek & Riley,
MobileHCI 2022, arXiv:2209.11311): a Gaussian key model with **personalized per-key offset
means** plus a global-prior fallback, learned online from confident taps only (outlier taps
rejected), clamped so no tap can skew the layout. Their study showed personalized offset means
give small-but-significant accuracy/speed wins; covariance learning was optional. We also do
language-weighted near-boundary tie-breaking (`nextCharWeights`) — Gboard's "key target
resizing" — boost-only, so geometry always wins on a confident tap.

## Data assets

| Asset | Size | License / provenance |
|---|---|---|
| 6 language wordlists (`assets/dict/*_wordlist.txt`) | – | shipped |
| Bigram LM seed (`assets/dict/en_bigrams.txt`, 597 pairs) | 8 KB | generated by `tools/lm/build_bigrams.py --seed` (owned) |
| Neural swipe models (`swipe_{encoder,decoder}.onnx`) | – | trained by `tools/neural_swipe` on OWN synthetic data |
| On-device glide corpus (filesDir JSONL) | capped | user's own swipes, never uploaded |

## Tier model for text intelligence

1. **Tier 1 — unified ranker** (this doc): per-keystroke, <5 ms, background thread.
2. **Tier 2 — sentence checks** (`SentenceChecks`, built + tested, not yet surfaced in UI):
   doubled-word collapse, standalone-i capitalization, a/an agreement (with silent-h /
   consonant-sound exceptions). Runs at word boundaries only. UI wiring pending a UX decision
   (auto-apply vs one-tap chip). Harper (Apache-2.0 Rust linter) is the candidate engine for the
   full rule set later — via NDK, behind the same interface.
3. **Tier 3 — AI polish**: existing Gemini compose/polish hooks (long-press space/enter).

## Research notes → roadmap (highest value first)

1. **Real-gesture decoder training — the big one.** FUTO released the largest MIT-licensed swipe
   corpus (swipe.futo.org, >1M donated swipes). Their models/inference are NOT permissive (FUTO
   Model License / GPL) — do not import code or weights — but the MIT corpus can legally train
   OUR pipeline. Action: extend `tools/neural_swipe/train.py` to mix the FUTO corpus with our
   synthetic generator and the on-device corpus. Expected: the single biggest glide-accuracy jump
   available.
2. **Layout-conditioned decoder (FUTO's architectural idea, our implementation):** feed key
   geometry as inference-time input instead of baking QWERTY into weights. Our featurization
   already normalizes to the key box; adding per-key position conditioning is a `train.py` +
   featurizer change we own end-to-end.
3. **Bigger bigram LM:** replace the 8 KB seed with a corpus-built table
   (`build_bigrams.py --corpus …`, ~8k bigrams ≈ 60 KB in APK). Pure data swap, no code.
4. **Touch-point-aware correction:** thread the current word's actual tap coordinates into the
   ranker so substitution costs use THIS word's touch evidence (SpatialScorer.probability), not
   just static adjacency. (Gboard fuses spatial scores this way.)
5. **Trigram context** for the LM signal once the bigram table proves out; same file format with
   a two-word prev key.
6. **Covariance learning** in SpatialScorer (the optional half of the Gboard paper) — only if
   pick-rank telemetry shows spatial misses still dominate.

Sources: Google Research "Machine Intelligence Behind Gboard" (research.google/blog); Ouyang et
al., "Mobile Keyboard Input Decoding with Finite-State Transducers"; Sivek & Riley, "Spatial
model personalization in Gboard" (arXiv:2209.11311); "FUTO Swipe: Layout-Agnostic Neural Swipe
Decoding" (arXiv:2606.25247) + swipe.futo.org corpus release.
