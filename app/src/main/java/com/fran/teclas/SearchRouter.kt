package com.fran.teclas

import java.util.Locale

/**
 * Decides what a typed query actually wants: an answer the model can give, or a web search.
 *
 * The split is NOT "is it a question?". It's "does answering this require information the model
 * cannot have?" — because the model that answers here is on-device: it has a knowledge cutoff and
 * no internet. Ask it for a stock price, a score, or store hours and it will invent one, fluently.
 * So the rule is:
 *
 *   ANSWER  stable knowledge, reasoning, explanation, definitions, conversion, composition.
 *           "why is the sky blue", "difference between tcp and udp", "write a standup update"
 *   WEB     anything live, local, navigational, or commercial — the things that change or that
 *           live at a URL. "btc price", "who won", "pizza near me", "nike returns policy"
 *   NONE    too short / a bare keyword that the launcher's own results already answer.
 *
 * Ordering matters and is deliberate: freshness/local/navigational signals are checked BEFORE
 * question phrasing, because "what's the weather" is a question but is emphatically not something
 * to answer from memory. This runs on every keystroke, so it is pure string work — microseconds,
 * no model call. The model gets a second veto at answer time (see NEEDS_WEB in MainActivity):
 * if it decides mid-answer that it needs live data, the card flips itself to a web search.
 */
internal object SearchRouter {

    enum class Route {
        /** The model answers, inline, at the top of search. */
        ANSWER,
        /** Belongs on the web — live, local, navigational, or commercial. */
        WEB,
        /** Neither; the launcher's own results (apps, contacts, settings) are the answer. */
        NONE,
    }

    data class Verdict(val route: Route, val why: String)

    // ── Live data: true today, false next week. The model cannot know these. ──────────────
    private val FRESHNESS = listOf(
        "price", "prices", "cost of", "how much is", "how much does", "stock", "shares",
        "score", "scores", "who won", "standings", "fixtures", "schedule", "lineup",
        "news", "latest", "breaking", "today's", "tonight's", "this week's", "right now",
        "weather", "forecast", "temperature", "rain", "snow",
        "release date", "when does", "when is the", "coming out", "out yet",
        "exchange rate", "usd", "eur", "gbp", "btc", "eth", "crypto",
        "election", "results", "live", "update", "updated",
        "flight status", "delayed", "traffic",
    )

    // ── Local: needs the user's surroundings + an index of the physical world. ────────────
    private val LOCAL = listOf(
        "near me", "nearby", "near by", "closest", "nearest", "around here", "walking distance",
        "open now", "open late", "hours", "opening hours", "closing time", "directions",
        "menu", "reservation", "book a table", "delivery", "takeout",
    )

    // ── Navigational: the user wants to GO somewhere, not learn something. ────────────────
    private val NAVIGATIONAL = listOf(
        "http://", "https://", "www.", ".com", ".org", ".net", ".io", ".dev", ".co.uk", ".gov",
        "login", "log in", "sign in", "sign up", "download", "install", "app store",
        "official site", "website", "homepage", "dashboard", "my account",
    )

    // ── Commercial: shopping intent. Live inventory and pricing → web. ───────────────────
    private val COMMERCIAL = listOf(
        "buy", "cheapest", "deal", "deals", "discount", "coupon", "promo code", "on sale",
        "for sale", "shipping", "return policy", "refund", "in stock", "order",
        "amazon", "ebay", "etsy", "walmart", "target",
    )

    // ── Sites whose content is the point — you want the page, not a summary of it. ───────
    private val DESTINATIONS = listOf(
        "reddit", "youtube", "twitter", "instagram", "tiktok", "linkedin", "github",
        "wikipedia", "imdb", "yelp", "tripadvisor", "stack overflow", "stackoverflow",
    )

    // ── Composition: the model is the only thing that can do this. Always ANSWER. ────────
    private val COMPOSE = listOf(
        "write ", "draft ", "compose ", "rewrite ", "summarize", "summarise", "translate ",
        "give me ideas", "brainstorm", "help me write", "make a list", "outline ",
        "reply to", "respond to", "shorten", "expand on", "proofread",
    )

    // ── Explanation / stable knowledge. ──────────────────────────────────────────────────
    private val EXPLAIN = listOf(
        "what is", "what are", "what does", "what's a", "whats a",
        "why is", "why are", "why does", "why do", "why did",
        "how do i", "how to", "how does", "how can i", "how would",
        "explain", "meaning of", "definition of", "define ",
        "difference between", "compare", "pros and cons", "should i use",
        "example of", "examples of", "what happens if", "is it safe to", "can i ",
    )

    // ── Computation / conversion — deterministic, stable, model-answerable. ──────────────
    private val COMPUTE = listOf(
        "convert", "how many", "how long", "how far", "in celsius", "in fahrenheit",
        "to kg", "to lbs", "to miles", "to km", "percent of", "% of",
    )

    private fun hit(q: String, needles: List<String>) = needles.any { q.contains(it) }

    /**
     * Classify [raw]. [hasDirectHits] is true when the launcher already found real results (an app,
     * a contact, a setting) — a bare keyword that matched something local shouldn't be dragged to
     * the web or handed to a model.
     */
    fun route(raw: String, hasDirectHits: Boolean = false): Verdict {
        val q = raw.trim().lowercase(Locale.US)
        if (q.length < 2) return Verdict(Route.NONE, "too short")
        val words = q.split(' ', '\n').filter { it.isNotBlank() }

        // Composition can't be served by a search engine, and its phrasing ("write a note about
        // the game tonight") often contains freshness words that would otherwise route to web.
        // So it wins outright — checked first, before any WEB signal.
        if (hit(q, COMPOSE)) return Verdict(Route.ANSWER, "composition")

        // Live/local/navigational/commercial BEFORE question phrasing: "what's the btc price" is a
        // question the model must not answer from memory.
        if (hit(q, NAVIGATIONAL)) return Verdict(Route.WEB, "navigational")
        if (hit(q, FRESHNESS)) return Verdict(Route.WEB, "needs live data")
        if (hit(q, LOCAL)) return Verdict(Route.WEB, "local")
        if (hit(q, COMMERCIAL)) return Verdict(Route.WEB, "shopping")
        // A destination is a web signal when you're after something ON it ("reddit best knife
        // sharpener"). A BARE site name that the launcher already matched to an installed app is
        // the app — sending "instagram" to a web search when Instagram is on the phone is absurd.
        if (hit(q, DESTINATIONS) && !(hasDirectHits && words.size <= 2)) {
            return Verdict(Route.WEB, "destination site")
        }

        if (hit(q, COMPUTE)) return Verdict(Route.ANSWER, "computation")
        if (hit(q, EXPLAIN)) return Verdict(Route.ANSWER, "explanation")

        // A trailing "?" that survived every check above is a genuine knowledge question, but only
        // if there's enough of it to be one — "ok?" isn't.
        if (q.endsWith("?") && words.size >= 3) return Verdict(Route.ANSWER, "question")

        // Explicit invocation always answers.
        if (q.startsWith("ask ") || q.startsWith("ai ") || q.startsWith("gemini ")) {
            return Verdict(Route.ANSWER, "explicit")
        }

        // The launcher already has a real answer for this — don't editorialise.
        if (hasDirectHits) return Verdict(Route.NONE, "local results")

        // Left over: a short keyword-ish query — a name, a product, a place. That's a web lookup.
        // A longer free-form phrase with no web signal reads more like something to reason about.
        return if (words.size <= 3) Verdict(Route.WEB, "keyword lookup")
        else Verdict(Route.ANSWER, "free-form")
    }

    /** Strip the leading invocation verb so the model sees the actual question. */
    fun cleanPrompt(raw: String): String {
        val t = raw.trim()
        listOf("ask ", "ai ", "gemini ").forEach { p ->
            if (t.lowercase(Locale.US).startsWith(p)) return t.substring(p.length).trim()
        }
        return t
    }
}
