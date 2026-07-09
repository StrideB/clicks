package com.fran.teclas.predict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure checks for the dock's auto-follow-with-respected-override flow:
 * "switch to context when the Space changes; if the user swipes back to pinned, respect it."
 */
class DockContextPolicyTest {

    @Test fun `no override shows context (context-first default)`() {
        val d = DockContextPolicy.onSpaceObserved(override = null, current = "work")
        assertTrue(d.showContext)
        assertNull(d.override)
    }

    @Test fun `pinned override in the current space is respected`() {
        val d = DockContextPolicy.onSpaceObserved(override = "work", current = "work")
        assertFalse("must stay pinned while in the space the user pinned in", d.showContext)
        assertEquals("work", d.override)
    }

    @Test fun `leaving the space drops the override and auto-shows context`() {
        // User pinned at work, then the Space changed to driving.
        val d = DockContextPolicy.onSpaceObserved(override = "work", current = "driving")
        assertTrue("a genuine space change re-arms auto-context", d.showContext)
        assertNull("the stale override is cleared", d.override)
    }

    @Test fun `swiping to pinned records an override for the current space`() {
        assertEquals("home", DockContextPolicy.onUserSwipe(toContext = false, current = "home"))
    }

    @Test fun `swiping back to context clears the override`() {
        assertNull(DockContextPolicy.onUserSwipe(toContext = true, current = "home"))
    }

    @Test fun `returning to a space you once pinned in does not resurrect the override`() {
        // Left work (override cleared) -> now back at work with no override -> context.
        val cleared = DockContextPolicy.onSpaceObserved(override = "work", current = "driving").override
        val backAtWork = DockContextPolicy.onSpaceObserved(override = cleared, current = "work")
        assertTrue(backAtWork.showContext)
    }
}
