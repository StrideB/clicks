#!/usr/bin/env python3
"""Build the compact bigram LM asset (app/src/main/assets/dict/en_bigrams.txt) for ContextModel.

Two modes:

1. From a real corpus (recommended once you have one):
     python build_bigrams.py --corpus big.txt --wordlist ../../app/src/main/assets/dict/en_wordlist.txt \
         --top-bigrams 8000 --out ../../app/src/main/assets/dict/en_bigrams.txt
   Counts word pairs over the corpus, keeps pairs whose BOTH words are in the app wordlist,
   drops rare pairs, writes `prev word count` lines. 8k bigrams ≈ ~120KB raw, ~60KB in APK.

2. Seed mode (no corpus): --seed writes the curated high-frequency function-word bigram seed
   that ships until a corpus-built table replaces it. These are the closed-class continuations
   (article→noun-ish, pronoun→verb, preposition→article, …) that carry most of a bigram model's
   disambiguation power for typing.

Output format (ContextModel.load): one bigram per line, `prev word count`, '#' comments allowed.
Counts are arbitrary positive integers — the model normalizes per-prev at load time.
"""
import argparse
import collections
import re
import sys

WORD_RE = re.compile(r"[a-z']+")


def build_from_corpus(corpus_path, wordlist_path, top_bigrams, min_count):
    vocab = set()
    with open(wordlist_path, encoding="utf-8") as f:
        for line in f:
            w = line.strip().split()[0].lower() if line.strip() else ""
            if w:
                vocab.add(w)
    counts = collections.Counter()
    with open(corpus_path, encoding="utf-8", errors="ignore") as f:
        prev = None
        for line in f:
            for w in WORD_RE.findall(line.lower()):
                if prev is not None and prev in vocab and w in vocab:
                    counts[(prev, w)] += 1
                prev = w
            prev = None  # sentence/line boundary resets context
    kept = [(p, w, n) for (p, w), n in counts.most_common(top_bigrams) if n >= min_count]
    return kept


# Curated seed: (prev, [continuations most-likely-first]). Expanded into counts by rank.
SEED = {
    "the": ["same first best other most next only new following right way day time world end"],
    "a": ["few lot little bit good great new big small long very single couple moment while"],
    "an": ["hour example idea old email extra easy early important amazing"],
    "to": ["be do go get make see the take have know say help find keep work start"],
    "of": ["the a course all this his my your our their its them us it what"],
    "in": ["the a this my your his our their order fact front case general terms"],
    "on": ["the a my your his their top time this that monday it"],
    "for": ["the a this you me us them your my his it now example"],
    "and": ["the i then a we they it he she that so this you"],
    "i": ["am was have will would can think know want need just don't didn't love"],
    "you": ["are can have will know want need should think were don't didn't"],
    "we": ["are have will can were should need want don't didn't"],
    "they": ["are have were will can don't didn't said want"],
    "he": ["was is has had will said would can didn't doesn't"],
    "she": ["was is has had will said would can didn't doesn't"],
    "it": ["is was will would has can seems looks doesn't didn't"],
    "is": ["a the not that this it what going very really just still"],
    "was": ["a the not that just so very really going still"],
    "are": ["you the not that they we going still really very"],
    "be": ["a the able here there good great done ready sure"],
    "have": ["a the to been not any some no you never"],
    "has": ["been a the to not no never already just"],
    "had": ["a the to been not no never already just"],
    "will": ["be have not never do get make take see"],
    "would": ["be have not like love never do make"],
    "can": ["be do you get see make take help not"],
    "do": ["you not it that this the something anything"],
    "don't": ["know want think have see forget worry need"],
    "not": ["a the sure going to be that really very"],
    "at": ["the a least this that home work all night noon"],
    "with": ["the a you me us them my your his this"],
    "this": ["is was will would year time week morning one"],
    "that": ["is was the you we they he she it would"],
    "my": ["own first phone house friend family life way work day"],
    "your": ["own phone house friend family life way work help time"],
    "what": ["is are do you the a about time happened"],
    "how": ["to do you are the much many long about"],
    "when": ["you i we the they it he she is was"],
    "where": ["you i we the they is are was did"],
    "so": ["i much many far that we they you it"],
    "just": ["a the one like wanted got finished about"],
    "about": ["the a it this that you me us them"],
    "all": ["the of that this day right over about"],
    "as": ["a the well soon much long you we it"],
    "from": ["the a my your his our their you me"],
    "there": ["is are was were will would"],
    "going": ["to on out over back home well"],
    "want": ["to a the you it that more some"],
    "need": ["to a the you it that more some help"],
    "like": ["a the to that this you it them"],
    "one": ["of the day thing more time way"],
    "good": ["morning night day luck idea time job news"],
    "thank": ["you"],
    "let": ["me us them you it"],
    "see": ["you the it them what if"],
    "talk": ["to about soon later with"],
    "get": ["a the it back home to some more"],
    "got": ["a the it back home to some more"],
    "right": ["now away here there back"],
    "last": ["night week year time month day"],
    "next": ["week year time month day to"],
    "every": ["day time week year one single"],
    "no": ["one problem way idea longer more matter"],
    "very": ["good much well nice happy important different"],
    "really": ["good nice want like appreciate happy"],
    "on my": ["way"],
}


def build_seed():
    out = []
    for prev, blobs in SEED.items():
        words = " ".join(blobs).split()
        n = len(words)
        for rank, w in enumerate(words):
            # Rank-decayed pseudo-counts: first continuation strongest.
            out.append((prev, w, max(1, (n - rank) * 10)))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--corpus")
    ap.add_argument("--wordlist")
    ap.add_argument("--seed", action="store_true")
    ap.add_argument("--top-bigrams", type=int, default=8000)
    ap.add_argument("--min-count", type=int, default=3)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    if args.seed:
        rows = build_seed()
    elif args.corpus and args.wordlist:
        rows = build_from_corpus(args.corpus, args.wordlist, args.top_bigrams, args.min_count)
    else:
        ap.error("either --seed or (--corpus and --wordlist)")
        return

    with open(args.out, "w", encoding="utf-8") as f:
        f.write("# bigram LM for ContextModel: `prev word count` (counts normalized per-prev at load)\n")
        for prev, w, n in rows:
            if " " in prev:  # multi-word prevs not supported by the on-device model yet
                continue
            f.write(f"{prev} {w} {n}\n")
    print(f"wrote {len(rows)} bigrams to {args.out}", file=sys.stderr)


if __name__ == "__main__":
    main()
