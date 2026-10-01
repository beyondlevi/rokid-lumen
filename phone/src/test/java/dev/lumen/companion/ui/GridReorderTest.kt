package dev.lumen.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class GridReorderTest {
    private val grid = listOf("notifications", "web:a", "web:b", "app:c", "settings")

    @Test
    fun `a dragged item lands where it was dropped`() {
        assertEquals(listOf("notifications", "web:b", "app:c", "web:a", "settings"), grid.moved(1, 3))
        assertEquals(listOf("app:c", "notifications", "web:a", "web:b", "settings"), grid.moved(3, 0))
    }

    @Test
    fun `out of range or in place changes nothing`() {
        assertEquals(grid, grid.moved(2, 2))
        assertEquals(grid, grid.moved(-1, 2))
        assertEquals(grid, grid.moved(1, 9))
    }
}
