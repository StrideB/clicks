# Clicks Universal Search — code-level design spec

Companion to `clicks-search-codex-prompt.md`. Exact layout/measurements for the neumorphic search
surface. Tokens, shadow presets, and the `Neu` helpers come from `../neumorphic-redesign/design-spec.md`
— this file only covers the search-specific structures. Reference: `prototype-universal-search.html`.

## Shared token reminder
One base color; depth from dual soft shadows (RAISED / RAISED_SM / PRESSED / PRESSED_SM). No borders,
no gloss, no colored edge-light. Color only in centered chips + semantic label text. Kind accents:
APP `#3B9DFF` · CONTACT `#FF8A4C` · EMAIL `#FF4B45` · MESSAGE `#5FD0C4` · CALENDAR `#E8B84B` ·
TRAVEL `#A071FF` · AI `#A071FF`. Query-highlight span = GREEN `#28E06A`.

## Result row (list form — widget mode + docked list)
- Container: radius 15dp, `Neu` RAISED_SM. Best match: same but RAISED + a 1.5dp accent-color outline
  (outline-offset -1) in the kind color. Padding 9×11, row layout, gap 11.
- Icon well: 40dp, radius 12, PRESSED_SM. Inside:
  - APP → real app icon (via existing `iconFor`), 26dp, clipped radius 8.
  - non-APP → mono glyph in a RAISED_SM inner chip, glyph = kind accent color, 11–12sp bold. Glyphs:
    CONTACT "P", EMAIL "@", MESSAGE "M", CALENDAR "C", TRAVEL "✈", AI "AI".
- Title: 13.5sp, weight 600, ink; `highlightedLabel` puts the matched span in GREEN. Ellipsize.
- Subtitle: 9sp mono, uppercase, letter-spacing .6, ink-faint. Ellipsize.
- KIND tag (right): mono 7.5sp, letter-spacing 1, ink-faint, carved PRESSED_SM pill, padding 3×7.
- Tap → existing `openSearchResult(result)`. Row press: RAISED_SM → PRESSED_SM.

## Result card (bento form — docked grid, `searchResultBentoCard`)
- 2-column grid, ~104dp row height, 10dp gaps (keep current grid math).
- Card: radius 20dp, RAISED_SM; best/first = RAISED + accent outline. Vertical: icon well (48dp for
  APP, 42dp otherwise, carved) → title (centered, highlighted) → for non-APP a mono subtitle line.
- Staggered entrance: translateY 8dp + fade, 28ms per index (keep current timing).

## Type-to-do command card (top of results when query is a command)
- Full-width RAISED card, radius 15, green (`#28E06A`) 1.5dp outline ring.
- Left: 40dp RAISED_SM well, green action glyph (➤ / ＋ / ✆ depending on verb).
- Title: 14sp weight 600 ink (e.g. "Message Alex Rivera"). Subtitle: 9sp mono green uppercase
  describing the action ("SEND \"on my way\" · MESSAGES", "CREATE EVENT · CALENDAR", etc.).
- Tap → runs the command (existing `executeTypeToDoCommand` path). This is the primary/first item.

## Gemini (AI) answer card
- Full-width RAISED card, radius 16, padding 12.
- Header: 28dp RAISED_SM well with Clicks "C" mark in PURPLE; "Clicks AI" (12sp, 600) + "GEMINI"
  (8sp mono, PURPLE).
- Answer bubble: carved PRESSED_SM, radius 11, padding 10, 12sp ink, line-height 1.55. Inline commands
  wrapped in a carved PRESSED_SM chip, mono 10.5sp GREEN.
- Optional "asked" bubble above the answer (carved, ink-dim) mirroring the Clicks AI pane.
- Action buttons row: RAISED_SM pills, 10.5sp, last button primary (GREEN text). Tap → existing actions.

## Layout — docked
Content area above the fixed keyboard = the bento/list (`searchResultsGrid`). Reskin only. Header
"App Library / Results" + Categories/Grid toggle stays; restyle to neumorphic (carved toggle pill).

## Layout — widget (interaction)
- A clip region spanning from just under the status bar to the keyboard's top edge. Overflow hidden.
- Results container lives inside it, `translateY(102%)` when idle (fully below the clip floor = behind
  the keyboard), `translateY(0)` when open. Transition transform ~400ms `cubic-bezier(.2,1,.32,1)`.
- On type: weather header animates opacity→0 and max-height→0 (collapses), contextual stack fades;
  results open. On clear: results close (slide back behind keyboard), then chrome restores (~180ms later).
- Keyboard shell: RAISED + an upward shadow so results tuck visibly under it. Keyboard z-above results.
- Hard rule: results never draw over the keyboard; the clip floor = keyboard top.

## Motion
- Chrome hide/return: 300ms opacity + max-height.
- Results slide: 400ms cubic-bezier(.2,1,.32,1), same curve reversed on dismiss.
- Rows/cards: staggered 8–10dp rise + fade, ~26–28ms per index.
- Card/row press: RAISED→PRESSED spring-back.

## Fidelity check
- [ ] 7 kinds correct glyph/accent/tag; best match ringed in kind color.
- [ ] Command card (green ring) + Gemini card render; query highlight in green.
- [ ] Widget: chrome collapses, results rise from behind keyboard, clamped above it, reverse on clear.
- [ ] Docked: bento fills above keyboard, neumorphic, header toggle restyled.
- [ ] Dark + light both; carved vs raised correct; no raw hex.
- [ ] Matches `prototype-universal-search.html`.
