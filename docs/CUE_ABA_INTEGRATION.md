# Cue ABA record search (`cue-aba` branch)

Puts an ABA clinic's records behind the launcher's own search bar. Type a record
type to get a ranked set; add a name to open one record.

**This branch is the variant.** There are no product flavors and no feature
flags — on `cue-aba` the integration is simply part of the launcher, and the
ordinary commands build it:

```sh
./gradlew installDebug      # sideload
./gradlew assembleRelease   # APK to hand to someone
```

`main` and the Play Store branch do not carry `app/src/main/java/com/fran/teclas/cue/`
at all, which is what keeps ABA code out of a consumer build. Merging this
branch into either of those would ship it — don't.

Server side lives in the `cue` repo: `src/lib/native/search.ts`,
`src/lib/native/search-grammar.ts`, `src/lib/native/org-scope.ts`, and a
`search` case in `src/app/api/native/v1/[resource]/route.ts`.

## Giving a build to someone else

Nothing to configure. `app/cue.properties` is committed, so a clone builds and
an installed APK needs only the person's own Cue sign-in.

They get **their own** scope, not yours: the launcher authenticates as a person,
and Cue derives organization, role and caseload from that person's staff row on
every request. Hand the same APK to an RBT and they see their assigned kiddos
and nothing else — no authorizations, no claims, no staff directory.

The committed Supabase anon key is a public, client-facing credential enforced
by Row-Level Security; `cue/android/cue.properties` commits the same value for
the same reason. Never put a `cue_live_…` API key there — Cue resolves an org
API key to admin-equivalent, org-wide access for whoever holds the APK, which
would defeat exactly the per-person scoping that makes this safe to hand out.

**One prerequisite, once, for everyone:** `/api/native/v1/search` must be live
at `cue.api.base.url`. It lives on the cue repo's `cue-aba` branch — same name,
both repos — and is not on `main` yet. Until it is deployed, the launcher shows
"Cue unavailable — Not found", which is the diagnostic for "the API half isn't
up".

## How it knows which clinic

You never type a clinic, and the app never invents one.

1. You sign in with Supabase email + password (`CueSignInActivity`).
2. Persisted: a refresh token, the cached identity, and — only if you work at
   more than one clinic — which one you chose. All in
   `EncryptedSharedPreferences`.
3. Every request carries that session's JWT. Cue's `loadContext()` trades your
   user id for a `staff` row and reads `organization_id` off it.
4. Every query is bounded by that org id, plus `canSeePatient()` /
   `canSeeStaff()` / `canSeeSession()`.

Scope is a server fact. The client can *narrow* to another of your own
memberships (below), but it cannot introduce one, so it can never widen what you
see. Revoke the Supabase session in Cue and this launcher loses access on its
next refresh — there is no second credential to hunt down.

### Working at more than one clinic

`loadContext()` used to select a single staff row (`.order("updated_at").limit(1)`),
so someone employed at two clinics silently got whichever was touched most
recently. Survivable on web, where the org is named in the chrome; not on a
homescreen, where you could search and see the wrong clinic's kiddos with no
signal at all.

It now reads every active membership and applies `selectMembership()`
(`cue/src/lib/native/org-scope.ts`, unit-tested):

- an explicit choice is honored **only** if it names one of the caller's own
  memberships — anything else is rejected outright, never silently defaulted, so
  a stale selection fails loudly instead of quietly returning another clinic's
  records;
- with no choice, the first membership is used and flagged
  `organization_ambiguous`.

The launcher sends its choice as `X-Cue-Organization` and stores it alongside
the session. `/me` returns every organization by name, and the sign-in screen
shows a picker whenever there is more than one — captioned "the default is a
guess" until you resolve it. The selection can only ever narrow to something you
already hold, so a forged header cannot widen scope.

## Where taps land

Every card action carries both a `cue://` deeplink and an `href` to the same
record on the web, because a client cannot know whether the Cue app is
installed. The launcher tries them in that order and only falls back to the Cue
home page if both are missing — landing someone on a dashboard root when they
tapped a named kiddo is a failure, not a fallback.

`cue://` was previously registered nowhere: Cue's Android app declared
`com.cueaba.app` and a `wear` scheme, while the iOS widgets were already
emitting `cue://` links. Android now registers it and folds it into the existing
notification-route path, so `cue://kiddo/<id>` opens the Kiddos screen through
the same `routeForNotification()` matcher push notifications use.

**Follow-up on Cue's side:** that matcher resolves to a *section*, not a record.
The launcher already sends the id in the URI, so routing to the exact kiddo is
purely a change inside the Cue app.

## Writes — the EVV clock

Session cards offer **Clock in** / **Clock out**, and nothing else writes.

Offered only when all of these hold, checked server-side in `sessionActions()`
and enforced again in the POST handler:

- you are the assigned technician on that session
- the session is today
- the EVV state allows that direction
- the session is not cancelled

Every write action carries a `writes` descriptor, and the launcher refuses to
fire one on a single tap: `CueBridge.confirmAndRun()` shows a dialog naming the
consequence ("This starts EVV for the visit and begins a billable record")
before anything is sent. A homescreen is exactly where a mis-tap happens.

A write action never doubles as navigation — it has no `href` or `deeplink` — so
there is no path where a tap meant to open a record files a billable one.

## Today brief

Cue's clinical items are ranked *alongside* your notifications and calendar
rather than living in a separate list, because a clinician's day is one list.

`CueSignal` is a `Signal` subtype (it has to live in the `brief` package — the
interface is sealed), fed by `BriefCollector`'s `cueProvider`. An overdue note
outranks everything; an item with no deadline sits just under calendar.

`briefSignals()` never blocks: it returns the last fetch and refreshes in the
background on a 15-minute TTL, because `collect()` runs on the main thread while
the brief is assembled.

It reuses `BriefCategory.CALENDAR` rather than adding an enum value — that enum
is matched exhaustively in `MainActivity`, `SpaceTodayScreen` and `PreScorer`,
and a clinical item is a time-bound commitment anyway.

## Push — deliberately not in the launcher

Cue's own Android app already ships `CueFirebaseMessagingService`. Registering a
second FCM device from the launcher would deliver every alert twice to the same
phone. The launcher's contribution is the brief above: it turns those same
events into ranked homescreen items you see without opening anything.

If the Cue app is not installed, push is genuinely absent — that is the tradeoff,
and the brief's 15-minute refresh is the substitute.

## Query grammar

Three layers, in the order people type:

| You type | You get |
| --- | --- |
| `mara` | Free text across every type your role may see |
| `auths` | Every authorization, **soonest expiry first** |
| `auth mara` | One authorization, expanded |
| `expiring auths` | Modifier + noun, in either order |
| `stride` | The company card — NPI, tax ID, phone, fax, address, logo |

Each type owns its default sort (`NATIVE_TYPES[type].sortedBy` on the server):
kiddos by authorization pressure, staff by credential expiry, claims denied
first, sessions today first.

**Sort order lives on the server.** The response carries `sortedBy` as display
text and the launcher just prints it, so "soonest expiry" can change meaning
without an APK. Nouns sync from `/api/native/v1/search-vocabulary`;
`CueVocabulary.BUILT_IN` is only a cold-start seed.

## Typing latency

The launcher searches on every keystroke, so the integration never blocks the
main thread:

- `CueVocabulary` resolves type nouns locally, from ordinary prefs. No PHI, no
  network, no Keystore.
- `CueIndex` holds record names locally, so free text that matches nothing in
  this clinic never costs a request. **It is PHI** — patient names — so it lives
  in the same Keystore-backed store as the session, and is cleared on sign-out
  and on clinic switch. Names only: no diagnosis, no payer, no dates, so a
  leaked index reveals membership rather than condition. A cold or empty index
  means "unknown", never "no match", so a miss still asks the server.
- `CueBridge.signedIn` is a `@Volatile` snapshot computed once on a worker.
  Resolving it for real opens `EncryptedSharedPreferences`, which unlocks a
  Keystore key — tens of milliseconds that the typing path cannot pay.
- A query naming a type noun fetches immediately; free text waits out a 280 ms
  debounce and needs 3+ characters.
- A refinement of the same query keeps the old cards on screen while the sharper
  ones load. Diverging queries drop them at once, so a stale card never sits
  under a new query. Same rule as the Brave cards.
- Request ids are monotonic — a slow response for an older query cannot
  overwrite a newer one.

## Routing

`SearchRouter.route(raw, hasDirectHits, cueClaimed)`. When Cue claims a query it
returns `Route.NONE` ("cue record") **before every other signal**, including
`COMPOSE`.

That ordering is the point: several record nouns collide with generic web
vocabulary — `claims`, `reports`, `schedule`, `billing` all carry a WEB signal on
their own. A patient-adjacent query reaching Google or an on-device model is the
failure this prevents. `NONE` rather than a new enum case, because the launcher's
own results already *are* the answer here, which is what `NONE` means — and no
caller has to learn a new branch. Covered in `SearchRouterTest`.

## PHI on a homescreen

A launcher search overlay is the most screenshot-, shoulder-surf- and
recents-exposed surface on the phone.

- **Masking is on by default.** The safe default is the one that shows nothing
  until you ask. Masking preserves word shape, so a masked card still reads as a
  card rather than a redaction.
- **Tap to reveal.** A masked card spends its first tap lifting the mask instead
  of navigating — opening a record you cannot read yet is the wrong default, and
  it would make the mask pointless. A reveal lasts two minutes.
- **Screen-off re-arms it.** A receiver on `ACTION_SCREEN_OFF` clears the reveal
  and drops cached records, so putting the phone in your pocket always closes
  the mask. Registered on the application context — the launcher has no other
  hook that reliably covers "the phone is away now".
- **`FLAG_SECURE` while records are on screen.** Applied by `CueBridge.views()`
  whenever the rendered set contains PHI, cleared as soon as it doesn't, so the
  rest of the launcher stays screenshot-able. `CueSignInActivity` sets it
  unconditionally.
- **No plaintext fallback in `CueSession`** — unlike `PredictCrypto`, which
  degrades to plain prefs. Losing prediction weights beats storing them in the
  clear; the reverse is true for a refresh token, which is a standing key to a
  clinic's PHI. Without the Keystore the session lives in memory for that
  process only.
- **Audited, without logging the query.** Every search writes through Cue's
  `writeAuditLog()`, recording the shape of the request only — the raw query can
  name a kiddo.
- **Read-only card actions.** Every action is a `cue://` deeplink or a
  `tel:`/`geo:` intent. Nothing in the launcher writes to a clinical record, so
  no homescreen tap can create a billable one.

`FLAG_SECURE` clears on the next search render rather than the moment the
surface closes, so it can briefly outlive the cards. That errs toward secure.

## Reaching the connection

Two ways in, because "type the magic word" is not discoverable for someone who
just installed the APK:

- **Settings search** — `cue`, `clinic`, `connect`, `sign in`, `account` and
  friends all surface a **Cue ABA** row that reports its own state ("Connected ·
  Bright Path ABA Clinic" / "Not connected"). Registered in
  `settingSearchEntries()`.
- **Typing `cue`** — signed out gives a Connect card; signed in gives an account
  card with role, clinic, masking toggle, Switch clinic and Sign out.

### The sign-in screen

`CueSignInActivity`, plain Android views, on the launcher's own `Neu` tokens and
the user's `go_key_color` accent. Two rules it learned by rendering as an empty
white rectangle:

- **Every child gets an explicit height.** A bare `View` with `WRAP_CONTENT`
  does not collapse to its minimum — `View.getDefaultSize()` returns the full
  `AT_MOST` size — so a spacer added that way consumes the whole viewport and
  pushes the form off the bottom. `gap()` always sets a real height.
- **The root sits on a software layer.** `NeuDrawable`'s shadows use a
  `BlurMaskFilter`, which a hardware-accelerated canvas silently ignores,
  flattening every surface. `MainActivity` does the same for the daily brief.

It is wrapped in a `ScrollView` with `adjustResize`, so the keyboard never
covers the password field on a short display.

## Files

```
app/src/main/java/com/fran/teclas/cue/
  CueBridge.kt         entry point, cache, debounce, session snapshot
  CueSession.kt        Supabase auth, encrypted refresh token
  CueApi.kt            /api/native/v1/* client
  CueVocabulary.kt     type nouns, synced from the server
  CueModels.kt         card model + per-type spine colors
  CueCardViews.kt      neumorphic renderers
  CueSignInActivity.kt sign-in and resolved identity
```

This whole directory is absent on `main` and the Play Store branch.

Shared-code touchpoints are deliberately tiny: `cueSearchViews()` in
`SearchResultsHost` (two call sites), and one argument added to
`SearchRouter.route()`.

## Verifying without a device

`SearchRouterTest` covers the routing change and runs under plain JUnit.

The Cue package has no instrumented tests yet, because a launcher build needs
the Android SDK. What exists is a typecheck harness that resolves every
reference against the real Android framework (Robolectric's `android-all`) plus
stubs whose signatures are copied verbatim from the Teclas sources — so anything
compiling against the stubs compiles against the real thing. It caught a
recursive `put` shadowing `MutableMap.put`, reversed `EncryptedSharedPreferences`
key/value schemes, and a `signOut` where `clearCache` was meant.

It is not a substitute for `./gradlew installDebug`. Run that before trusting
a build.
