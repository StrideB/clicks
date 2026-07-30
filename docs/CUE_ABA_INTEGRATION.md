# Cue ABA record search (`cueAba` flavor)

Private integration that puts an ABA clinic's records behind the launcher's own
search bar. Type a record type to get a ranked set; add a name to open one
record. Nothing here ships in a normal Teclas build.

Server side lives in the `cue` repo: `src/lib/native/search.ts`,
`src/lib/native/search-grammar.ts`, and a `search` case in
`src/app/api/native/v1/[resource]/route.ts`.

## Build flavors

`distribution` dimension, two flavors:

| Flavor | What it contains |
| --- | --- |
| `consumer` | The launcher as it has always been. No Cue code, endpoints, or strings. |
| `cueAba` | Adds `app/src/cueAba/` — the whole integration. |

```
./gradlew installCueAbaDebug     # your build
./gradlew installConsumerDebug   # the clean one
```

The split is a **source set, not a runtime flag**. `CueBridge.ENABLED` is a
compile-time `const`, so every call site in shared code folds away in `consumer`
and R8 has nothing to strip — nothing was compiled in to begin with.

Both flavors share `applicationId` deliberately. A suffix would install a second
launcher with none of your prefs, themes, or trained prediction data. Add
`applicationIdSuffix = ".aba"` to the `cueAba` block if you ever want them side
by side.

### Configuration

Copy `app/cue.properties.example` to `app/cue.properties` and fill it in. That
file is gitignored — the endpoint stays off the repo. Building without it works;
the integration just reports itself unconfigured and stays out of search.

Never put a `cue_live_…` API key there. Cue resolves an org API key to
admin-equivalent, org-wide scope, which would hand every kiddo in the
organization to whoever holds the APK. The launcher signs in as a person.

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

## Reaching your account

There is no settings entry to hunt for. Type `cue`:

- signed out — a **Connect to Cue** card, one tap to sign-in;
- signed in — an account card with your name, role, clinic, masking toggle,
  **Switch clinic** (when you have more than one), and **Sign out**.

## Files

```
app/src/cueAba/java/com/fran/teclas/cue/
  CueBridge.kt         entry point, cache, debounce, session snapshot
  CueSession.kt        Supabase auth, encrypted refresh token
  CueApi.kt            /api/native/v1/* client
  CueVocabulary.kt     type nouns, synced from the server
  CueModels.kt         card model + per-type spine colors
  CueCardViews.kt      neumorphic renderers
  CueSignInActivity.kt sign-in and resolved identity
app/src/consumer/java/com/fran/teclas/cue/CueBridge.kt   no-op twin
```

Shared-code touchpoints are deliberately tiny: `cueSearchViews()` in
`SearchResultsHost` (two call sites), and one argument added to
`SearchRouter.route()`.

## Verifying without a device

`SearchRouterTest` covers the routing change and runs under plain JUnit.

The rest of the flavor has no test source set yet, because a launcher build
needs the Android SDK. What exists is a typecheck harness that resolves every
reference against the real Android framework (Robolectric's `android-all`) plus
stubs whose signatures are copied verbatim from the Teclas sources — so anything
compiling against the stubs compiles against the real thing. It caught a
recursive `put` shadowing `MutableMap.put`, reversed `EncryptedSharedPreferences`
key/value schemes, and a `signOut` where `clearCache` was meant.

It is not a substitute for `./gradlew installCueAbaDebug`. Run that before
trusting a build.
