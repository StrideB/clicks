package com.fran.teclas.grid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceBoardSeedTest {

    private fun apps(n: Int) = (0 until n).map { SpaceBoardSeed.SeedApp("pkg.$it", null, "App $it") }

    @Test fun `apps flow left-to-right, top-to-bottom as 1x1 tiles`() {
        val items = SpaceBoardSeed.seed(apps(6), cols = 4, rows = 6, reserveBottomRows = 3)
        assertEquals(6, items.size)
        assertTrue(items.all { it.type == GridItemType.APP && it.spanX == 1 && it.spanY == 1 })
        assertEquals(0 to 0, items[0].cellX to items[0].cellY)
        assertEquals(3 to 0, items[3].cellX to items[3].cellY)
        assertEquals(1 to 1, items[5].cellX to items[5].cellY) // wraps to row 1
    }

    @Test fun `bottom rows are reserved for widgets`() {
        // 4 cols, 6 rows, reserve 3 -> app rows = 3 -> capacity 12; extra apps are dropped.
        val items = SpaceBoardSeed.seed(apps(20), cols = 4, rows = 6, reserveBottomRows = 3)
        assertEquals(12, items.size)
        assertTrue("no app tile intrudes on the reserved widget zone", items.all { it.cellY < 3 })
    }

    @Test fun `default seeds a single top row of pinned apps, rest reserved for widgets`() {
        val items = SpaceBoardSeed.seed(apps(10)) // default cols=4, rows=6, reserve=5 -> 1 app row
        assertEquals(4, items.size)
        assertTrue("apps stay on the top row", items.all { it.cellY == 0 })
    }

    @Test fun `empty app list yields an empty board`() {
        assertTrue(SpaceBoardSeed.seed(emptyList()).isEmpty())
    }
}
