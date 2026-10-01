package dev.lumen.glasses

import org.junit.Assert.assertEquals
import org.junit.Test

class GridNavigatorTest {
    private val grid = GridNavigator()

    @Test
    fun movesInsideAPage() {
        assertEquals(1, grid.move(0, 9, BandCommand.RIGHT))
        assertEquals(3, grid.move(0, 9, BandCommand.DOWN))
        assertEquals(1, grid.move(4, 9, BandCommand.UP))
        assertEquals(3, grid.move(4, 9, BandCommand.LEFT))
    }

    @Test
    fun stopsAtTheEdgesOfTheOnlyPage() {
        assertEquals(0, grid.move(0, 9, BandCommand.LEFT))
        assertEquals(0, grid.move(0, 9, BandCommand.UP))
        assertEquals(8, grid.move(8, 9, BandCommand.RIGHT))
        assertEquals(8, grid.move(8, 9, BandCommand.DOWN))
    }

    @Test
    fun crossesPagesOnTheSameRow() {
        // Row 1, last column of page 0 → row 1, first column of page 1.
        assertEquals(12, grid.move(5, 20, BandCommand.RIGHT))
        assertEquals(5, grid.move(12, 20, BandCommand.LEFT))
        // The next page is shorter than the row: its last item.
        assertEquals(10, grid.move(8, 11, BandCommand.RIGHT))
    }

    @Test
    fun downToAShorterRowTakesItsLastItem() {
        // Items 0..4: row 1 has 3 and 4 only.
        assertEquals(4, grid.move(2, 5, BandCommand.DOWN))
        assertEquals(2, grid.move(2, 3, BandCommand.DOWN))
    }

    @Test
    fun forwardAndBackwardWalkInOrder() {
        assertEquals(9, grid.move(8, 12, BandCommand.FORWARD))
        assertEquals(11, grid.move(11, 12, BandCommand.FORWARD))
        assertEquals(0, grid.move(0, 12, BandCommand.BACKWARD))
    }

    @Test
    fun pages() {
        assertEquals(1, grid.pageCount(0))
        assertEquals(1, grid.pageCount(9))
        assertEquals(2, grid.pageCount(10))
        assertEquals(1, grid.pageOf(9))
    }
}
