# Clicks — Google Play Store Listing Kit

Everything below is paste-ready copy for the Play Console, plus the asset plan
for screenshots. Package: `com.fran.teclas` · brand name: **Clicks**.

> Note: the app's internal `app_name` resource still reads "Teclas" and the
> package is `com.fran.teclas`. The public-facing product name is **Clicks**
> (see the launcher's own copy: notifications sign as "Clicks", the search bar
> reads "SEARCH · CLICKS FOR SETTINGS"). Update `strings.xml` `app_name` to
> "Clicks" before the store upload so the installed launcher label matches the
> listing.

---

## 1. App name / title  (max 30 chars)

**Primary**
```
Clicks: Type-First Launcher
```
(27 characters)

**Alternates**
```
Clicks — Keyboard Launcher      (26)
Clicks: The Typing Launcher     (27)
```

## 2. Short description  (max 80 chars)

```
Type to search, launch & do anything. A fast, private, keyboard-first launcher.
```
(79 characters)

**Alternates**
```
The launcher you type to. Search apps, contacts & the web from one keyboard.
A private, keyboard-first home screen that learns where you are and what you need.
```

## 3. Full description  (max 4000 chars)

```
Clicks is a home screen built around one idea: you already know what you want, so just type it.

No more hunting through pages of icons. Open Clicks, start typing, and the right thing is already there — an app, a contact, a calendar event, a song, a website, a quick calculation, or a setting. One keyboard runs your whole phone.

━━━━━━━━━━━━━━━━━━━━
TYPE TO DO ANYTHING
━━━━━━━━━━━━━━━━━━━━
The keyboard is the launcher. Start typing and Clicks searches everything at once:
• Apps — by name, instantly, ranked by how you actually use them
• Contacts — call, message, or open a chat in one tap
• The web — type "theverge.com" and GO opens the site, not a search about it
• Math — "45*1.2" answers inline
• Markets — "aapl stock", "bitcoin price", "eur to usd"
• Settings — type "glass", "accent", "dark", "haptics" or "icon pack" to change your launcher live, no menus

━━━━━━━━━━━━━━━━━━━━
A HOME SCREEN THAT MAKES SENSE
━━━━━━━━━━━━━━━━━━━━
• Live weather header — temperature, feels-like, humidity and wind
• A centered, contextual widget stack — music now playing, calendar, recent people, email, news and driving context appear only when they matter
• A clean favorites dock for your five most-used apps
• A dark, premium, glass design — no icon clutter

━━━━━━━━━━━━━━━━━━━━
SPACES — YOUR PHONE, BY CONTEXT
━━━━━━━━━━━━━━━━━━━━
Clicks quietly rearranges itself around your day. Create Spaces like Work, Driving, Commute or Home and set what triggers them — time of day, day of week, a place, driving speed, car Bluetooth, headphones, or a calendar meeting. When a Space is active, your apps re-rank to fit the moment. It even learns your places on its own ("Was that an airport?") — and all of it stays on your phone.

━━━━━━━━━━━━━━━━━━━━
A KEYBOARD WORTH TYPING ON
━━━━━━━━━━━━━━━━━━━━
• On-device prediction and autocorrect that learns your style
• Glide/swipe typing powered by an original, on-device neural model
• Multiple themes — default, Teclas, GoKeys and the premium Skeuo keycaps
• Haptics, long-press symbols, flick input and space-cursor control
• Dock it below your home screen or float it as a home-screen widget
• Six languages: English, Spanish, Portuguese, Italian, French, German

━━━━━━━━━━━━━━━━━━━━
MUSIC & PHOTOS, BUILT IN
━━━━━━━━━━━━━━━━━━━━
• Control whatever's playing from the home screen, with several now-playing looks
• Browse recent photos, albums and favorites without leaving the launcher

━━━━━━━━━━━━━━━━━━━━
PRIVATE BY DESIGN
━━━━━━━━━━━━━━━━━━━━
Clicks is built to keep your data on your device. Your typing model, your app habits and your learned places never leave your phone — prediction weights are even encrypted at rest. The only data that leaves is to services you choose to connect yourself (like weather, Spotify or Gemini).

━━━━━━━━━━━━━━━━━━━━
FREE — AND YOURS TO KEEP
━━━━━━━━━━━━━━━━━━━━
The full launcher is free: type-to-search, type-to-do, Spaces, the widget stack, the app library, weather, music controls, photos, on-device prediction and glide typing, and three keyboard themes.

━━━━━━━━━━━━━━━━━━━━
CLICKS PRO — ONE-TIME UNLOCK
━━━━━━━━━━━━━━━━━━━━
Pay once, own it forever. No subscription. Pro adds:
• Gemini AI keyboard — full-context suggestions and smart compose
• AI chat, right from your home screen
• Full Spotify library and search, with the deluxe now-playing click-wheel
• Flights & boarding passes surfaced automatically when you travel
• The premium Skeuo keyboard theme
• Every future Pro feature, included

Download Clicks, start typing, and run your phone at the speed of thought.
```

## 4. Keywords / ASO terms

launcher, keyboard launcher, type to search, home screen, minimal launcher,
productivity launcher, app drawer, universal search, custom keyboard, swipe
typing, glide typing, contextual launcher, privacy launcher, dark launcher,
android launcher, quick search, app launcher, keyboard

## 5. Store settings

| Field | Value |
| --- | --- |
| Category | Personalization |
| Tags | Launcher, Keyboard, Productivity |
| Content rating | Everyone |
| Pricing | Free, with in-app purchase (Clicks Pro, one-time) |
| In-app product | `com.fran.teclas.pro` — replace the placeholder ID with the real Play Console product before launch |
| Contact email | admin@cueaba.com |
| Privacy policy | Required (contacts, calendar, location, notification access) — host a URL before upload |

## 6. Data safety summary (for the form)

- Almost everything stays on-device: typing model, app-usage habits, and learned
  places never leave the phone (prediction weights are encrypted at rest).
- Data leaves the device only to user-connected services: weather/location,
  Spotify, Gemini, and (for boarding-pass parsing) Gmail.
- No ads, no third-party analytics selling.

## 7. Pre-launch blockers (from docs/release-readiness.md)

Address before any track, including closed beta:
1. Remove `READ_SMS` (Play restricts it to default-SMS apps) or gate it behind a
   non-Play flavor.
2. Create a real upload key + enroll in Play App Signing (never ship the debug key).
3. Gmail restricted scopes need Google app verification (stay ≤100 users in
   testing mode for closed beta) — used only for flight/boarding-pass parsing.
4. Declare the AccessibilityService (`InputInjectionService`) with a
   core-functionality justification and in-app prominent disclosure, or ship the
   beta with it flagged off.
5. Create the real `Clicks Pro` in-app product; the current ID is a placeholder.
6. Set proper versionCode / versionName per upload (currently 1 / 0.1.0).

## 8. Screenshot kit

Play requires 2–8 phone screenshots, PNG/JPEG, 16:9 or 9:16, min side ≥ 320px,
max ≥ 3840px. The originals in `/screenshots` are the right resolution for upload.
Recommended set and order:

| # | File | Shows |
| --- | --- | --- |
| 1 | `teclas-dock-context-work.png` | Home: weather, Work space, dock, docked keyboard (hero) |
| 2 | `vivo-brushed-final-check.png` | Home (dark): calendar widget stack + Skeuo keyboard |
| 3 | `teclas-space-board-work.png` | App library / drawer with categories |
| 4 | `spaces-editor-driving.png` | Spaces editor — triggers (Driving) |
| 5 | `spaces-notification.png` | Auto-learned places ("Was that an airport?") |
| 6 | `teclas-dock-pinned.png` | Music now-playing pinned + widget |
| 7 | `teclas-preferred-categories.png` | Spaces preferred categories / pinned apps |

Add short caption overlays in your image editor before upload (Play does not add
captions). Suggested captions live in the presentation deck.
