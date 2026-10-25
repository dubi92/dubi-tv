package tv.dubi.launcher

import kotlin.math.abs

enum class Dir { UP, DOWN, LEFT, RIGHT }

/** A focusable box in stage pixels (layout position, ignoring lifts and scrolls). */
data class Box(val x: Int, val y: Int, val w: Int, val h: Int) {
    val cx get() = x + w / 2f
    val cy get() = y + h / 2f
}

/**
 * D-pad focus search, ported from the mockup's `core.js` (itself modelled on Android's FocusFinder):
 * prefer candidates inside the beam (overlapping on the cross axis); outside the beam only accept ones
 * within a narrow cone, so Right at a row's end never jumps rows. A vertical move with nothing in its
 * cone still lands on the next row up/down; a horizontal one never wraps.
 * Returns the index of the best box in [list], or -1 when the move hits an edge.
 */
fun nearest(cur: Box, dir: Dir, list: List<Box>, skip: Int): Int {
    val horiz = dir == Dir.LEFT || dir == Dir.RIGHT
    var best = -1; var bestScore = Float.MAX_VALUE
    var loose = -1; var looseScore = Float.MAX_VALUE
    for ((i, r) in list.withIndex()) {
        if (i == skip) continue
        val main: Float; val cross: Float
        when (dir) {
            Dir.LEFT -> { main = cur.cx - r.cx; cross = abs(r.cy - cur.cy) }
            Dir.RIGHT -> { main = r.cx - cur.cx; cross = abs(r.cy - cur.cy) }
            Dir.UP -> { main = cur.cy - r.cy; cross = abs(r.cx - cur.cx) }
            Dir.DOWN -> { main = r.cy - cur.cy; cross = abs(r.cx - cur.cx) }
        }
        if (main < 8) continue
        val inBeam = if (horiz) r.y < cur.y + cur.h && r.y + r.h > cur.y else r.x < cur.x + cur.w && r.x + r.w > cur.x
        val beyond = when (dir) {
            Dir.RIGHT -> r.x >= cur.x + cur.w - 8
            Dir.LEFT -> r.x + r.w <= cur.x + 8
            Dir.DOWN -> r.y >= cur.y + cur.h - 8
            Dir.UP -> r.y + r.h <= cur.y + 8
        }
        if (!inBeam && !beyond) continue
        val score = main + cross * 2.2f + if (inBeam) 0f else 4000f
        if (!inBeam && cross * 2 > main) {
            if (!horiz && score < looseScore) { looseScore = score; loose = i }
            continue
        }
        if (score < bestScore) { bestScore = score; best = i }
    }
    return if (best >= 0) best else loose
}
