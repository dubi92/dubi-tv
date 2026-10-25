package tv.dubi.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface

/** Drawing primitives in stage pixels (1920x1080): hard-edged boxes, whole-number bitmap scales, VT323 text. */
class Ink(font: Typeface) {
    private val fill = Paint()
    /** Nearest-neighbour sampling: pixel art stays crisp at 4x, 8x, 16x. */
    private val pix = Paint().apply { isFilterBitmap = false; isDither = false }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = font }
    private val src = Rect()
    private val dst = RectF()

    fun rect(c: Canvas, x: Float, y: Float, w: Float, h: Float, color: Int) {
        fill.color = color
        c.drawRect(x, y, x + w, y + h, fill)
    }

    /**
     * The mockup's `.px` box: a square fill plus four 8 px hard shadows, which leaves the corners notched.
     * No blur, no rounded corners.
     */
    fun pxBox(c: Canvas, x: Float, y: Float, w: Float, h: Float, bg: Int, border: Int) {
        rect(c, x, y - 8, w, 8f, border)
        rect(c, x, y + h, w, 8f, border)
        rect(c, x - 8, y, 8f, h, border)
        rect(c, x + w, y, 8f, h, border)
        rect(c, x, y, w, h, bg)
    }

    /** The whole of [b] at an integer [scale] with its top-left at (x, y). */
    fun bitmap(c: Canvas, b: Bitmap, x: Float, y: Float, scale: Int) =
        cell(c, b, 0, 0, b.width, b.height, x, y, scale)

    /** A [w]x[h] cell of a sprite sheet at an integer [scale]. */
    fun cell(c: Canvas, b: Bitmap, sx: Int, sy: Int, w: Int, h: Int, x: Float, y: Float, scale: Int) {
        src.set(sx, sy, sx + w, sy + h)
        dst.set(x, y, x + w * scale, y + h * scale)
        c.drawBitmap(b, src, dst, pix)
    }

    fun stretch(c: Canvas, b: Bitmap, x: Float, y: Float, w: Float, h: Float) {
        dst.set(x, y, x + w, y + h)
        c.drawBitmap(b, null, dst, pix)
    }

    /** VT323 text on [baseline]; [shadow] > 0 adds the mockup's hard black text-shadow offset. */
    fun text(
        c: Canvas, s: String, x: Float, baseline: Float, size: Float, color: Int,
        shadow: Float = 0f, align: Paint.Align = Paint.Align.LEFT,
    ) {
        text.textSize = size
        text.textAlign = align
        if (shadow > 0) {
            text.color = Pal.K
            c.drawText(s, x + shadow, baseline + shadow, text)
        }
        text.color = color
        c.drawText(s, x, baseline, text)
    }

    fun width(s: String, size: Float): Float {
        text.textSize = size
        return text.measureText(s)
    }

    companion object {
        /**
         * Baseline of a CSS line box: VT323's ascent is 0.8 em and descent 0.2 em, so a line box of
         * [lineHeight] em puts the baseline at top + (lineHeight - 1) / 2 em + 0.8 em.
         */
        fun baseline(top: Float, size: Float, lineHeight: Float = 1f) = top + size * ((lineHeight - 1) / 2 + 0.8f)
    }
}
