# Fold 8 Book Pages — visual prototype

Mock of the two-page calm unfolded home shipped in PR #144, at the Fold 8's inner
geometry (2448×1848 ≈ 933×704dp, 4:3 landscape, two ~420dp pages).

Open `index.html` in a browser:

- **Day page** (left by default): the Today timeline — next event hero, message and
  travel signals.
- **Continuity page** (right by default): clock, contextual widget stack (music,
  recent people), with the favorites dock aligned beneath at page width — the half
  that was physically the cover, so unfolding adds a page instead of rearranging
  the one you were on.
- **Gutter**: the fold line. Flex Titanium killed the crease, so it's layout
  rhythm, not a drawn divider.
- **SWAP** mirrors the pages (`inner_pages_swap` pref).
- **TYPE-TO-SEARCH** (or the typing strip / any key) shows the takeover: typing
  collapses the pages into one spanning canvas; clearing the query settles back.

The keyboard deck is Phase 1's centered wide deck. Phase 2 replaces it with split
half-decks under the thumbs; Phase 3 turns the search takeover into real
two-column zones.
