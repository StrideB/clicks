# Teclas Neumorphic Design Spec — complete, code-level

This is the **authoritative design bible** for the neumorphic homescreen reskin. It hands over exact values, shadow strings, structures, and proportions so the implementation is a translation, not an interpretation. The approved prototype at `work/artifacts/teclas_home_full_stack/index.html` is the **source of truth** — when in doubt, open it and read the CSS/DOM directly. Everything below is extracted from it.

> Read this ALONGSIDE `teclas-neumorphic-home-codex-prompt.md` (which has the task scope, theme-setting behavior, and constraints). This file is the pixel/design detail.

---

## 0. Golden rule
Neumorphism = **one base color everywhere**, depth from **two shadows** (light top-left, dark bottom-right). Raised = both shadows outset. Carved/pressed = both shadows `inset`. No borders. No gloss. No gradients on surfaces. No colored edges/spines. Color only in small centered chips + semantic label text.

---

## 1. Design tokens (verbatim)

### Dark mode
```
--base:    #181b21
--base-hi: #20242c    /* light shadow (top-left)  */
--base-lo: #0e1014    /* dark shadow  (bottom-right) */
--ink:     #e8ebef    /* primary text */
--ink-dim: #899099    /* secondary text */
--ink-faint:#565d66   /* labels, faint */
```
### Light mode  (SOFT — tight tonal range; do not widen)
```
--base:    #eceef2
--base-hi: #ffffff
--base-lo: #d3d7de    /* MUST stay close to base — a darker grey creates hard ugly edges */
--ink:     #3a3f47
--ink-dim: #828892
--ink-faint:#a8adb6
```
### Shared semantic accents (both modes)
```
green  #28E06A   (NOW PLAYING, ON THE ROAD, DOCK key, positive)
accent #FF4B45   (RIGHT NOW, INBOX, GO key)
amber  #E8B84B   (UP NEXT, weather sun core)
blue   #3B9DFF   (GOOGLE NEWS)
teal   #5FD0C4   (RECENT PEOPLE)
orange #FF8A4C   (people accent)
purple #A071FF   (people/AI accent)
```
Light-mode chip glyph text: `#20242b` (so bold chips stay legible on the pale base).

### Shadow presets (the four you will reuse everywhere)
Dark:
```
raised     : -5px -5px 11px var(--base-hi), 6px 6px 14px var(--base-lo)
raised-sm  : -3px -3px 7px  var(--base-hi), 4px 4px 9px  var(--base-lo)
pressed    : inset -4px -4px 9px  var(--base-hi), inset 5px 5px 11px var(--base-lo)
pressed-sm : inset -2px -2px 5px  var(--base-hi), inset 3px 3px 7px  var(--base-lo)
```
Light (larger blur, smaller offset = softer):
```
raised     : -4px -4px 12px var(--base-hi), 5px 5px 13px var(--base-lo)
raised-sm  : -2px -2px 6px  var(--base-hi), 3px 3px 8px  var(--base-lo)
pressed    : inset -3px -3px 8px var(--base-hi), inset 4px 4px 9px var(--base-lo)
pressed-sm : inset -2px -2px 4px var(--base-hi), inset 2px 2px 5px var(--base-lo)
```
Treat `px` as `dp` on Android.

---

## 2. CSS → Android translation (how to build the look natively)

Android Views have no dual-shadow. Build a reusable helper that paints two offset blurred rounded-rects behind the surface, then the base-color rounded-rect on top. For "pressed," paint the two shadows as **inner** shadows instead.

```
object Neu {
  // radius in dp; level = RAISED | RAISED_SM | PRESSED | PRESSED_SM
  fun apply(view: View, radius: Float, level: Level)
  // reads Theme.base / baseHi / baseLo for the CURRENT mode
}
```
Implementation options (any that renders cleanly at 60fps):
- A custom `Drawable` that, in `draw()`, paints: dark-shadow rounded-rect offset (+dx,+dy) with blur (BlurMaskFilter) → light-shadow rounded-rect offset (-dx,-dy) with blur → base fill rounded-rect. For pressed, clip to the shape and paint the two shadows inward.
- OR a `LayerDrawable` of three `GradientDrawable`s (two offset shadow layers + base). Simpler, slightly less soft.
- Corner radius per element is specified in §4.
- Base fill = `--base`; shadow colors = `--base-hi` / `--base-lo`. All resolved from the active theme, never hardcoded at the call site.
- For Compose (`HomeWidgetStack.kt`): a `Modifier.neu(radius, level)` that draws two `drawRoundRect` shadow passes (offset + soft alpha) then the base fill; pressed = draw shadows clipped inside. Reuse the same theme tokens.

Press interaction: on `ACTION_DOWN` swap raised→pressed; on `ACTION_UP` back to raised. Optional 1dp translate for keys.

---

## 3. Home layout & proportions (the skeleton)

Vertical stack inside the screen, top→bottom:
```
status bar            (system)
weather header        flex:0 0 auto   — COMPACT (see §4.1)
widget stack          flex:1 1 auto   — TALL HERO, fills all remaining space   ← dominant element
favorites dock        flex:0 0 auto   — carved tray
keyboard              flex:0 0 auto   — raised, proportionally shorter than the stack
```
Rules:
- The **widget stack is the biggest element on screen** — it takes all leftover vertical space between the compact weather header and the dock. Each card fills that full height; content is distributed vertically, never clustered at the top.
- Home horizontal padding ≈ 13–14dp. Gap between stacked sections ≈ 9dp.
- Screen well itself is **pressed** (the whole content area sits carved into the phone).

---

## 4. Element-by-element spec

### 4.1 Weather header (compact, raised card OR bare row)
- Sun: 38dp circle, **raised-sm**, with an inner amber core (inset ~10dp, color `--amber`, ~0.9 alpha).
- Temp: 25–28dp, weight 300, letter-spacing -1. Condition: 8dp mono, `--ink-faint`, letter-spacing 1.5, UPPERCASE.
- Right-aligned meta ("Feels 88° / 91% RH · 3 mph"): 10dp, `--ink-dim`, line-height 1.5.

### 4.2 Widget stack cards (shared shell)
- Card: corner radius **22dp**, background `--base`, **raised**, padding 16dp × 17dp.
- Only one card visible at a time (existing pager). Vertical dot indicator on the right edge: dots 5dp, inactive `--ink-faint` @0.5, active = `--green`, elongated to 16dp tall.
- Label row (`.wlabel`): 8.5dp mono, letter-spacing 1.5, UPPERCASE, colored per widget.
- Title (`.wtitle`): 16dp, weight 600, `--ink`, ellipsis. Sub (`.wsub`): 11dp, `--ink-dim`, ellipsis.
- Count badge (`.count`): mono 9dp, `--ink-dim`, **pressed-sm**, radius 7dp, padding 2×7.
- Icon well (`.chip-ic`): 46dp, radius 13dp, **pressed-sm**, holds a 30dp chip (`.glyph`) radius 9dp filled with the chip color, glyph text 15dp weight 800 `#0c1310`. Mono variant: chip is `--base` + **raised-sm**, glyph text = the accent color.

### 4.3 Music card
- Label "NOW PLAYING" green.
- Album art (`.m-art`): **82dp**, radius 18dp, **pressed-sm** well, padding 6dp, holds a 2×2 grid of raised-sm tiles (gap 5dp, radius 7dp). If a real `albumArt` bitmap exists, show it inside a raised-sm soft frame instead of the 4-tile grid.
- Title + artist to the right.
- Progress (`.m-prog`): 7dp tall, radius 99, **pressed-sm** groove, green fill at ~44% (opacity .7).
- Equalizer (`.eq`): bars 3dp wide, radius 2, `--green` @0.85, animating height 6↔20dp (stagger the delays).

### 4.4 Maps card ("ON THE ROAD", green label)
- Map tile (`.m-map`): **88dp**, radius 18dp, **pressed-sm**. Inside: 2–3 thin road lines (4–5dp, `--ink-faint` @0.4) at angles, one "lit" road in `--green` @0.8, and a 11dp green pin centered with a 3dp base-color ring.
- Title = ETA/destination ("12 min · Home"), sub = distance/route, plus a "Tap to resume route" subrow (green dot + green text).

### 4.5 Email card ("INBOX", accent/red label + count)
- Header: small mono chip "M" (accent) + red INBOX label + carved count badge.
- Primary tray (`.tray`): radius 14dp, **pressed-sm**, padding 12×13; holds a chip-ic (accent) + sender title + preview.
- Secondary rows (`emails.drop(1).take(2)`): dotted subrows — 5dp accent dot @0.7 + 10.5dp `--ink-dim` text, gap 9dp.

### 4.6 Recent People card ("RECENT PEOPLE", teal label + count)
- Multi-person: 3 chips (`.pchip`) filling width — each radius 16dp, **pressed-sm**, padding 14×6, centered; containing a **raised-sm** profile orb (46dp circle, initials 14dp weight 700 in the person's accent color, or the avatar bitmap) + name 11dp weight 600.
- Single person: one wide carved row with a larger orb + name + preview (keep existing single-person layout).

### 4.7 News card ("GOOGLE NEWS", blue label)
- Mono news icon (blue, raised-sm) at ~50dp, headline (title) + 2-line preview (`--ink-dim`, line-height 1.4, wraps).

### 4.8 Calendar card (always present; two carved blocks)
- `.cal` = two `.cblk` side by side, gap 11dp, each filling full card height.
- Block: radius 16dp, **pressed-sm**, padding 14×13, space-between column.
- Left: "RIGHT NOW" label (accent, or green when free) + event title (wraps, 14dp) + time sub.
- Right: "UP NEXT" label (amber) + carved `+N` count badge + event title (13dp) + time sub.

### 4.9 Favorites dock
- Tray: radius 19dp, **pressed-sm**, padding 9×14, space-between row of up to 5.
- Each fav: 38–42dp circle, `--base`, **raised-sm**, holding a ~20–26dp chip glyph (centered color chip, glyph `#0c1310`).

### 4.10 Keyboard
- Shell: radius 16dp top corners, `--base`, **raised** + a soft upward shadow so results tuck behind it. Sits above results in z-order.
- Typing strip: carved groove (radius ~11dp, **pressed-sm**), placeholder text `--ink-dim`, blinking green caret when active.
- Keys: `--base`, **raised-sm**, radius 6–9dp; on press → **pressed-sm** + 1dp translate. Letter text `--ink` (or `--ink-dim` at rest). Wide keys flex 1.5–1.6, space flex 4.
- DOCK key text `--green`; GO key text `--accent`; 123/period `--ink-dim`.
- ZEISS button: carved pill (**pressed-sm**), text `--blue`.
- Keep GoKeys geometry/labels scoped to the `gokeys` theme only.

### 4.11 Misc chrome
- Nav gesture pill: `--base`, **pressed-sm**, 104dp × 5dp, radius 99.
- Any segmented/count/badge element: carved (**pressed-sm**).

---

## 5. Motion
- Card→card (pager): fade + 14dp vertical translate, ~320ms.
- Result rows / list items: stagger in, translateY 8–10dp → 0, ~320ms, ~26ms stagger.
- Key/button press: instant raised→pressed, spring back on release (`androidx.dynamicanimation` ok).
- Equalizer bars: continuous height loop, staggered delays.
- Theme switch (Dark↔Light↔System): re-apply drawables + re-render; a ~300ms cross-fade on the root is a nice touch, not required.

---

## 6. Do / Don't (guardrails)
DO: one base color per mode; dual soft shadows; carve vs. raise for hierarchy; color only in centered chips + semantic labels; keep light mode's tonal range tight and soft.
DON'T: add borders, gloss/inner-white highlights, colored edge-light, accent spines, surface gradients, drop-shadows in a single direction only, or hardcoded hex at call sites. Don't let light mode go high-contrast. Don't change any widget's data, order, or actions. Don't restyle Docked mode differently from the spec.

---

## 7. Fidelity check (match these against the prototype before declaring done)
- [ ] Widget stack is the tallest element; cards fill the height, content vertically distributed.
- [ ] Every surface uses only the four shadow presets; no borders/gloss anywhere.
- [ ] All six widget types match §4.3–4.8 structure and the prototype.
- [ ] Light mode reads soft (no hard edges); dark mode matches tokens.
- [ ] Semantic label colors preserved exactly (green/red/amber/blue/teal).
- [ ] Keys depress on touch; DOCK green, GO accent.
- [ ] Dark / Light / System all apply globally from one theme resolve; System follows OS live.
- [ ] Open `teclas_home_full_stack/index.html` side-by-side — the build matches it.
