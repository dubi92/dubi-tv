package tv.dubi.launcher

/** The fixed PICO-8 16-colour UI palette from the mockup's `:root` variables (opaque ARGB). */
object Pal {
    const val K = 0xff000000.toInt()
    const val N = 0xff1d2b53.toInt()
    const val PU = 0xff7e2553.toInt()
    const val GD = 0xff008751.toInt()
    const val BR = 0xffab5236.toInt()
    const val D = 0xff5f574f.toInt()
    const val S = 0xffc2c3c7.toInt()
    const val W = 0xfffff1e8.toInt()
    const val R = 0xffff004d.toInt()
    const val O = 0xffffa300.toInt()
    const val Y = 0xffffec27.toInt()
    const val G = 0xff00e436.toInt()
    const val B = 0xff29adff.toInt()
    const val L = 0xff83769c.toInt()
    const val P = 0xffff77a8.toInt()
    const val PE = 0xffffccaa.toInt()

    /** PICO-8 order, as `pixelize.js` indexes it. */
    val PICO = intArrayOf(K, N, PU, GD, BR, D, S, W, R, O, Y, G, B, L, P, PE)

    /** Dark outline the auto-pixelised tiles get in their reserved border. */
    const val OUTLINE = 0xff1d2340.toInt()
}
