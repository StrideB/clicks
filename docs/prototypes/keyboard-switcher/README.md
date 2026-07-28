# Switcher + Theme Store button — prototype

Open [`index.html`](./index.html). Shows the **Theme Store button riding on top of the existing
keyboard-switch animation** — the swipe-to-swap gesture is untouched; the store is an added doorway.

- **Swipe the deck** left/right to flick through themes in place — same interaction the app ships
  (`DockedKeyboardService.handleDockedKeyboardSwapTouch`): the 0.22 drag factor, the ~42px commit
  threshold, the slide-out/slide-in, and the bottom **theme dots** (active = wide green pill).
- **Theme Store pill** at the top of the deck, shown **only in switch mode** (when the deck tilts back
  and the dots appear) so it never costs space while typing. Tapping it slides up a browse sheet of the
  full catalog; picking a theme applies it and closes the sheet.
- Both paths just set the `keyboard_theme` preference — the store doesn't replace the swipe, it sits
  beside it.

## How it maps to the app

- The switch UI is `DockedKeyboardService`: `updateSwapLayout` (the tilt + dots fade-in),
  `previewDockedKeyboardTheme` (the slide), `updateDockedThemeDots` (the dot row).
- To add the button: in the swap-mode overlay (the `FrameLayout` that already hosts `themeDotsView` at
  `Gravity.BOTTOM` and `freeformRestoreButton` at `Gravity.TOP or END`), add a **top-center pill** that
  fades in with `swapMode` and launches the store/Theme Studio. Nothing on the swipe path changes.
- The sheet's grid reuses the same live-preview rendering as the theme-store prototype
  (`../theme-store/`), which maps to `ThemeStudioActivity.KeyboardPreview` + `KeyboardThemeDrawables`.
