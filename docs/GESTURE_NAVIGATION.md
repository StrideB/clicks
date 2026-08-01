# Full-screen gesture navigation

**Settings → LAUNCHER → GESTURE NAVIGATION →**, or search settings for "gestures".

The problem this solves: several ROMs disable full-screen gestures the moment a third-party
launcher becomes the default home, and force 3-button navigation instead. Xiaomi (MIUI 12+ and
HyperOS) is the one people hit hardest — a Xiaomi 17 Ultra running this launcher gets buttons and no
way to ask for gestures in system settings.

The ROM has an override for it, and the launcher also ships its own gesture navigation for where
that override does not deliver — which, on HyperOS, is where it lands. Start with step 1.

---

## If you have no navigation right now

Buttons gone and gestures dead? Over adb:

```
adb shell settings put global force_fsg_nav_bar 0
adb shell settings put global hide_gesture_line 0
adb shell cmd statusbar disable-for-setup false
adb reboot
```

To move around before rebooting: `adb shell input keyevent KEYCODE_HOME` (also `KEYCODE_BACK`,
`KEYCODE_APP_SWITCH`).

In-app, the same thing is **RESTORE THE BUTTONS** in the gesture settings — deliberately never gated
on what the toggles believe the state is.

---

## Why the ROM does this

MIUI's full-screen gestures are not implemented in SystemUI alone — the swipe-up-and-hold recents
animation is driven by `com.miui.home`, Xiaomi's launcher. With a different launcher as default,
that half of the gesture stack is gone, so the ROM falls back to buttons rather than ship gestures
that only half work.

The ROM keeps an override for exactly this case: `Settings.Global.force_fsg_nav_bar`. Setting it to
`1` is meant to keep full-screen gestures available with any default home. That is what step 2
writes — and on HyperOS it takes the buttons away without delivering the gestures. See the warning
under step 2.

Stock-shaped Android is a different mechanism entirely. There the navigation mode comes from
whichever `com.android.internal.systemui.navbar.*` runtime overlay is enabled;
`Settings.Secure.navigation_mode` is the value SystemUI *publishes* after reading that overlay, not
an input. Writing it alone changes nothing on stock, so the AOSP path uses `cmd overlay` instead —
which needs shell uid, and therefore Shizuku.

---

## The three steps

The settings screen is a ladder. Each step only matters if the one above it did not already solve
it on your device, and each one reports back exactly what landed and what did not.

### 1 · The launcher's own gesture bar

The rung that works everywhere, including ROMs that will not hand their gestures back — and the
only one that cannot leave the phone un-navigable, so it goes first.

Three invisible strips, hosted as accessibility overlay windows by the launcher's existing
accessibility service:

| Gesture | Action |
| --- | --- |
| Swipe inward from the left or right edge | Back |
| Swipe up from the bottom | Home |
| Swipe up from the bottom and hold | Recents |
| Swipe down on the bottom strip | Notification shade |
| Long swipe sideways along the bottom | App switcher |

Requires the launcher's accessibility service to be on — `performGlobalAction` on a connected
accessibility service is the only thing on the device that can press a system Back. The settings
screen says so plainly and links straight to the accessibility settings when it is off.

**The honest cost.** A touchable strip consumes the DOWN before anyone knows what the stroke will
become, so whatever is underneath it never sees the touch. That is inherent to every third-party
gesture bar on Android. Three things keep it small:

- the strips default to roughly the size of the system's own gesture regions (16dp at the sides,
  20dp at the bottom), and both are adjustable;
- each strip can be switched off independently;
- **pass taps through** (default on) replays a bare tap as a synthesised tap underneath the bottom
  strip, so an app's bottom navigation row stays alive with the navigation bar gone.

There is no quick-switch. AOSP exposes no such action to accessibility services — the recents
animation it rides on is private to the system's own launcher. A long sideways swipe opens the app
switcher instead: one extra tap, and it never lands on the wrong app.

### 2 · Ask the system for its own gestures

> **Verified failure on a Xiaomi 17 Ultra / HyperOS.** `force_fsg_nav_bar` stopped the ROM drawing
> the buttons, and the gesture handler it is supposed to hand over to lives in `com.miui.home` — so
> with this launcher as default it never engaged. Buttons gone, gestures absent, no way back or home.
>
> That is why this step is second and gated: it refuses to run unless the accessibility service is
> live, it switches step 1 on as a safety net when it does run, and **RESTORE THE BUTTONS** sits
> under it unconditionally. On HyperOS, expect step 1 to be the answer and this step to be a
> disappointment.


Best outcome when it works: real animations, real quick-switch, nothing intercepting touches.

| Device | What gets written | Needs |
| --- | --- | --- |
| Xiaomi / Redmi / POCO | `settings put global force_fsg_nav_bar 1` (plus `hide_gesture_line 1` if you asked to hide the handle) | one-time adb grant |
| Everything else | `cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural`, plus `settings put secure navigation_mode 2` | Shizuku for the overlay, adb grant for the setting |

The one-time grant:

```
adb shell pm grant com.fran.teclas android.permission.WRITE_SECURE_SETTINGS
```

This is the same grant the docked-freeform feature already asks for, so on a device set up for that
it costs nothing extra. Run it once from any computer; it survives reboots and app updates, and it
is not needed again.

MIUI re-evaluates the third-party-launcher rule whenever the default home changes, so the flag
genuinely comes back off after a launcher switch or a system update. The launcher re-asserts it on
every resume rather than making you revisit this screen.

Some ROMs only re-read the navigation mode when SystemUI restarts. The screen offers **RESTART
SYSTEM UI** when Shizuku is connected; otherwise, reboot once.

### 3 · Take the navigation bar away

**On the home screen** — free, no permission. The launcher hides the bar in its own window. Other
apps keep theirs. Only offered when step 1 is on, so hiding the buttons never leaves the home screen
with no way off it.

The window uses immersive-sticky, so a swipe from the bottom edge brings the bar back transiently.
That is the escape hatch if the gesture bar is switched on but the accessibility service is not:
the strips draw but cannot fire, and the transient swipe is how you get back to the buttons.

**System-wide, every app** — Android 12 removed every other route (`wm overscan` went in Android 11,
the `policy_control` immersive shortcut in Android 12), so what remains is the switch the setup
wizard itself uses:

```
adb shell cmd statusbar disable-for-setup true      # hide
adb shell cmd statusbar disable-for-setup false     # restore
```

Read before using it: it hides the **status bar** as well as the navigation bar, it locks the
notification shade, and it clears itself at the next reboot. Only worth it with the gesture bar
already working — otherwise the device has no way to go back or home. The launcher runs it through
Shizuku when Shizuku is connected, and refuses if neither gestures nor the gesture bar is live.

---

## Doing it by hand

Everything above is these commands, run for you. The settings screen lists the device-appropriate
ones with a copy button.

```
adb shell pm grant com.fran.teclas android.permission.WRITE_SECURE_SETTINGS

# Xiaomi / MIUI / HyperOS
adb shell settings put global force_fsg_nav_bar 1
adb shell settings put global hide_gesture_line 1

# Stock-shaped ROMs
adb shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural
adb shell settings put secure navigation_mode 2

# Both: hide the bars entirely until the next reboot
adb shell cmd statusbar disable-for-setup true
```

---

## Where the code lives

| File | Role |
| --- | --- |
| `nav/SystemGestureBridge.kt` | Step 1. Vendor detection, the settings writes, the overlay switch, and the report the settings screen renders. |
| `nav/ShizukuShell.kt` | Runs a shell command at adb-level uid via Shizuku. Reflective — `Shizuku.newProcess` is library-internal. |
| `nav/GestureClassifier.kt` | Pure stroke → action rules. Split out so the part where the bugs live is unit-testable. |
| `nav/GestureNavOverlay.kt` | The three strips, the back arrow, tap passthrough. |
| `nav/NavActions.kt` | The bridge to `performGlobalAction`. Holds the connected accessibility service. |
| `nav/GestureNavPrefs.kt` | Everything persisted, in the shared `teclas` preference file. |
| `nav/GestureNavSettingsActivity.kt` | The settings screen. |
| `InputInjectionService.kt` | Hosts the overlay and hands itself to `NavActions` on connect. |
| `MainActivity.syncSystemBars()` | Hides the navigation bar inside the launcher's own window. |

Tests: `app/src/test/java/com/fran/teclas/nav/`.

---

## No computer at all: the Shizuku route

Every command on this page is `pm grant` / `settings put` / `cmd …` run at **shell uid**. That is the
only thing adb was ever contributing. Shizuku provides the same uid on-device, so when Shizuku is
connected the launcher runs all of it itself:

- **Settings → GESTURE NAVIGATION → GRANT IT NOW — NO COMPUTER NEEDED** grants
  `WRITE_SECURE_SETTINGS` to the launcher via `pm grant`, then APPLY NOW does the rest.
- The docked top-region setup dialog gains **Set up now**, which self-grants and arms freeform in
  one tap.

Shizuku itself still has to be started once — on Android 11+ that can be done entirely on-device
through wireless debugging, no cable. If Shizuku is not running, everything falls back to the
copyable adb commands, and nothing silently does less than it says.

Privileged steps run off the main thread throughout: a shell round-trip has an 8-second ceiling, and
on the main thread a wedged Shizuku would be an ANR rather than a slow button.
