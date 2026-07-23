# Slide-up keyboard — interactive prototype

A feel-first prototype of an **Apple-Stock-style slide panel for the widget-mode
keyboard**: the keyboard peeks at the bottom as the search bar, you **drag it up
to reveal the full keyboard** and **drag it down to tuck it away** so the content
above goes almost full-screen. On tall phones you never have to reach the top of
the screen to summon typing — you pull it in from the bottom, right under your
thumb, and it drops back out of the way.

Open `index.html` on a phone (or any browser). Everything is self-contained —
no build, no dependencies.

## What it demonstrates

The point of the prototype is the **motion**, not the pixels:

- **1:1 finger tracking** — while dragging, the sheet follows your finger
  exactly (no easing, no lag). The live `open %` / `vel` readout in the corner
  makes the tracking visible.
- **Rubber-banding** at both extremes, so it never feels like it hits a wall.
- **Velocity-driven spring settle** — on release, a critically-damped spring
  finishes the motion. A fast flick commits even from a small pull; a slow drag
  settles by position (past ~42% → open). This is the "smoothness / flow" of the
  Stock panel.
- **Peek = the search bar.** When collapsed, the visible strip *is* the search
  field (magnifier + "Search apps, people, rides…"), matching the app's existing
  collapsed handle. Tap it to reveal; drag it to reveal interactively.
- **Auto-hide on scroll** — scrolling the feed while the keyboard is up springs
  it back down (with a brief "tucked away" toast), exactly the behaviour you get
  in Stock when you scroll the article list.
- Typing on the keys fills the search field, so the "pull up → type → it drops
  away" loop feels real.

Colours, the glass field, and the accent (`#C9A7FF`, the app's default
`goKeyColor`) are pulled from the real app so it reads as *clicks*, not a
generic mock.

## How this maps onto the real app

The app **already has** a widget-mode hide/show mechanism — it's just
**gesture-triggered canned animation**, not an interactive drag. The relevant
code lives in `app/src/main/java/com/fran/teclas/MainActivity.kt`:

| Concern | Existing hook to reuse |
| --- | --- |
| Is the slider available? | `widgetKeyboardSliderAvailable()` (widget placement / unfolded inner) |
| Hidden ↔ shown state + persistence | `widgetKeyboardHidden`, `saveWidgetKeyboardHiddenForCurrentPosture()` |
| Collapsed / expanded slot heights | `widgetKeyboardCollapsedDockHeight()` (≈ the peek), `expandedRootDockHeight()` |
| Animate the dock/host slot height | `animateWidgetKeyboardSlotHeight()` |
| Keyboard panel offset when hidden/shown | `widgetKeyboardHiddenTranslationY()`, `widgetKeyboardBaseTranslationY()` |
| The collapsed "search bar" handle view | `KeyboardSliderHandleView` + `widgetKeyboardSliderHandleView` |
| Current reveal / hide animations | `showWidgetKeyboardSlider()`, `hideWidgetKeyboardSlider()` |
| Touch entry points | `handleWidgetKeyboardSliderHandleTouch()`, `handleVisibleWidgetKeyboardGlobalGesture()`, `handleHiddenWidgetKeyboardGlobalGesture()` |

### The gap this prototype fills

Today the handle drag only checks a threshold and then fires a fixed-duration
`ObjectAnimator` (`showWidgetKeyboardSlider()` / `hideWidgetKeyboardSlider()`).
To get the Stock feel, the drag needs to become **interactive**:

1. On `ACTION_DOWN` on the handle (single finger), capture `startRawY` and the
   current slot height.
2. On `ACTION_MOVE`, drive a normalized `progress ∈ [0,1]` from the finger delta
   over the travel (`expandedRootDockHeight() - widgetKeyboardCollapsedDockHeight()`),
   and **each frame** set the dock slot height and the module `translationY`
   directly from `progress` (lerp between the collapsed and expanded values) —
   instead of only animating on release. Apply rubber-band resistance past the
   ends.
3. On `ACTION_UP`, read the gesture velocity and hand off to a
   `SpringAnimation` / `FlingAnimation` (androidx.dynamicanimation) that settles
   `progress` to 0 or 1, then commit `widgetKeyboardHidden` and persist.

The existing two-finger swipe and tap-to-reveal paths can stay as shortcuts; the
new interactive drag on the full-width handle is the addition. Auto-hide on
scroll would call `hideWidgetKeyboardSlider()` from the launcher feed's scroll
listener when `!widgetKeyboardHidden`.

The `settleTo()` spring in `index.html` (stiffness/damping constants) is a direct
analogue of the `SpringAnimation` parameters to use on-device.

## Can this slide work inside third-party apps (Chrome, etc.), not just the launcher?

Yes — and this app already ships the three pieces Android requires. There are
three surfaces a keyboard can live on, with different rules:

1. **IME (`TeclasImeService`)** — the normal system keyboard. It renders over
   *every* app, so the slide-to-expand interaction can live entirely inside the
   IME input view (`onCreateInputView` / `setInputView`). **Limit:** the IME only
   appears when an app hands over an `InputConnection` — i.e. a field is already
   focused. A pure IME cannot summon itself over Chrome with nothing focused, and
   cannot reach out and focus a field itself. So the IME alone can't give you
   "slide in and just start typing" without you first tapping a field.

2. **Overlay (`DockedKeyboardService`)** — a keyboard deck the app draws *on top
   of* other apps with `TYPE_APPLICATION_OVERLAY` (the app already holds
   `SYSTEM_ALERT_WINDOW`). This is where a **persistent bottom grabber over
   Chrome** lives: it exists whether or not a field is focused, so you can pull it
   up any time. Adding the interactive slide to this overlay window = the
   system-wide version of this prototype.

3. **Accessibility (`InputInjectionService`)** — the piece that makes "it auto-
   recognizes text fields and I just begin typing" actually possible. Ordinary
   keyboards **cannot** focus another app's field; an `AccessibilityService` can.
   This app's service already calls `findForegroundAppEditable()` →
   `ACTION_FOCUS` → `ACTION_SET_TEXT` on the foreground app (skipping its own
   launcher window). So: *slide up → focus Chrome's omnibox → inject keystrokes*
   is reachable with the parts already present.

**The one honest caveat:** auto-focus is best-effort. Accessibility reliably
finds standard `EditText`/editable nodes, but some targets don't expose one —
certain WebView inputs, canvas/game fields, and flagged password/DRM surfaces —
so "any field in any app" is ~90%, not 100%. And the overlay + accessibility
combo is permission-heavy (draw-over-apps + accessibility must be granted); that
injection surface is exactly what Play Store scrutinizes, though it's a non-issue
for a directly-installed APK like this one.

**Bottom line:** the launcher prototype here proves the *feel*; the same
`open ∈ [0,1]` + spring model drives the **`DockedKeyboardService` overlay** for
the over-Chrome version, and the existing **`InputInjectionService`** is what lets
you start typing without reaching up to tap the field.

## `third-party.html` — the over-other-apps mock

`third-party.html` is a second, self-contained mock that puts the slide keyboard
**over generic stand-ins for Chrome, Spotify, and WhatsApp**, in both
placements:

- **App switcher** (Chrome / Spotify / WhatsApp) — swaps the host backdrop and
  its focus target (address bar / search field / message box).
- **Mode switcher** — **Widget** (floating rounded card, side margins, sits above
  the nav bar) vs **Docked** (full-bleed, pinned to the bottom edge). Same slide,
  different framing — mirroring `KEYBOARD_PLACEMENT_WIDGET` vs the docked overlay.
- **Auto-focus** — as the keyboard rises past ~8%, the host app's field lights up
  with a focus ring and a "▸ focused the address bar" toast, and the keys type
  straight into it. That's the visual stand-in for the accessibility
  `ACTION_FOCUS` → `ACTION_SET_TEXT` path, i.e. "just start typing without
  tapping the field."
- WhatsApp's compose bar **rides up with the keyboard**; Chrome/Spotify content
  gets a bottom inset and **auto-hides on scroll**.

> It's a UI mock of *our* keyboard over generic representations of those apps —
> no real logos, no login/credential fields, not affiliated with them.

## Shipping it for real, behind a flag

Plan for the on/off flag so it can be tested against real third-party apps
without disturbing existing behaviour:

1. **Pref + gate.** Add a `SLIDE_KEYBOARD_OVERLAY_PREF` boolean (default off) next
   to the existing keyboard prefs. A single `slideKeyboardEnabled()` gate guards
   every new code path, so flipping it off restores today's behaviour exactly.
2. **Widget surface first (lowest risk).** Turn the existing release-only
   `showWidgetKeyboardSlider()` / `hideWidgetKeyboardSlider()` animation into the
   interactive per-frame drive + `SpringAnimation`, gated by the flag. This
   touches only the widget-mode slider already in `MainActivity`.
3. **Docked-over-apps surface.** Apply the same `open ∈ [0,1]` driver to the
   `DockedKeyboardService` overlay window (drag the overlay height/translation),
   and add the auto-focus rule via `InputInjectionService.findForegroundAppEditable()`.
   Gate it behind the same flag plus the existing accessibility/overlay permission
   checks.
4. **Auto-focus policy toggle.** A sub-option (also default off) for "focus the
   foreground field automatically on reveal" vs "reveal only, focus on tap" — so
   the ~90% auto-focus behaviour is opt-in while it's being tested across apps.

Each step is independently shippable and reversible via the flag.
