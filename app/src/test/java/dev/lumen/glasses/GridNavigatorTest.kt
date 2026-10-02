package dev.lumen.glasses

import org.junit.Assert.assertEquals
import org.junit.Test

class GridNavigatorTest {
    private val grid = GridNavigator()

    @Test
    fun movesInTwoDimensionsAcrossRows() {
        assertEquals(1, grid.move(0, 11, BandCommand.RIGHT))
        assertEquals(3, grid.move(0, 11, BandCommand.DOWN))
        assertEquals(10, grid.move(7, 11, BandCommand.DOWN))
        assertEquals(4, grid.move(7, 11, BandCommand.UP))
        // Right and left walk on across rows (the forward and back swipes).
        assertEquals(3, grid.move(2, 11, BandCommand.RIGHT))
        assertEquals(2, grid.move(3, 11, BandCommand.LEFT))
        assertEquals(10, grid.move(10, 11, BandCommand.RIGHT))
    }

    @Test
    fun leavingTheGridIsTheHomesBusiness() {
        assertEquals(GridNavigator.OUT_UP, grid.move(1, 11, BandCommand.UP))
        assertEquals(GridNavigator.OUT_LEFT, grid.move(0, 11, BandCommand.LEFT))
        assertEquals(5, grid.move(6, 11, BandCommand.LEFT))
        assertEquals(GridNavigator.OUT_UP, grid.move(0, 0, BandCommand.UP))
        assertEquals(GridNavigator.OUT_LEFT, grid.move(0, 0, BandCommand.LEFT))
    }

    @Test
    fun downToAShorterRowTakesItsLastItemAndStopsAtTheEnd() {
        assertEquals(9, grid.move(8, 10, BandCommand.DOWN))
        assertEquals(9, grid.move(9, 10, BandCommand.DOWN))
        assertEquals(4, grid.move(4, 5, BandCommand.DOWN))
    }

    @Test
    fun forwardAndBackwardWalkInOrder() {
        assertEquals(3, grid.move(2, 11, BandCommand.FORWARD))
        assertEquals(10, grid.move(10, 11, BandCommand.FORWARD))
        assertEquals(0, grid.move(0, 11, BandCommand.BACKWARD))
    }

    @Test
    fun rows() {
        assertEquals(0, grid.rowCount(0))
        assertEquals(1, grid.rowCount(3))
        assertEquals(4, grid.rowCount(10))
        assertEquals(3, grid.rowOf(9))
    }
}
