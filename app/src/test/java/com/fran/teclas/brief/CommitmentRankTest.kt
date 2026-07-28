package com.fran.teclas.brief

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recall behaviour of the commitment memory — the thing that answers "what did Kelly need me to do
 * today?". These are the questions a person actually types, so a miss here is the whole feature
 * reading as broken.
 */
class CommitmentRankTest {

    private val now = 1_700_000_000_000L
    private fun hoursAgo(h: Long) = now - h * 3_600_000L

    private fun c(text: String, person: String, whenMs: Long) =
        CommitmentStore.Commitment(text = text, person = person, pkg = "com.whatsapp", key = "k$text", whenMs = whenMs)

    private val store = listOf(
        c("send the Q1 draft", "Kelly", hoursAgo(3)),
        c("book the dentist", "Ana", hoursAgo(20)),
        c("review the lease", "Kelly", hoursAgo(200)),
        c("pick up the passport", "Ethan", hoursAgo(400)),
    )

    // ---------------------------------------------------------------- the reported question

    @Test fun `naming a person surfaces their commitments`() {
        val r = CommitmentStore.rank(store, "what did kelly need me to do today", now)
        assertTrue("Kelly's items must be found", r.isNotEmpty())
        assertEquals("recent Kelly item ranks first", "send the Q1 draft", r.first().text)
        assertTrue("both Kelly items reachable", r.count { it.person == "Kelly" } == 2)
    }

    @Test fun `an all-stopword question returns recent items instead of nothing`() {
        // "what do I need to do today" is entirely stopwords. Returning nothing here is what made
        // the feature read as broken.
        val r = CommitmentStore.rank(store, "what do i need to do today", now)
        assertTrue("must not be empty", r.isNotEmpty())
        assertEquals("most recent first", "send the Q1 draft", r.first().text)
    }

    @Test fun `bare todo question returns something`() {
        assertTrue(CommitmentStore.rank(store, "any todos?", now).isNotEmpty())
    }

    // ---------------------------------------------------------------- recall by content

    @Test fun `content words find the commitment`() {
        val r = CommitmentStore.rank(store, "what about the lease", now)
        assertEquals("review the lease", r.first().text)
    }

    @Test fun `prefix relationship matches in both directions`() {
        // stored "draft" vs asked "drafts", and stored "review" vs asked "reviewing"
        assertTrue(CommitmentStore.rank(store, "the drafts", now).any { it.text.contains("draft") })
        assertTrue(CommitmentStore.rank(store, "reviewing something", now).any { it.text.contains("review") })
    }

    @Test fun `unrelated query returns nothing rather than noise`() {
        assertTrue(CommitmentStore.rank(store, "pizza recipe carburetor", now).isEmpty())
    }

    // ---------------------------------------------------------------- ordering

    @Test fun `recent outranks stale for the same person`() {
        val r = CommitmentStore.rank(store, "kelly", now)
        assertEquals("send the Q1 draft", r[0].text)
        assertEquals("review the lease", r[1].text)
    }

    @Test fun `a today query favours the last two days`() {
        val r = CommitmentStore.rank(store, "kelly today", now)
        assertEquals("send the Q1 draft", r.first().text)
    }

    @Test fun `old commitments are still reachable when nothing recent matches`() {
        // Nothing is deleted — recency orders, it does not filter.
        val r = CommitmentStore.rank(store, "passport", now)
        assertEquals("pick up the passport", r.first().text)
    }

    @Test fun `limit is respected`() {
        val many = (1..20).map { c("task $it", "P$it", hoursAgo(it.toLong())) }
        assertEquals(5, CommitmentStore.rank(many, "what do i need to do", now).size)
        assertEquals(2, CommitmentStore.rank(many, "what do i need to do", now, limit = 2).size)
    }

    @Test fun `empty store never crashes`() {
        assertTrue(CommitmentStore.rank(emptyList(), "kelly", now).isEmpty())
        assertTrue(CommitmentStore.rank(emptyList(), "what do i need to do today", now).isEmpty())
    }
}
