# Clicks

Clicks is a native Android launcher built around typing as the primary action. It is not the old icon-list scaffold, not the old Message Hub prototype, and not the left-column/right-ribbon communicator mockup. The current launcher is a centered premium homescreen with contextual widgets, a favorites dock, a custom on-screen keyboard, type-to-do search, app library, media controls, photos, and native Android integrations.

Package: `com.fran.clicks`  
Platform: Android app, Kotlin, classic Android views plus Jetpack Compose surfaces  
SDK: `minSdk 31`, `targetSdk 35`, `compileSdk 35`

## Current Product Shape

The launcher has two keyboard placement modes:

- **Docked mode**: the current Clicks command-console layout. The typing strip and keyboard are fixed below the homescreen content. Typing can open the full app/library search experience because the keyboard owns the bottom of the phone.
- **Widget mode**: the same keyboard/input engine is mounted as a homescreen widget. Typing stays on the homescreen and does not auto-open the full app library. The `clicks` key becomes `DOCK`, which switches back to Docked mode.

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

The homescreen visual language is dark, premium, and glass-like:

- Raised glass cards use `#20232A -> #14161B` style gradients.
- Nested widget rows use recessed dark trays.
- Widget cards use colored accent spines.
- Avatars, badges, and dock icons are domed/radial surfaces.

Important: do not redesign this as the original hard-coded Message Hub/app ribbon prototype or as a plain app icon list.

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

Keyboard themes:

- `default`
- `clicks`
- `skeuo`
- `gokeys`

The GoKeys reference keyboard geometry and visual treatment is scoped to the `gokeys` theme only. Do not apply GoKeys key geometry or labels globally.

Widget mode special behavior:

- The visible `clicks` key label changes to `DOCK`.
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

Widget clicks should perform the real action:

- Music opens the in-launcher now playing screen.
- Calendar opens the calendar event or create-event flow on long press.
- Recent people opens the actual notification/conversation where possible.
- Email opens the actual notification/email where possible.

Each stack widget has a user-controlled visibility mode, persisted per widget
(`widget_stack_mode_<id>` in the `clicks` prefs):

- `auto` (default): the classic contextual behavior described above.
- `pin`: the widget sorts to the top of the stack whenever it has anything to
  show. Pinned music stays up while a media session merely exists (paused counts).
- `hide`: the widget never appears.

Modes are edited in place by long-pressing the pager-dot rail on the stack (or
tapping the hidden-stack placeholder when everything is hidden), or through
type-to-customize by typing `widgets` or a widget name. Card long-presses remain
content actions; they are not used for configuration.

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

## ZEISS Optics / Photos

Clicks includes a launcher photo experience:

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
- Gemini API configuration
- Spotify OAuth/API

Permissions are requested as needed. Notification Listener access is required for the richest widget/message/media behavior.

## Build

Build debug APK:

```sh
env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug
```

Install on the current Vivo test device:

```sh
/Users/fran/Library/Android/sdk/platform-tools/adb -s adb-10AG4A2FFS0029L-ZHx6Q9._adb-tls-connect._tcp install -r app/build/outputs/apk/debug/app-debug.apk
```

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
