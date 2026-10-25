package tv.dubi.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.text.Collator
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.Executors

/** One launchable TV app. */
class AppEntry(val pkg: String, val component: ComponentName, val label: String, val updated: Long) {
    /** Hand-drawn brand icon id (`brands/br_<id>.png`) for the five dock favourites, else null. */
    var hand: String? = null
    /** Name on the cartridge / in the grid. */
    var name: String = label
    /** 16x16 auto-pixelised (or fallback) tile; null until pixelised, then drawn from the cache. */
    var tile: Bitmap? = null
    /** Cartridge / icon-box colour. */
    var color: Int = Pal.N

    fun intent(): Intent = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        .setComponent(component)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
}

/**
 * The installed TV apps (leanback launch intents), the dock picked from them, and the icon tiles.
 * Tiles are pixelised once per app version on a background thread and cached as 16x16 PNGs keyed by
 * the package's lastUpdateTime, so a fresh install or update is pixelised exactly once.
 */
class Apps(private val ctx: Context, private val onChange: () -> Unit) {
    var all: List<AppEntry> = emptyList(); private set
    var dock: List<AppEntry> = emptyList(); private set
    /** Grid order: the dock first, then everything else alphabetically. */
    var grid: List<AppEntry> = emptyList(); private set

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "pixelize").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private val dir = File(ctx.filesDir, "tiles").apply { mkdirs() }

    private class Slot(val id: String, val name: String, val color: Int, val pkgs: List<String>, val key: String)

    fun reload() {
        val pm = ctx.packageManager
        val found = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
        val collator = Collator.getInstance(Locale("vi"))
        val list = found.asSequence()
            .filter { it.activityInfo.packageName != ctx.packageName }
            .distinctBy { it.activityInfo.packageName }
            .map {
                val ai = it.activityInfo
                val updated = try { pm.getPackageInfo(ai.packageName, 0).lastUpdateTime } catch (e: PackageManager.NameNotFoundException) { 0L }
                AppEntry(ai.packageName, ComponentName(ai.packageName, ai.name), it.loadLabel(pm).toString().trim(), updated)
            }
            .sortedWith { a, b -> collator.compare(a.label, b.label) }
            .toList()

        val dockList = ArrayList<AppEntry>()
        for (s in SLOTS) {
            val app = match(list, s.pkgs, s.key) ?: continue
            app.hand = s.id; app.name = s.name; app.color = s.color
            dockList += app
        }
        // The family's TV has VTV Go and HDViet rather than VieON and Spotify: they take free slots.
        for (key in SUBSTITUTES) {
            if (dockList.size >= SLOTS.size) break
            val app = match(list, emptyList(), key) ?: continue
            if (app !in dockList) dockList += app
        }
        if (dockList.isEmpty()) dockList += list.take(SLOTS.size)

        for (app in list) if (app.hand == null) loadTile(app)
        all = list
        dock = dockList
        grid = dockList + list.filter { it !in dockList }
        pixelizeMissing()
    }

    private fun match(list: List<AppEntry>, pkgs: List<String>, key: String): AppEntry? =
        list.firstOrNull { it.pkg in pkgs } ?: list.firstOrNull { key(it.label) == key } ?: list.firstOrNull { key(it.label).startsWith(key) }

    private fun tileFile(app: AppEntry) = File(dir, "${app.pkg}@${app.updated}.png")

    private fun loadTile(app: AppEntry) {
        val f = tileFile(app)
        if (!f.exists()) return
        val bmp = BitmapFactory.decodeFile(f.path) ?: return
        app.tile = bmp
        app.color = colorFor(app, bmp)
    }

    private fun colorFor(app: AppEntry, tile: Bitmap): Int {
        KNOWN_COLORS[key(app.label)]?.let { return it }
        val px = IntArray(Pixelizer.SIZE * Pixelizer.SIZE)
        tile.getPixels(px, 0, Pixelizer.SIZE, 0, 0, Pixelizer.SIZE, Pixelizer.SIZE)
        return Pixelizer.brandOf(px)
    }

    private fun pixelizeMissing() {
        val todo = all.filter { it.hand == null && it.tile == null }
        if (todo.isEmpty()) return
        worker.execute {
            for (app in todo) {
                val tile = try { pixelize(app) } catch (e: Exception) { Log.w(TAG, "pixelize ${app.pkg}", e); null } ?: continue
                main.post {
                    app.tile = tile
                    app.color = colorFor(app, tile)
                    onChange()
                }
            }
        }
    }

    /** Runs on the worker thread: icon -> 16x16 tile, cached as PNG (stale versions removed). */
    private fun pixelize(app: AppEntry): Bitmap {
        // A reload while this batch was queued may already have cached this version.
        BitmapFactory.decodeFile(tileFile(app).path)?.let { return it }
        val pm = ctx.packageManager
        val icon = pm.getActivityIcon(app.component)
        val side = icon.intrinsicWidth.coerceIn(48, 256)
        val src = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, side, side)
        icon.draw(Canvas(src))
        val px = IntArray(side * side)
        src.getPixels(px, 0, side, 0, 0, side, side)
        src.recycle()
        val r = Pixelizer.pixelize(px, side, side, app.label, KNOWN_COLORS[key(app.label)])
        if (r.muddy) Log.i(TAG, "${app.pkg}: fallback tile (${r.reason})")
        val tile = Bitmap.createBitmap(r.tile, Pixelizer.SIZE, Pixelizer.SIZE, Bitmap.Config.ARGB_8888)
        dir.listFiles { f -> f.name.startsWith(app.pkg + "@") }?.forEach { it.delete() }
        tileFile(app).outputStream().use { tile.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return tile
    }

    companion object {
        private const val TAG = "DubiApps"

        /** HANDOFF dock: YouTube, Netflix, FPT Play, VieON, Spotify, with their cartridge colours. */
        private val SLOTS = listOf(
            Slot("youtube", "YouTube", Pal.R, listOf("com.google.android.youtube.tv", "com.google.android.youtube.googletv"), "youtube"),
            Slot("netflix", "Netflix", Pal.PU, listOf("com.netflix.ninja"), "netflix"),
            Slot("fptplay", "FPT Play", Pal.O, listOf("net.fptplay.ottbox"), "fptplay"),
            Slot("vieon", "VieON", Pal.GD, listOf("com.vieon.tv"), "vieon"),
            Slot("spotify", "Spotify", Pal.G, listOf("com.spotify.tv.android"), "spotify"),
        )
        private val SUBSTITUTES = listOf("vtvgo", "hdviet")

        /** Brand colours for fallback tiles of apps the mockup knows (its PC map). */
        private val KNOWN_COLORS = mapOf("youtube" to Pal.R, "netflix" to Pal.PU, "fptplay" to Pal.O, "vieon" to Pal.GD, "spotify" to Pal.G, "tv360" to Pal.BR)

        /** "FPT Play" -> "fptplay", "VTV Go" -> "vtvgo": lower case, no diacritics, letters and digits only. */
        fun key(label: String): String =
            Normalizer.normalize(label.replace('Đ', 'D').replace('đ', 'd'), Normalizer.Form.NFD)
                .lowercase(Locale.ROOT).filter { it in 'a'..'z' || it in '0'..'9' }
    }
}
