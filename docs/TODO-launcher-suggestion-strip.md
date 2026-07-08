# TODO (codex/design): launcher suggestion strip — collapse when idle, expand on typing

**Goal:** On the **launcher keyboard**, when nothing is being typed there should be **no empty
suggestion-strip space above the search/typing well**. When the user starts typing, the row should
**expand** to show a typing indicator + the suggestion box, then collapse again when the field clears.

## Why this is a design/layout task (not a quick flag)
The launcher **bakes the strip height into the keyboard at build time**, so hiding the strip's
*content* at runtime doesn't reclaim the space — it leaves a gap. (I tried the runtime `visibility =
GONE` approach in `updateSuggestionBar()` and reverted it in commit `45a2e21` because of that gap.)
Doing this properly needs the keyboard height itself to be **dynamic**.

Relevant code in `MainActivity.kt`:
- `keyboardSuggestionStripHeight()` ≈ `dp(28)` — the strip row height, applied at build time where
  `suggestionStrip()` is `addView`'d (~line 8613).
- `suggestionStripHeight()` = `keyboardTypingWellHeight() + dp(9)` — reserved in the **total keyboard
  height** calc (~line 17104). This is the space that stays behind when the strip content hides.
- `updateSuggestionBar()` (~line 9315) — where strip content is rendered; `fieldBlank` already exists
  here as the "nothing typed" signal.

## Desired behavior
- `query` / `composeText` **blank** → suggestion row height 0 (search sits right above the keys).
- First keystroke → row expands to `keyboardSuggestionStripHeight()`, showing a **typing indicator**
  + the suggestion box; stays up while there's text; collapses when the field clears.
- Avoid per-keystroke jank — animate the height change, or reserve height only while composing.

## Reference
The **IME** already does the simple version cleanly: `ClicksImeService.updateStrip()` sets
`suggestionStrip.visibility = GONE` when the field is empty and `VISIBLE` otherwise (the IME's strip
collapses because its height isn't baked into a fixed total). Use that as the behavior model; the
launcher just also needs the **container/keyboard height** to follow.
