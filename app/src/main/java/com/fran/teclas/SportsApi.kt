package com.fran.teclas

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Live scores via ESPN's public site API (no key, no quota) — the launcher's sports vertical.
 * Type a team ("miami heat") for a single-game card — live score, final, or next tip-off — or a
 * league ("nba scores", "world cup") for today's board. [hasSportsIntent] also gates the curated
 * web results: sports queries pin Brave rows to espn.com so one trusted brand answers instead of
 * a mixed page of aggregators. Endpoints are unofficial-but-stable (site.api.espn.com); every
 * parse is tolerant and a failed fetch just means no card. Blocking; call off the main thread.
 */
object SportsApi {

    class League(val sport: String, val code: String, val label: String, val glyph: String) {
        val webUrl: String
            get() = if (sport == "soccer") "https://www.espn.com/soccer/scoreboard/_/league/$code"
            else "https://www.espn.com/$code/scoreboard"
    }

    /** [slug] is ESPN's team path (abbrev for US leagues, numeric id for clubs); null means the
     *  team has no page we hit directly (World Cup nations) — the league scoreboard is scanned
     *  for [match] instead. */
    class TeamRef(val league: League, val slug: String?, val name: String, val match: String = name)

    /** One game, ready to render: blank scores until tip-off, bold flags mark the leader. */
    data class Game(
        val awayName: String, val awayScore: String, val awayBold: Boolean,
        val homeName: String, val homeScore: String, val homeBold: Boolean,
        val status: String, val live: Boolean, val link: String
    )

    data class ScoreCard(
        val label: String, val glyph: String, val detail: String,
        val games: List<Game>, val link: String
    )

    private val NBA = League("basketball", "nba", "NBA", "🏀")
    private val NFL = League("football", "nfl", "NFL", "🏈")
    private val MLB = League("baseball", "mlb", "MLB", "⚾")
    private val NHL = League("hockey", "nhl", "NHL", "🏒")
    private val MLS = League("soccer", "usa.1", "MLS", "⚽")
    private val EPL = League("soccer", "eng.1", "Premier League", "⚽")
    private val LALIGA = League("soccer", "esp.1", "La Liga", "⚽")
    private val SERIE_A = League("soccer", "ita.1", "Serie A", "⚽")
    private val BUNDESLIGA = League("soccer", "ger.1", "Bundesliga", "⚽")
    private val LIGUE_1 = League("soccer", "fra.1", "Ligue 1", "⚽")
    private val UCL = League("soccer", "uefa.champions", "Champions League", "⚽")
    private val WORLD_CUP = League("soccer", "fifa.world", "World Cup", "🏆")

    private val leagues: Map<String, League> = buildMap {
        put("nba", NBA); put("nfl", NFL); put("mlb", MLB); put("nhl", NHL); put("mls", MLS)
        put("premier league", EPL); put("epl", EPL)
        put("la liga", LALIGA); put("laliga", LALIGA)
        put("serie a", SERIE_A); put("bundesliga", BUNDESLIGA); put("ligue 1", LIGUE_1)
        put("champions league", UCL); put("ucl", UCL)
        put("world cup", WORLD_CUP); put("fifa world cup", WORLD_CUP); put("mundial", WORLD_CUP)
    }

    // Full names always match; bare nicknames only when they aren't everyday words ("lakers" yes,
    // "heat"/"jazz"/"magic" no) and don't collide across leagues ("giants", "rangers", "jets",
    // "kings", "panthers", "cardinals" all need their city).
    private val teams: Map<String, TeamRef> = buildMap {
        fun team(lg: League, slug: String?, name: String, vararg alias: String) {
            val ref = TeamRef(lg, slug, name)
            put(norm(name), ref)
            alias.forEach { put(norm(it), ref) }
        }

        // NBA
        team(NBA, "atl", "Atlanta Hawks", "hawks")
        team(NBA, "bos", "Boston Celtics", "celtics")
        team(NBA, "bkn", "Brooklyn Nets", "nets")
        team(NBA, "cha", "Charlotte Hornets", "hornets")
        team(NBA, "chi", "Chicago Bulls", "bulls")
        team(NBA, "cle", "Cleveland Cavaliers", "cavaliers", "cavs")
        team(NBA, "dal", "Dallas Mavericks", "mavericks", "mavs")
        team(NBA, "den", "Denver Nuggets", "nuggets")
        team(NBA, "det", "Detroit Pistons", "pistons")
        team(NBA, "gs", "Golden State Warriors", "warriors", "golden state")
        team(NBA, "hou", "Houston Rockets", "rockets")
        team(NBA, "ind", "Indiana Pacers", "pacers")
        team(NBA, "lac", "LA Clippers", "clippers", "los angeles clippers")
        team(NBA, "lal", "Los Angeles Lakers", "lakers", "la lakers")
        team(NBA, "mem", "Memphis Grizzlies", "grizzlies")
        team(NBA, "mia", "Miami Heat")
        team(NBA, "mil", "Milwaukee Bucks", "bucks")
        team(NBA, "min", "Minnesota Timberwolves", "timberwolves")
        team(NBA, "no", "New Orleans Pelicans", "pelicans")
        team(NBA, "ny", "New York Knicks", "knicks", "ny knicks")
        team(NBA, "okc", "Oklahoma City Thunder", "thunder", "okc thunder")
        team(NBA, "orl", "Orlando Magic")
        team(NBA, "phi", "Philadelphia 76ers", "76ers", "sixers", "philadelphia sixers")
        team(NBA, "phx", "Phoenix Suns", "suns")
        team(NBA, "por", "Portland Trail Blazers", "blazers", "trail blazers")
        team(NBA, "sac", "Sacramento Kings")
        team(NBA, "sa", "San Antonio Spurs", "spurs")
        team(NBA, "tor", "Toronto Raptors", "raptors")
        team(NBA, "utah", "Utah Jazz")
        team(NBA, "wsh", "Washington Wizards", "wizards")

        // NFL
        team(NFL, "ari", "Arizona Cardinals")
        team(NFL, "atl", "Atlanta Falcons", "falcons")
        team(NFL, "bal", "Baltimore Ravens", "ravens")
        team(NFL, "buf", "Buffalo Bills", "bills")
        team(NFL, "car", "Carolina Panthers")
        team(NFL, "chi", "Chicago Bears", "bears")
        team(NFL, "cin", "Cincinnati Bengals", "bengals")
        team(NFL, "cle", "Cleveland Browns", "browns")
        team(NFL, "dal", "Dallas Cowboys", "cowboys")
        team(NFL, "den", "Denver Broncos", "broncos")
        team(NFL, "det", "Detroit Lions", "lions")
        team(NFL, "gb", "Green Bay Packers", "packers")
        team(NFL, "hou", "Houston Texans", "texans")
        team(NFL, "ind", "Indianapolis Colts", "colts")
        team(NFL, "jax", "Jacksonville Jaguars", "jaguars", "jags")
        team(NFL, "kc", "Kansas City Chiefs", "chiefs")
        team(NFL, "lv", "Las Vegas Raiders", "raiders")
        team(NFL, "lac", "Los Angeles Chargers", "chargers")
        team(NFL, "lar", "Los Angeles Rams", "rams")
        team(NFL, "mia", "Miami Dolphins", "dolphins")
        team(NFL, "min", "Minnesota Vikings", "vikings")
        team(NFL, "ne", "New England Patriots", "patriots", "pats")
        team(NFL, "no", "New Orleans Saints", "saints")
        team(NFL, "nyg", "New York Giants", "ny giants")
        team(NFL, "nyj", "New York Jets", "ny jets")
        team(NFL, "phi", "Philadelphia Eagles", "eagles")
        team(NFL, "pit", "Pittsburgh Steelers", "steelers")
        team(NFL, "sf", "San Francisco 49ers", "49ers", "niners")
        team(NFL, "sea", "Seattle Seahawks", "seahawks")
        team(NFL, "tb", "Tampa Bay Buccaneers", "buccaneers", "bucs")
        team(NFL, "ten", "Tennessee Titans", "titans")
        team(NFL, "wsh", "Washington Commanders", "commanders")

        // MLB
        team(MLB, "ari", "Arizona Diamondbacks", "diamondbacks", "dbacks")
        team(MLB, "atl", "Atlanta Braves", "braves")
        team(MLB, "bal", "Baltimore Orioles", "orioles")
        team(MLB, "bos", "Boston Red Sox", "red sox")
        team(MLB, "chc", "Chicago Cubs", "cubs")
        team(MLB, "chw", "Chicago White Sox", "white sox")
        team(MLB, "cin", "Cincinnati Reds", "reds")
        team(MLB, "cle", "Cleveland Guardians", "guardians")
        team(MLB, "col", "Colorado Rockies", "rockies")
        team(MLB, "det", "Detroit Tigers", "tigers")
        team(MLB, "hou", "Houston Astros", "astros")
        team(MLB, "kc", "Kansas City Royals", "royals")
        team(MLB, "laa", "Los Angeles Angels")
        team(MLB, "lad", "Los Angeles Dodgers", "dodgers", "la dodgers")
        team(MLB, "mia", "Miami Marlins", "marlins")
        team(MLB, "mil", "Milwaukee Brewers", "brewers")
        team(MLB, "min", "Minnesota Twins", "twins")
        team(MLB, "nym", "New York Mets", "mets")
        team(MLB, "nyy", "New York Yankees", "yankees")
        team(MLB, "ath", "Athletics", "athletics", "oakland athletics")
        team(MLB, "phi", "Philadelphia Phillies", "phillies")
        team(MLB, "pit", "Pittsburgh Pirates", "pirates")
        team(MLB, "sd", "San Diego Padres", "padres")
        team(MLB, "sea", "Seattle Mariners", "mariners")
        team(MLB, "sf", "San Francisco Giants", "sf giants")
        team(MLB, "stl", "St. Louis Cardinals", "st louis cardinals")
        team(MLB, "tb", "Tampa Bay Rays", "rays")
        team(MLB, "tex", "Texas Rangers", "texas rangers")
        team(MLB, "tor", "Toronto Blue Jays", "blue jays")
        team(MLB, "wsh", "Washington Nationals", "nationals", "nats")

        // NHL
        team(NHL, "ana", "Anaheim Ducks")
        team(NHL, "bos", "Boston Bruins", "bruins")
        team(NHL, "buf", "Buffalo Sabres", "sabres")
        team(NHL, "cgy", "Calgary Flames", "flames")
        team(NHL, "car", "Carolina Hurricanes", "hurricanes")
        team(NHL, "chi", "Chicago Blackhawks", "blackhawks")
        team(NHL, "col", "Colorado Avalanche", "avalanche")
        team(NHL, "cbj", "Columbus Blue Jackets", "blue jackets")
        team(NHL, "dal", "Dallas Stars")
        team(NHL, "det", "Detroit Red Wings", "red wings")
        team(NHL, "edm", "Edmonton Oilers", "oilers")
        team(NHL, "fla", "Florida Panthers")
        team(NHL, "la", "Los Angeles Kings", "la kings")
        team(NHL, "min", "Minnesota Wild")
        team(NHL, "mtl", "Montreal Canadiens", "canadiens", "habs")
        team(NHL, "nsh", "Nashville Predators", "predators", "preds")
        team(NHL, "nj", "New Jersey Devils", "devils")
        team(NHL, "nyi", "New York Islanders", "islanders")
        team(NHL, "nyr", "New York Rangers", "ny rangers")
        team(NHL, "ott", "Ottawa Senators", "senators", "sens")
        team(NHL, "phi", "Philadelphia Flyers", "flyers")
        team(NHL, "pit", "Pittsburgh Penguins", "penguins", "pens")
        team(NHL, "sj", "San Jose Sharks", "sharks")
        team(NHL, "sea", "Seattle Kraken", "kraken")
        team(NHL, "stl", "St. Louis Blues", "st louis blues")
        team(NHL, "tb", "Tampa Bay Lightning")
        team(NHL, "tor", "Toronto Maple Leafs", "maple leafs", "leafs")
        team(NHL, "utah", "Utah Mammoth", "mammoth")
        team(NHL, "van", "Vancouver Canucks", "canucks")
        team(NHL, "vgk", "Vegas Golden Knights", "golden knights", "vegas knights")
        team(NHL, "wsh", "Washington Capitals", "capitals")
        team(NHL, "wpg", "Winnipeg Jets", "winnipeg jets")

        // Soccer clubs — ESPN numeric team ids
        team(EPL, "359", "Arsenal")
        team(EPL, "364", "Liverpool")
        team(EPL, "360", "Manchester United", "man united", "man utd")
        team(EPL, "382", "Manchester City", "man city")
        team(EPL, "363", "Chelsea")
        team(EPL, "367", "Tottenham Hotspur", "tottenham")
        team(EPL, "361", "Newcastle United", "newcastle")
        team(LALIGA, "83", "Barcelona", "barca", "fc barcelona")
        team(LALIGA, "86", "Real Madrid")
        team(LALIGA, "1068", "Atlético Madrid", "atletico madrid", "atletico")
        team(SERIE_A, "111", "Juventus", "juve")
        team(SERIE_A, "103", "AC Milan")
        team(SERIE_A, "110", "Inter Milan")
        team(BUNDESLIGA, "132", "Bayern Munich", "bayern")
        team(BUNDESLIGA, "124", "Borussia Dortmund", "dortmund")
        team(LIGUE_1, "160", "PSG", "paris saint-germain")
        team(MLS, "20232", "Inter Miami")
        team(MLS, "187", "LA Galaxy")

        // World Cup nations — no team page; the fifa.world scoreboard is scanned for the name.
        fun nation(name: String, match: String = name, vararg alias: String) {
            val ref = TeamRef(WORLD_CUP, null, name, match)
            put(norm(name), ref)
            alias.forEach { put(norm(it), ref) }
        }
        nation("Argentina"); nation("Brazil"); nation("France"); nation("England")
        nation("Spain"); nation("Germany"); nation("Portugal"); nation("Netherlands")
        nation("Belgium"); nation("Croatia"); nation("Italy"); nation("Uruguay")
        nation("Colombia"); nation("Mexico", "Mexico", "el tri")
        nation("USA", "United States", "usmnt", "united states")
        nation("Canada"); nation("Japan"); nation("South Korea", "South Korea", "korea")
        nation("Morocco"); nation("Senegal"); nation("Ecuador"); nation("Switzerland")
        nation("Denmark"); nation("Australia"); nation("Saudi Arabia"); nation("Ghana")
        nation("Cameroon"); nation("Nigeria"); nation("Norway"); nation("Poland")
    }

    /** Cheap main-thread gate: does [query] name a team or league we can score? Also drives the
     *  curated-source rewrite that pins sports web results to espn.com. */
    fun hasSportsIntent(query: String): Boolean {
        val q = key(query)
        return leagues.containsKey(q) || teams.containsKey(q)
    }

    /** Resolve [query] to a score card, or null when it isn't sports / nothing is scheduled. */
    fun lookup(query: String): ScoreCard? {
        val q = key(query)
        leagues[q]?.let { return boardCard(it, null) }
        val team = teams[q] ?: return null
        return if (team.slug != null) teamCard(team) else boardCard(team.league, team)
    }

    /** Team page card: the team's current or next event (live score, final, or upcoming). */
    private fun teamCard(team: TeamRef): ScoreCard? {
        val lg = team.league
        val body = get("https://site.api.espn.com/apis/site/v2/sports/${lg.sport}/${lg.code}/teams/${team.slug}")
            ?: return null
        val t = runCatching { JSONObject(body).optJSONObject("team") }.getOrNull() ?: return null
        val ev = t.optJSONArray("nextEvent")?.optJSONObject(0) ?: return null
        val game = parseEvent(ev, compact = false) ?: return null
        val record = t.optJSONObject("record")?.optJSONArray("items")?.optJSONObject(0)
            ?.optString("summary").orEmpty()
        return ScoreCard(
            label = "${t.optString("displayName").ifBlank { team.name }} · ${lg.label}",
            glyph = lg.glyph,
            detail = record.takeIf { it.isNotBlank() }?.let { "Season $it" }.orEmpty(),
            games = listOf(game),
            link = game.link.ifBlank { lg.webUrl }
        )
    }

    /** League scoreboard card: today's games, or just [team]'s game when scanning for a nation. */
    private fun boardCard(lg: League, team: TeamRef?): ScoreCard? {
        val body = get("https://site.api.espn.com/apis/site/v2/sports/${lg.sport}/${lg.code}/scoreboard")
            ?: return null
        val events = runCatching { JSONObject(body).optJSONArray("events") }.getOrNull() ?: return null
        val filter = team?.let { norm(it.match) }
        val games = ArrayList<Game>()
        var link = lg.webUrl
        for (i in 0 until events.length()) {
            val ev = events.optJSONObject(i) ?: continue
            if (filter != null && !norm(ev.optString("name")).contains(filter)) continue
            val game = parseEvent(ev, compact = team == null) ?: continue
            games.add(game)
            if (filter != null) { link = game.link.ifBlank { link }; break }
            if (games.size >= 4) break
        }
        if (games.isEmpty()) return null
        return ScoreCard(
            label = team?.let { "${it.name} · ${lg.label}" } ?: "${lg.label} · Today",
            glyph = lg.glyph, detail = "",
            games = games, link = link
        )
    }

    /** Shared event parser: team-page nextEvent and scoreboard events differ only in how the
     *  score field is typed (object vs string), so both paths land here. [compact] renders
     *  abbreviations (board rows) instead of short names (single-game card). */
    private fun parseEvent(ev: JSONObject, compact: Boolean): Game? {
        val comp = ev.optJSONArray("competitions")?.optJSONObject(0) ?: return null
        val sides = comp.optJSONArray("competitors") ?: return null
        var home: JSONObject? = null
        var away: JSONObject? = null
        for (i in 0 until sides.length()) {
            val c = sides.optJSONObject(i) ?: continue
            when (c.optString("homeAway")) { "home" -> home = c; "away" -> away = c }
        }
        if (home == null || away == null) return null
        val status = comp.optJSONObject("status") ?: ev.optJSONObject("status") ?: return null
        val state = status.optJSONObject("type")?.optString("state").orEmpty()
        var statusText = status.optJSONObject("type")?.optString("shortDetail").orEmpty()
            .replace(Regex("\\s+E[DS]T$"), "")   // chip space is tight; local-ish TZ implied
        if (state == "pre" && (statusText.isBlank() || statusText.equals("Scheduled", true))) {
            // Soccer pre-game status carries no time — derive the local kickoff from the date.
            statusText = formatStart(ev.optString("date")) ?: statusText
        }

        fun name(c: JSONObject): String {
            val t = c.optJSONObject("team")
            return if (compact) t?.optString("abbreviation").orEmpty()
                .ifBlank { t?.optString("shortDisplayName").orEmpty() }
            else t?.optString("shortDisplayName").orEmpty()
                .ifBlank { t?.optString("displayName").orEmpty() }
        }
        fun score(c: JSONObject): String = when (val s = c.opt("score")) {
            is String -> s
            is JSONObject -> s.optString("displayValue")
            is Number -> s.toInt().toString()
            else -> ""
        }

        val started = state == "in" || state == "post"
        val homeScore = score(home)
        val awayScore = score(away)
        val h = homeScore.toDoubleOrNull() ?: -1.0
        val a = awayScore.toDoubleOrNull() ?: -1.0
        return Game(
            awayName = name(away),
            awayScore = if (started) awayScore else "",
            awayBold = started && a >= h,
            homeName = name(home),
            homeScore = if (started) homeScore else "",
            homeBold = started && h >= a,
            status = statusText.ifBlank { if (state == "post") "Final" else "" },
            live = state == "in",
            link = ev.optJSONArray("links")?.optJSONObject(0)?.optString("href").orEmpty()
        )
    }

    /** ESPN event dates are UTC "2026-07-11T19:00Z" → device-local "Sat 3:00 PM". */
    private fun formatStart(iso: String): String? = try {
        val utc = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        utc.parse(iso)?.let { java.text.SimpleDateFormat("EEE h:mm a", Locale.US).format(it) }
    } catch (_: Exception) { null }

    private fun norm(s: String): String = s.lowercase(Locale.US)
        .replace(Regex("[.'’]"), "").replace(Regex("\\s+"), " ").trim()

    // Suffix words users type after a team/league that shouldn't break the match.
    private val NOISE = setOf(
        "score", "scores", "game", "games", "match", "matches", "live",
        "today", "tonight", "result", "results", "schedule", "next"
    )

    /** Normalized lookup key: "the Miami Heat score tonight" → "miami heat". */
    private fun key(query: String): String {
        var words = norm(query).split(' ').filter { it.isNotBlank() }
        if (words.firstOrNull() == "the") words = words.drop(1)
        while (words.size > 1 && words.last() in NOISE) words = words.dropLast(1)
        return words.joinToString(" ")
    }

    private fun get(url: String): String? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 5_000; readTimeout = 8_000
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    } catch (_: Exception) { null }
}
