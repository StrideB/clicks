package com.fran.teclas

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The router's contract, stated as examples. The rule under test is not "is it a question?" but
 * "can an offline model with a knowledge cutoff answer this without inventing it?" — so the
 * question-shaped-but-live cases below are the ones that actually matter.
 */
class SearchRouterTest {

    private fun route(q: String, hits: Boolean = false) = SearchRouter.route(q, hits).route

    @Test fun `stable knowledge questions are answered`() {
        listOf(
            "why is the sky blue",
            "what is a vector database",
            "explain tcp handshake",
            "difference between http and https",
            "how do i tie a tie",
            "what does ttl mean?",
        ).forEach { assertEquals("'$it' should ANSWER", SearchRouter.Route.ANSWER, route(it)) }
    }

    @Test fun `live data goes to the web even when phrased as a question`() {
        // The whole point: these are questions, but an offline model would fabricate the answer.
        listOf(
            "what is the btc price",
            "who won the game",
            "what's the weather tomorrow",
            "when does the new season come out",
            "how much is a tesla",
            "latest news",
            "nvidia stock",
        ).forEach { assertEquals("'$it' should WEB", SearchRouter.Route.WEB, route(it)) }
    }

    @Test fun `local intent goes to the web`() {
        listOf("pizza near me", "coffee open now", "target hours", "directions to the airport")
            .forEach { assertEquals("'$it' should WEB", SearchRouter.Route.WEB, route(it)) }
    }

    @Test fun `navigational and commercial go to the web`() {
        listOf("github.com", "gmail login", "buy airpods", "cheapest flights", "reddit")
            .forEach { assertEquals("'$it' should WEB", SearchRouter.Route.WEB, route(it)) }
    }

    @Test fun `composition always answers even with live words in it`() {
        // "tonight" is a freshness word, but nothing but a model can write the note.
        listOf(
            "write a note about the game tonight",
            "summarize this",
            "draft an email to my landlord",
            "translate good morning to spanish",
        ).forEach { assertEquals("'$it' should ANSWER", SearchRouter.Route.ANSWER, route(it)) }
    }

    @Test fun `bare keywords with local hits stay out of both`() {
        assertEquals(SearchRouter.Route.NONE, route("instagram", hits = true))
        assertEquals(SearchRouter.Route.NONE, route("a"))
    }

    @Test fun `bare keyword with no local hit is a web lookup`() {
        assertEquals(SearchRouter.Route.WEB, route("mount rainier", hits = false))
    }

    @Test fun `explicit invocation always answers`() {
        assertEquals(SearchRouter.Route.ANSWER, route("ask why cats purr"))
        assertEquals("ask verb is stripped", "why cats purr", SearchRouter.cleanPrompt("ask why cats purr"))
        assertEquals("gemini verb is stripped", "what is rust", SearchRouter.cleanPrompt("gemini what is rust"))
    }

    @Test fun `short question marks are not questions`() {
        assertEquals(SearchRouter.Route.WEB, route("ok?"))
    }
}
