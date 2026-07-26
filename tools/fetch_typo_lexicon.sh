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
# The asset is only replaced after a fetch that actually parsed entries, so a failed or blocked
# download can never leave an empty file behind (an empty asset silently disables the feature while
# looking installed).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="$ROOT/app/src/main/assets/dict"
OUT="$OUT_DIR/en_typos.txt"
URL="https://en.wikipedia.org/wiki/Wikipedia:Lists_of_common_misspellings/For_machines?action=raw"

# Wikimedia's User-Agent policy rejects generic/default agents with 403, which is the most common
# reason this fetch comes back empty. Identify the tool properly.
UA="TeclasKeyboard-typo-lexicon/1.0 (https://github.com/StrideB/clicks) curl"

raw="$(mktemp)"; parsed="$(mktemp)"
cleanup() { rm -f "$raw" "$parsed"; }
trap cleanup EXIT

echo "Fetching $URL"
code="$(curl -sSL -A "$UA" -w '%{http_code}' -o "$raw" "$URL")" || code="000"

if [ "$code" != "200" ]; then
  if [ "$code" = "000" ]; then
    echo "ERROR: could not reach Wikipedia (connection/proxy failure) — asset NOT modified." >&2
  else
    echo "ERROR: HTTP $code from Wikipedia — asset NOT modified." >&2
    [ "$code" = "403" ] && echo "  403 usually means the User-Agent was rejected by Wikimedia policy." >&2
  fi
  [ -s "$raw" ] && { echo "--- first 300 bytes of response ---" >&2; head -c 300 "$raw" >&2; echo >&2; }
  exit 1
fi

# Keep only the `typo->correction` payload lines; drop wiki markup, comments and blanks.
# The page indents every entry by one space (" abandonned->abandoned"), so the pattern must allow
# leading whitespace — anchoring straight to a letter silently matched nothing. Strip the indent on
# the way out so the asset is clean.
grep -E '^[[:space:]]*[A-Za-z]+->' "$raw" | sed 's/^[[:space:]]*//' > "$parsed" || true

if [ ! -s "$parsed" ]; then
  echo "ERROR: fetched $(wc -c < "$raw" | tr -d ' ') bytes but parsed 0 entries — asset NOT modified." >&2
  echo "The page format may have changed. First 300 bytes:" >&2
  head -c 300 "$raw" >&2; echo >&2
  exit 1
fi

mkdir -p "$OUT_DIR"
mv "$parsed" "$OUT"
trap - EXIT; rm -f "$raw"

total=$(wc -l < "$OUT" | tr -d ' ')
unambiguous=$(grep -cv ',' "$OUT" || true)
echo "Wrote $OUT"
echo "  $total entries total"
echo "  $unambiguous unambiguous (these are the ones the keyboard will actually install)"
echo
echo "Sample:"
head -3 "$OUT" | sed 's/^/  /'
echo
echo "Now commit it (it is a build input, like the wordlists):"
echo "  git add $OUT && git commit -m 'Add bulk English typo lexicon asset' && git push"
echo
echo "Verify on device via logcat:  TypoLexicon: installed N bulk typo entries"
