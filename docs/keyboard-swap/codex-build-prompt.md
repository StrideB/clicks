# Clicks — Widget Mode: hold-DOCK to detach, swipe to browse, snap to seat

Implement a new keyboard-swap interaction in the `com.fran.clicks` Android launcher, **Widget placement mode only**. The keyboard should feel like a physical accessory you unclip from the phone, browse, and snap back on. This spec matches an approved interactive prototype exactly — follow the phases and motion below.

## Context (existing code — do not break it)
- `MainActivity.kt` (~10k lines). Relevant existing pieces:
  - `keyboardPlacement` (`KEYBOARD_PLACEMENT_DOCKED` / `KEYBOARD_PLACEMENT_WIDGET`), persisted in prefs under `KEYBOARD_PLACEMENT_PREF`.
  - `keyboardTheme` (`default` / `clicks` / `skeuo` / `gokeys`), persisted under `KEYBOARD_THEME_PREF`. Constants: `KEYBOARD_THEME_DEFAULT`, and the theme ids used in `keyLabel()`.
  - `render()` builds the layout; in Widget mode the keyboard lives inside `home()` via `homeKeyboardWidget()` (a `LinearLayout` containing `typingStripView()` + `keyboard()`), seated above the favorites dock.
  - `keyLabel()` already renders the `clicks` key as `"DOCK"` when `keyboardPlacement == KEYBOARD_PLACEMENT_WIDGET`. A short **tap** on DOCK currently switches back to Docked mode via `setKeyboardPlacement(...)` — KEEP that behavior for a tap.
  - `keyboard()` builds key `TextView`s into `keyViews`; keys use an `OnTouchListener` with existing long-press handling (`longPressRunnable`, `cancelLongPress()`, `longPressFired`).
  - `haptic(view)` / `keyHaptic(label)` for haptics. `dp(int)` for density. `setKeyboardTheme(...)`/theme apply path already exists (find how theme changes currently re-render the keyboard).
- **Do not change Docked mode at all.** This entire feature is gated on `keyboardPlacement == KEYBOARD_PLACEMENT_WIDGET`.
- Match the README design rules: centered homescreen, GoKeys geometry stays scoped to the `gokeys` theme, everything data-driven. No new dependencies — use `ValueAnimator` / `ViewPropertyAnimator` / `SpringAnimation` from the already-present `androidx.dynamicanimation` dependency.

## The interaction (4 phases)

### Phase 1 — Hold DOCK to detach
- In Widget mode, **long-pressing the `DOCK` key** (the `clicks` key) detaches the keyboard. Use a hold threshold of **~650ms**.
  - A **short tap** on DOCK must still switch to Docked mode (existing behavior). Only a completed hold triggers detach. Reuse/extend the existing long-press machinery on that key rather than adding a competing listener.
  - While holding, show a **radial fill progress** on the DOCK key (a conic/sweep progress from 0→360° over the hold duration) and a green glow ring. If the finger lifts or moves past touch-slop before threshold, cancel cleanly (reset the fill, no detach).
  - On threshold reached: fire `haptic()`, then animate detach.
- **Detach animation** on the widget keyboard container (`homeKeyboardWidget()`'s view):
  - Translate up by ~`dp(118)`, apply a slight `rotationX ≈ 9°`, `scaleX/Y ≈ 0.94`, and raise elevation/shadow so it reads as "lifted off." Use an overshoot interpolator (or `SpringAnimation` with low stiffness) ~460ms.
  - Add a small secondary "pop off socket" bob after it lifts.
  - Reveal an **empty socket** in the dock area it left behind: recessed connector ports drawn where it was seated. Add matching **gold connector prongs** to the bottom of the floating keyboard module (drawn via a small custom View or a 9-patch/`GradientDrawable` row). These are the "it's an accessory" tell.

### Phase 2 — Coach the user
- The instant it detaches, show a lightweight **coach hint** floating just above the detached keyboard: text `"Swipe to browse · tap to install"` with an animated left-right `⇆` nudge.
- Also render a row of **theme dots** (one per installed theme, current = elongated/green) along the bottom of the floating module.
- Dismiss the coach hint once the user performs their first swipe (and never auto-show it again in the same detached session).

### Phase 3 — Swipe to browse themes
- While detached, a **horizontal swipe** on the keyboard body cycles themes in order `[default, clicks, skeuo, gokeys]` (wrap around). Left swipe = next, right = previous. Threshold ~`dp(42)` and horizontal-dominant (`|dx| > |dy|`).
  - During the drag, translate/rotate the module slightly to follow the finger (`translationX = dx*0.5`, `rotationZ ≈ dx*0.03`) for tactility; snap back to the hover pose on release.
  - On a committed swipe: slide the current skin out sideways + fade, rebuild the keyboard view with the new theme's skin, and bring it in from the opposite edge (~300ms, overshoot). Update the active theme dot.
  - Tapping a theme dot jumps directly to that theme (same transition).
- **Important:** browsing only changes a *pending* preview theme. Do **not** commit `KEYBOARD_THEME_PREF` yet — only on seat (Phase 4). If the user backs out (see below), the previously committed theme stays.

### Phase 4 — Select → fly back and snap in
- A **tap on the keyboard body** (distinct from a swipe: movement < ~`dp(8)`) selects the currently-previewed theme and seats it.
- **Seat animation:**
  - Animate the module back to its docked pose (`translationY=0, rotationX=0, scale=1`) with a spring/overshoot (~440ms) so it visibly *snaps* into the slot.
  - On contact: settle bounce (small down-up), **flash the gold connector prongs** (brightness pulse, staggered), a subtle **whole-phone/device haptic** (`haptic()` + optionally a stronger `HapticFeedbackConstants.CONFIRM` if available), and pulse the socket glow green.
  - Show a brief confirmation pill centered on the keyboard: `"✓ {THEME} LOCKED IN"` that pops in and fades out (~1.1s).
  - **Commit the theme now:** write the selected theme to `KEYBOARD_THEME_PREF` and run the normal theme-apply path so the seated keyboard is the real, persisted keyboard.
- Hide the coach hint, theme dots, prongs, and empty-socket ports; return to the normal seated Widget keyboard.

## State machine
Implement an explicit small state enum for the widget keyboard: `SEATED → (hold) → DETACHING → DETACHED → (swipe) DETACHED… → (tap) SEATING → SEATED`. Guard all gestures on state so animations can't overlap or re-enter. Provide a safe path to cancel back to `SEATED` (e.g., if the activity pauses mid-detach, snap back to the committed theme without persisting a preview).

## Constraints & quality bar
- Widget mode only. Docked mode untouched.
- Don't regress: typing, the DOCK-tap→Docked behavior, favorites dock, weather header, contextual widget stack, and the keyboard input/prediction engine must all keep working. The detached keyboard is non-typing (it's being configured); restore full input on seat.
- 60fps: animate with hardware-accelerated properties (translation/rotation/scale/alpha). No layout thrash mid-animation — animate the container, rebuild key skins off the visible transition where possible.
- Respect existing theme skins exactly; GoKeys stays lowercase/compact and scoped to its own theme.
- Keep it self-contained: prefer a new `WidgetKeyboardSwapController` class (or similar) that owns the state machine, the detached-module views (prongs, coach, dots, locked pill), and the animators, wired into `MainActivity` at the `homeKeyboardWidget()` seam — rather than piling more into `MainActivity`. If full extraction is impractical, isolate it behind clearly-commented methods.
- Add haptics at: hold-threshold reached, each theme change during browse, and seat/lock-in.

## Deliverable
- Working feature in Widget mode matching the 4 phases and motion above.
- Small, reviewable commits: (1) state machine + hold-to-detach + progress ring, (2) detached pose + prongs/socket/coach/dots, (3) swipe browsing with preview-only theme, (4) seat/snap + confirmation + theme commit.
- Confirm `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug` succeeds.
- Note anything you had to change in shared code (e.g., the DOCK key touch handling) and why.

## Reference
The approved visual/interaction prototype (HTML) is at `work/artifacts/clicks_keyboard_swap/index.html` — match its phases, motion feel (lift → hover-tilt → sideways card-swap browse → drop-and-snap), and the "accessory" tells (gold prongs, empty socket ports, socket glow, LOCKED IN pill). Timings there are the target: hold ~650ms, detach ~460ms, browse ~300ms, seat ~440ms.

## Acceptance checklist (self-verify before declaring done)
Do not report the task complete until every item passes. For each, state PASS/FAIL and how you verified it.

**Gesture disambiguation (highest risk)**
- [ ] In Widget mode, a **short tap** on DOCK still switches to Docked mode — detach does NOT fire.
- [ ] A **completed ~650ms hold** on DOCK detaches and never also triggers the Docked-mode switch.
- [ ] Releasing the hold **early** (before threshold) cancels cleanly: progress ring resets, keyboard stays seated, no state left dangling.
- [ ] Moving the finger past touch-slop during the hold cancels the hold (no accidental detach).
- [ ] While detached, a **tap** (movement < ~dp(8)) seats/selects; a **horizontal drag** (> ~dp(42)) browses — a sloppy swipe never accidentally seats the wrong theme, and a still tap never registers as a browse.

**Theme correctness**
- [ ] Browsing cycles `default → clicks → skeuo → gokeys → default` (and reverse) with wraparound.
- [ ] Browsing is **preview-only**: `KEYBOARD_THEME_PREF` is unchanged until seat.
- [ ] After seating, the selected theme is persisted and **survives an app restart / device reboot**.
- [ ] If detached and then the activity is paused/backgrounded (or a safe cancel occurs), the keyboard returns to the **previously committed** theme — no preview leaks into prefs.
- [ ] GoKeys still renders lowercase/compact and its geometry stays scoped to the `gokeys` theme only (no leakage into other skins after a swap).

**No regressions (run in BOTH modes)**
- [ ] Docked mode is visually and behaviorally identical to before this change.
- [ ] After a seat, **typing, prediction, glide, autocorrect, and the suggestion path all work** on the newly seated keyboard.
- [ ] Favorites dock, weather header, and the contextual widget stack are unaffected during and after a swap.
- [ ] The empty-socket ports, gold prongs, coach hint, theme dots, and LOCKED IN pill are all **removed** once seated (no leftover overlay views).

**Motion & robustness**
- [ ] All transitions animate hardware-accelerated properties (translation/rotation/scale/alpha) and hold ~60fps on the Vivo test device.
- [ ] Rapid repeated gestures (hold → immediately swipe → tap, or double-detach attempts) cannot overlap animations or enter a broken state — the state guard holds.
- [ ] Haptics fire at exactly: hold-threshold reached, each theme change during browse, and seat/lock-in.

**Build**
- [ ] `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug` succeeds with no new warnings introduced by this change.
