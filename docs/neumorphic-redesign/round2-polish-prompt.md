# Clicks Neumorphic — Round 2 Full Design Pass (authoritative)

I am the designer. This document is the complete, self-contained spec to bring the neumorphic
homescreen fully in line with the approved look. It contains the design decisions AND the exact code
for the hard parts. You should not need any other file, but if present, the prototype at
`docs/neumorphic-redesign/prototype-home-full-stack.html` is the visual source of truth.

**Scope:** visual polish + layout fixes only. Do NOT change data, ordering, click/long-press actions,
the pager, the theme token values, or the Dark/Light/System selector logic. Keep
`env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug`
green; commit incrementally.

Files in play: `app/src/main/java/com/fran/clicks/Neu.kt`,
`app/src/main/java/com/fran/clicks/HomeWidgetStack.kt`,
`app/src/main/java/com/fran/clicks/MainActivity.kt`.

---

## FIX 1 — Carved surfaces render as outlines in Compose. Make them truly inset. (highest impact)

**Problem:** `Modifier.neu(...)` PRESSED/PRESSED_SM path draws two *offset stroked* rounded-rects, so
every recessed well (album art, calendar blocks, icon wells, trays, typing strip) looks like an
outline, not a carved recess. The View `NeuDrawable` pressed branch is correct; the Compose path is not.

**Do this:** replace the pressed branch of `Modifier.neu` in `Neu.kt` with a true inner-shadow render —
fill the base, clip to the rounded shape, then draw two blurred shadow rounded-rects pushed *inward*
(dark from the top-left inner edge, light from the bottom-right inner edge). Use a Compose
`BlurMaskFilter`-equivalent via `drawIntoCanvas` + native `Paint` so it matches the View drawable.

Replace the whole `Modifier.neu` function with:

```kotlin
fun Modifier.neu(tokens: NeuTokens, radius: Dp, level: NeuLevel): Modifier = this.drawWithContent {
    val r = radius.toPx()
    val light = tokens.mode == NeuMode.LIGHT
    // offset+blur per level (dp values from the spec), scaled to px
    val (offNeg, offPos, blurDp) = when (level) {
        NeuLevel.RAISED     -> Triple(if (light) 4f else 5f, if (light) 5f else 6f, if (light) 12f else 11f)
        NeuLevel.RAISED_SM  -> Triple(if (light) 2f else 3f, if (light) 3f else 4f, if (light) 6f else 7f)
        NeuLevel.PRESSED    -> Triple(if (light) 3f else 4f, if (light) 4f else 5f, if (light) 8f else 9f)
        NeuLevel.PRESSED_SM -> Triple(2f, if (light) 2f else 3f, if (light) 4f else 5f)
    }
    val dLight = offNeg.dp.toPx()
    val dDark  = offPos.dp.toPx()
    val blur   = blurDp.dp.toPx()
    val hi = tokens.baseHi
    val lo = tokens.baseLo
    val hiA = if (light) 0.9f else 0.55f
    val loA = if (light) 0.85f else 0.78f
    val raised = level == NeuLevel.RAISED || level == NeuLevel.RAISED_SM

    if (raised) {
        drawIntoCanvas { c ->
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                maskFilter = android.graphics.BlurMaskFilter(blur, android.graphics.BlurMaskFilter.Blur.NORMAL)
            }
            // dark shadow bottom-right
            p.color = androidx.compose.ui.graphics.Color(lo).copy(alpha = loA).toArgb()
            c.nativeCanvas.drawRoundRect(dDark, dDark, size.width + dDark, size.height + dDark, r, r, p)
            // light shadow top-left
            p.color = androidx.compose.ui.graphics.Color(hi).copy(alpha = hiA).toArgb()
            c.nativeCanvas.drawRoundRect(-dLight, -dLight, size.width - dLight, size.height - dLight, r, r, p)
        }
        drawRoundRect(color = tokens.baseCompose, cornerRadius = CornerRadius(r, r))
    } else {
        // base first, then inner shadows CLIPPED to the shape → reads as carved
        drawRoundRect(color = tokens.baseCompose, cornerRadius = CornerRadius(r, r))
        val clip = androidx.compose.ui.graphics.Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, size.width, size.height, CornerRadius(r, r)))
        }
        clipPath(clip) {
            drawIntoCanvas { c ->
                val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = blur * 1.6f
                    maskFilter = android.graphics.BlurMaskFilter(blur, android.graphics.BlurMaskFilter.Blur.NORMAL)
                }
                // dark inner shadow: pushed in from top-left → strong on top/left inner edges
                p.color = androidx.compose.ui.graphics.Color(lo).copy(alpha = if (light) 0.9f else 0.85f).toArgb()
                c.nativeCanvas.drawRoundRect(dDark, dDark, size.width + dDark, size.height + dDark, r, r, p)
                // light inner shadow: pushed in from bottom-right → highlight on bottom/right inner edges
                p.color = androidx.compose.ui.graphics.Color(hi).copy(alpha = if (light) 0.85f else 0.5f).toArgb()
                c.nativeCanvas.drawRoundRect(-dLight, -dLight, size.width - dLight, size.height - dLight, r, r, p)
            }
        }
    }
    drawContent()
}
```
Add imports as needed: `androidx.compose.ui.graphics.drawscope.drawIntoCanvas`,
`androidx.compose.ui.graphics.nativeCanvas`, `androidx.compose.ui.graphics.toArgb`,
`androidx.compose.ui.graphics.Path`, `androidx.compose.ui.geometry.RoundRect`,
`androidx.compose.ui.graphics.drawscope.clipPath`.
**Parity check:** a carved element must look identical whether drawn via the View `NeuDrawable` or this Modifier.

---

## FIX 2 — Correct depth per element (stop flattening everything to PRESSED_SM)

In `HomeWidgetStack.kt`, `recessedTraySurface` maps every carved surface to `PRESSED_SM`. Deep surfaces
must use full `PRESSED`. Replace the helper and its call sites:

```kotlin
private fun Modifier.recessedTray(tokens: NeuTokens, radiusDp: Int, deep: Boolean = false): Modifier =
    this.neu(tokens, radiusDp.dp, if (deep) NeuLevel.PRESSED else NeuLevel.PRESSED_SM)
```
- **deep = true** for: music album well, calendar RIGHT NOW / UP NEXT blocks, email primary tray, maps tile.
- **deep = false** for: small ContextIcon wells, count badges, and the typing strip.
- Card shells (`drawXSurface`) stay `RAISED`. Small chips/orbs stay `RAISED_SM`.

---

## FIX 3 — Rebuild the Music card (it regressed to the pre-redesign layout)

Current music card = old 76dp tray + 6-bar equalizer, missing the well/grid/progress groove. Replace
`MusicWidgetCard` body with the spec §4.3 layout:

- **82dp** album well, radius 18, `recessedTray(tokens, 18, deep = true)`. If `albumArt != null`, show it
  inside a `RAISED_SM` soft frame (a Box with `.neu(tokens, 16.dp, RAISED_SM)` padding 4dp, then the
  Image clipped to radius 12). Else draw the 2×2 grid: a 2×2 arrangement of `RAISED_SM` tiles (radius 7,
  4dp gaps) inside the well.
- Keep green **NOW PLAYING** label + the equalizer bars.
- Add a **carved progress groove** under the title: height 7dp, radius 99, `recessedTray(tokens, 99, deep = false)`,
  with a green fill Box at ~44% width, `GreenSoft.copy(alpha = 0.7f)`.
- Distribute content to fill card height (title/artist block centered, groove below, eq at bottom).

---

## FIX 4 — Route all semantic colors through tokens/`Neu` (no hardcoded hex)

In `HomeWidgetStack.kt` replace raw semantic hex:
- email red `Color(0xFFEA4335)` → `Color(Neu.ACCENT)` (or keep a dedicated `Neu.EMAIL` if you want Gmail-red; define it in `Neu` if so — no inline hex).
- news blue `Color(0xFF8AB4F8)` → `Color(Neu.BLUE)`
- people teal `Color(0xFF5FD0C4)` → `Color(Neu.TEAL)`
- chip glyph text: dark-mode use `Color(0xFF0C1310)`… → make it token-aware: `if (tokens.mode == NeuMode.LIGHT) Color(Neu.LIGHT_CHIP_TEXT) else Color(0xFF0C1310)`. Apply to `ContextIcon`, `ProfileOrb` initials background text, email "M" glyph (currently `Color(0xFF260805)`), and any fav/app chip glyph.
- The module-level `GreenSoft/Amber/Purple/Accent` vals may stay as convenience aliases but must equal the `Neu` constants. Prefer referencing `Neu.*`.

Rule: no raw semantic hex inline in widgets. Chips must stay legible in BOTH modes.

---

## FIX 5 — Tall-hero proportions: cards fill height, bigger media (§3)

The cards kept old fixed sizes and top-clustered content. Match the prototype:
- Album well 82dp; maps tile 88dp; news icon 50dp; people orbs 46dp (chips) / 54dp (wide).
- People chips and calendar blocks stretch to fill card height (`fillMaxHeight()` / `weight`), not fixed 78/82dp when the card is taller.
- Card bodies distribute vertically (`Arrangement.SpaceBetween` or weighted spacers) so content uses the full tall card, never clustered at the top.
- The widget stack region itself must be the dominant/tallest block on the homescreen (see Fix 8).

---

## FIX 6 — Guard `NeuDrawable` against clipping small elements

In `Neu.kt`, `NeuDrawable.draw` insets content by `blur + maxOffset + 1` (~18px for RAISED). On small
raised views (keyboard keys, 5–8dp dots) this shrinks/clips the shape. Cap the inset relative to bounds:

```kotlin
val rawInset = spec.blur + kotlin.math.max(kotlin.math.abs(spec.lightDx), kotlin.math.abs(spec.darkDx)) + 1f
val inset = kotlin.math.min(rawInset, kotlin.math.min(b.width(), b.height()) * 0.28f)
```
Verify keys, dots, and small orbs keep their footprint and still show soft depth.

---

## FIX 7 — Remove dead code
Remove `CalendarDateMark`, `calendarTimeParts`, and `drawNeutralSurface` if unreferenced after these changes (verify no callers first).

---

## FIX 8 — Kill the large empty gap below the keyboard; keyboard bottom-anchors

There is a big dead space at the bottom that pushes the keyboard up. The keyboard must sit at the
bottom (only the system gesture inset below it), with the freed space going to the tall widget stack.

In `MainActivity` `home()` / `render()` (and `homeKeyboardWidget()` for Widget mode, plus the docked
keyboard container):
- Find and remove/reduce any trailing bottom **spacer**, weighted `View`, `bottomMargin`, or
  `paddingBottom` below the keyboard/dock that reserves empty space. The flexible weight should live in
  the widget-stack region ABOVE the keyboard, so extra space grows the stack, not a bottom void.
- Ensure the keyboard container is the LAST child of the root column and is effectively bottom-anchored.
- The reskin can contribute: the keyboard shell RAISED shadow + `NeuDrawable` inset add padding. Make
  sure that inset/shadow is not rendering as empty space *below* the keys — bottom-align/clip so the
  keys reach the screen bottom minus the gesture-bar inset. (Fix 6's inset cap helps here too.)
- Apply to BOTH Docked and Widget placement; verify both. Do not change key sizes, DOCK/GO behavior, or
  typing — only vertical position and the surrounding empty space.

---

## Final verification (state PASS/FAIL for each)
- [ ] Carved surfaces look pressed-IN (not outlined) in Compose AND View, in dark AND light.
- [ ] Music card: 82dp well (real art or 2×2 grid) + green NOW PLAYING + equalizer + carved progress groove.
- [ ] Calendar blocks / email tray / maps tile use deep PRESSED and fill card height.
- [ ] No inline semantic hex remains; chips legible in both modes.
- [ ] Small raised elements not shrunk/clipped by shadow inset.
- [ ] No large empty gap under the keyboard; keyboard bottom-anchored in Docked AND Widget.
- [ ] Widget stack is the tallest block; content fills card height.
- [ ] Data, ordering, actions, pager, theme selector, Docked/Widget behavior unchanged.
- [ ] `./gradlew assembleDebug` green. End with a summary of changes + any `// TODO:`.
