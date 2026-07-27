# Realistic 3D keycaps — without a frame-budget hit

Open [`index.html`](./index.html) in any browser. It's a self-contained, interactive prototype:
move the pointer over the keyboard (the light travels and the tray tilts), tap keys to feel the travel,
and toggle light/dark. Every keycap is generated the same way the Android app can produce them —
**composited once into an off-screen raster, cached by state, and blitted** — so the prototype itself is
a proof of the performance model, not just a picture of it.

---

## The one idea

> **Bake once. Blit many. Never light in the loop.**

The illusion of a physical keycap — curvature, ambient occlusion, a soft contact shadow, a chamfered rim —
is *expensive* to compute but *cheap* to copy. So we do all the expensive work a single time, at
theme/size change, and store the finished pixels. The typing hot path then only ever does one
`drawImage` (Android: `canvas.drawBitmap`) per key. Realism becomes free per frame.

This is already how Teclas draws keys today; the proposal is to make the *baked* artwork richer, not to
change the loop.

## What Teclas does now

- **`TeclasCanvasKeyboardView`** draws the whole grid on one `View`, blitting a cached `Drawable` per cell.
- **`keyBgCache`** (`TeclasImeService`) is a `HashMap<String, Drawable>` keyed by `label|pressed|row`,
  invalidated by a theme/palette signature. This is the cache we want.
- **`KeyboardThemeDrawables.depthKeyLayer()`** builds the 3D look as a stack of flat `GradientDrawable`
  layers: ambient shadow → three side walls → face gradient → top rim → sheen, with `setLayerInset`
  offsets that fake extrusion. `pressed=true` drops the face and collapses the wall — the mechanical
  travel.

The ceiling: `GradientDrawable` only gives you **linear gradients on rectangles**. You can't express a
curved keycap top, a radial "dish," or a soft blurred occlusion. That's the realism we're leaving on the
table.

## What the prototype adds

Everything below is baked into a bitmap **once** and then blitted. None of it touches per-frame cost.

| Technique | Realism it buys | Per-frame cost |
|---|---|---|
| **Dished / domed face** | A `RadialGradient` crown makes the top read as concave (chiclet) or convex (gum key) instead of flat. This is the single biggest jump. | 1 blit |
| **Tray ambient occlusion** | A soft, blurred dark shadow baked under and around the cap so it sits *in* a well instead of floating. | 1 blit (+ one shared board underlay) |
| **Contact shadow on travel** | The drop shadow tightens and darkens as the key sinks — the eye reads "it touched down." | swap to the pre-baked `pressed` tile |
| **Moving specular** | A single pre-baked radial sprite, drawn with `PorterDuff.SCREEN` at a light-derived offset, so caps glint as the light (or device tilt) moves. | +1 sprite blit |
| **Tilt parallax** | `SensorManager` tilt feeds a 2–3px offset *between* the cached face and wall layers — genuine parallax depth for ~zero cost. | 2 blits + translate |
| **Chamfer rim** | A bright top edge + dark bottom edge baked onto the face — the bevel that sells injection-molded plastic. | free (part of the face bake) |

## How it lands in the Android code

1. **Add `bakeCap()`** — render one keycap into an off-screen `Bitmap` with the full `Canvas`/`Paint`
   toolkit: `RadialGradient` for the dish, `BlurMaskFilter` for soft AO, the chamfer strokes, the
   embossed legend. Run it **once per theme + key-size + state**.
2. **Store a `BitmapDrawable` in `keyBgCache`** instead of the `LayerDrawable`. The key, the invalidation
   signature, and the blit site all stay exactly as they are.
3. **Keep `depthKeyLayer()`'s press geometry** — bake both the `rest` and `pressed` states; the loop just
   picks one.
4. **Optional polish, both loop-cheap:** a pre-baked specular sprite composited with `SCREEN`, and a
   `SensorManager` tilt offset between two cached layers.

## Cheap vs. forbidden on the hot path

**Cheap (safe every frame)**
- Blit a cached bitmap — one `drawBitmap`, zero allocation.
- Swap `rest ⇄ pressed` cached tiles.
- Translate a pre-baked specular sprite.
- Offset two cached layers by tilt.

**Costly (bake-time only — never per frame)**
- `RadialGradient` / `BlurMaskFilter` construction — cache the result, don't rebuild it.
- Rebuilding `GradientDrawable` stacks on every draw.
- `RenderEffect` blur or `elevation` shadows on live views — they force relayout.
- Per-key overdraw from many translucent layers — flatten into the one baked tile.

## The eight styles in the gallery

`flat` (control) · `bevel` (ships today) · `dish` (concave chiclet) · `dome` (convex) ·
`tray` (dish + occlusion) · `glass` (frosted, matches the `3dglass` theme) · `specular` (dish + light
tracker) · `accent` (Enter key, tap for travel).

Each maps to a `KbDepthStyle` variant, so a new look is a data row, not a rewrite.
