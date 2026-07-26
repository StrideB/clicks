# On-device heat diagnostics

How to verify (or refute) the heat fix in PR #97 on a real phone, and how to run the test suite
locally. Written for the specific hypothesis below — but the measurement recipe generalises to the
next "why is it warm" question.

## The hypothesis under test

Before the fix, **every notification posted by any app on the device** ran a ~700-token LLM
generation:

```
notification posted (any app)
  → TeclasNotificationListener.onNotificationPosted
  → onBriefChanged                       (fired unconditionally)
  → BriefRepository.refreshNow()
  → BriefGenerator.geminiRank()          ~700 tokens, no cache
```

With a local model installed that runs on-device, so the prediction is: **a multi-second CPU spike
correlated 1:1 with incoming notifications**, including reposts of identical content, and including
while the launcher is backgrounded with the screen off.

After the fix, spikes should occur only for genuinely new notification content, at most once per
10-minute bucket for an unchanged signal set.

If you see sustained CPU with *no* correlation to notifications, this hypothesis is wrong and the
heat is coming from somewhere else — jump to [If it's not the brief path](#if-its-not-the-brief-path).

## 0. Setup

The phone is your daily driver, and `dumpsys batterystats` only accumulates while **on battery**.
So a USB cable defeats the measurement. Use wireless debugging.

```bash
# Phone: Settings → Developer options → Wireless debugging → Pair device with pairing code
adb pair <phone-ip>:<pairing-port>      # enter the 6-digit code
adb connect <phone-ip>:<debug-port>     # the port on the main Wireless debugging screen
adb devices                             # confirm "device", not "unauthorized"
```

Two properties of this project make the A/B unusually clean:

- `app/teclas-debug.keystore` is committed and **release builds are signed with it too**, so debug
  and release APKs install over each other with no data wipe. Your Spaces config, learned places,
  and usage history survive the swap.
- `app-release.apk` at the repo root is the **current shipping 0.3.8 build** — the "before" APK,
  already built, no work needed.

Get the "after" APK from the PR's CI run: GitHub → PR #97 → Checks → `build` → Artifacts →
`teclas-apks` → `app-debug.apk`. Use the **debug** one; `run-as` (step 4) needs a debuggable build.

```bash
adb install -r app-release.apk    # before
adb install -r app-debug.apk      # after
```

After each reinstall, re-check the notification-listener and accessibility grants — Android
sometimes drops them across an install even with a matching signature:

```
Settings → Notifications → Device & app notifications → Teclas   (must be ON)
Settings → Accessibility → Installed apps → Teclas               (only if you use docked mode)
```

## 1. The core A/B measurement

Run this once per APK. Keep the two runs as similar as you can — same time of day, same rough
message volume, phone idle in your pocket rather than in use.

```bash
adb shell dumpsys batterystats --reset
# unplug / disconnect USB. Leave the phone alone for 45-60 min with notifications arriving normally.
adb shell dumpsys batterystats --charged com.fran.teclas > before.txt
```

Then swap APKs, repeat, and save as `after.txt`.

What to compare — these are the lines that matter:

```bash
grep -E "Total cpu time|Foreground activities|Wake lock|Uid .*com.fran.teclas" before.txt after.txt
```

- **`Total cpu time`** — the headline number. This is what the fix should move. A drop of anything
  under ~20% is probably noise between two uncontrolled runs; the predicted effect is much larger
  than that on a phone getting steady message traffic.
- **`Wake lock`** entries — how long the app kept the CPU awake, and why.
- **Foreground vs background CPU split** — pre-fix, expect meaningful background CPU (the callback
  ran regardless of launcher lifecycle). Post-fix that should be close to nothing.

A convenient overall sanity check, though it covers the whole device rather than just this app:

```bash
adb shell dumpsys batterystats --charged | grep -A3 "Estimated power use"
```

## 2. The targeted repro (the decisive one)

The A/B above tells you *whether* it got better. This tells you *whether the diagnosis was right*,
and it takes two minutes.

Terminal 1 — watch this app's CPU only:

```bash
adb shell top -d 1 | grep --line-buffered teclas
```

Terminal 2 — send yourself real messages. Use Telegram/WhatsApp Web, or a second device. **Real
messages matter here**: the brief path deliberately skips notifications with no actions and no
`contentIntent` (`captureBriefRecord`'s early return), and a synthetic
`adb shell cmd notification post -S bigtext -t 'Test' teclas 'hello'` may not carry either — so a
null result from a synthetic notification proves nothing.

What you're looking for:

| | Before fix | After fix |
|---|---|---|
| First message in a conversation | multi-second CPU spike | multi-second CPU spike (expected — new content) |
| App reposting the *same* notification | **spike again** | no spike |
| Second identical-content update within 10 min | **spike again** | no spike |
| Screen off, launcher backgrounded | **still spikes** | no spike |

The middle two rows are the fix. If reposts still spike after the fix, the content hash isn't
matching and I've got something wrong — capture `adb logcat` around one and it can be traced.

## 3. Thermals

Correlate the spikes with actual temperature rather than trusting how it feels in your hand:

```bash
adb shell dumpsys thermalservice | grep -A20 "Current temperatures"
adb shell cat /sys/class/thermal/thermal_zone*/type   # zone names
adb shell cat /sys/class/thermal/thermal_zone*/temp   # matching values, usually milli-°C
```

Sample it over a few minutes while step 2 runs:

```bash
while true; do
  printf '%s ' "$(date +%T)"
  adb shell cat /sys/class/thermal/thermal_zone0/temp
  sleep 5
done
```

`dumpsys thermalservice` also reports the current throttling status — if the phone has entered a
throttling state, that is the system confirming this is real and not perception.

## 4. Reading the app's own state

Debug build only:

```bash
# Spaces config, learned places, wifi bindings, usage history
adb shell run-as com.fran.teclas cat /data/data/com.fran.teclas/shared_prefs/teclas.xml

# just the Spaces bits
adb shell run-as com.fran.teclas cat /data/data/com.fran.teclas/shared_prefs/teclas.xml \
  | grep -E "spaces_config_v1|spaces_lock|spaces_ai_enabled|wifi_place_bindings_json"

# live logs for this process only
adb logcat --pid=$(adb shell pidof -s com.fran.teclas)
```

Jump straight to a screen without navigating:

```bash
adb shell am start -n com.fran.teclas/.SpacesSettingsActivity
```

That screen shows the currently detected Space and the snapshot behind it — which is the fastest
way to check the Spaces finding from PR #97. On a weekend at home it should report **Home**, and
Space Today should be empty, because `WORKLOAD_SPACES` is `{work, travel, fitness, gym}` and Work
is `weekdaysOnly`. That's the gap; detection itself is behaving correctly.

## 5. Running the tests locally

The native build needs llama.cpp vendored at the pinned tag — it's gitignored, so a fresh clone
doesn't have it and CMake fails at `add_subdirectory(llama.cpp)`:

```bash
git clone --depth 1 --branch b9967 https://github.com/ggml-org/llama.cpp app/src/main/cpp/llama.cpp
```

Then:

```bash
./gradlew testDebugUnitTest                                  # full JVM suite
./gradlew testDebugUnitTest --tests '*SpaceDetectionTest*'   # just Space detection
./gradlew assembleDebug                                      # build the APK yourself
```

Requires JDK 21 and an Android SDK with NDK + CMake 3.22.1 (`ANDROID_HOME` set). Test report lands
at `app/build/reports/tests/testDebugUnitTest/index.html`.

The suite is plain JVM — no device or emulator needed. `SpaceDetectionTest` covers
`SpaceManager.detectIn`, including the two weekend-gap cases added in PR #97.

Install what you just built, keeping data:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## If it's not the brief path

If step 2 shows no correlation between notifications and CPU, the diagnosis was wrong. Next
suspects, in the order worth checking:

```bash
# Which app is actually burning CPU — confirm it's even this one
adb shell top -o %CPU -n 1 | head -15

# Wakelocks device-wide: what is holding the CPU awake
adb shell dumpsys power | grep -A20 "Wake Locks"

# Is the accessibility service subscribed to the scroll firehose?
# Expect typeWindowStateChanged only, unless docked keyboard mode is ON.
adb shell dumpsys accessibility | grep -A10 "com.fran.teclas"

# Alarms / scheduled wakeups registered by the app
adb shell dumpsys alarm | grep -B2 -A8 "com.fran.teclas"

# GPU/rendering — rules in or out the wallpaper-blur and glass paths
adb shell dumpsys gfxinfo com.fran.teclas | head -30
```

The always-on surfaces are `TeclasNotificationListener`, `InputInjectionService` (accessibility),
`TeclasImeService`, and `DockedKeyboardService`. Everything driven by `MainActivity` is torn down
in `onPause`, so if the heat persists with the launcher backgrounded, it is one of those four.
