package dev.lumen.glasses

/**
 * Focus movement in a paged grid (3x3 on the glasses), kept apart from the view so it can be
 * tested. Items fill pages left to right, top to bottom. Up and down stay on the page; left and
 * right step through a row and cross to the neighbouring page at its edges, landing on the same
 * row there (or the last item, when that page is shorter). Forward and backward (the dial, the
 * touchpad) walk the items in order.
 */
class GridNavigator(private val columns: Int = 3, private val rows: Int = 3) {
    val pageSize get() = columns * rows

    fun pageOf(index: Int) = index / pageSize
    fun pageCount(count: Int) = if (count <= 0) 1 else (count - 1) / pageSize + 1

    /** Where [command] moves the focus from [index] among [count] items. */
    fun move(index: Int, count: Int, command: String): Int {
        if (count <= 0) return 0
        val current = index.coerceIn(0, count - 1)
        val page = current / pageSize
        val slot = current % pageSize
        val row = slot / columns
        val column = slot % columns
        return when (command) {
            BandCommand.FORWARD -> (current + 1).coerceAtMost(count - 1)
            BandCommand.BACKWARD -> (current - 1).coerceAtLeast(0)
            BandCommand.RIGHT -> when {
                column < columns - 1 && current + 1 < count -> current + 1
                page + 1 < pageCount(count) -> landOn(page + 1, row, 0, count)
                else -> current
            }
            BandCommand.LEFT -> when {
                column > 0 -> current - 1
                page > 0 -> landOn(page - 1, row, columns - 1, count)
                else -> current
            }
            BandCommand.DOWN -> when {
                row < rows - 1 && current + columns < count -> current + columns
                // The row below is shorter: its last item.
                row < rows - 1 && page * pageSize + (row + 1) * columns < count -> count - 1
                else -> current
            }
            BandCommand.UP -> if (row > 0) current - columns else current
            else -> current
        }
    }

    private fun landOn(page: Int, row: Int, column: Int, count: Int): Int =
        (page * pageSize + row * columns + column).coerceAtMost(count - 1)
}
