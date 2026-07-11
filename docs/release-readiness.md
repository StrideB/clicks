# Teclas — Play Store & Open-Source Readiness (2026-07-09)

Honest assessment for a Play Store beta and/or open-sourcing. TL;DR: **beta is feasible in
1–2 focused weeks of policy work; the code is ready before the paperwork is.** Nothing in the
open-source dependencies blocks a Play release.

## Play Store: hard blockers (must fix before any track, including closed beta)

1. **`READ_SMS` will be rejected.** Play restricts the SMS permission group to default-SMS-handler
   apps and a short list of approved exceptions; "prediction seeding" does not qualify. Action:
   remove the permission and gate SMS seeding behind a non-Play build flavor (or drop it — the
   n-gram store already learns from typing).
2. **Release signing.** The committed debug keystore is fine for sideloads but must never sign a
   Play upload. Create a proper upload key, enroll in Play App Signing, and give the `release`
   build type its own signing config (keep the debug-key fallback only for local installs).
3. **Gmail API restricted scopes.** The flight/boarding-pass parsing reads Gmail. Any OAuth client
   using restricted Gmail scopes needs Google's app verification, and for >100 users a CASA
   security assessment (annual, third-party). For a closed beta you can stay in "testing" mode
   (≤100 test users, consent screen shows "unverified"). Long-term: move flight parsing to a
   user-forwarded-email model or make the Gmail integration an optional companion feature.
4. **Accessibility service declaration.** `InputInjectionService` (BIND_ACCESSIBILITY_SERVICE)
   requires the Play Console AccessibilityService declaration with a core-functionality
   justification, plus in-app prominent disclosure before enabling. Keyboards/launchers do get
   approved, but write the justification carefully — "inject typed input into other apps" needs
   framing around the docked-keyboard accessibility use case. Alternative: ship the beta with this
   feature flagged off and add it after approval.

## Play Store: required paperwork (not blockers, just work)

- **Privacy policy URL** (required given contacts/calendar/location/notification access) and the
  **Data safety form**. Teclas has a great story here: essentially everything stays on-device
  (prediction weights are even encrypted at rest); the only data leaving the device goes to APIs
  the user explicitly connects (Gemini, Spotify, Gmail, weather).
- **Notification listener + RECORD_AUDIO + location**: each needs a sentence of justification in
  the listing/declarations; all are standard for launcher/assistant apps.
- **`WRITE_SECURE_SETTINGS`** is adb-granted developer functionality; allowed on Play but expect a
  review question. Consider moving it to the non-Play flavor too.
- **Billing**: create the real Pro product in Play Console (`PRO_PRODUCT_ID` is a placeholder) and
  test with license testers.
- **Spotify**: production-mode API application (dev mode caps at 25 users) and add the
  `com.fran.teclas://spotify-callback` redirect.
- versionCode/versionName discipline per upload (currently hardcoded 1 / 0.1.0).

## Honest product-readiness opinion

The engineering fundamentals are now solid: modern toolchain, 35.8 MB minified release build,
R8-clean, lint-gated, leak-monitored, battery-audited. For a **closed/internal beta of daily-driver
enthusiasts, it's ready once the four blockers above are addressed** — the Vivo/Honor-specific
branches degrade gracefully and the launcher has been daily-driven for weeks. For an **open beta or
production**, budget hardening time for the long tail launchers face (hundreds of OEM/DPI/locale
combinations, edge-case apps in the drawer, IME conformance across apps) and set expectations with
a beta label. Recommended path: internal testing track → closed beta (≤100, keeps Gmail in testing
mode) → decide on CASA vs. dropping Gmail scope before open beta.

## Open source: the lowdown

**Using open source does NOT block Play release.** What matters is each dependency's license:

- All AndroidX/Jetpack, Kotlin, coroutines: **Apache-2.0** — free to use, no source obligation.
- ONNX Runtime: **MIT**. Play Billing, ML Kit GenAI: proprietary Google SDKs explicitly licensed
  for distribution in Play apps.
- **No GPL/copyleft code is present** — this was a deliberate project decision (Lawnchair was
  evaluated and ruled out over GPLv3; the swipe model contract in
  `keyboard/neural/NeuralSwipeContract.kt` documents it is original, and the model was trained on
  an MIT-licensed swipe corpus, not FUTO/CleverKeys weights).

So you have both options, independently or together:

1. **Closed source on Play**: fully compliant today. Optionally add a NOTICE/licenses screen
   (Apache-2.0 doesn't require it in-app, but it's good form; `oss-licenses-plugin` automates it).
2. **Open-sourcing your code**: it's your copyright — pick MIT or Apache-2.0 (Apache-2.0
   recommended: patent grant, contributor clarity). Before publishing: add `LICENSE` and `NOTICE`,
   document the dictionary wordlists' provenance in `assets/dict/`, and scrub git history if any
   secrets were ever committed (the debug keystore is intentionally public-safe; API keys live in
   runtime prefs, not the repo — verified). A "bare core" public repo + private full app is a
   common and legal split; nothing forces you to open-source anything just because you *use* OSS.

## Battery/perf posture (for the beta listing)

- Release builds AOT-optimize via R8 + bundled baseline profiles (profileinstaller).
- No polling while backgrounded (music tick, context-dock check + brief refresh pause in onPause).
- Cosmetic sensors run at 15 Hz, stop when not visible, and skip their callbacks entirely when the
  device is held steady (dead-band on emitted deltas), so an idle home screen draws no frames;
  no wakelocks, no alarms, no foreground services except the user-invoked docked keyboard overlay.
- All decorative animations are burst-bounded (weather ≤6.5 s, dock music notes ≤12 s @ 30 fps) —
  nothing self-invalidates at vsync indefinitely.
- Wallpapers decode at screen-fill size for the active zoom (not camera resolution) and are only
  re-decoded when the wallpaper id/uri actually changes, not on every resume.
- LeakCanary guards debug builds; heap-verified on emulator.
