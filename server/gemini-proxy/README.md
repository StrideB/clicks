# Teclas AI proxy (Cloudflare Worker)

Lets the app use Gemini **without any user ever seeing or pasting an API key**. Your one Gemini key
lives here as a server secret. The app signs the user in with Google and sends that Google **ID
token** on each request; this Worker verifies it, meters per-user usage (free daily quota, higher for
Pro), and forwards the prompt to Gemini.

```
App ──(Google ID token)──► this Worker ──(GEMINI_API_KEY secret)──► Gemini
```

## One-time setup

You need a (free) Cloudflare account and Node installed.

1. **Install Wrangler and log in**
   ```bash
   cd server/gemini-proxy
   npm install
   npx wrangler login
   ```

2. **Create the KV namespace** (holds per-user daily counters) and paste the id into `wrangler.toml`:
   ```bash
   npx wrangler kv namespace create QUOTA
   # → copy the "id" it prints into the [[kv_namespaces]] block in wrangler.toml
   ```

3. **Set the secrets** (never committed):
   ```bash
   npx wrangler secret put GEMINI_API_KEY     # your key from https://aistudio.google.com/apikey
   npx wrangler secret put GOOGLE_CLIENT_ID    # the OAuth Web client ID the app signs in with (see below)
   ```

4. **Deploy**
   ```bash
   npx wrangler deploy
   ```
   Wrangler prints your Worker URL, e.g. `https://teclas-gemini.<your-subdomain>.workers.dev`.
   Put that URL in the app as `GeminiProxy.DEFAULT_PROXY_URL` (in `AccountAuth.kt`) — it is **not** a
   secret and is safe to ship in the app.

## The Google OAuth client

The app uses Google Sign-In (PKCE) to obtain the user's ID token. In
[Google Cloud Console](https://console.cloud.google.com) → APIs & Services → Credentials:

- Create an **OAuth client ID**. The **audience** (`aud`) of the ID token must equal the
  `GOOGLE_CLIENT_ID` secret above, so use the **same client ID** on both sides.
- Put that client ID in the app (`ACCOUNT_CLIENT_ID` in `AccountAuth.kt`) and set the matching
  reversed-scheme redirect in `AndroidManifest.xml`.

## Endpoint

`POST /v1/generate`
Header: `Authorization: Bearer <google_id_token>`
Body: `{ "prompt": "...", "model": "gemini-2.5-flash", "maxTokens": 256, "temperature": 0.5 }`
Reply: `{ "text": "...", "used": 3, "cap": 30, "pro": false }` (or `429` `{"error":"quota_exceeded"}`)

## Marking a user Pro

Pro accounts get the higher daily cap. Until Play Billing verification is wired server-side, mark a
user Pro by their Google `sub` (subject id):
```bash
npx wrangler kv key put --binding QUOTA "pro:<google_sub>" "1"
```
(Productionization: verify the Play purchase token from the app against the Play Developer API in the
Worker, then set `pro:<sub>` automatically. Left as a follow-up.)

## Tuning

`FREE_DAILY`, `PRO_DAILY`, and `ALLOWED_MODELS` are constants at the top of `src/index.js`.
