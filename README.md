# Teclas

Teclas is a native Android launcher built around typing as the primary action. It is not the old icon-list scaffold, not the old Message Hub prototype, and not the left-column/right-ribbon communicator mockup. The current launcher is a centered premium homescreen with contextual widgets, a favorites dock, a custom on-screen keyboard, type-to-do search, app library, media controls, photos, and native Android integrations.

Package: `com.fran.teclas`  
Platform: Android app, Kotlin, Jetpack Compose (settings, Today page, music player, widget stack, photos) plus native views for the launcher core and IME  
SDK: `minSdk 31`, `targetSdk 37`, `compileSdk 37`  
Toolchain: Gradle 9.6 (configuration cache), AGP 9.2 (built-in Kotlin), Kotlin 2.3, versions managed in `gradle/libs.versions.toml`  
Builds: debug (LeakCanary attached) and R8-minified release (~36 MB, arm64-only); lint gated by `app/lint-baseline.xml`

## Current Product Shape

The launcher has two keyboard placement modes:

- **Docked mode**: the current Teclas command-console layout. The typing strip and keyboard are fixed below the homescreen content. Typing can open the full app/library search experience because the keyboard owns the bottom of the phone.
- **Widget mode**: the same keyboard/input engine is mounted as a homescreen widget. Typing stays on the homescreen and does not auto-open the full app library. The `teclas` key becomes `DOCK`, which switches back to Docked mode.

New installs show a first-run picker for Docked vs Widget. The same setting is also available in Keyboard Settings under `PLACE`.

## Screen Order

The main launcher layout is:

1. Android status bar inset
2. Homescreen content area
3. Typing strip, only in Docked mode
4. Keyboard dock, only in Docked mode

In Widget mode, the typing strip and keyboard move into the homescreen content area as a keyboard widget, above the favorites dock.

## Homescreen

The homescreen is a centered vertical composition, not a split layout:

- Weather header at top with animated local weather, temperature, feels-like, humidity, and wind.
- Contextual widget stack centered in the middle of the screen.
- Favorites dock near the bottom with up to 5 apps.
- Optional keyboard widget when `keyboardPlacement == widget`.

Current homescreen layout rules:

- No left-side widget stack.
- No left-side Message Hub.
- No hard-coded Mara / Dev grp / Boss rows.
- No right-side Niagara app ribbon as the main homescreen.
- No hard-coded `COMMUNICATOR` status row.
- App access belongs in the app library and the 5-app favorites dock.
- Context belongs in the centered widget stack.
- Typing belongs in the docked keyboard or the keyboard widget, depending on placement mode.

The homescreen visual language is neumorphic and token-driven, not hard-coded dark:

- All surfaces derive from `Neu` tokens (`Neu.Dark` / `Neu.Light`, selected by the `theme_mode` pref), rendered as raised/recessed soft surfaces in views and via the `Modifier.neu()` extension in Compose.
- The user's look is owned by the Theme Studio (see Theme System below): wallpaper, keyboard theme, icon style, brief/weather styles, and the `go_key_color` accent (brand default: Cursor Violet `#C9A7FF`).
- Widget cards use colored accent spines; avatars, badges, and dock icons are domed/radial surfaces.

Important: do not redesign this as the original hard-coded Message Hub/app ribbon prototype or as a plain app icon list.

Battery/heat: the homescreen has no sensor-driven parallax. The favorites dock
does not tilt with device motion, and the home wallpaper does not pan with
device motion — both were removed (along with their `TYPE_ROTATION_VECTOR`
sensor listeners, `ParallaxSensorEngine` and `LiveWallpaperMotionController`,
deleted entirely) because continuous ~30 Hz sensor fusion plus full-screen
wallpaper re-invalidation was a real heat/battery cost. Do not re-add tilt-
driven dock or wallpaper motion. (The keyboard's separate "tilt lighting"
setting/toggle is unrelated and untouched — it's keycap-highlight territory,
not a homescreen effect, and out of scope here.)

## Today (Daily Brief)

Swiping right on the homescreen opens the Today page — a Compose live timeline (`brief/TodayPage.kt`, hosted by `TodayPaneHost`) built from notification signals, calendar, weather, and travel. The `brief/` package owns collection (`BriefRepository`, refreshed on resume and every 45 minutes while the launcher is foreground), classification, and Gemini-assisted summarization. It needs Notification Listener access for the richest content and degrades to an access prompt without it. Brief style/visibility is a themable layer with its own picker (`BriefThemePicker`).

## Spaces (Contextual Prediction)

The `predict/` package is an on-device bandit engine that rearranges the dock, drawer, and search suggestions around context (hour bucket, weekday, saved places, driving, headphones, charging). Spaces are user-editable in `SpacesSettingsActivity` (Compose): triggers, pinned/excluded apps, manual places (stored encrypted via `PlaceStore`), and per-Space learning resets. Place suggestions surface as actionable notifications (`PlaceSuggestionNotifier`). Everything is decided on-device; transition logs are Keystore-encrypted.

## Current Dimensions And Layout Constants

These are the current code-level sizing rules:

- Home grid constants still exist for internal layout support: `HOME_GRID_COLUMNS = 4`, `HOME_GRID_ROWS = 12`.
- Favorites dock limit: `DOCK_APP_LIMIT = 5`.
- Docked keyboard height: `dp(272 + keyboardSize * 80 / 100)`.
- Widget keyboard height: `keyboardHeight() + 44dp`, clamped between `250dp` and `380dp`.
- Typing strip height: `34dp`.
- Widget stack height is calculated from available content height and keyboard height, then clamped roughly between `126dp` and `214dp`.
- Keyboard size is user-controlled from `keyboardSize`, persisted in preferences.

The docked keyboard should feel like it owns the lower portion of the screen. The widget keyboard should feel like a compact homescreen typing instrument.

## Keyboard

The keyboard is rendered in native Android views and uses the same input engine in both placement modes.

Shared behavior:

- Type-to-search apps and commands.
- Type-to-do commands such as opening apps, contacts, calendar actions, music, and Gemini prompts.
- Haptic feedback.
- Long-press and flick/prediction behavior.
- Space cursor movement.
- Number/symbol modes.

Keyboard themes (`KEYBOARD_THEME_*` constants; art in `KbThemes.kt` / `KeyboardThemeDrawables.kt`):

- `default`
- `teclas`
- `skeuo`
- `gokeys`
- `brushed`
- `seeme`
- `hyper3d`, `hyper3d_black`, `hyper3d_light`

The GoKeys reference keyboard geometry and visual treatment is scoped to the `gokeys` theme only. Do not apply GoKeys key geometry or labels globally.

Widget mode special behavior:

- The visible `teclas` key label changes to `DOCK`.
- Pressing `DOCK` switches to Docked mode.
- Typing does not auto-open the app library, so the widget remains visible.

Docked mode special behavior:

- The typing strip and keyboard live below the content area.
- Typing can auto-open the full app library/search.
- This is the heavier command/search mode.

## App Library And Search

The app library is backed by installed launchable apps, not hard-coded entries. Search should find installed apps by user-facing app name, not show technical package IDs as primary UI.

The library supports:

- Grid/category display modes.
- Bento-style category presentation.
- Icon pack support.
- Per-app icon overrides.
- Long-press app actions, including adding/removing home dock favorites.

URL queries open directly: typing a URL-shaped query ("theverge.com") surfaces
an "Open theverge.com" card ranked first (`SearchKind.WEB`), so GO opens the
site itself instead of a Google search about it. Tap/GO opens the in-launcher
Custom Tab sheet (`InAppGoogleSearchEngine.launchInAppUrl`); long-press opens
the full default browser. Detection is `InAppGoogleSearchEngine.urlFromQuery`
(no spaces, no `@`, `Patterns.WEB_URL` full match, scheme defaulted to https).
Opening triggers only on tap/GO — never on keystroke, since a prefix of a URL
is itself a valid URL.

Phone numbers call directly: the number pad ("123" key) is a dialer — type
the digits, press GO, and the phone places the call itself (`placeCall` →
`ACTION_CALL`), no hop into the Phone app to press call again. GO calls the
matched saved contact if the typed digits match one, otherwise dials exactly
what was typed (no length filter in dial mode — short/extension numbers dial
too). Placing a call needs `CALL_PHONE`; it's requested on first use, and if
the user declines, GO falls back to `ACTION_DIAL` (dialer pre-filled) so the
call is still one tap away. `pendingCallNumber` bridges the request → the
result callback places the call on grant.

In universal search, a typed bare number ("5551234567", "(555) 123-4567",
"+1 555 123 4567" — digits plus phone-shaped separators, at most one leading
`+`, no letters, 7-15 digits) surfaces a "Tap to call" card under People
(`phoneNumberFromQuery`, same `placeCall` path), skipped when a real saved
contact already matched or the query also parsed as a URL (e.g. a bare IP).
The explicit `call`/`text` verb commands (`findPhoneContact`) fall back to
the raw typed number when no saved contact matches the name, so
"call 5551234567" and "text 5551234567 on my way" work for unsaved numbers.

Search results read top-down in three zones: one instant answer, apps, then
everything else grouped under headers ("People", "Web", "Because you're home",
etc. — `searchZoneHeader`, sized as real headings, not tiny eyebrow labels).
Apps (zone 2, and predicted-app context suggestions) render as a fixed-column
grid matching the App Library's own tile treatment (icon frame size, label
size/color) via `searchAppGrid`/`searchAppTile` — never a horizontal scroll
row, so nothing in search reads as draggable. Tap/long-press stay search-
specific (open app; long-press pins to dock or the active Space board), not
the library's rename/hide icon menu.

Tap invariant: the results list is torn down and rebuilt whenever async
searches land (contacts/web/semantic arrive a beat after typing). If that
rebuild fires between a card's finger-down and finger-up the card is removed
mid-touch and its click is cancelled — so only long-press (which fires during
the hold) would register. The three rebuild entry points
(`refreshLibraryContent`, `refreshWidgetSearchContent`,
`refreshUnfoldedLibraryContent`) therefore call `deferSearchRebuildWhileTouching()`
first: while a finger is down on the results, the rebuild is held (pending) and
flushed on finger-up (`trackSearchResultTouch`, run for every event at the top
of `dispatchTouchEvent`). Do not rebuild the results view unconditionally from
an async callback, or single taps break again.

Search text size is user-controlled: Settings → `SEARCH TEXT SIZE` slider
(0–100, default 50 = Medium, `SEARCH_FONT_SIZE_PREF`) scales zone headers,
result titles/subtitles, and app-tile labels together via `searchFontScale()`.
Also reachable through type-to-customize by typing `search text size` or
`font size`.

## Search Backends

Universal search composes several result sources into the three-zone layout above (`SearchKind`: APP, CONTACT, EMAIL, MESSAGE, CALENDAR, AI, TRAVEL, MUSIC, FILE, SETTING, WEB, ANSWER):

- **Brave Search API** (`BraveSearchApi.kt`) is the web backend — inline result rows and rich answer cards rendered natively (no popups), with the required Brave attribution. Rich results cost 2 API requests; the free credit is ~1k requests/month, so web calls are debounced and only fire for query shapes that want the web.
- **Semantic search** (`semantic/SemanticSearchEngine.kt` + `llm/EmbedEngine.kt`): on-device EmbeddingGemma embeddings over apps/contacts/settings for meaning-based matches ("photo editor" finds apps that never say those words). The model is user-imported; everything runs locally.
- **Verticals**: sports live-score cards via ESPN (`SportsApi.kt`, e.g. "miami heat"), stocks (`StockApi.kt`), odds (`OddsApi.kt`) — the sports vertical is the template for adding new ones.
- **Instant answers** (`SmartCompute`, dictionary via `DictionaryApi.kt`) fill the single ANSWER hero zone.

## Widget Stack

The homescreen widget stack is contextual and live:

- Music now playing
- Calendar
- Recent people
- Email
- News
- Maps/driving context

The widget stack is a centered homescreen module. It is not a left rail, not a left column, and not a replacement for the removed Message Hub.

Music appears only while media is playing. Email and people widgets appear when there is content. Calendar remains available as a persistent context widget.

Widget teclas should perform the real action:

- Music opens the in-launcher now playing screen.
- Calendar opens the calendar event or create-event flow on long press.
- Recent people opens the actual notification/conversation where possible.
- Email opens the actual notification/email where possible.

Each stack widget has a user-controlled visibility mode, persisted per widget
(`widget_stack_mode_<id>` in the `teclas` prefs):

- `auto` (default): the classic contextual behavior described above.
- `pin`: the widget sorts to the top of the stack whenever it has anything to
  show. Pinned music stays up while a media session merely exists (paused counts).
- `hide`: the widget never appears.

Modes are edited in place by long-pressing the pager-dot rail on the stack (or
tapping the hidden-stack placeholder when everything is hidden), or through
type-to-customize by typing `widgets` or a widget name. Card long-presses remain
content actions; they are not used for configuration.

## Theme System

Teclas has a first-class Theme Studio (`com.fran.teclas.theme`) that bundles the launcher’s existing visual layers into one named `LauncherTheme`: Daily Brief style/visibility, weather style/visibility, keyboard theme, icon style/pack, wallpaper, and the `go_key_color` accent. Applying a theme writes through the same `teclas` SharedPreferences that the launcher, IME, brief widget, weather widget, dock, and icon-pack code already read. Built-in themes are read-only templates; editing a layer forks the active theme into a user “Custom” theme stored in `user_themes_json`.

Curated defaults live in `DefaultThemes.kt`. To add another default theme, add a `LauncherTheme(builtIn = true)` there and make sure its `wallpaperId` resolves in `assets/wallpapers/` or gracefully falls back to the generated background.

Wallpaper packs are asset-driven. Drop `.jpg`, `.jpeg`, `.png`, or `.webp` files into `app/src/main/assets/wallpapers/`; the runtime `WallpaperRegistry` scans that folder, uses the filename stem as the stable id, and title-cases it for the Theme Studio picker. User-picked wallpapers still use Android’s document picker and persist URI permission.

Type-to-customize registers theme settings in `settingSearchEntries()`: `theme`, `themes`, `wallpaper`, `keyboard theme`, `icons`, `icon pack`, `accent`, `brief`, and `weather` surface actionable setting cards. These entries rank below app matches like the rest of launcher settings.

## Type-To-Customize

Launcher settings are exposed as universal-search results (`SearchKind.SETTING`).
Typing `glass`, `accent`, `icon pack`, `dark`, `widgets`, `haptics`, etc. surfaces
the matching setting as a result card; tapping it applies the change immediately
and re-renders the results in place with the new state. Search is the primary
settings surface — the settings pane still exists for browsing, but should never
be required for a common tweak.

Rules for this surface:

- Toggles flip live. Multi-value settings (accent color, launcher look, keyboard
  theme, icon size, icon pack) cycle to the next value per tap, showing the
  current value in the subtitle.
- Settings results rank below app matches and are capped at 4.
- Setting cards use the user's accent color (`goKeyColor`) and the `⚙` glyph.
- New user-facing settings should be registered in `settingSearchEntries()` in
  addition to any pane UI, so they stay searchable.

## Music

The launcher has an in-launcher Music experience:

- Reads active media sessions.
- Supports media controls via `MediaController`.
- Has multiple now-playing themes.
- Uses the same global playback state as the homescreen music widget.
- Spotify API integration exists in addition to Android media-session reading.

Music screen modes include art-focused and dark record/player-style presentations. Do not put old Spotify badges inside album art unless explicitly requested.

Last.fm integration (`LastFmAuth.kt` / `LastFmApi.kt`) adds scrobbling (on by default once connected, `scrobble_enabled` pref) on top of whatever player is active.

## On-Device AI

- **Gemini API** (`GeminiClient.kt`) powers AI suggestions, smart compose, brief summaries, and travel parsing — via the user's own API key or the account-proxy mode (`AccountAuth`/`GeminiProxy`, Cloudflare worker in `server/gemini-proxy/`).
- **Gemini Nano / AICore** (`NanoPromptEngine.kt`, ML Kit GenAI proofreading) runs free and offline on supported devices; the keyboard's Proofread mode uses it and hides itself elsewhere.
- **Local models** (`llm/LocalLlmEngine.kt`, `llm/EmbedEngine.kt`): user-importable on-device LLM/embedding models backing semantic search, with no network dependency.
- **Neural swipe typing** (`keyboard/neural/`): an original ONNX seq2seq glide decoder (trained on an MIT-licensed corpus — see `NeuralSwipeContract.kt`; no GPL code) blended with a statistical classifier.
- **Agentic skills** (`AgenticEngine`/`AgenticRouter`/`AgenticPlanner`, managed in `AgenticSkillsActivity`): typed commands routed to built-in and user-defined skills (play music, navigate, timers, translate, custom `{q}` URL skills).

## Grid Workspace Lab

`grid/` is an isolated AOSP-style grid workspace test bench (drag-to-arrange, folders, its own widget host) behind a settings toggle that enables a separate HOME alias (`GridHomeAlias`). It is a lab, not the product homescreen — keep it isolated.

## ZEISS Optics / Photos

Teclas includes a launcher photo experience:

- Opens from the Vivo-only ZEISS button area.
- Shows recent local photos.
- Supports albums, favorites, delete-to-trash, and full-screen viewing.
- Uses manufacturer branding logic: Vivo shows ZEISS; other brands should fall back to the phone maker.

The ZEISS button appears only on Vivo devices.

## Native Integrations

Current Android integrations include:

- Contacts
- SMS seeding/prediction
- Calendar events
- Weather/location
- Notification Listener for messages, media sessions, emails, maps/news contexts
- Media sessions and playback controls
- Photos/media store
- Installed app discovery
- Icon packs
- Gemini API configuration (key or account-proxy mode)
- Spotify OAuth/API
- Last.fm (scrobbling)
- Brave Search, ESPN sports, stocks, odds, dictionary APIs
- Notion API (agentic skills)

Permissions are requested as needed. Notification Listener access is required for the richest widget/message/media behavior.

## Build

Build debug APK:

```sh
env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug
```

Build the minified release APK (R8 + resource shrinking, arm64-only, signed with the same shared debug key so it installs over debug builds):

```sh
env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleRelease
```

Install on a connected test device (Vivo, Honor, etc.). Don't hard-code a
device serial — wireless-adb transport names change every time the phone
sleeps, switches Wi-Fi, or reboots, so a pinned `-s adb-…_tcp` serial goes
stale and fails with "device not found". Look up the current device instead:

```sh
ADB=/Users/fran/Library/Android/sdk/platform-tools/adb

# See what's connected (empty? plug in USB + accept the debugging prompt, or
# reconnect wireless: Developer options → Wireless debugging → adb connect <ip>:<port>)
"$ADB" devices -l

# One device connected: no serial needed
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk

# Multiple devices: copy the serial from the left column of `adb devices` above
"$ADB" -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

After a package rename (e.g. an old `com.fran.clicks` build still installed),
`install -r` won't upgrade across a different applicationId — uninstall the old
package first (`"$ADB" uninstall com.fran.clicks`) or you'll get two launchers.
On the first call/dial, allow the CALL_PHONE prompt; MagicOS/OriginOS may also
need "Install via USB" enabled in Developer options.

## Code Map

`MainActivity.kt` is still the launcher core (~24k lines) but is being decomposed into same-package hosts — follow the established `with(activity) { }` verbatim-move pattern when extracting more:

- `TodayPaneHost`, `MusicPaneHost`, `TravelPaneHost` — pane hosting split out of MainActivity
- `PaneModels`, `LauncherModels`, `ThemeDrawables` — types and pure drawing/geometry helpers
- `TeclasImeService` — the system IME (shares the keyboard engine); `DockedKeyboardService` — overlay keyboard
- Packages: `brief/` Today page · `predict/` Spaces · `semantic/` + `llm/` on-device search/AI · `theme/` Theme Studio · `weather/` weather styles/widget · `keyboard/` typing engine (+`neural/` glide) · `grid/` AOSP lab · `fold/` foldable posture · `db/` Room · `brand/` brand tokens · `glide/` statistical glide

## Design Rules For Future Agents

- Do not revert the launcher to a simple icon list.
- Do not resurrect the hard-coded Mara/Dev grp/Boss Message Hub.
- Do not create a left-side Message Hub.
- Do not create a left-side widget stack.
- Do not make the right-side app ribbon the primary homescreen model.
- Keep the current homescreen: top weather, centered contextual widget stack, bottom favorites dock, and keyboard placement modes.
- Preserve the difference between Docked mode and Widget mode.
- Keep GoKeys keyboard changes scoped to the `gokeys` theme.
- Installed apps, notifications, messages, email, calendar, media, and photos should be data-driven, not hard-coded.

## Legacy Names In Code

Some internal function names may still contain older words such as `hub`, `ribbon`, or `nowPlayingCard` because the project evolved quickly. Treat those as implementation history, not product direction. The current product direction is the centered homescreen described above.
