# Teclas launcher — hardening pass (fixes & improvements only)

You are working on the `StrideB/teclas` repository: a native Android launcher (Kotlin, `com.fran.teclas`, minSdk 31 / targetSdk 35, classic Views + some Jetpack Compose surfaces).

## Ground rules — READ FIRST
- **Do not change product behavior, UX, layout, visuals, or feature set.** No redesigns, no new features, no removed features.
- This is a **hardening and correctness pass only**: improve robustness, fix latent bugs, fix data-loss risks, and reduce maintainability debt **without altering how the app looks or behaves for the user.**
- Preserve everything the README's "Design Rules For Future Agents" section mandates: the centered homescreen (top weather → centered contextual widget stack → bottom favorites dock), Docked vs Widget keyboard placement modes, GoKeys changes scoped only to the `gokeys` theme, and fully data-driven apps/notifications/context.
- Where a change could alter behavior, **default to the safest option, keep the current behavior, and leave a `// TODO:` note instead of guessing.**
- Work incrementally. After each change, ensure the project still compiles (`./gradlew assembleDebug`). Do not proceed if a change breaks the build.
- Make small, focused commits — one logical fix per commit with a clear message. Do not squash unrelated fixes together.
- If any fix cannot be done without changing behavior, STOP and describe the tradeoff rather than forcing it.

## Fixes to apply, in priority order

### 1. Room: replace destructive migration with real migrations (data-loss risk — highest priority)
- In `app/src/main/java/com/fran/teclas/db/NgramDatabase.kt`, the database currently uses `.fallbackToDestructiveMigration()` with `exportSchema = false`. This means **any future schema change silently wipes everything the keyboard has learned about the user.**
- Change `exportSchema = false` → `exportSchema = true` and configure the Room schema export directory in `app/build.gradle.kts` (KSP `arg("room.schemaLocation", "$projectDir/schemas")`), so schema versions are tracked in source control.
- Remove `.fallbackToDestructiveMigration()`. Since the current schema is version 1 with no prior released migrations, no `Migration` objects are needed yet — but the builder must be set up so that **future** version bumps require an explicit migration rather than a silent wipe.
- Do not change the current schema shape (`NgramEntry`) or table name. This is purely about making future upgrades non-destructive.
- If removing destructive fallback would risk a crash for existing installs on first launch, keep behavior safe: keep the DB at version 1 and simply ensure the migration path is wired for the future. Leave a `// TODO:` explaining the first real migration must be added on the next schema change.

### 2. N-gram store: use the `lastUsed` column that is currently written but never read
- In `NgramEntry` / `NgramDao`, `lastUsed` is updated on every `increment` but no query ever reads it, so the table grows unbounded and ranking has no recency component.
- Add (do NOT wire into the hot path in a way that changes suggestions yet):
  - A DAO query to prune entries older than a cutoff and/or beyond a max row count (e.g. keep the top-N by count, or delete where `lastUsed <` cutoff).
  - A DAO count query already exists (`wordCount()`); use it to trigger pruning only when the table exceeds a sane ceiling.
- **Keep current suggestion ordering (`ORDER BY count DESC`) unchanged** so predictions the user sees do not shift. Pruning must only remove clearly-stale/overflow rows, not reorder live results.
- Call the prune opportunistically off the main thread (the repository already has an IO `CoroutineScope`). Do not block typing.

### 3. NotificationListener: remove static mutable state as the activity↔service bridge
- In `app/src/main/java/com/fran/teclas/TeclasNotificationListener.kt`, `notificationIntents` and `notificationAvatars` are `companion object` mutable maps shared between the service and `MainActivity`. This is a leak vector (static `Bitmap` maps) and has no synchronization.
- Make these thread-safe without changing behavior: at minimum wrap them in a synchronized/concurrent structure so concurrent notification callbacks and UI reads cannot corrupt them or throw `ConcurrentModificationException`.
- Keep the existing `MAX_AVATARS` FIFO eviction and `recycle()` hygiene. Ensure eviction and access are consistent under the new synchronization.
- **Do not change** which notifications are captured, how they're classified, or how the widgets read them. Callers in `MainActivity` must keep working with identical results.
- If a full refactor to a lifecycle-aware singleton/repository is too invasive to do without behavior risk, do the minimal thread-safety fix now and leave a `// TODO:` describing the cleaner ownership model.

### 4. Gemini: harden the network + JSON parsing (no behavior change on success path)
- In `MainActivity.kt`, `fetchGeminiSuggestions` and `fetchGeminiCompletion` use raw `HttpURLConnection` and parse the model output with a regex over the text.
- Improvements that must NOT change successful results:
  - Ensure connections are always closed (wrap in try/finally or `use`), including on non-2xx responses, to avoid leaked connections.
  - Replace the brittle regex array-extraction with proper JSON parsing (`JSONArray`) **with a fallback to the current regex behavior** if strict parsing fails, so any response that works today still works.
  - Keep the same timeouts, same prompt text, same model/endpoint, same Pro-gating (`requirePro` / `isUnlocked`), and same debounce timings. Purely defensive hardening.

### 5. `MainActivity.kt` decomposition (maintainability — do this carefully and last)
- `MainActivity.kt` is ~10,400 lines and mixes keyboard input, glide typing, autocorrect, Gemini/network, Spotify/Gmail OAuth callbacks, billing, home-grid drag-and-drop, the ZEISS shutter view, and pane routing.
- **This is a pure structural refactor: extract cohesive groups into separate classes/files WITHOUT changing any logic or behavior.** Suggested seams (extract only if it can be done mechanically and safely):
  - The keyboard/input controller (key handling, shift state, autocorrect, glide, prediction wiring).
  - The AI/network layer (Gemini fetch functions).
  - The home-screen layout + home-tile drag/edit logic.
  - Inner `View` subclasses (e.g. `ZeissCameraButtonView`, `WheelWellView`, `ClickWheelView`, `SwipeKeyboardLayout`) into their own files.
- Constraints:
  - Move code, do not rewrite it. Preserve method bodies verbatim where possible.
  - Keep identical behavior, ordering of side effects, and lifecycle calls.
  - Prefer many small commits, one extraction per commit, compiling and (if possible) smoke-testable after each.
  - If any extraction requires changing visibility/state ownership in a way that could alter behavior, STOP and leave it in place with a `// TODO:` note.

## Explicitly OUT OF SCOPE (do not touch unless asked)
- The `READ_SMS` permission and the Gmail OAuth placeholder scheme in the manifest are **known productization/Play-Store items** — do NOT change them in this pass; they need product decisions, not code hardening.
- Do not rename the package, change `applicationId`, versionCode/versionName, or the freemium/Pro gating.
- Do not migrate the widget-context SharedPreferences store to Room in this pass (larger change) — but you MAY leave a `// TODO:` at the read/write sites noting Room would be the more robust home, consistent with the n-gram store.
- Do not touch legacy internal names (`hub`, `ribbon`, `nowPlayingCard`) — the README says treat them as history.

## Deliverable
- A series of small commits implementing 1–4 fully and 5 as far as is safely mechanical.
- A short summary at the end listing: what changed, what was intentionally left as `// TODO:`, and anything you stopped on because it risked changing behavior.
- Confirm `./gradlew assembleDebug` succeeds after the final change.
