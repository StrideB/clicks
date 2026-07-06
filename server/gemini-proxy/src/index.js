/**
 * Clicks AI proxy — a Cloudflare Worker that lets the app use Gemini WITHOUT any user ever seeing or
 * pasting an API key. The one Gemini key lives here as a server secret; the app authenticates each
 * request with the signed-in user's Google ID token, and this Worker verifies it, meters per-user
 * usage (free daily quota, higher for Pro), and forwards the prompt to Gemini.
 *
 *   App ──(Google ID token)──► this Worker ──(GEMINI_API_KEY secret)──► Gemini
 *
 * Deploy: see README.md. Requires two secrets (GEMINI_API_KEY, GOOGLE_CLIENT_ID) and a KV namespace
 * bound as QUOTA.
 */

const FREE_DAILY = 30;   // AI calls/day for a free account
const PRO_DAILY = 500;   // AI calls/day for a Pro account (marked in KV as pro:<sub> = "1")
const ALLOWED_MODELS = new Set([
  "gemini-2.5-flash",
  "gemini-2.5-flash-lite",
  "gemini-2.0-flash",
  "gemini-1.5-flash",
]);
const DEFAULT_MODEL = "gemini-2.5-flash";

export default {
  async fetch(request, env) {
    if (request.method !== "POST") return json({ error: "POST only" }, 405);
    const url = new URL(request.url);
    if (url.pathname !== "/v1/generate") return json({ error: "not found" }, 404);

    // 1) Identity: verify the caller's Google ID token (audience must be OUR OAuth client).
    const authz = request.headers.get("Authorization") || "";
    const idToken = authz.startsWith("Bearer ") ? authz.slice(7).trim() : "";
    if (!idToken) return json({ error: "missing_token" }, 401);
    const claims = await verifyGoogleIdToken(idToken, env.GOOGLE_CLIENT_ID);
    if (!claims) return json({ error: "invalid_token" }, 401);
    const sub = claims.sub;

    // 2) Per-user metering (soft daily cap). Pro accounts get the higher cap.
    const pro = (await env.QUOTA.get("pro:" + sub)) === "1";
    const cap = pro ? PRO_DAILY : FREE_DAILY;
    const day = new Date().toISOString().slice(0, 10); // YYYY-MM-DD (UTC)
    const key = `q:${sub}:${day}`;
    const used = parseInt((await env.QUOTA.get(key)) || "0", 10) || 0;
    if (used >= cap) return json({ error: "quota_exceeded", used, cap, pro }, 429);

    // 3) Forward to Gemini with the server-held key.
    let body;
    try { body = await request.json(); } catch { return json({ error: "bad_json" }, 400); }
    const prompt = String(body.prompt || "");
    if (!prompt) return json({ error: "empty_prompt" }, 400);
    const model = ALLOWED_MODELS.has(body.model) ? body.model : DEFAULT_MODEL;

    const geminiBody = {
      contents: [{ parts: [{ text: prompt }] }],
      generationConfig: {
        temperature: clampNum(body.temperature, 0, 2, 0.5),
        maxOutputTokens: clampInt(body.maxTokens, 1, 2048, 256),
      },
    };
    const resp = await fetch(
      `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent?key=${env.GEMINI_API_KEY}`,
      { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(geminiBody) }
    );
    if (!resp.ok) return json({ error: "upstream_error", status: resp.status }, 502);
    const data = await resp.json();
    const text = data?.candidates?.[0]?.content?.parts?.[0]?.text?.trim?.() || "";

    // Best-effort increment (soft cap; not strictly atomic, which is fine here). TTL ~2 days.
    await env.QUOTA.put(key, String(used + 1), { expirationTtl: 172800 });

    return json({ text, used: used + 1, cap, pro });
  },
};

/** Verify a Google-issued ID token via Google's tokeninfo endpoint and check it's for OUR client. */
async function verifyGoogleIdToken(idToken, clientId) {
  try {
    const r = await fetch(
      "https://oauth2.googleapis.com/tokeninfo?id_token=" + encodeURIComponent(idToken)
    );
    if (!r.ok) return null; // tokeninfo returns 400 for expired/invalid tokens
    const c = await r.json();
    if (!c.sub) return null;
    if (c.aud !== clientId) return null; // token was minted for a different app
    if (c.iss !== "accounts.google.com" && c.iss !== "https://accounts.google.com") return null;
    return c;
  } catch {
    return null;
  }
}

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function clampNum(v, lo, hi, dflt) {
  const n = Number(v);
  return Number.isFinite(n) ? Math.min(hi, Math.max(lo, n)) : dflt;
}
function clampInt(v, lo, hi, dflt) {
  const n = parseInt(v, 10);
  return Number.isFinite(n) ? Math.min(hi, Math.max(lo, n)) : dflt;
}
