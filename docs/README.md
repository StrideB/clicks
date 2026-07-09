# Teclas — design & build docs

Everything from the Teclas design/redesign work, ready to hand to a coding agent (Codex/Claude Code)
or to pick back up later. Three independent workstreams live here.

_Last updated: 2026-07-03._

## Current status
- **hardening** — DONE, merged to `main` (4 commits: Room migration, n-gram prune, thread-safe notifications, Gemini hardening).
- **neumorphic-redesign** — round 1 DONE, merged (token system, appearance selector, chrome + widget-stack reskin). **Round 2 polish (`round2-polish-prompt.md`) is the current next step** — fixes carved-surface rendering, music card regression, proportions, and the empty gap under the keyboard.
- **keyboard-swap** — NOT started yet.

## Contents

```
docs/
├── neumorphic-redesign/     ← reskin the whole homescreen + Dark/Light/System theme
│   ├── codex-build-prompt.md      (round-1 task scope, theme setting, constraints, commits) — DONE
│   ├── design-spec.md             (CODE-LEVEL design bible: tokens, shadows, per-widget specs)
│   ├── round2-polish-prompt.md    (ROUND 2: self-contained polish pass w/ exact code — NEXT)
│   ├── prototype-home-full-stack.html      (PRIMARY reference — full widget stack, dark+light)
│   ├── prototype-all-screens.html          (home/search/library/music/themes)
│   ├── prototype-music.html                (music widget, real vs redesign)
│   ├── prototype-real-vs-redesign.html     (your launcher vs redesign, side by side)
│   └── prototype-widget-search.html        (widget-mode search: slide-from-keyboard + grab-to-stash)
├── keyboard-swap/           ← hold-DOCK to detach, swipe to browse themes, snap to seat
│   ├── codex-build-prompt.md      (4-phase spec + acceptance checklist)
│   └── prototype.html
└── hardening/               ← fixes-only pass (no behavior change) from a 6-pass code review
    └── codex-hardening-prompt.md
```

Open any `.html` in a browser to see/feel the approved prototype. The `.md` files are the build prompts.

## What each workstream does

**neumorphic-redesign** — Reskins the entire homescreen in dark neumorphism (soft UI) and adds a
theme setting: Dark / Light / System (System follows the OS live). All six widget-stack cards
(music, maps, email, people, news, calendar) are redesigned. `design-spec.md` has exact tokens,
the four shadow presets, the CSS→Android translation, and per-widget measurements. Read it with the
build prompt; verify against `prototype-home-full-stack.html`.

**keyboard-swap** — Widget-mode only. Hold the DOCK key to physically detach the keyboard, swipe to
browse the 4 themes (preview-only), tap to snap it back and commit. Full 4-phase spec + a self-verify
acceptance checklist in the prompt.

**hardening** — Non-behavioral robustness fixes surfaced by reviewing the repo: real Room migrations
(stop wiping learned n-grams), use the unused `lastUsed` column, thread-safe notification-listener
state, Gemini network/JSON hardening, and breaking up the 10k-line MainActivity. Independent of the
two design features; run anytime.

## Recommended run order (these touch overlapping files)

All modify `MainActivity.kt` and/or `HomeWidgetStack.kt`, so run sequentially and commit between each
to avoid merge pain. hardening + neumorphic round 1 are already merged; remaining order:

1. ~~**hardening**~~ — DONE (merged).
2. ~~**neumorphic-redesign** round 1~~ — DONE (merged).
3. **neumorphic-redesign round 2** (`round2-polish-prompt.md`) — NEXT. Self-contained; carries exact
   code for the carved-surface fix (Fix 1) and the shadow-inset cap (Fix 6), plus the keyboard
   bottom-gap fix (Fix 8). Run it, rebuild the APK, eyeball against `prototype-home-full-stack.html`.
4. **keyboard-swap** — LAST. Layers the detach/swap interaction onto the (now reskinned) Widget-mode
   keyboard, so it should see the finished neumorphic keyboard rather than fight it.

Kickoff line for round 2 (run Codex in the repo):
> Read docs/neumorphic-redesign/round2-polish-prompt.md and apply all 8 fixes. Use the exact code it
> provides for Fix 1 and Fix 6. Verify against docs/neumorphic-redesign/prototype-home-full-stack.html
> and the final checklist.

## Files each workstream touches (for conflict awareness)
- **hardening:** `db/NgramDatabase.kt`, `db/NgramDao.kt`, `db/NgramEntry.kt`, `TeclasNotificationListener.kt`, `MainActivity.kt` (Gemini fns + extraction), `app/build.gradle.kts`.
- **neumorphic-redesign (r1 + r2):** `Neu.kt`, `LauncherModels.kt`, `MainActivity.kt` (home chrome, keyboard, dock, settings selector, bottom-gap fix), `HomeWidgetStack.kt` (all cards), prefs (`THEME_MODE_PREF`).
- **keyboard-swap:** `MainActivity.kt` (DOCK key handling, `homeKeyboardWidget()`), new `WidgetKeyboardSwapController`, prefs (`KEYBOARD_THEME_PREF` commit-on-seat).

## Round-2 audit findings (why round 2 exists)
Verified against the merged code on `main`. Round 1 landed the token system, selector, and reskin
correctly, but these gaps remain — all addressed in `round2-polish-prompt.md`:
- Compose `Modifier.neu` PRESSED path draws offset strokes → carved wells look like outlines, not inset.
- Every carved surface flattened to `PRESSED_SM`; deep surfaces (album well, calendar blocks, tray) need `PRESSED`.
- Music card regressed to the old 76dp tray — lost the 82dp well, 2×2 grid, and progress groove.
- Semantic colors re-hardcoded as hex instead of `Neu.*` tokens (inconsistent across light/dark).
- Tall-hero proportions not applied; cards kept old fixed sizes, content top-clustered.
- Large empty gap below the keyboard pushes it too high.

## Open questions parked
- Neumorphism on Android is render-heavy (dual blur shadows). If 60fps suffers, fall back to the
  layered-GradientDrawable approach (less soft, much cheaper). Watch on the Vivo device.
- Round 2's `Modifier.neu` replacement is a blind (uncompiled) translation — tune stroke width/alpha
  until Compose carved wells match the View drawable.
- keyboard-swap: exact hold threshold so hold-to-detach and tap-to-dock never misfire.
- Whether Widget-mode "select" should be a plain tap or a deliberate Install affordance.
