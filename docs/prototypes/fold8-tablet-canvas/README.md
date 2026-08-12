# Fold 8 Tablet Canvas — visual prototype

The Fold 8's inner screen (7.6" 4:3, 2448×1848 ≈ 933×704dp) is a small tablet, so the
launcher stops rebuilding a phone on it. This mock shows the default unfolded experience:

- **The wallpaper canvas is the home.** No imposed columns or pages.
- **The favorites dock** is the one fixed element (bottom center).
- **The keyboard slides in and out** (⌨ button in the mock; on device the widget-keyboard
  slider — hidden state persists separately for the inner display) instead of permanently
  owning ~40% of a tablet.
- **Everything else is freeform, addable widgets** sized for this canvas: drag them
  anywhere, add from + WIDGETS (long-press the wallpaper on device for the "Add to Home"
  menu), remove in EDIT mode (long-press a widget on device for its manage menu). Clock,
  weather, agenda, brief, and the inner-only Music now-playing card are real; the People
  card mocks the next tablet-sized addition. (Agenda already renders its wide two-event
  card layout on the inner display.)

Open `index.html` in a browser. An earlier iteration of this prototype showed the
two-page "book" layout, which survives in the app as an off-by-default experiment
(`inner_pages`, searchable as "fold pages").
