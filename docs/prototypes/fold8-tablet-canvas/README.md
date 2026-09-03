# Fold 8 Tablet Canvas — visual prototype

The Fold 8's inner screen (7.6" 4:3, 2448×1848 ≈ 933×704dp) is a small tablet, so the
launcher stops rebuilding a phone on it. This mock shows the default unfolded experience:

- **The wallpaper canvas is the home.** No imposed columns or pages.
- **The favorites dock** is the one fixed element (bottom center).
- **The keyboard slides in and out** (⌨ button in the mock; on device the widget-keyboard
  slider — hidden state persists separately for the inner display) instead of permanently
  owning ~40% of a tablet.
- **Everything else is freeform, addable widgets** sized for this canvas: drag them
  anywhere, add from the long-press "Add to Home" menu, remove via a widget's long-press
  manage menu. (Agenda already renders its wide two-event card on the inner display.)
- **Music is an edge banner, not a canvas widget.** While something is playing,
  now-playing sticks flush to the left or right screen edge — album art plus a live
  pulse when collapsed; tapping glides it out with title, EQ, and controls, and it
  tucks itself back after a few seconds. Drag it vertically to pin it at any height
  along the edge, or across the screen to stick it to the other side. It stays clear
  of the keyboard deck when the keyboard slides in, and it disappears entirely when
  playback stops. The dock is not involved.

Open `index.html` in a browser. An earlier iteration of this prototype showed the
two-page "book" layout, which survives in the app as an off-by-default experiment
(`inner_pages`, searchable as "fold pages").
