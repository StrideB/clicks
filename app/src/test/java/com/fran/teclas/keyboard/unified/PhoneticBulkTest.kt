package com.fran.teclas.keyboard.unified

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The bulk (asset-loaded) misspelling lexicon must extend the curated table without ever overriding
 * it — a hand-verified entry always beats a bulk one.
 */
class PhoneticBulkTest {

    @After fun clearBulk() = PhoneticPatterns.installBulk(emptyMap())

    @Test fun bulkEntriesAreUsedWhenNotCurated() {
        assertNull(PhoneticPatterns.fix("abandonned"))
        PhoneticPatterns.installBulk(mapOf("abandonned" to "abandoned"))
        assertEquals("abandoned", PhoneticPatterns.fix("abandonned"))
        assertEquals(1, PhoneticPatterns.bulkSize())
    }

    @Test fun curatedAlwaysWinsOverBulk() {
        // "teh" is curated as "the"; a bad bulk entry must not be able to hijack it.
        PhoneticPatterns.installBulk(mapOf("teh" to "tea"))
        assertEquals("the", PhoneticPatterns.fix("teh"))
    }

    @Test fun emptyBulkLeavesCuratedIntact() {
        PhoneticPatterns.installBulk(emptyMap())
        assertEquals("the", PhoneticPatterns.fix("teh"))
        assertEquals("separate", PhoneticPatterns.fix("seperate"))
    }
}
