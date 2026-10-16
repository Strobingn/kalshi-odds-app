package com.dirk.kalshiodds.decision

/**
 * Pool Adjacent Violators isotonic regression.
 * Knots keep the empirical rate, including 0 and 1. No 2% display floor.
 */
object PavIsotonic {
    data class Knot(val x: Double, val y: Double)

    fun fit(pairs: List<Pair<Double, Double>>): List<Knot> {
        if (pairs.isEmpty()) return emptyList()
        val sorted = pairs.sortedBy { it.first }
        data class Block(var w: Double, var sum: Double, var x0: Double, var x1: Double, var xSum: Double) {
            val y: Double get() = sum / w
        }
        val blocks = ArrayList<Block>(sorted.size)
        for ((x, y) in sorted) {
            val yy = y.coerceIn(0.0, 1.0)
            blocks.add(Block(1.0, yy, x, x, x))
            while (blocks.size >= 2 && blocks[blocks.lastIndex - 1].y > blocks.last().y + 1e-15) {
                val b = blocks.removeAt(blocks.lastIndex)
                val a = blocks.removeAt(blocks.lastIndex)
                blocks.add(
                    Block(
                        w = a.w + b.w,
                        sum = a.sum + b.sum,
                        x0 = a.x0,
                        x1 = b.x1,
                        xSum = a.xSum + b.xSum
                    )
                )
            }
        }
        return blocks.map { Knot(x = it.xSum / it.w, y = it.y) }
    }

    fun apply(p: Double, knots: List<Knot>): Double {
        if (!p.isFinite() || knots.isEmpty()) return p
        if (p <= knots.first().x) return knots.first().y
        if (p >= knots.last().x) return knots.last().y
        for (i in 0 until knots.size - 1) {
            val a = knots[i]
            val b = knots[i + 1]
            if (p >= a.x && p <= b.x) {
                val span = (b.x - a.x).coerceAtLeast(1e-12)
                val t = (p - a.x) / span
                return a.y * (1.0 - t) + b.y * t
            }
        }
        return knots.last().y
    }
}
