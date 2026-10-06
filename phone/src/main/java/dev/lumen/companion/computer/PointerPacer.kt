package dev.lumen.companion.computer

/**
 * Plays the air mouse's movement back smoothly, a fixed moment behind the arm (kinesis
 * `PointerPacer`, AirCursor.swift, MIT). The band's samples arrive in batches every 15 ms,
 * sometimes 30 ms, so moving the pointer by whatever arrived looked choppy. Each step sits at
 * the time the band sampled it, and each frame takes the movement up to [seconds] ago, part of a
 * step when the frame falls between two samples. A batch later than that is taken at once.
 * Units are whatever the caller adds (mouse counts here). Times are seconds.
 */
class PointerPacer(private val seconds: Double = PLAYBACK_SECONDS) {
    private class Step(val start: Double, val end: Double, var x: Double, var y: Double)

    private val steps = ArrayDeque<Step>()
    private var takenUntil = Double.NEGATIVE_INFINITY

    val isEmpty get() = steps.isEmpty()

    /** Movement the band sampled at [time]; it spans the time since the sample before. */
    fun add(x: Double, y: Double, time: Double) {
        val previous = steps.lastOrNull()?.end ?: takenUntil
        val end = maxOf(time, previous)
        steps.addLast(Step(maxOf(previous, end - 1.0 / 64), end, x, y))
    }

    /** The movement for a frame at [now], or null when it's too small to move. */
    fun take(now: Double): DoubleArray? {
        val until = if (seconds > 0) now - seconds else Double.POSITIVE_INFINITY
        var x = 0.0
        var y = 0.0
        while (steps.isNotEmpty()) {
            val first = steps.first()
            if (first.end <= until) {
                x += first.x
                y += first.y
                steps.removeFirst()
                takenUntil = maxOf(takenUntil, first.end)
                continue
            }
            val from = maxOf(first.start, takenUntil)
            if (until > from) {
                val share = (until - from) / (first.end - from)
                x += first.x * share
                y += first.y * share
                first.x -= first.x * share
                first.y -= first.y * share
                takenUntil = until
            }
            break
        }
        if (Math.abs(x) + Math.abs(y) < MINIMUM) {
            // Keep a tiny remainder for the next frame instead of losing it.
            if ((x != 0.0 || y != 0.0) && steps.isNotEmpty()) {
                steps.first().x += x
                steps.first().y += y
            }
            return null
        }
        return doubleArrayOf(x, y)
    }

    fun clear() {
        steps.clear()
    }

    companion object {
        const val PLAYBACK_SECONDS = 0.03
        private const val MINIMUM = 0.05
    }
}

/** Mouse counts are whole: keeps the fractions for the next move, so slow moves still add up. */
class PointerCounts {
    private var x = 0.0
    private var y = 0.0

    /** The whole counts to send for [dx], [dy] plus what was left over. */
    fun add(dx: Double, dy: Double): IntArray {
        x += dx
        y += dy
        val wholeX = x.toInt()
        val wholeY = y.toInt()
        x -= wholeX
        y -= wholeY
        return intArrayOf(wholeX, wholeY)
    }

    fun reset() {
        x = 0.0
        y = 0.0
    }
}
