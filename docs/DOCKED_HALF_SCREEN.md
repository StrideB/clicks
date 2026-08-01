# Docked half-screen on Xiaomi

Settings → scroll to the **KEYBOARD** heading → **APPS IN TOP REGION**, with **WHY IS IT
FULLSCREEN? →** indented underneath it.

That row is triple-gated — docked placement, not the unfolded inner layout, and `APPS IN TOP REGION`
already on — so on a phone where the feature looks broken it can be the one thing you cannot find.
Searching settings for **"fullscreen"** opens the diagnosis directly, whatever those toggles say.

Docked mode is supposed to open every app in the top half of the screen, above the keyboard. It does
that on Galaxy and on Vivo/OriginOS. On Xiaomi (MIUI 12+ / HyperOS) apps open fullscreen instead.

## Why it falls through on Xiaomi

Two routes existed, and both are AOSP-shaped:

1. `DockedFreeform` launches with `ActivityOptions.launchBounds` plus the freeform windowing mode.
2. `ShizukuPinner` re-asserts that through the privileged `IActivityTaskManager`.

MIUI's multi-window is a separate implementation ("small window"), and the ATM calls AOSP leaves
open to a privileged caller are gated to Xiaomi's own launcher. So `launchBounds` is ignored and
`setTaskWindowingMode` returns without doing anything — **no exception, no error code, just a
fullscreen app**. Every API involved reports success.

There was also a third route, `requestDockedSplitFallbackIfNeeded()`. It was written, and it had no
caller — the last resort had never once run. That is now wired up.

## What happens now

After a docked launch the launcher waits ~1.4s (first frame plus the OEM's window animation), then
**measures where the window actually is** — the accessibility service records the real
`getBoundsInScreen` rect into `DockedFreeform.lastExternalAppBounds`. That measurement, not
an API return value, decides what happens next:

| Rung | What it does | Needs |
| --- | --- | --- |
| `native` | The in-process launch already landed. Nothing to do. | — |
| `binder` | `ShizukuPinner.pin` — `setTaskWindowingMode` + `resizeTask`. | Shizuku |
| `shell` | `am task resize <id> …` at shell uid. MIUI's gate is on *who is asking*, and shell is not a third-party app. | Shizuku |
| `split` | Toggle split screen. Not freeform — the app gets a system-chosen half rather than our measured bounds — but every OEM implements it properly. | accessibility service |

The winning rung is latched in prefs (`docked_window_rung`), so this ladder is walked once per
device, not once per app launch. A rung that stops working escalates again from where it is.

**Each rung is verified before it is believed.** A rung returning true only means the call did not
throw, and MIUI accepts `setTaskWindowingMode` / `resizeTask` without acting on them — observed on
HyperOS as a window that *is* freeform but sits at the ROM's own default small-window geometry, not
filling the top region. So after each rung the ladder waits ~600ms and re-measures; only a window
that actually moved stops the climb. Width is checked as well as the bottom edge, because a default
small window can land at roughly the right height while covering only the middle of the screen.

One rung is deliberately *not* automatic: `am start --windowingMode 5`, a shell-uid launch, which is
the most likely thing to work on HyperOS but relaunches the app visibly and drops any deep link.
It is offered by hand as **Try shell launch** in the diagnostics dialog.

## Diagnostics

**WHY IS IT FULLSCREEN? →** runs every rung against the last app you opened from the dock and reports
what the device actually did: which calls were reachable, which were accepted, where the window
really ended up, and which route last worked. Copyable.

This exists because none of the above is verifiable without a Xiaomi in hand — every rung is reasoned
from how MIUI gates these calls, not from a device. **The next change to `DockedWindowStrategy.kt`
should be driven by that report rather than by another guess.**

### What the first real report cost us

A Xiaomi 25128PNA1G on Android 16 came back with three lines that were not true, all of them
flattering:

- `task lookup: no task found for <pkg>` — the task list is read through Shizuku, which was not
  connected. We had not looked at all. It now says so instead of blaming the app.
- `Window measured: not measured` — guaranteed, on every device, forever. The live measurement is
  cleared the moment no external app is in front, and reaching this screen means leaving the app.
  The measurement is now also kept in a sticky field that nothing clears, with the package name.
- `Last working route: split` — split screen had not placed anything; the rung was latched on the
  way *into* the fallback rather than after checking it. It is now latched only once a measurement
  agrees, like every rung above it, and the line reads "last route that placed a window".

A diagnostic that flatters itself is worse than none: it sends the next change in the wrong
direction with confidence.

## MIUI optimization

`DockedWindowStrategy.applyMiuiOptimizationOff()` writes `settings put global miui_optimization 0`,
which stops MIUI substituting its own implementations for a number of AOSP paths, multi-window among
them. It is deliberately never called during a normal launch: it is a broad, system-wide change that
also relaxes MIUI's permission handling and can affect battery behaviour. Wire it to an explicit,
warned action if the diagnostics show it is the missing piece.

## No computer needed

The `WRITE_SECURE_SETTINGS` grant this feature has always wanted can now be self-granted through
Shizuku — the setup dialog shows **Set up now** instead of an adb command when Shizuku is connected.
See the Shizuku section of `docs/GESTURE_NAVIGATION.md`.

## Where the code lives

| File | Role |
| --- | --- |
| `DockedWindowStrategy.kt` | The extra rungs, the latch, and the diagnosis. |
| `DockedFreeform.kt` | `lastExternalAppBounds` / `placedInTopRegion` — measured ground truth, distinct from the optimistic `externalAppInFront`. |
| `ShizukuPinner.kt` | `foregroundTaskId` — reading tasks and mutating them fail independently on MIUI. |
| `InputInjectionService.updateFreeformState()` | Records the real window bottom. |
| `MainActivity.escalateDockedPlacement()` | Verifies, escalates, and falls back to split screen. |
