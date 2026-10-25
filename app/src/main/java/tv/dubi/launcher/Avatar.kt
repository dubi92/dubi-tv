package tv.dubi.launcher

import android.graphics.Canvas
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The captain's chibi in mode B2: he stands on the promenade below the focused cartridge and walks
 * between cartridges. One 3072x96 strip (32 cells of 96x96) drawn at 4x; a frame is a source
 * rectangle, never a separate bitmap. Every act ends on its own clock and falls back to idle, so the
 * state is a pure function of time and nothing needs a timer.
 */
class Avatar(private val ink: Ink, private val art: Art) {
    enum class Act { IDLE, WALK, LOOK, BUMP, WAVE, DROP, TRAVEL }

    private var act = Act.IDLE
    private var start = 0L
    private var end = Long.MAX_VALUE

    var placed = false; private set
    private var fromX = 0
    var toX = 0; private set
    private var moveAt = 0L
    private var moveDur = 1L
    private var moveSteps = 1
    private var left = false

    private fun set(a: Act, now: Long, dur: Long = Long.MAX_VALUE) {
        act = a; start = now; end = if (dur == Long.MAX_VALUE) dur else now + dur
    }

    private fun current(now: Long) = if (now >= end) Act.IDLE else act
    private fun idleSince(now: Long) = if (now >= end) end else start

    fun isIdle(now: Long) = current(now) == Act.IDLE
    fun isLook(now: Long) = current(now) == Act.LOOK

    fun place(x: Int) { placed = true; fromX = x; toX = x; moveDur = 1; moveAt = 0 }

    fun unplace() { placed = false }

    /** 480 ms per cartridge of distance, as a stepped translate with 5 steps per cartridge. */
    fun walkTo(x: Int, now: Long, stride: Int) {
        val cur = x(now)
        val tiles = max(1, (abs(x - cur).toFloat() / stride).roundToInt())
        if (x != toX) left = x < toX
        fromX = cur; toX = x; moveAt = now; moveDur = tiles * 480L; moveSteps = tiles * 5
        set(Act.WALK, now, moveDur)
    }

    fun look(now: Long) = set(Act.LOOK, now)
    fun idle(now: Long) = set(Act.IDLE, now)
    fun bump(now: Long) = set(Act.BUMP, now, 200)
    fun wave(now: Long) = set(Act.WAVE, now, 900)
    fun dropWave(now: Long) = set(Act.DROP, now, 1200)
    fun travel(now: Long) = set(Act.TRAVEL, now, 1950)

    private fun x(now: Long): Int {
        val t = now - moveAt
        if (t >= moveDur) return toX
        return lerp(fromX, toX, (t * moveSteps / moveDur).toFloat() / moveSteps)
    }

    /** Draws the 384 px box with its top at [y]; [calm] holds the idle on frame 0. */
    fun draw(c: Canvas, f: Frame, y: Float, calm: Boolean) {
        val now = f.now
        val x = if (now - moveAt >= moveDur) toX else { f.steps(moveAt, moveDur, moveSteps); x(now) }
        var dy = 0f
        var shake = 0f
        val frame = when (current(now)) {
            Act.IDLE -> {
                val t = now - idleSince(now)
                if (calm || t >= IDLE_CYCLES * IDLE.sum()) 0 else f.loop(t, IDLE)
            }
            Act.WALK -> WALK0 + f.loop(now - start, WALK)
            Act.LOOK -> WAVE0 + 1
            Act.BUMP -> {
                val p = f.steps(start, 180, 3)
                shake = if (p == 0f || p == 1f) 0f else if (p < 0.5f) -8f else 8f
                HOP0
            }
            Act.WAVE -> WAVE0 + f.once(start, WAVE)
            Act.DROP -> {
                dy = -640f * (1 - f.steps(start, 280, 7))
                WAVE0 + f.once(start, WAVE)
            }
            Act.TRAVEL -> TRAVEL0 + f.once(start, TRAVEL)
        }
        if (end != Long.MAX_VALUE) f.at(end)
        cell(c, frame, x.toFloat(), y + dy, shake, left)
    }

    /** The screensaver pose: sleeping on the promenade, 500 ms per frame since [since]. */
    fun drawSleep(c: Canvas, f: Frame, x: Float, y: Float, since: Long) =
        cell(c, SLEEP0 + f.loop(f.now - since, SLEEP), x, y, 0f, false)

    private fun cell(c: Canvas, frame: Int, x: Float, y: Float, shake: Float, flip: Boolean) {
        if (flip) {
            c.save()
            c.scale(-1f, 1f, x + BOX / 2f, 0f)
        }
        ink.cell(c, art.avatar, frame * CELL, 0, CELL, CELL, x + shake, y, SCALE)
        if (flip) c.restore()
    }

    companion object {
        const val CELL = 96
        const val SCALE = 4
        const val BOX = CELL * SCALE

        // Cell indices in avatar-strip.png (avatar-strip.json).
        private const val WAVE0 = 4
        private const val WALK0 = 10
        private const val HOP0 = 15
        private const val SLEEP0 = 23
        private const val TRAVEL0 = 27

        private val IDLE = longArrayOf(700, 500, 700, 140)
        private const val IDLE_CYCLES = 6
        private val WAVE = LongArray(6) { 150 }
        private val WALK = LongArray(5) { 120 }
        private val SLEEP = LongArray(4) { 500 }
        private val TRAVEL = longArrayOf(150, 150, 150, 600, 900)
    }
}
