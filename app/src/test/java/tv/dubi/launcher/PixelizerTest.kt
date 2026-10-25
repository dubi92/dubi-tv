package tv.dubi.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PixelizerTest {
    private val n = Pixelizer.SIZE

    /** A [w]x[h] icon painted by [paint] (returns ARGB or 0 for transparent). */
    private fun icon(w: Int, h: Int, paint: (Int, Int) -> Int) = IntArray(w * h) { paint(it % w, it / w) }

    @Test fun boldTwoColourMarkPixelisesAutomatically() {
        // red disc with a white square in the middle, on transparency
        val px = icon(64, 64) { x, y ->
            val dx = x - 31.5; val dy = y - 31.5
            when {
                x in 24..39 && y in 24..39 -> 0xffffffff.toInt()
                dx * dx + dy * dy < 30.0 * 30.0 -> 0xffff0000.toInt()
                else -> 0
            }
        }
        val r = Pixelizer.pixelize(px, 64, 64, "Test")
        assertFalse(r.reason, r.muddy)
        assertEquals(n * n, r.tile.size)
        // every pixel is transparent, the outline colour, or a palette colour
        for (c in r.tile) assertTrue(c == 0 || c == Pal.OUTLINE || c in Pal.PICO)
        assertEquals(Pal.OUTLINE, r.tile[0 * n + 7]) // outline above the shape's top edge
    }

    @Test fun thinWordmarkFallsBackToBrandTile() {
        // a 200x20 grey wordmark: aspect 10, text far under 9 px once squeezed into 14 px
        val px = icon(200, 20) { x, _ -> if (x % 7 < 4) 0xff808080.toInt() else 0 }
        val r = Pixelizer.pixelize(px, 200, 20, "FPT Play", Pal.O)
        assertTrue(r.muddy)
        assertTrue(r.reason, r.reason.startsWith("chữ quá nhỏ"))
        assertEquals(Pal.O, r.tile[5 * n + 1]) // brand colour fill next to the border
    }

    @Test fun lowContrastFallsBack() {
        // two near-identical reds: dominant colour vs second colour contrast < 1.6
        val px = icon(32, 32) { x, _ -> if (x < 16) 0xffff0040.toInt() else 0xffff0050.toInt() }
        val m = Pixelizer.Metrics(colors = 2, coverage = 0.8, speckle = 0.0, contrast = 1.2, aspect = 1.0, textHeight = 14)
        assertEquals("thiếu tương phản", Pixelizer.readability(m))
        assertTrue(Pixelizer.pixelize(px, 32, 32, "Red").tile.isNotEmpty())
    }

    @Test fun readabilityRulesMatchHandoff() {
        fun m(aspect: Double = 1.0, text: Int = 14, speckle: Double = 0.0, contrast: Double = 5.0, coverage: Double = 0.5) =
            Pixelizer.Metrics(3, coverage, speckle, contrast, aspect, text)
        assertEquals("", Pixelizer.readability(m()))
        assertTrue(Pixelizer.readability(m(aspect = 2.1, text = 8)).isNotEmpty())
        assertEquals("", Pixelizer.readability(m(aspect = 2.1, text = 9)))
        assertTrue(Pixelizer.readability(m(speckle = 0.19)).isNotEmpty())
        assertTrue(Pixelizer.readability(m(contrast = 1.59)).isNotEmpty())
        assertTrue(Pixelizer.readability(m(coverage = 0.11)).isNotEmpty())
    }

    @Test fun emptyIconUsesFallback() {
        val r = Pixelizer.pixelize(IntArray(16 * 16), 16, 16, "TV360", Pal.BR)
        assertTrue(r.muddy)
        assertEquals(Pal.BR, Pixelizer.brandOf(r.tile))
    }

    @Test fun fallbackTileHasNotchedOutlineAndInitial() {
        val t = Pixelizer.fallback(Pal.R, "YouTube")
        assertEquals(Pal.OUTLINE, t[0])
        assertEquals(Pal.OUTLINE, t[1 * n + 1]) // notched inner corner
        assertEquals(Pal.R, t[1 * n + 2])
        // 'Y' at scale 2 starting (3,1): its top-left stroke pixel is ink; on #ff004d black out-contrasts white
        assertEquals(Pal.K, t[1 * n + 3])
        assertEquals(Pal.W, Pixelizer.fallback(Pal.N, "Y")[1 * n + 3])
    }

    @Test fun initialStripsVietnameseMarks() {
        assertEquals('A', Pixelizer.initial("Ảnh"))
        assertEquals('D', Pixelizer.initial("Đài truyền hình"))
        assertEquals('T', Pixelizer.initial("  trò chơi"))
    }

    @Test fun everyFontGlyphIsFiveBySeven() {
        for (ch in ('A'..'Z') + ('0'..'9')) Pixelizer.fallback(Pal.N, ch.toString()) // throws on a short glyph
    }

    @Test fun nearestSnapsToPico() {
        assertEquals(8, Pixelizer.nearest(255.0, 0.0, 77.0)) // #ff004d
        assertEquals(0, Pixelizer.nearest(10.0, 10.0, 10.0))
        assertEquals(7, Pixelizer.nearest(255.0, 255.0, 255.0))
    }
}
