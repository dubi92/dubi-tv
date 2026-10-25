package tv.dubi.launcher

import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Turns an app icon into a 16x16 launcher tile. Port of the mockup's `pixelize.js`; runs ONCE per app at
 * install/update time and the result is cached, Home never pixelises while drawing.
 *  1. trim transparent margins
 *  2. box-filter (exact area average, premultiplied alpha) into the 14x14 inside of the tile
 *  3. alpha threshold + snap to the PICO-8 palette (redmean nearest colour)
 *  4. 1 px dark outline in the reserved border
 *  5. readability check; a muddy result is replaced by the brand-colour tile with the app's initial
 * Pixels are ARGB ints, non-premultiplied (what Bitmap.getPixels returns).
 */
object Pixelizer {
    const val SIZE = 16
    private const val TRANSPARENT = -1
    private const val EDGE = -2

    class Metrics(
        val colors: Int, val coverage: Double, val speckle: Double,
        val contrast: Double, val aspect: Double, val textHeight: Int,
    )

    /** [tile] is what the launcher shows: the pixelised icon, or the fallback tile when [muddy]. */
    class Result(val tile: IntArray, val muddy: Boolean, val reason: String, val metrics: Metrics?)

    fun pixelize(px: IntArray, w: Int, h: Int, label: String, brand: Int? = null): Result {
        val size = SIZE
        // 1. trim
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
        for (y in 0 until h) for (x in 0 until w) if ((px[y * w + x] ushr 24) > 16) {
            if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
        }
        if (x1 < 0) return Result(fallback(brand ?: Pal.N, label), true, "không có icon", null)
        val bw = x1 - x0 + 1; val bh = y1 - y0 + 1; val inner = size - 2
        val scale = min(inner.toDouble() / bw, inner.toDouble() / bh)
        val tw = max(1, (bw * scale).roundToInt()); val th = max(1, (bh * scale).roundToInt())
        val ox = 1 + (inner - tw) / 2; val oy = 1 + (inner - th) / 2
        // 2. exact area averaging into tw x th cells (premultiplied)
        val acc = DoubleArray(tw * th * 5)
        for (y in y0..y1) for (x in x0..x1) {
            val cx = min(tw - 1, (x - x0) * tw / bw); val cy = min(th - 1, (y - y0) * th / bh)
            val c = px[y * w + x]; val a = (c ushr 24) / 255.0; val k = (cy * tw + cx) * 5
            acc[k] += ((c shr 16) and 255) * a; acc[k + 1] += ((c shr 8) and 255) * a; acc[k + 2] += (c and 255) * a
            acc[k + 3] += a; acc[k + 4] += 1.0
        }
        // 3. threshold + palette snap; grid holds a palette index, TRANSPARENT or EDGE
        val grid = IntArray(size * size) { TRANSPARENT }
        val use = IntArray(16)
        for (cy in 0 until th) for (cx in 0 until tw) {
            val q = (cy * tw + cx) * 5; val al = acc[q + 3] / acc[q + 4]
            if (al < 0.5) continue
            val p = nearest(acc[q] / acc[q + 3], acc[q + 1] / acc[q + 3], acc[q + 2] / acc[q + 3])
            grid[(oy + cy) * size + ox + cx] = p; use[p]++
        }
        // 4. outline
        for (y in 0 until size) for (x in 0 until size) {
            if (grid[y * size + x] != TRANSPARENT) continue
            if (NB.any { (dx, dy) -> at(grid, x + dx, y + dy) >= 0 }) grid[y * size + x] = EDGE
        }
        // 5. readability metrics
        var opaque = 0; var lonely = 0
        for (y in 0 until size) for (x in 0 until size) {
            val v = grid[y * size + x]; if (v < 0) continue
            opaque++
            if (NB.none { (dx, dy) -> at(grid, x + dx, y + dy) == v }) lonely++
        }
        val ranked = rank(use)
        if (ranked.isEmpty()) return Result(fallback(brand ?: Pal.N, label), true, "hình quá nhỏ", null)
        // contrast = dominant colour vs the strongest secondary colour covering >= 3% of the shape
        // (with a single colour the outline is the figure)
        val c1 = Pal.PICO[ranked[0]]
        var best = if (ranked.size > 1) 1.0 else contrast(c1, Pal.OUTLINE)
        for (i in 1 until ranked.size) {
            val k = ranked[i]
            if (use[k].toDouble() / opaque >= 0.03) best = max(best, contrast(c1, Pal.PICO[k]))
        }
        val m = Metrics(
            colors = ranked.size, coverage = opaque.toDouble() / (size * size),
            speckle = lonely.toDouble() / opaque, contrast = best,
            aspect = bw.toDouble() / bh, textHeight = th,
        )
        val reason = readability(m)
        if (reason.isEmpty()) return Result(draw(grid), false, "", m)
        return Result(fallback(brand ?: pickBrand(ranked), label), true, reason, m)
    }

    /** Empty when the tile reads well; otherwise why the fallback tile is used (HANDOFF section 4). */
    fun readability(m: Metrics): String = when {
        m.aspect > 2 && m.textHeight < 9 -> "chữ quá nhỏ (logo dạng chữ cao ${m.textHeight} px)"
        m.speckle > 0.18 -> "lấm tấm, ${(m.speckle * 100).roundToInt()}% điểm lẻ"
        m.contrast < 1.6 -> "thiếu tương phản"
        m.coverage < 0.12 -> "hình quá nhỏ"
        else -> ""
    }

    /** Brand colour plus the app's initial in the 5x7 font at the largest whole scale that fits. */
    fun fallback(color: Int, label: String, size: Int = SIZE): IntArray {
        val grid = IntArray(size * size) { TRANSPARENT }
        val bi = Pal.PICO.indexOf(color or 0xff000000.toInt()).let {
            if (it >= 0) it else nearest(((color shr 16) and 255).toDouble(), ((color shr 8) and 255).toDouble(), (color and 255).toDouble())
        }
        for (y in 1 until size - 1) for (x in 1 until size - 1) grid[y * size + x] = bi
        for (p in listOf(1 to 1, size - 2 to 1, 1 to size - 2, size - 2 to size - 2)) grid[p.second * size + p.first] = EDGE
        for (y in 0 until size) for (x in 0 until size) if (x == 0 || y == 0 || x == size - 1 || y == size - 1) grid[y * size + x] = EDGE
        val ink = if (contrast(Pal.PICO[bi], Pal.W) >= contrast(Pal.PICO[bi], Pal.K)) 7 else 0
        val glyph = FONT[initial(label)]
        if (glyph != null) {
            val s = max(1, (size - 2) / 7); val lx = (size - 5 * s) / 2; val ly = (size - 7 * s) / 2
            for (r in 0 until 7) for (c in 0 until 5) if (glyph[r * 5 + c] == '1')
                for (dy in 0 until s) for (dx in 0 until s) grid[(ly + r * s + dy) * size + lx + c * s + dx] = ink
        }
        return draw(grid, size)
    }

    /** Box colour for a cached tile: its most-used saturated palette colour, else its most-used one. */
    fun brandOf(tile: IntArray): Int {
        val use = IntArray(16)
        for (c in tile) { val i = Pal.PICO.indexOf(c); if (i >= 0 && (c ushr 24) == 255) use[i]++ }
        val ranked = rank(use)
        return if (ranked.isEmpty()) Pal.N else pickBrand(ranked)
    }

    /** Upper-case first letter with Vietnamese diacritics removed ("Ảnh" -> 'A', "Đài" -> 'D'). */
    fun initial(label: String): Char {
        val ch = label.trim().firstOrNull() ?: return '?'
        if (ch == 'Đ' || ch == 'đ') return 'D'
        return Normalizer.normalize(ch.toString(), Normalizer.Form.NFD)[0].uppercaseChar()
    }

    fun nearest(r: Double, g: Double, b: Double): Int {
        var best = 0; var bd = Double.MAX_VALUE
        for (i in Pal.PICO.indices) {
            val p = Pal.PICO[i]; val pr = (p shr 16) and 255; val pg = (p shr 8) and 255; val pb = p and 255
            val rm = (r + pr) / 2; val dr = r - pr; val dg = g - pg; val db = b - pb
            val d = (2 + rm / 256) * dr * dr + 4 * dg * dg + (2 + (255 - rm) / 256) * db * db
            if (d < bd) { bd = d; best = i }
        }
        return best
    }

    fun contrast(a: Int, b: Int): Double {
        val x = lum(a); val y = lum(b)
        return (max(x, y) + 0.05) / (min(x, y) + 0.05)
    }

    private fun lum(c: Int): Double {
        fun f(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * f((c shr 16) and 255) + 0.7152 * f((c shr 8) and 255) + 0.0722 * f(c and 255)
    }

    private fun pickBrand(ranked: IntArray): Int {
        for (i in ranked) {
            val c = Pal.PICO[i]; val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            if (max(r, max(g, b)) - min(r, min(g, b)) > 80) return c
        }
        return Pal.PICO[ranked[0]]
    }

    /** Palette indices in use, most-used first; ties keep palette order (JS's stable sort). */
    private fun rank(use: IntArray): IntArray =
        use.indices.filter { use[it] > 0 }.sortedByDescending { use[it] }.toIntArray()

    private val NB = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

    private fun at(grid: IntArray, x: Int, y: Int): Int =
        if (x < 0 || y < 0 || x >= SIZE || y >= SIZE) TRANSPARENT else grid[y * SIZE + x]

    private fun draw(grid: IntArray, size: Int = SIZE): IntArray = IntArray(size * size) {
        when (val v = grid[it]) { TRANSPARENT -> 0; EDGE -> Pal.OUTLINE; else -> Pal.PICO[v] }
    }

    // 5x7 capitals from pixelize.js, plus J, X and digits so no app falls back to a wrong letter.
    private val FONT = mapOf(
        'A' to "01110100011000111111100011000110001", 'B' to "11110100011000111110100011000111110",
        'C' to "01110100011000010000100001000101110", 'D' to "11110100011000110001100011000111110",
        'E' to "11111100001000011110100001000011111", 'F' to "11111100001000011110100001000010000",
        'G' to "01110100011000010111100011000101111", 'H' to "10001100011000111111100011000110001",
        'I' to "01110001000010000100001000010001110", 'J' to "00111000100001000010000101001001100",
        'K' to "10001100101010011000101001001010001", 'L' to "10000100001000010000100001000011111",
        'M' to "10001110111010110101100011000110001", 'N' to "10001110011010110011100011000110001",
        'O' to "01110100011000110001100011000101110", 'P' to "11110100011000111110100001000010000",
        'Q' to "01110100011000110001101011001001101", 'R' to "11110100011000111110101001001010001",
        'S' to "01111100001000001110000010000111110", 'T' to "11111001000010000100001000010000100",
        'U' to "10001100011000110001100011000101110", 'V' to "10001100011000110001100010101000100",
        'W' to "10001100011000110101101011010101010", 'X' to "10001100010101000100010101000110001",
        'Y' to "10001100010101000100001000010000100", 'Z' to "11111000010001000100010001000011111",
        '0' to "01110100011001110101110011000101110", '1' to "00100011000010000100001000010001110",
        '2' to "01110100010000100010001000100011111", '3' to "11111000100010000010000011000101110",
        '4' to "00010001100101010010111110001000010", '5' to "11111100001111000001000011000101110",
        '6' to "00110010001000011110100011000101110", '7' to "11111000010001000100010000100001000",
        '8' to "01110100011000101110100011000101110", '9' to "01110100011000101111000010001001100",
    )
}
