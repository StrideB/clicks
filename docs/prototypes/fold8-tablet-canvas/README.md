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
- **Music is a banner, not a canvas widget.** While something is playing, now-playing
  lives in a slim glass banner the user pins bottom-left, bottom-right, or inside the
  dock (the dock grows a music segment after a divider); it disappears entirely when
  playback stops. The banner rides the same shelf as the dock, so it lifts with the
  keyboard. In the mock: switch with the PIN chips or drag the banner onto a drop zone.

Open `index.html` in a browser. An earlier iteration of this prototype showed the
two-page "book" layout, which survives in the app as an off-by-default experiment
(`inner_pages`, searchable as "fold pages").
