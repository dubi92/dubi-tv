package tv.dubi.launcher

/**
 * One draw pass. Everything on screen moves in CSS-style steps, so instead of running at 60 fps each
 * animation reports when its next step lands and the view sleeps until the earliest one. When nothing
 * is moving (5 s after the last key) no frame is scheduled at all.
 */
class Frame {
    var now = 0L
    var wake = Long.MAX_VALUE
        private set

    fun begin(t: Long) { now = t; wake = Long.MAX_VALUE }

    /** Ask for another frame at [t] (ignored when it is not in the future). */
    fun at(t: Long) { if (t > now && t < wake) wake = t }

    /**
     * Progress 0..1 of a transition of [dur] ms in [n] steps started at [start], like CSS `steps(n)`
     * (jump-end): 0 until the first step, 1 once [dur] has passed.
     */
    fun steps(start: Long, dur: Long, n: Int): Float {
        val t = now - start
        if (t >= dur) return 1f
        if (t < 0) { at(start); return 0f }
        val k = t * n / dur
        at(start + ((k + 1) * dur + n - 1) / n)
        return k.toFloat() / n
    }

    /** Index into [durations] for a loop whose clock reads [t] ms; a [running] clock schedules its next change. */
    fun loop(t: Long, durations: LongArray, running: Boolean = true): Int {
        val r = t % durations.sum()
        var acc = 0L
        for (i in durations.indices) {
            acc += durations[i]
            if (r < acc) { if (running) at(now + acc - r); return i }
        }
        return 0
    }

    /** Stepped position 0..1 of an endless [dur] ms sweep in [n] steps on a loop clock reading [t]. */
    fun sweep(t: Long, dur: Long, n: Int, running: Boolean): Float {
        val r = t % dur
        val k = r * n / dur
        if (running) at(now + ((k + 1) * dur + n - 1) / n - r)
        return k.toFloat() / n
    }

    /** Frame index of a one-shot sequence started at [start]; holds the last frame when done. */
    fun once(start: Long, durations: LongArray): Int {
        var acc = start
        for (i in durations.indices) {
            acc += durations[i]
            if (now < acc) { at(acc); return i }
        }
        return durations.size - 1
    }
}

/** [Frame.steps] without scheduling, for positions read outside a draw ([t] = ms since the transition began). */
fun stepped(t: Long, dur: Long, n: Int): Float = when {
    t >= dur -> 1f
    t < 0 -> 0f
    else -> (t * n / dur).toFloat() / n
}

/** Integer linear step between two stage positions. */
fun lerp(a: Int, b: Int, p: Float): Int = a + Math.round((b - a) * p)
