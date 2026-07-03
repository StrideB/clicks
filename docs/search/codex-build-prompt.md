# Clicks — Universal Search reskin (docked + widget), neumorphic

Reskin the launcher's **universal search** surface in the neumorphic language, for BOTH docked and widget
placement. This is the search that opens when you type on the homescreen — it is NOT app-only. Visual +
layout only; do not change search logic, ranking, the command parser, or what any result does.

**Read first:** `docs/neumorphic-redesign/design-spec.md` (tokens, `Neu.kt` helpers, shadow model) and
`docs/search/design-spec.md` (this surface's exact layout). Reference prototype:
`docs/search/prototype-universal-search.html`. Match it.

## What "universal search" actually is (from the repo — do not simplify)
Search results come from `universalSearchResults()` and render via `searchResultBentoCard` /
`searchResultRow`, typed by `SearchKind` in `LauncherModels.kt`:
`APP · CONTACT · EMAIL · MESSAGE · CALENDAR · AI · TRAVEL`. There is also **type-to-do**
(`executeTypeToDoCommand`, `ContactCommand`, `CalendarCommand`) for verbs (open/message/call/remind/
calendar/Gemini/music), and inline **Gemini** answers (`AiAnswerState`). The reskin must preserve ALL
of these result kinds, the best-match highlight, the command action, and the AI answer card. Keep
`highlightedLabel(...)` query highlighting.

## Ground rules
- Visual/layout only. Do NOT change `universalSearchResults()`, ranking, `executeTypeToDoCommand`,
  `openSearchResult`, or result actions. Reskin the *views* those functions build.
- Everything resolves from `NeuTokens` via the existing `Neu.kt` helpers (`Neu.apply` / `Modifier.neu`).
  No raw hex for surfaces or semantic colors; use `Neu.GREEN/ACCENT/AMBER/BLUE/TEAL/ORANGE/PURPLE`.
- Respect the active theme mode (Dark/Light/System) — search must reskin in both.
- Incremental commits; `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug` stays green.

## Behavior — DOCKED
Keyboard owns the bottom (unchanged). Results fill the whole content area above it as the bento/list
grid (`searchResultsGrid`). Reskin those cards to neumorphic (see design-spec). No behavior change to
how docked search opens (`auto-open library on homescreen typing`).

## Behavior — WIDGET (the important new interaction)
Keyboard is a fixed homescreen fixture — never moves, never covered. On typing:
1. Homescreen chrome (weather header + contextual widget stack / now-playing) **slides/collapses away**
   to free vertical space.
2. Results **slide up from BEHIND the keyboard's top edge** into the freed area (a clip region whose
   bottom = keyboard top; results start translated fully below that floor, i.e. behind the keyboard,
   and animate to 0).
3. Results occupy ONLY the space above the keyboard — the keyboard is z-above and is never overlaid.
On clear/dismiss: results **slide back down behind the keyboard** (same animation reversed), and the
chrome returns. Timings: chrome hide ~300ms; results slide ~400ms `cubic-bezier(.2,1,.32,1)`.
Implement in `MainActivity` where widget-mode search currently renders (the `homeKeyboardWidget()` /
content-region path). The keyboard shell keeps its RAISED neumorphic surface + upward shadow so results
visibly tuck under it.

## Result card reskin (both modes) — from design-spec §Search
- **Result card/row:** RAISED neumorphic surface (`Neu` RAISED_SM); **best match** gets a subtle accent
  ring in the kind's color (outline, not a filled border).
- **Icon well:** carved (PRESSED_SM) holding either the real app icon (APP) or a mono glyph for
  CONTACT/EMAIL/MESSAGE/CALENDAR/TRAVEL/AI in that kind's accent (glyphs P / @ / M / C / ✈ / AI as in
  `searchResultIcon`).
- **KIND tag:** small carved pill, mono, `ink-faint`.
- **Title** uses `highlightedLabel` (query span in `Neu.GREEN`); subtitle mono uppercase `ink-faint`.
- **Type-to-do command:** a prominent RAISED card, green ring, green action glyph, "DO THIS" style
  subtitle (e.g. "SEND … · MESSAGES") — ranked at top when the query is a command.
- **Gemini (AI) answer:** full-width RAISED card — carved "asked"/answer bubbles, inline command chips
  (carved, green mono), RAISED action buttons (primary = green). Purple "GEMINI" label + Clicks-C mark.
- Chip glyph text: dark ink in dark mode, `Neu.LIGHT_CHIP_TEXT` in light.

## Kind accent map (use `Neu.*`)
APP→BLUE, CONTACT→ORANGE, EMAIL→ACCENT, MESSAGE→TEAL, CALENDAR→AMBER, TRAVEL→PURPLE, AI→PURPLE.

## Verify (before done)
- [ ] All 7 `SearchKind`s render with correct glyph + accent + KIND tag; best match ringed.
- [ ] Type-to-do command card appears for command queries; Gemini answer card renders inline.
- [ ] Query highlighting preserved; result taps still call `openSearchResult` (unchanged).
- [ ] DOCKED: bento fills above the keyboard; keyboard unchanged.
- [ ] WIDGET: chrome slides away, results slide up from behind keyboard, never overlay it, slide back on clear.
- [ ] Both dark and light; no raw hex; carved vs raised correct.
- [ ] Search logic/ranking/actions unchanged. `./gradlew assembleDebug` green.

End with a summary + any `// TODO:`.
