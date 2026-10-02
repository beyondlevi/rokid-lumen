package dev.lumen.glasses

/**
 * Focus movement in the apps grid: [columns] across, as many rows as it takes (it scrolls
 * vertically), kept apart from the view so it can be tested. Items fill rows left to right.
 * Up, down, left and right move in two dimensions; down to a shorter last row takes its last
 * item. Leaving the grid upward (from the first row) or leftward (from the first column) is
 * the home's business: [move] answers [OUT_UP] or [OUT_LEFT] then. Forward and backward (the
 * dial, the touchpad) walk the items in order.
 */
class GridNavigator(val columns: Int = 3) {
    fun rowOf(index: Int) = index / columns
    fun rowCount(count: Int) = if (count <= 0) 0 else (count - 1) / columns + 1

    /** Where [command] moves the focus from [index] among [count] items, or [OUT_UP] / [OUT_LEFT]. */
    fun move(index: Int, count: Int, command: String): Int {
        if (count <= 0) return if (command == BandCommand.UP) OUT_UP else if (command == BandCommand.LEFT) OUT_LEFT else 0
        val current = index.coerceIn(0, count - 1)
        val column = current % columns
        return when (command) {
            BandCommand.FORWARD -> (current + 1).coerceAtMost(count - 1)
            BandCommand.BACKWARD -> (current - 1).coerceAtLeast(0)
            BandCommand.RIGHT -> if (column < columns - 1 && current + 1 < count) current + 1 else current
            BandCommand.LEFT -> if (column > 0) current - 1 else OUT_LEFT
            BandCommand.DOWN -> when {
                current + columns < count -> current + columns
                // The row below is shorter: its last item.
                rowOf(current) < rowOf(count - 1) -> count - 1
                else -> current
            }
            BandCommand.UP -> if (current >= columns) current - columns else OUT_UP
            else -> current
        }
    }

    companion object {
        /** Up from the first row: the focus goes to the home's tabs. */
        const val OUT_UP = -1
        /** Left from the first column: the previous tab. */
        const val OUT_LEFT = -2
    }
}
