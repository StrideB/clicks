package com.fran.teclas

import com.fran.teclas.AppShortcuts.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ordering rules for the app-declared shortcuts shown on long-press. The menu has to look the same
 * every time it opens — a list that reshuffles is one nobody can learn.
 */
class AppShortcutsTest {

    private fun item(id: String, label: String, rank: Int = 0, enabled: Boolean = true) =
        Item(id, label, rank, enabled)

    @Test fun `the app's own ranking is respected`() {
        val out = AppShortcuts.order(listOf(
            item("c", "Scan a code", rank = 2),
            item("a", "New message", rank = 0),
            item("b", "Reorder", rank = 1),
        ))
        assertEquals(listOf("New message", "Reorder", "Scan a code"), out.map { it.label })
    }

    @Test fun `ties break on label so the menu never reshuffles`() {
        // Same rank twice is common. Without a stable tiebreak the order would depend on whatever
        // the platform happened to return, and the menu would move between openings.
        val out = AppShortcuts.order(listOf(
            item("b", "Zebra", rank = 1),
            item("a", "apple", rank = 1),
        ))
        assertEquals(listOf("apple", "Zebra"), out.map { it.label })
    }

    @Test fun `disabled shortcuts are dropped rather than shown greyed out`() {
        // A menu of things that do nothing is worse than a shorter menu.
        val out = AppShortcuts.order(listOf(
            item("a", "Works"),
            item("b", "Broken", enabled = false),
        ))
        assertEquals(listOf("Works"), out.map { it.label })
    }

    @Test fun `unlabelled shortcuts are dropped`() {
        val out = AppShortcuts.order(listOf(item("a", "  "), item("b", "Real")))
        assertEquals(listOf("Real"), out.map { it.label })
    }

    @Test fun `duplicate ids appear once`() {
        val out = AppShortcuts.order(listOf(
            item("a", "First", rank = 0),
            item("a", "Same id again", rank = 1),
        ))
        assertEquals(1, out.size)
        assertEquals("First", out.first().label)
    }

    @Test fun `the menu stays a glance`() {
        val many = (1..20).map { item("s$it", "Shortcut $it", rank = it) }
        assertEquals(AppShortcuts.MAX_SHOWN, AppShortcuts.order(many).size)
        assertEquals(2, AppShortcuts.order(many, limit = 2).size)
    }

    @Test fun `an app with no shortcuts is not an error`() {
        assertTrue(AppShortcuts.order(emptyList()).isEmpty())
        assertTrue(AppShortcuts.order(listOf(item("a", "x", enabled = false))).isEmpty())
    }
}
