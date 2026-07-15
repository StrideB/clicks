package com.fran.teclas

import java.util.Locale

/**
 * Local intent resolution for search: "hungry" → restaurants, "drinks" → bars, "shopping" → malls.
 *
 * Deliberately a static lexicon, NOT an LLM call. This runs on every keystroke, so it must cost
 * microseconds — the whole point is that the nearby card is already on screen before a model
 * could have answered. The LLM's job (see MainActivity's refiner) is only the long tail the
 * lexicon can't cover ("somewhere nice for a date"), and it runs async on top of a live card.
 *
 * [chips] are the follow-up question, answered instantly: a broad intent like "food" offers
 * Steak · Sushi · Pizza; tapping one re-queries that subject. Same flow as asking the user what
 * they're in the mood for, minus the round-trip.
 */
internal object SearchIntent {

    data class PlaceIntent(
        val subject: String,          // what Places actually searches for
        val label: String,            // card header ("Restaurants")
        val chips: List<String>,      // instant refinements
        val rankByRating: Boolean,    // "best"/"top rated" → sort by rating, not relevance
        val openNowOnly: Boolean      // "open now", or a night-context drinks run
    )

    private data class Entry(val subject: String, val label: String, val chips: List<String>)

    // Trigger word → what it means. Keys are matched as whole words in the query, so "im hungry"
    // and "hungry" both hit. Order doesn't matter; the longest/most specific match wins.
    private val LEXICON: Map<String, Entry> = buildMap {
        val food = Entry("restaurants", "Restaurants",
            listOf("Steak", "Sushi", "Pizza", "Burgers", "Italian", "Mexican", "Thai", "Vegan"))
        listOf("hungry", "food", "eat", "eats", "starving", "lunch", "dinner", "supper",
            "restaurant", "restaurants", "brunch", "breakfast").forEach { put(it, food) }

        val drinks = Entry("bars", "Bars",
            listOf("Cocktails", "Beer", "Wine", "Rooftop", "Live music", "Pub"))
        listOf("drink", "drinks", "thirsty", "beer", "bar", "bars", "pub", "cocktail",
            "cocktails", "wine", "nightcap", "brewery").forEach { put(it, drinks) }

        val coffee = Entry("coffee shops", "Coffee",
            listOf("Espresso", "Bakery", "Wifi", "Breakfast"))
        listOf("coffee", "caffeine", "espresso", "latte", "cafe", "cafes").forEach { put(it, coffee) }

        val shopping = Entry("shopping malls", "Shopping",
            listOf("Mall", "Clothing", "Electronics", "Thrift", "Shoes", "Groceries"))
        listOf("shopping", "shop", "shops", "mall", "malls", "store", "stores", "buy",
            "plaza", "outlet").forEach { put(it, shopping) }

        val grocery = Entry("grocery stores", "Groceries", listOf("Supermarket", "Organic", "Open now"))
        listOf("grocery", "groceries", "supermarket", "market").forEach { put(it, grocery) }

        val pharmacy = Entry("pharmacies", "Pharmacy", listOf("Open now", "24 hours"))
        listOf("pharmacy", "drugstore", "medicine", "prescription").forEach { put(it, pharmacy) }

        val gas = Entry("gas stations", "Gas", listOf("Open now", "Diesel", "EV charging"))
        listOf("gas", "fuel", "petrol", "gasoline").forEach { put(it, gas) }

        val hotel = Entry("hotels", "Hotels", listOf("Cheap", "Luxury", "Pet friendly"))
        listOf("hotel", "hotels", "motel", "lodging", "stay").forEach { put(it, hotel) }

        val atm = Entry("ATMs", "ATM", listOf("Bank", "No fee"))
        listOf("atm", "atms", "cash", "bank").forEach { put(it, atm) }

        val gym = Entry("gyms", "Gyms", listOf("24 hours", "Yoga", "Climbing", "Pool"))
        listOf("gym", "gyms", "workout", "fitness").forEach { put(it, gym) }

        val health = Entry("hospitals", "Health", listOf("Emergency", "Urgent care", "Dentist"))
        listOf("hospital", "doctor", "emergency", "urgentcare", "clinic", "dentist").forEach { put(it, health) }

        val parking = Entry("parking", "Parking", listOf("Garage", "Free", "Open now"))
        listOf("parking", "park my car", "garage").forEach { put(it, parking) }

        val fun_ = Entry("things to do", "Nearby", listOf("Museums", "Parks", "Cinema", "Live music"))
        listOf("bored", "todo", "attractions", "sightseeing").forEach { put(it, fun_) }

        val movies = Entry("movie theaters", "Cinema", listOf("IMAX", "Showtimes"))
        listOf("movie", "movies", "cinema", "theater").forEach { put(it, movies) }
    }

    private val RATING_WORDS = setOf("best", "top", "great", "good", "highest rated", "top rated", "nice")
    private val OPEN_WORDS = setOf("open", "open now", "late", "24 hours")

    /**
     * Resolve [rawQuery] to a place intent, or null when it isn't one. [spaceId] is the detected
     * Space (travel / home / night / …) and only *modulates* — it never invents intent from
     * nothing, so ordinary searches are untouched.
     */
    fun of(rawQuery: String, spaceId: String? = null): PlaceIntent? {
        val q = rawQuery.trim().lowercase(Locale.US)
        if (q.isBlank() || q.length < 3) return null
        val words = q.split(' ', ',').filter { it.isNotBlank() }

        // Longest key first so "coffee shop" beats "shop".
        val hit = LEXICON.keys
            .filter { key -> key.contains(' ') && q.contains(key) || words.contains(key) }
            .maxByOrNull { it.length }
            ?.let { LEXICON[it]!! }
            ?: return null

        val rankByRating = RATING_WORDS.any { w -> if (w.contains(' ')) q.contains(w) else words.contains(w) }
        var openNow = OPEN_WORDS.any { w -> if (w.contains(' ')) q.contains(w) else words.contains(w) }
        var subject = hit.subject

        // Context modulation — the same word means different things in different Spaces.
        when (spaceId) {
            // Out at night, "drinks"/"food" means what's actually open right now.
            "night" -> openNow = true
            // Travelling: bias to what a visitor wants rather than the everyday version.
            "travel" -> if (hit.label == "Restaurants") subject = "popular restaurants"
        }
        if (rankByRating && !subject.startsWith("best")) subject = "best $subject"
        return PlaceIntent(subject, hit.label, hit.chips, rankByRating, openNow)
    }

    /** True when the query is a bare trigger with no qualifier — the case where offering
     *  refinement chips ("what are you in the mood for?") actually helps. */
    fun isBroad(rawQuery: String): Boolean {
        val words = rawQuery.trim().lowercase(Locale.US).split(' ').filter { it.isNotBlank() }
        return words.size <= 2 && words.any { LEXICON.containsKey(it) }
    }
}
