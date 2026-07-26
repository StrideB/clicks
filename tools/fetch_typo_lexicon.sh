#!/usr/bin/env bash
# Fetches Wikipedia's machine-readable common-misspellings list and installs it as the bulk typo
# lexicon asset. Run from anywhere; writes app/src/main/assets/dict/en_typos.txt.
#
#   bash tools/fetch_typo_lexicon.sh
#
# The list is CC BY-SA (Wikipedia) and contains ~4,000 entries in `typo->correction` form. Lines
# offering MORE THAN ONE correction are kept verbatim here — TypoLexiconLoader skips them at load
# time on purpose, because an ambiguous source entry can't be resolved without sentence context and
# guessing would produce a confident wrong rewrite.
#
# Rerun any time to refresh. Nothing else needs to change: the loader picks the file up at startup,
# and curated built-ins in PhoneticPatterns always win over anything in here.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="$ROOT/app/src/main/assets/dict"
OUT="$OUT_DIR/en_typos.txt"
URL="https://en.wikipedia.org/wiki/Wikipedia:Lists_of_common_misspellings/For_machines?action=raw"

mkdir -p "$OUT_DIR"
echo "Fetching $URL"
tmp="$(mktemp)"
curl -fsSL "$URL" -o "$tmp"

# Keep only the `typo->correction` payload lines; drop wiki markup, comments and blanks.
grep -E '^[A-Za-z]+->' "$tmp" > "$OUT" || {
  echo "No entries parsed — the page format may have changed. Left $OUT untouched." >&2
  rm -f "$tmp"; exit 1
}
rm -f "$tmp"

total=$(wc -l < "$OUT" | tr -d ' ')
unambiguous=$(grep -cv ',' "$OUT" || true)
echo "Wrote $OUT"
echo "  $total entries total"
echo "  $unambiguous unambiguous (these are the ones the keyboard will actually install)"
echo
echo "Rebuild the app to pick it up. Verify on device via logcat:  TypoLexicon: installed N bulk typo entries"
