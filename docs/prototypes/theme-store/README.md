# Keyboard Theme Store — prototype

Open [`index.html`](./index.html). A browsable store for the keyboard themes, with **Rcaps** featured.

- **Live previews** — every card is a real mini-keyboard rendered from that theme's actual palette (the
  same colors defined in `KbThemes.kt`). Rcaps caps are baked exactly the way the app bakes them
  (`RcapsKeycaps`): radial dish, contact shadow, chamfer rim.
- **Dynamic accent** — the accent swatches in the top bar recolor every accent-driven theme (Rcaps,
  Teclas Glass, 3D Depth) live, mirroring how the app's `go_key_color` feeds `usesDynamicAccent()`.
- **Light & dark** — the toggle re-renders all previews in the theme's light or dark palette.
- **Search + Apply** — filter by name; Apply marks the active theme. In the app, Apply writes the
  `keyboard_theme` SharedPreference — the single switch every existing picker already uses.

## Why this is a prototype, not the shipped screen

There's no keyboard theme *store* in the app today — themes are picked in Theme Studio, a settings
selector, a gallery row, and the swipe-swap gesture, all backed by `keyboard_theme`. This page shows
what a dedicated store could look like. To ship it, the reusable pieces already exist:

- `ThemeStudioActivity.KeyboardPreview` renders a live mini-keyboard via `KeyboardThemeDrawables`.
- `KeyboardThemeDrawables.displayName(id)` for titles; `KbThemes.RENDERABLE` for the catalog.
- `ThemeRepository.applyTheme(...)` (or a direct `putString(KEYBOARD_THEME_PREF, id)`) to apply.

Sections used here (3D & Depth / Playful / Minimal & Pro / Nature & Warm) map cleanly onto the existing
`KbTheme` set — a store is a grouping + chrome layer over the catalog that already renders.
