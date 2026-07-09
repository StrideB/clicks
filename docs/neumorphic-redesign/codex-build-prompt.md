# Teclas — Neumorphic homescreen redesign + Light/Dark/System theme

Reskin the entire Teclas launcher homescreen in a **dark-and-light neumorphic (soft UI) design language**, and add a **theme setting** (Dark / Light / System) that switches the whole launcher. This spec matches an approved interactive prototype — match its look, proportions, and per-widget treatments exactly.

## Ground rules
- **This is a visual reskin + a theme setting. Do NOT change behavior, data, layout structure, or features.** Same widgets, same order, same conditional logic, same click/long-press actions.
- Preserve the README design rules: centered homescreen (weather header → centered contextual widget stack → favorites dock → keyboard placement modes), data-driven widgets, GoKeys scoped to its theme, Docked vs Widget placement untouched.
- Work incrementally; keep `./gradlew assembleDebug` green after each step. Small, focused commits.
- If a reskin would require changing behavior or structure, STOP and leave a `// TODO:` rather than forcing it.

## The design language: dark neumorphism (soft UI)
Every surface is extruded from or carved into **one continuous base color**. Depth comes ONLY from dual soft shadows — a lighter shadow top-left, a darker shadow bottom-right. No gloss highlights, no borders, no colored edge-light, no colored accent spines. Color appears ONLY as small centered chips (app/icon glyphs) and the existing semantic label colors (green NOW PLAYING, red INBOX/RIGHT NOW, amber UP NEXT, blue GOOGLE NEWS, teal RECENT PEOPLE).

Two element roles:
- **Raised** — extruded outward (cards, keys, favorites caps, profile orbs, transport buttons). Dual outer shadows.
- **Pressed/carved** — inset (screen well, icon wells, album-art well, progress grooves, typing strip, calendar sub-blocks, dock tray, count badges). Inset dual shadows.
- Keys/buttons animate raised→pressed on touch-down so they physically depress.

### Token values (use these exactly)
**Dark mode**
```
base    #181b21
base-hi #20242c   (light shadow, top-left)
base-lo #0e1014   (dark shadow, bottom-right)
ink     #e8ebef   ink-dim #899099   ink-faint #565d66
raised     : -5dp -5dp 11dp base-hi , 6dp 6dp 14dp base-lo
raised-sm  : -3dp -3dp 7dp  base-hi , 4dp 4dp 9dp  base-lo
pressed    : inset -4dp -4dp 9dp base-hi , inset 5dp 5dp 11dp base-lo
pressed-sm : inset -2dp -2dp 5dp base-hi , inset 3dp 3dp 7dp base-lo
```
**Light mode** (tight tonal range — soft, NOT high-contrast; larger blur, smaller offset)
```
base    #eceef2
base-hi #ffffff   (light shadow, top-left)
base-lo #d3d7de   (dark shadow, bottom-right)  ← keep close to base; do NOT use a dark grey
ink     #3a3f47   ink-dim #828892   ink-faint #a8adb6
raised     : -4dp -4dp 12dp base-hi , 5dp 5dp 13dp base-lo
raised-sm  : -2dp -2dp 6dp  base-hi , 3dp 3dp 8dp  base-lo
pressed    : inset -3dp -3dp 8dp base-hi , inset 4dp 4dp 9dp base-lo
pressed-sm : inset -2dp -2dp 4dp base-hi , inset 2dp 2dp 5dp base-lo
```
Semantic accent colors are shared across modes: green `#28E06A`, red/accent `#FF4B45`, amber `#E8B84B`, blue `#3B9DFF`, teal `#5FD0C4`, orange `#FF8A4C`, purple `#A071FF`. In light mode, darken chip glyph text to `#20242b` so bold chips stay legible.

### Implementing neumorphic shadows on Android
Android Views don't do dual drop-shadows natively. Use layered `GradientDrawable`/`LayerDrawable` backgrounds or custom `Drawable`s that paint a light shadow (top-left) and dark shadow (bottom-right), plus an inset variant for "pressed." Centralize this in a small helper (e.g. `Neu.raised(view, radius)`, `Neu.pressed(view, radius)`, `Neu.raisedSmall(...)`, `Neu.pressedSmall(...)`) that reads the current theme's base/hi/lo colors. Compose surfaces (the widget stack in `HomeWidgetStack.kt`) should get equivalent Modifiers (two offset shadow layers + rounded background in the base color). Do NOT hardcode the hex values at call sites — resolve them from the active theme so Light/Dark/System swaps everything at once.

## Per-widget treatments (match the prototype)
Apply to `HomeWidgetStack.kt` cards and the surrounding home chrome. The widget stack is the **tall hero block** in the center — cards fill that height, content distributed vertically (not clustered at top). Keep the existing pager, ordering, and recency sort.

- **Weather header** — compact. Sun = raised disc with an amber core. Temp/cond/feels text in ink tones.
- **Music** (`WidgetItem.Music`) — album art becomes a **carved well holding raised art tiles** (or a raised soft frame around the real bitmap — keep the real `albumArt` when present). Green NOW PLAYING label + equalizer kept. Add the progress as a **carved groove with green fill**.
- **Maps** (`WidgetItem.Maps`) — "ON THE ROAD" green label, carved map tile, title (ETA/destination) + preview, "Tap to resume route" line. Keep real content.
- **Email** (`WidgetItem.Email`) — red INBOX label + carved count badge, a primary **carved tray row** (sender + preview), then up to two secondary dotted subrows (`emails.drop(1).take(2)`).
- **Recent People** (`WidgetItem.People`) — teal RECENT PEOPLE label + count; multi-person = 3 **carved chips** each with a **raised profile orb** (avatar bitmap when present, else initials); single person = wide card.
- **News** (`WidgetItem.News`) — blue GOOGLE NEWS label, raised/mono news icon, headline + 2-line preview.
- **Calendar** (`WidgetItem.Calendar`) — two **carved blocks**: RIGHT NOW (red/green when free) and UP NEXT (amber) with the `+N` badge. Always-present floor.
- **Favorites dock** — a **carved tray** holding up to 5 **raised circular caps** with centered color chips.
- **Keyboard** — keys become **raised soft keys** that depress on touch; typing strip = carved groove; ZEISS button = carved pill; DOCK stays green, GO stays red/accent. Apply within existing key rendering; keep GoKeys geometry scoped to the gokeys theme.
- **Nav / gesture pill / count badges** — carved.

Match the prototype's proportions: compact weather, tall centered widget stack, dock, then keyboard — the widget stack is the dominant central element.

## Theme setting: Dark / Light / System
- Add a **theme mode** preference: `THEME_MODE_PREF` with values `dark` | `light` | `system` (default `system`).
- Add a control in **Keyboard/Launcher Settings** (wherever the existing appearance settings live) — a 3-way selector: **Dark · Light · System**.
- **System** follows the OS: resolve via `Configuration.uiMode & UI_MODE_NIGHT_MASK` (or `AppCompatDelegate` night mode). When the device toggles dark/light, the launcher follows — honor `onConfigurationChanged` / re-resolve on resume so it updates live.
- Resolve the active token set (base/hi/lo/ink + the raised/pressed drawables) from the mode, and re-apply on: launch, setting change, and system-appearance change. A single `applyTheme()` that rebuilds the neumorphic drawables and re-renders home is the cleanest path.
- Persist the choice; apply on next launch before first draw to avoid a flash.

## Constraints & quality
- Don't regress: widget content, pager swiping, click/long-press actions, favorites, weather, calendar permission states, keyboard input/prediction, Docked/Widget placement — all unchanged.
- No new heavy dependencies; layered drawables / custom Drawable painting is enough. `androidx.dynamicanimation` (already present) is fine for press animations.
- Light mode must read **soft, not high-contrast** — keep the base/hi/lo tonal range tight per the tokens above. This was an explicit correction: a too-dark `base-lo` makes hard ugly edges.
- Centralize theme tokens; no hardcoded hex at call sites.

## Deliverable & commits
1. Theme token system + `Neu` drawable helpers (dark + light) + `applyTheme()` plumbing.
2. Theme-mode preference + Dark/Light/System selector in settings + System-follow logic.
3. Reskin home chrome (weather, dock, keyboard, strip).
4. Reskin the widget stack cards in `HomeWidgetStack.kt` (all six types) to match the prototype, tall-hero proportions.
End with a summary of what changed, any `// TODO:` left, and confirmation `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug` succeeds.

## Reference
Approved prototype: `work/artifacts/teclas_home_full_stack/index.html` (full stack, dark+light, correct proportions). Supporting mocks: `teclas_neumorphic_system/` (all screens), `teclas_music_redesign/`, `teclas_launcher_redesign/` (real-vs-redesign). Match their look, the token values above, and the per-widget treatments.
