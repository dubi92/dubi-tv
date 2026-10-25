package tv.dubi.launcher

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface

/**
 * Every bitmap the launcher draws. All are tiny pixel-art sheets (the 240x135 backdrops are 3-4 KB PNGs)
 * decoded at 1x and scaled up by the GPU with nearest-neighbour sampling; nothing is pre-scaled.
 */
class Art(private val assets: AssetManager) {
    private val opts = BitmapFactory.Options().apply { inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888 }

    fun load(path: String): Bitmap = assets.open(path).use { BitmapFactory.decodeStream(it, null, opts)!! }

    val font: Typeface = Typeface.createFromAsset(assets, "fonts/VT323-Regular.ttf")

    val logo = load("dubi_a.png")
    val avatar = load("avatar/avatar-strip.png")
    val bootWave = load("avatar/boot-wave.png")
    val glint = load("danang/glint.png")
    val boat = load("danang/boat.png")
    val cloud = load("danang/cloud_drift.png")
    val fire by lazy { load("danang/fire.png") }
    val spray by lazy { load("danang/spray.png") }

    private val backdrops = HashMap<String, Bitmap>()

    /** Night [light] cycles gold, blue, green, rainbow (0..3). Only the bitmaps actually shown get decoded. */
    fun backdrop(tod: Tod, light: Int): Bitmap {
        val name = when (tod) {
            Tod.DAY -> "dn_day_nb"
            Tod.DUSK -> "dn_dusk_nb"
            Tod.NIGHT -> "dn_night_nb" + LIGHTS[light]
        }
        return backdrops.getOrPut(name) { load("danang/$name.png") }
    }

    /** Drops backdrops other than [keep] (time of day moved on). */
    fun trimBackdrops(keep: Tod) {
        val prefix = when (keep) { Tod.DAY -> "dn_day"; Tod.DUSK -> "dn_dusk"; Tod.NIGHT -> "dn_night" }
        backdrops.keys.retainAll { it.startsWith(prefix) }
    }

    private val brands = HashMap<String, Bitmap>()
    fun brand(id: String): Bitmap = brands.getOrPut(id) { load("brands/br_$id.png") }

    val input = sprite(SPR_INPUT)
    val wifi = sprite(SPR_WIFI)
    val gear = sprite(SPR_GEAR)
    val arrow = sprite(SPR_ARROW)
    val arrowR = sprite(SPR_ARROWR)

    companion object {
        private val LIGHTS = arrayOf("", "_blue", "_green", "_rainbow")

        // The mockup's 12x12 UI sprites (b-pixel.html SPR) and their palette letters.
        private val SPR_PAL = mapOf('w' to Pal.W, 'k' to Pal.K, 'y' to Pal.Y, 's' to Pal.S)
        private val SPR_INPUT = arrayOf("............", "ssssssssssss", "s..........s", "s..........s", "s..........s", "s..........s", "ssssssssssss", "....ssss....", "..ssssssss..", "............", "............", "............")
        private val SPR_WIFI = arrayOf("............", "..ssssssss..", ".s........s.", "s..ssssss..s", "..s......s..", "....ssss....", "...s....s...", ".....ss.....", ".....ss.....", "............", "............", "............")
        private val SPR_GEAR = arrayOf(".....ww.....", "..ww.ww.ww..", "..wwwwwwww..", "...ww..ww...", "wwww....wwww", "wwww....wwww", "...ww..ww...", "..wwwwwwww..", "..ww.ww.ww..", ".....ww.....", "............", "............")
        private val SPR_ARROW = arrayOf("yyyyyyyyyy", "kyyyyyyyyk", ".kyyyyyyk.", "..kyyyyk..", "...kyyk...", "....kk....", "..........")
        private val SPR_ARROWR = arrayOf("yk.....", "yyk....", "yyyk...", "yyyyk..", "yyyyyk.", "yyyyk..", "yyyk...", "yyk....", "yk.....", ".......")

        fun sprite(rows: Array<String>): Bitmap {
            val w = rows[0].length; val h = rows.size
            val px = IntArray(w * h) { SPR_PAL[rows[it / w][it % w]] ?: 0 }
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        }
    }
}
