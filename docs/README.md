# Clicks — design & build docs

Everything from the Clicks design/redesign work, ready to hand to a coding agent (Codex/Claude Code)
or to pick back up later. Three independent workstreams live here.

_Last updated: 2026-07-03._

## Contents

```
docs/
├── neumorphic-redesign/     ← reskin the whole homescreen + Dark/Light/System theme
│   ├── codex-build-prompt.md      (task scope, theme setting, constraints, commits)
│   ├── design-spec.md             (CODE-LEVEL design bible: tokens, shadows, per-widget specs)
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

All three modify `MainActivity.kt` and/or `HomeWidgetStack.kt`, so run them sequentially and commit
between each to avoid merge pain:

1. **hardening** first — pure cleanup, smallest surface, makes the later work safer (and may extract
   pieces out of MainActivity that the next steps benefit from).
2. **neumorphic-redesign** — the big visual reskin of home chrome + widget stack + theme system.
3. **keyboard-swap** — layers the detach/swap interaction onto the (now reskinned) Widget-mode keyboard.

Rationale: hardening is behavior-neutral so it rebases cleanly; the reskin changes how surfaces are
drawn; the keyboard-swap builds on the keyboard that the reskin restyles. Doing them in this order
means each later prompt sees the previous one's result instead of fighting it.

## Files each workstream touches (for conflict awareness)
- **hardening:** `db/NgramDatabase.kt`, `db/NgramDao.kt`, `db/NgramEntry.kt`, `ClicksNotificationListener.kt`, `MainActivity.kt` (Gemini fns + extraction), `app/build.gradle.kts`.
- **neumorphic-redesign:** new theme/`Neu` helper files, `MainActivity.kt` (home chrome, keyboard, dock, settings selector), `HomeWidgetStack.kt` (all cards), prefs (`THEME_MODE_PREF`).
- **keyboard-swap:** `MainActivity.kt` (DOCK key handling, `homeKeyboardWidget()`), new `WidgetKeyboardSwapController`, prefs (`KEYBOARD_THEME_PREF` commit-on-seat).

## Open questions parked
- Neumorphism on Android is render-heavy (dual blur shadows). If 60fps suffers, fall back to the
  layered-GradientDrawable approach (less soft, much cheaper). Watch on the Vivo device.
- keyboard-swap: exact hold threshold so hold-to-detach and tap-to-dock never misfire.
- Whether Widget-mode "select" should be a plain tap or a deliberate Install affordance.
