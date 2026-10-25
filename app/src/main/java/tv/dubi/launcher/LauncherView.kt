package tv.dubi.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import java.util.Calendar
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * The whole launcher on one 1920x1080 stage drawn into a single View: Home, app grid, quick panel,
 * launch overlay, screensaver and boot, as in the approved mockup (`mockups/b-pixel.html` + `core.js`).
 *
 * Cheap by construction (HANDOFF section 5): one static backdrop bitmap, at most 5 stepped sprite loops,
 * every loop pauses 5 s after the last key, while an app is open and on the screensaver, and frames are
 * only drawn when a stepped animation actually changes (see [Frame]).
 */
@SuppressLint("ViewConstructor")
class LauncherView(context: Context, private val apps: Apps, private val host: Host) : View(context) {

    interface Host {
        /** Starts [app]; false when it could not be launched. */
        fun launch(app: AppEntry): Boolean
        /** Starts a system screen (TV input, Wi-Fi or settings). */
        fun open(intent: Intent): Boolean
    }

    enum class St { BOOT, HOME, GRID, PANEL, APP, SAVER }

    private val art = Art(context.assets)
    private val ink = Ink(art.font)
    private val avatar = Avatar(ink, art)
    private val frame = Frame()
    private val h = Handler(Looper.getMainLooper())
    private val vi = Locale("vi")

    private var resumed = false
    private var state = St.HOME
    /** Where the launch overlay and the screensaver return to. */
    private var prevState = St.HOME
    private var panelReturn = St.HOME

    // ---- Home layout: 3 shortcut buttons, then the dock cartridges
    private val buttons = arrayOf("HDMI", "WI-FI", "CÀI ĐẶT")
    private val buttonKinds = arrayOf(PanelKind.INPUT, PanelKind.WIFI, PanelKind.SETTINGS)
    private val buttonIcons = arrayOf(art.input, art.wifi, art.gear)
    private var homeSpots: List<Box> = emptyList()
    private var dock: List<AppEntry> = emptyList()
    private var homeFocus = 3
    /** Whether the Home element currently holds the focus ring (false while the grid or a panel has it). */
    private var homeShown = false
    private var lifted = BooleanArray(0)
    private var liftAt = LongArray(0)
    private var bumpAt = -1L
    /** The focused element's name; [nameText] is what fits in the box. */
    private var nameFull = ""
    private var nameText = ""
    private var hintText = ""
    /** Name box width, fitted to its texts so it stays clear of the dragon's head. */
    private var nameW = 0f
    private var typeAt = 0L
    private var ringFrom = 0
    private var ringTo = 0
    private var ringAt = 0L
    private var ringOn = false
    private var ringOnAt = 0L
    private var ringPlaced = false

    // ---- App grid
    private var gridApps: List<AppEntry> = emptyList()
    private var gridSpots: List<Box> = emptyList()
    private var gridNames: List<List<String>> = emptyList()
    private var gridFocus = 0
    private var gridOpen = false
    private var gridAt = -1000L
    private var gRingFrom = Box(0, 0, 0, 0)
    private var gRingTo = Box(0, 0, 0, 0)
    private var gRingAt = 0L
    private var scrollFrom = 0
    private var scrollTo = 0
    private var scrollAt = 0L

    // ---- Quick panel
    private var panel: Panel? = null
    private var panelOpen = false
    private var panelAt = -1000L
    private var panelFocus = 0
    private var panelScroll = 0
    private var tapIdx = -1
    private var tapAt = 0L
    /** Opened in place of the last panel screen if the launcher is still in front [OPEN_WAIT_MS] later. */
    private var openFallback: Intent? = null

    // ---- Launch overlay
    private var launchApp: AppEntry? = null
    private var launchBox = RectF()
    private var launchOpenAt = -1L
    private var launchCloseAt = -1L
    private var launchColor = 0
    private var launchStarted = false

    // ---- Screensaver, boot, backdrop
    private var saverAt = 0L
    private var driftFrom = intArrayOf(0, 0)
    private var driftTo = intArrayOf(0, 0)
    private var driftAt = 0L
    private var bootAt = -10_000L
    private var showAt = -10_000L
    private var lastShow = -SHOW_GAP
    private var todOverride: Tod? = null
    private var tod = Tod.DAY
    private var light = 0
    private var timeText = "--:--"
    private var dateText = ""

    // ---- Loop clock: advances only while the backdrop loops run (not calm, no app, no screensaver)
    private var loopAcc = 0L
    private var loopFrom = 0L
    private var loopOn = false
    private var calmAt = 0L

    private val stageRect = RectF(0f, 0f, STAGE_W, STAGE_H)

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setApps()
        readClock()
    }

    // ================================================================== lifecycle (from MainActivity)

    fun onResumed(fresh: Boolean) {
        resumed = true
        val now = now()
        activity(now)
        resetIdle()
        tick.run()
        scheduleLights()
        if (fresh) boot()
        else if (state == St.APP) closeApp()
        invalidate()
    }

    fun onPaused() {
        resumed = false
        syncLoop(now())
        for (r in arrayOf(saverR, travelR, tick, lightsR, driftR, frameR, fallbackR)) h.removeCallbacks(r)
    }

    /** HOME pressed while the launcher is already in front: back to Home from anywhere. */
    fun onHomeIntent() {
        when (state) {
            St.BOOT, St.HOME -> {}
            St.APP -> { prevState = St.HOME; closeApp() }
            else -> goHome()
        }
        invalidate()
    }

    /** The installed apps changed: rebuild the dock and the grid. */
    fun setApps() {
        dock = apps.dock
        gridApps = apps.grid
        layoutHome()
        layoutGrid()
        invalidate()
    }

    // ================================================================== layout

    private fun layoutHome() {
        val spots = ArrayList<Box>()
        var x = 96 + 44 * 8 + 16 + 40
        for (b in buttons) {
            val w = (24 + 48 + 16 + ink.width(b, 40f) + 24).toInt()
            spots += Box(x, 92, w, 64)
            x += w + 40
        }
        val n = dock.size
        val start = (1920 - (n * 224 + (n - 1) * 56)) / 2
        for (i in 0 until n) spots += Box(start + i * 280, 576, 224, 256)
        homeSpots = spots
        lifted = BooleanArray(n)
        liftAt = LongArray(n) { -1000L }
        if (homeFocus >= spots.size) homeFocus = if (n > 0) 3 else 0
        if (homeShown && homeFocus >= 3) lifted[homeFocus - 3] = true
        // cartridges may have moved: the arrow and the avatar jump to the focused one
        if (homeFocus >= 3) {
            val x = spots[homeFocus].x
            ringFrom = x; ringTo = x; ringAt = 0
            if (avatar.placed) avatar.place(avatarX(homeFocus))
        } else ringPlaced = false
    }

    private fun layoutGrid() {
        gridSpots = gridApps.indices.map { Box(216 + (it % 4) * 378, 264 + (it / 4) * 192, 354, 168) }
        gridNames = gridApps.map { wrap(it.name, 130f, 40f, 3) }
        if (gridFocus >= gridApps.size) gridFocus = 0
    }

    /** Greedy word wrap into at most [maxLines] lines of [width] px; the last line is ellipsised. */
    private fun wrap(s: String, width: Float, size: Float, maxLines: Int): List<String> {
        val lines = ArrayList<String>()
        var line = ""
        for (word in s.split(' ').filter { it.isNotEmpty() }) {
            val tryLine = if (line.isEmpty()) word else "$line $word"
            if (ink.width(tryLine, size) <= width) { line = tryLine; continue }
            if (line.isNotEmpty()) lines += line
            line = word
            while (ink.width(line, size) > width && line.length > 1) {
                var k = line.length - 1
                while (k > 1 && ink.width(line.substring(0, k), size) > width) k--
                lines += line.substring(0, k)
                line = line.substring(k)
            }
        }
        if (line.isNotEmpty()) lines += line
        if (lines.size <= maxLines) return lines
        var last = lines[maxLines - 1]
        while (last.isNotEmpty() && ink.width("$last…", size) > width) last = last.dropLast(1)
        return lines.take(maxLines - 1) + "$last…"
    }

    private fun avatarX(spot: Int): Int {
        val b = homeSpots[spot]
        return b.x + b.w / 2 - Avatar.BOX / 2 + 72
    }

    // ================================================================== input

    /** Called by the Activity for every key; true when the launcher consumed it (both its down and up). */
    fun handleKey(e: KeyEvent): Boolean = when (e.action) {
        KeyEvent.ACTION_DOWN -> press(e.keyCode)
        KeyEvent.ACTION_UP -> e.keyCode in OWN_KEYS || e.keyCode in DEBUG_KEYS
        else -> false
    }

    private fun press(code: Int): Boolean {
        val now = now()
        val dir = when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> Dir.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> Dir.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> Dir.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> Dir.RIGHT
            else -> null
        }
        val back = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE
        val ok = dir == null && !back && code in OWN_KEYS
        // Keyboard-only debug keys, as in the mockup: s screensaver, r boot, t time of day, f dragon show, g travel.
        val debug = code in DEBUG_KEYS
        activity(now)
        resetIdle()
        if (dir == null && !ok && !back && !debug) {
            // Volume, mute, input... still wake the screensaver but go on to the system.
            if (state == St.SAVER) { wake(); invalidate() }
            return false
        }
        travelSoon()
        when {
            code == KeyEvent.KEYCODE_R -> boot()
            state == St.BOOT -> {}
            state == St.SAVER -> wake()
            code == KeyEvent.KEYCODE_S -> saver()
            else -> {
                if (code == KeyEvent.KEYCODE_T) cycleTod()
                if (code == KeyEvent.KEYCODE_F) show(now)
                if (code == KeyEvent.KEYCODE_G && state == St.HOME) avatar.travel(now)
                when {
                    state == St.APP -> if (back) closeApp()
                    dir != null -> move(dir, now)
                    ok -> enter(now)
                    back -> when (state) {
                        St.GRID -> goHome()
                        St.PANEL -> closePanel()
                        else -> {} // Home swallows Back: a launcher has nowhere to go back to.
                    }
                }
            }
        }
        invalidate()
        return true
    }

    private fun move(dir: Dir, now: Long) {
        when (state) {
            St.HOME -> {
                if (homeSpots.isEmpty()) return
                val i = nearest(homeSpots[homeFocus], dir, homeSpots, homeFocus)
                if (i >= 0) return focusHome(i, now)
                if (dir == Dir.DOWN) return openGrid(now)
                avatar.bump(now)
                if (homeFocus >= 3) bumpAt = now
            }
            St.GRID -> {
                if (gridSpots.isEmpty()) { if (dir == Dir.UP) goHome(); return }
                val i = nearest(gridSpots[gridFocus], dir, gridSpots, gridFocus)
                if (i >= 0) focusGrid(i, now) else if (dir == Dir.UP) goHome()
            }
            St.PANEL -> {
                val rows = panel?.rows ?: return
                val i = panelFocus + when (dir) { Dir.UP -> -1; Dir.DOWN -> 1; else -> 0 }
                if (i in rows.indices && i != panelFocus) focusPanel(i)
            }
            else -> {}
        }
    }

    private fun enter(now: Long) {
        when (state) {
            St.HOME -> {
                if (homeFocus < 3) {
                    val kind = buttonKinds[homeFocus]
                    if (kind == PanelKind.SETTINGS && Panels.settingsIntent(context)?.let { host.open(it) } == true) return
                    return openPanel(kind, now)
                }
                val b = homeSpots[homeFocus]
                launch(dock[homeFocus - 3], RectF(b.x.toFloat(), b.y - 24f, b.x + 224f, b.y + 232f), now)
            }
            St.GRID -> {
                if (gridApps.isEmpty()) return
                val b = gridSpots[gridFocus]
                val y = b.y - scrollTo
                launch(gridApps[gridFocus], RectF(b.x.toFloat(), y.toFloat(), (b.x + b.w).toFloat(), (y + b.h).toFloat()), now)
            }
            St.PANEL -> {
                val p = panel ?: return
                val row = p.rows.getOrNull(panelFocus) ?: return
                tapIdx = panelFocus; tapAt = now
                val intent = row.intent ?: return
                // OK flashes the row yellow for 220 ms, then the system screen opens.
                h.postDelayed({
                    if (state == St.PANEL && panel === p) {
                        Panels.remember(context, row)
                        open(intent, row.fallback)
                    }
                }, TAP_MS)
            }
            else -> {}
        }
    }

    /** Starts a system screen; one that fails or never comes to the front gives way to [fallback]. */
    private fun open(intent: Intent, fallback: Intent?) {
        h.removeCallbacks(fallbackR)
        openFallback = fallback
        if (!host.open(intent)) fallbackR.run()
        else if (fallback != null) h.postDelayed(fallbackR, OPEN_WAIT_MS)
    }

    /** Still resumed means the screen never showed (onPaused cancels this). */
    private val fallbackR = Runnable {
        val fb = openFallback ?: return@Runnable
        openFallback = null
        if (resumed && state == St.PANEL) host.open(fb)
    }

    // ================================================================== states (core.js)

    private fun setState(s: St) {
        val now = now()
        val old = state
        state = s
        if (gridOpen != (s == St.GRID)) { gridOpen = s == St.GRID; gridAt = now }
        if (panelOpen != (s == St.PANEL)) { panelOpen = s == St.PANEL; panelAt = now }
        activity(now)
        if (s == St.HOME) {
            travelSoon()
            if (showTime() && now - lastShow > SHOW_GAP) show(now)
            if (old == St.BOOT) avatar.dropWave(now)
        }
        if (s == St.SAVER || s == St.BOOT) h.removeCallbacks(travelR)
    }

    private fun focusHome(i: Int, now: Long) {
        val old = homeFocus
        if (homeShown && old != i && old >= 3) { lifted[old - 3] = false; liftAt[old - 3] = now }
        if ((!homeShown || old != i) && i >= 3) { lifted[i - 3] = true; liftAt[i - 3] = now }
        homeFocus = i
        homeShown = true
        if (i >= 3) {
            val x = homeSpots[i].x
            if (!ringPlaced) { ringFrom = x; ringTo = x; ringAt = 0; ringPlaced = true }
            else if (x != ringTo) { ringFrom = ringX(now); ringTo = x; ringAt = now }
            if (!ringOn) { ringOn = true; ringOnAt = now }
        } else if (ringOn) { ringOn = false; ringOnAt = now }

        val text = if (i < 3) buttons[i] else dock[i - 3].name.uppercase(vi)
        if (text != nameFull) {
            val hint = when {
                i >= 3 -> "Nhấn OK để chơi"
                buttonKinds[i] == PanelKind.SETTINGS -> "Nhấn OK để mở cài đặt"
                else -> "Nhấn OK để mở bảng"
            }
            setName(text, "$hint · ▼ tất cả ứng dụng")
            typeAt = now
        }

        if (i < 3) avatar.look(now)
        else {
            val x = avatarX(i)
            if (!avatar.placed) avatar.place(x)
            else if (x != avatar.toX) avatar.walkTo(x, now, 280)
            else if (avatar.isLook(now)) avatar.idle(now)
        }
    }

    /** A name longer than the box allows is ellipsised; the box ends at [NAME_MAX_W], left of the dragon's head. */
    private fun setName(name: String, hint: String) {
        nameFull = name
        nameText = wrap(name, NAME_MAX_W - 80f, 80f, 1).firstOrNull() ?: ""
        hintText = hint
        nameW = min(NAME_MAX_W, max(ink.width(nameText, 80f), ink.width(hintText, 36f)) + 80f)
    }

    /** The Home element loses the focus ring (the grid or a panel takes it); the cartridge drops back. */
    private fun blurHome(now: Long) {
        if (homeShown && homeFocus >= 3) { lifted[homeFocus - 3] = false; liftAt[homeFocus - 3] = now }
        homeShown = false
    }

    private fun goHome() {
        setState(St.HOME)
        if (homeSpots.isNotEmpty()) focusHome(homeFocus, now())
    }

    private fun openGrid(now: Long) {
        blurHome(now)
        setState(St.GRID)
        scrollFrom = 0; scrollTo = 0; scrollAt = 0
        if (gridSpots.isNotEmpty()) {
            gRingFrom = gridSpots[0]; gRingTo = gridSpots[0]; gRingAt = 0
            focusGrid(0, now)
        }
    }

    private fun focusGrid(i: Int, now: Long) {
        gridFocus = i
        val b = gridSpots[i]
        if (b != gRingTo) { gRingFrom = gRing(now); gRingTo = b; gRingAt = now }
        // keepVisible: scroll the list so the focused item stays 60 px inside the 664 px window
        val view = 664
        val content = 16 + ((gridSpots.size + 3) / 4) * 192 - 24
        val top = b.y - 264 + 8
        var off = scrollTo
        if (top - off < 60) off = top - 60 else if (top + b.h - off > view - 60) off = top + b.h - view + 60
        off = max(0, min(max(0, content - view), off))
        if (off != scrollTo) { scrollFrom = scroll(now); scrollTo = off; scrollAt = now }
    }

    private fun openPanel(kind: PanelKind, now: Long) {
        panelReturn = state
        panel = Panels.build(context, kind)
        blurHome(now)
        setState(St.PANEL)
        panelFocus = 0
        panelScroll = 0
        tapIdx = -1
    }

    private fun focusPanel(i: Int) {
        panelFocus = i
        if (i < panelScroll) panelScroll = i
        if (i >= panelScroll + PANEL_ROWS) panelScroll = i - PANEL_ROWS + 1
    }

    private fun closePanel() {
        val back = panelReturn
        setState(back)
        if (back == St.GRID) focusGrid(gridFocus, now()) else focusHome(homeFocus, now())
    }

    private fun launch(app: AppEntry, from: RectF, now: Long) {
        val fromHome = state == St.HOME
        prevState = state
        setState(St.APP)
        launchApp = app
        launchBox = from
        launchColor = app.color
        launchOpenAt = -1
        launchCloseAt = -1
        launchStarted = false
        // B2: he stays in place and waves at the cartridge while the app opens after a 420 ms lead-in.
        if (fromHome) avatar.wave(now)
        h.removeCallbacks(openR); h.removeCallbacks(readyR)
        h.postDelayed(openR, if (fromHome) LEAD_IN_MS else 0)
    }

    private val openR = Runnable {
        if (state != St.APP) return@Runnable
        launchOpenAt = now()
        h.postDelayed(readyR, READY_MS)
        invalidate()
    }

    private val readyR = Runnable {
        val app = launchApp
        if (state != St.APP || app == null) return@Runnable
        launchStarted = host.launch(app)
        if (!launchStarted) closeApp()
        invalidate()
    }

    private fun closeApp() {
        h.removeCallbacks(openR); h.removeCallbacks(readyR)
        launchCloseAt = if (launchOpenAt >= 0) now() else -1
        launchOpenAt = -1
        launchApp = null
        launchStarted = false
        if (prevState == St.GRID) { setState(St.GRID); focusGrid(gridFocus, now()) } else goHome()
    }

    private fun saver() {
        if (state == St.SAVER || state == St.BOOT) return
        prevState = if (state == St.APP) St.HOME else state
        if (state == St.APP) { h.removeCallbacks(openR); h.removeCallbacks(readyR); launchApp = null; launchOpenAt = -1 }
        setState(St.SAVER)
        saverAt = now()
        driftTo = intArrayOf(0, 0)
        drift()
    }

    /** The big clock drifts to a random spot every 9 s, an 8 s move in 16 steps. */
    private fun drift() {
        h.removeCallbacks(driftR)
        if (state != St.SAVER) return
        val now = now()
        driftFrom = driftPos(now)
        driftTo = intArrayOf(((Math.random() - 0.5) * DRIFT * 2).toInt(), ((Math.random() - 0.5) * DRIFT).toInt())
        driftAt = now
        h.postDelayed(driftR, 9000)
        invalidate()
    }

    private val driftR = Runnable { drift() }

    private fun wake() {
        h.removeCallbacks(driftR)
        if (prevState == St.GRID) { setState(St.GRID); focusGrid(gridFocus, now()) } else goHome()
    }

    private fun boot() {
        h.removeCallbacks(bootEndR)
        launchApp = null; launchOpenAt = -1; launchCloseAt = -1
        homeFocus = if (dock.isNotEmpty()) 3 else 0
        homeShown = false
        lifted.fill(false)
        avatar.unplace()
        ringPlaced = false
        setName(dock.firstOrNull()?.name?.uppercase(vi) ?: "", "Nhấn OK để chơi · ▼ tất cả ứng dụng")
        typeAt = 0
        bootAt = now()
        setState(St.BOOT)
        h.postDelayed(bootEndR, BOOT_MS)
    }

    private val bootEndR = Runnable { if (state == St.BOOT) { goHome(); invalidate() } }

    // ================================================================== timers

    /** Any key or state change: loops resume and the 5 s calm countdown restarts. */
    private fun activity(now: Long) {
        syncLoop(now)
        calmAt = now + CALM_MS
    }

    private fun syncLoop(now: Long) {
        loopAcc = loopTime(now)
        loopFrom = now
        loopOn = resumed && state != St.APP && state != St.SAVER
    }

    private fun loopTime(now: Long) = if (loopOn) loopAcc + max(0L, min(now, calmAt) - loopFrom) else loopAcc

    private fun resetIdle() {
        h.removeCallbacks(saverR)
        if (resumed) h.postDelayed(saverR, SAVER_MS)
    }

    private val saverR = Runnable { saver(); invalidate() }

    private fun travelSoon() {
        h.removeCallbacks(travelR)
        if (resumed) h.postDelayed(travelR, TRAVEL_MS)
    }

    /** Three seconds after the last key on Home he spreads his arms once (travel). */
    private val travelR = Runnable {
        val now = now()
        if (state == St.HOME && avatar.isIdle(now)) { avatar.travel(now); invalidate() }
    }

    /** Clock, time of day and the dragon-show schedule, checked every 30 s on the wall-clock half minute. */
    private val tick: Runnable = object : Runnable {
        override fun run() {
            h.removeCallbacks(this)
            val changed = readClock()
            val now = now()
            if (state == St.HOME && showTime() && now - lastShow > SHOW_GAP) show(now)
            if (changed) invalidate()
            if (resumed) h.postDelayed(this, 30_000 - System.currentTimeMillis() % 30_000 + 50)
        }
    }

    /** Updates the clock texts and the time of day; true when anything on screen changed. */
    private fun readClock(): Boolean {
        val c = Clock.calendar()
        val t = Clock.time(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        val d = Clock.date(c.get(Calendar.DAY_OF_WEEK), c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1)
        val real = Clock.tod(c.get(Calendar.HOUR_OF_DAY))
        val newTod = todOverride ?: real
        val changed = t != timeText || d != dateText || newTod != tod
        timeText = t; dateText = d
        if (newTod != tod) { tod = newTod; art.trimBackdrops(tod); scheduleLights() }
        return changed
    }

    private fun showTime(): Boolean {
        val c = Clock.calendar()
        return Clock.showTime(c.get(Calendar.DAY_OF_WEEK), c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    /** Fire for 3 s, then water for 3 s, from the dragon's mouth. */
    private fun show(now: Long) {
        showAt = now
        lastShow = now
    }

    private fun cycleTod() {
        todOverride = Tod.entries[((todOverride ?: tod).ordinal + 1) % 3]
        readClock()
    }

    /** At night the bridge lights step gold -> blue -> green -> rainbow, one bitmap swap every 10 s. */
    private fun scheduleLights() {
        h.removeCallbacks(lightsR)
        if (resumed && tod == Tod.NIGHT) h.postDelayed(lightsR, LIGHTS_MS)
    }

    private val lightsR = Runnable {
        if (tod == Tod.NIGHT && state != St.APP) { light = (light + 1) % 4; invalidate() }
        scheduleLights()
    }

    private val frameR = Runnable { invalidate() }

    // ================================================================== drawing

    override fun onDraw(c: Canvas) {
        val f = frame
        f.begin(now())
        c.save()
        c.scale(width / STAGE_W, height / STAGE_H)
        val saverFull = state == St.SAVER && f.now - saverAt >= SAVER_FADE
        val bootCovered = state == St.BOOT && f.now - bootAt < BOOT_BLOCKS_MS
        val launchFull = launchOpenAt >= 0 && f.now - launchOpenAt >= LAUNCH_FILL
        if (!saverFull && !bootCovered && !launchFull) {
            drawHome(c, f)
            drawGrid(c, f)
            drawPanel(c, f)
        }
        if (state == St.SAVER) drawSaver(c, f)
        if (state == St.BOOT) drawBoot(c, f)
        drawLaunch(c, f)
        c.restore()
        h.removeCallbacks(frameR)
        // Steps from different loops land on a shared 40 ms grid, so they share one frame (25 fps at most).
        if (f.wake != Long.MAX_VALUE) {
            val at = (f.wake + FRAME_GRID - 1) / FRAME_GRID * FRAME_GRID
            h.postDelayed(frameR, max(0L, at - SystemClock.uptimeMillis()))
        }
    }

    private fun drawHome(c: Canvas, f: Frame) {
        val now = f.now
        val calm = now >= calmAt
        ink.stretch(c, art.backdrop(tod, light), 0f, 0f, STAGE_W, STAGE_H)

        // backdrop loops on the paused-when-calm clock
        val lt = loopTime(now)
        val run = loopOn && now < calmAt
        if (run) f.at(calmAt)
        if (tod != Tod.NIGHT) ink.bitmap(c, art.cloud, lerp(1960, -140, f.sweep(lt, 80_000, 255, run)).toFloat(), 256f, 8)
        ink.cell(c, art.glint, 16 * f.loop(lt, GLINT, run), 0, 16, 3, 32f, 808f, 8)
        ink.cell(c, art.boat, 0, 0, 11, 6, lerp(-120, 1960, f.sweep(lt, 36_000, 255, run)).toFloat(), 864f, 8)
        drawShow(c, f)

        // top bar: logotype, shortcuts, clock
        ink.bitmap(c, art.logo, 96f, 80f, 8)
        for (i in buttons.indices) {
            val b = homeSpots[i]
            val on = homeShown && homeFocus == i
            ink.pxBox(c, b.x.toFloat(), b.y.toFloat(), b.w.toFloat(), b.h.toFloat(), if (on) Pal.Y else Pal.K, if (on) Pal.W else Pal.D)
            ink.bitmap(c, buttonIcons[i], b.x + 24f, b.y + 8f, 4)
            ink.text(c, buttons[i], b.x + 88f, b.y + 44f, 40f, if (on) Pal.K else Pal.S)
        }
        ink.text(c, timeText, 1824f, Ink.baseline(64f, 160f, 0.8f), 160f, Pal.Y, 8f, Paint.Align.RIGHT)
        ink.text(c, dateText, 1824f, Ink.baseline(192f, 44f), 44f, Pal.W, 6f, Paint.Align.RIGHT)

        // name box + cartridges dim to 25% behind the grid
        val dim = state == St.GRID
        if (dim) c.saveLayerAlpha(0f, 300f, STAGE_W, 920f, 64)
        drawNameBox(c, f)
        drawRow(c, f, calm)
        if (dim) c.restore()

        if (state != St.GRID && state != St.BOOT && avatar.placed) avatar.draw(c, f, 664f, calm)
    }

    /** Shown after every key, then it fades out in 4 steps [NAME_MS] after the last one so the bridge shows again. */
    private fun drawNameBox(c: Canvas, f: Frame) {
        if (nameText.isEmpty()) return
        val fade = f.steps(calmAt - CALM_MS + NAME_MS, 200, 4)
        if (fade >= 1f) return
        if (fade > 0f) c.saveLayerAlpha(480f, 320f, 480f + nameW, 456f, ((1 - fade) * 255).toInt())
        ink.pxBox(c, 480f, 320f, nameW, 136f, Pal.K, Pal.W)
        val k = f.steps(typeAt, 200, 8)
        c.save()
        c.clipRect(520f, 336f, 520f + (nameW - 80f) * k, 416f)
        ink.text(c, nameText, 520f, Ink.baseline(336f, 80f), 80f, Pal.W)
        c.restore()
        ink.text(c, hintText, 520f, Ink.baseline(416f, 36f), 36f, Pal.S)
        if (fade > 0f) c.restore()
    }

    private fun drawRow(c: Canvas, f: Frame, calm: Boolean) {
        val now = f.now
        // the yellow arrow over the focused cartridge: moves 160 ms in 4 steps, bobs 8 px every 250 ms
        val visible = if (now - ringOnAt >= 80) ringOn else !ringOn.also { f.at(ringOnAt + 80) }
        if (visible && ringPlaced) {
            val sinceKey = now - (calmAt - CALM_MS)
            val bob = if (calm) 0 else if (sinceKey % 500 >= 250) 8 else 0
            if (!calm) f.at(now + 250 - sinceKey % 250)
            f.steps(ringAt, 160, 4)
            ink.bitmap(c, art.arrow, ringX(now) + 112f - 40f, 576f - 88f + bob, 8)
        }
        for (i in dock.indices) drawCart(c, f, i)
    }

    private fun ringX(now: Long): Int = lerp(ringFrom, ringTo, stepped(now - ringAt, 160, 4))

    private fun drawCart(c: Canvas, f: Frame, i: Int) {
        val app = dock[i]
        val b = homeSpots[3 + i]
        val p = f.steps(liftAt[i], 120, 2)
        var lift = -24f * if (lifted[i]) p else 1 - p
        var dx = 0f
        if (bumpAt >= 0 && homeFocus == 3 + i) {
            val q = f.steps(bumpAt, 160, 2)
            if (q == 0.5f) { dx = 8f; lift = -24f }
        }
        val x = b.x + dx
        val y = b.y + lift
        val focused = homeShown && homeFocus == 3 + i
        ink.pxBox(c, x, y, 224f, 256f, app.color, if (focused) Pal.Y else Pal.K)
        ink.rect(c, x + 48, y - 8, 128f, 16f, Pal.K)
        ink.rect(c, x + 24, y + 24, 176f, 128f, Pal.K)
        drawIcon(c, app, x + 24, y + 24, 176, 128, 8)
        ink.text(c, app.name, x + 112, y + 168 + 32, 40f, Pal.W, 4f, Paint.Align.CENTER)
    }

    /** The app's icon centred in a [w]x[h] label: hand-drawn at the largest whole scale up to [max], else its tile. */
    private fun drawIcon(c: Canvas, app: AppEntry, x: Float, y: Float, w: Int, h: Int, max: Int, fit: Int = 0) {
        val hand = app.hand
        val bmp = if (hand != null) art.brand(hand) else app.tile ?: return
        val k = if (hand == null) max
        else if (fit > 0) min(max, fit / max(bmp.width, bmp.height))
        else min(max, min(w / bmp.width, h / bmp.height))
        ink.bitmap(c, bmp, x + (w - bmp.width * k) / 2, y + (h - bmp.height * k) / 2, k)
    }

    private fun drawShow(c: Canvas, f: Frame) {
        val t = f.now - showAt
        if (t >= SHOW_MS) return
        f.at(showAt + SHOW_MS)
        val water = t >= SHOW_MS / 2
        val bmp = if (water) art.spray else art.fire
        val fw = if (water) 28 else 30
        val k = f.loop(t, SHOW_FRAMES)
        c.save()
        c.rotate(-18f, 1672f, 344f + 56f)
        ink.cell(c, bmp, fw * k, 0, fw, 14, 1672f, 344f, 8)
        c.restore()
    }

    private fun gRing(now: Long): Box {
        val p = stepped(now - gRingAt, 160, 4)
        return Box(lerp(gRingFrom.x, gRingTo.x, p), lerp(gRingFrom.y, gRingTo.y, p), gRingTo.w, gRingTo.h)
    }

    private fun scroll(now: Long): Int = lerp(scrollFrom, scrollTo, stepped(now - scrollAt, 220, 4))

    private fun drawGrid(c: Canvas, f: Frame) {
        val now = f.now
        val p = f.steps(gridAt, 240, 6)
        if (!gridOpen && p >= 1f) return
        val dy = (if (gridOpen) -1200f * (1 - p) else -1200f * p)
        c.save()
        c.translate(0f, dy)
        ink.pxBox(c, 160f, 128f, 1600f, 824f, Pal.N, Pal.W)
        ink.text(c, "ỨNG DỤNG", 208f, Ink.baseline(160f, 56f), 56f, Pal.Y)
        ink.text(c, "${gridApps.size} món · Back để đóng", 1712f, Ink.baseline(160f, 40f), 40f, Pal.S, align = Paint.Align.RIGHT)
        c.clipRect(208f, 256f, 1712f, 920f)
        f.steps(scrollAt, 220, 4)
        c.translate(0f, -scroll(now).toFloat())
        if (gridSpots.isNotEmpty()) {
            f.steps(gRingAt, 160, 4)
            val r = gRing(now)
            val calm = now >= calmAt
            val sinceKey = now - (calmAt - CALM_MS)
            val bob = if (calm) 0 else if (sinceKey % 500 >= 250) 8 else 0
            if (!calm) f.at(now + 250 - sinceKey % 250)
            ink.bitmap(c, art.arrowR, r.x + 8f + bob, r.y + r.h / 2f - 40f, 8)
        }
        for (i in gridApps.indices) {
            val b = gridSpots[i]
            val app = gridApps[i]
            val on = state == St.GRID && i == gridFocus
            val ix = b.x + 80f
            val iy = b.y + 28f
            ink.pxBox(c, ix, iy, 112f, 112f, app.color, if (on) Pal.Y else Pal.K)
            drawIcon(c, app, ix, iy, 112, 112, 6, fit = 108)
            val lines = gridNames[i]
            val top = b.y + (168 - lines.size * 36) / 2f
            for ((k, line) in lines.withIndex()) ink.text(c, line, b.x + 216f, Ink.baseline(top + k * 36, 40f, 0.9f), 40f, if (on) Pal.Y else Pal.S)
        }
        c.restore()
    }

    private fun drawPanel(c: Canvas, f: Frame) {
        val p = panel ?: return
        val now = f.now
        val q = f.steps(panelAt, 160, 4)
        if (!panelOpen && q >= 1f) return
        val dx = if (panelOpen) 800f * (1 - q) else 800f * q
        val x = 1216f + dx
        ink.pxBox(c, x, 200f, 640f, 560f, Pal.K, Pal.W)
        ink.text(c, p.title, x + 40, Ink.baseline(232f, 56f), 56f, Pal.Y)
        c.save()
        c.clipRect(x + 40, 312f, x + 600, 728f)
        val tapping = now - tapAt < TAP_MS
        if (tapping) f.at(tapAt + TAP_MS)
        for ((j, row) in p.rows.withIndex()) {
            val y = 312f + (j - panelScroll) * 88f
            if (y < 312f - 88f || y > 728f) continue
            val focused = state == St.PANEL && j == panelFocus
            val tap = tapping && j == tapIdx
            if (tap) ink.rect(c, x + 40, y, 560f, 88f, Pal.Y) else if (focused) ink.rect(c, x + 40, y, 560f, 88f, Pal.N)
            ink.rect(c, x + 56, y + 32, 24f, 24f, if (row.on) Pal.G else Pal.D)
            var tx = x + 104
            val base = Ink.baseline(y + 22, 44f)
            if (focused) { ink.text(c, "▶ ", tx, base, 44f, Pal.Y); tx += ink.width("▶ ", 44f) }
            ink.text(c, row.text, tx, base, 44f, if (tap) Pal.K else if (focused) Pal.W else Pal.S)
        }
        c.restore()
    }

    private fun driftPos(now: Long): IntArray {
        val p = stepped(now - driftAt, 8000, 16)
        return intArrayOf(lerp(driftFrom[0], driftTo[0], p), lerp(driftFrom[1], driftTo[1], p))
    }

    private fun drawSaver(c: Canvas, f: Frame) {
        val now = f.now
        val a = f.steps(saverAt, SAVER_FADE, 4)
        if (a < 1f) c.saveLayerAlpha(stageRect, (a * 255).toInt())
        ink.stretch(c, art.backdrop(tod, light), 0f, 0f, STAGE_W, STAGE_H)
        f.steps(driftAt, 8000, 16)
        val d = driftPos(now)
        ink.text(c, timeText, 960f + d[0], Ink.baseline(300f + d[1], 320f, 0.8f), 320f, Pal.Y, 16f, Paint.Align.CENTER)
        ink.text(c, dateText, 960f + d[0], Ink.baseline(556f + d[1], 64f), 64f, Pal.S, 6f, Paint.Align.CENTER)
        avatar.drawSleep(c, f, 1328f, 664f, saverAt)
        // "z" and a bigger "Z" float off every 1.2 s: 2.4 s rise in 6 steps, fading in by 20 %
        for (k in 0..1) {
            val t = now - saverAt - k * 1200L
            if (t < 0) { f.at(saverAt + k * 1200L); continue }
            val pr = f.sweep(t, 2400, 6, true)
            val alpha = if (pr < 0.2f) pr / 0.2f else 1 - (pr - 0.2f) / 0.8f
            if (alpha <= 0f) continue
            val size = if (k == 0) 48f else 64f
            c.saveLayerAlpha(1300f, 600f, 1800f, 900f, (alpha * 255).toInt())
            ink.text(c, if (k == 0) "z" else "Z", 1328f + 304 + 48 * pr, Ink.baseline(664f + 136 - 96 * pr, size), size, Pal.W, 6f)
            c.restore()
        }
        ink.bitmap(c, art.logo, 96f, 72f, 4)
        if (a < 1f) c.restore()
    }

    private fun drawBoot(c: Canvas, f: Frame) {
        val now = f.now
        val t = now - bootAt
        // a 16x9 grid of black blocks vanishing in a scattered order from about 1050 ms
        for (r in 0 until 9) for (col in 0 until 16) {
            val dl = 1050 + ((r * 16 + col) * 97 % 144) * 5
            if (t < dl) { ink.rect(c, col * 120f, r * 120f, 120f, 120f, Pal.K); f.at(bootAt + dl) }
        }
        if (t >= BOOT_LOGO_MS) return
        f.at(bootAt + BOOT_LOGO_MS)
        // the logotype wipes in at 16x in 8 steps over 400 ms; he waves beside it from 300 ms
        val k = f.steps(bootAt, 400, 8)
        c.save()
        c.clipRect(464f, 560f, 464f + 704f * k, 736f)
        ink.bitmap(c, art.logo, 464f, 560f, 16)
        c.restore()
        val wf = if (t < 300) { f.at(bootAt + 300); 0 } else f.once(bootAt + 300, BOOT_WAVE)
        ink.cell(c, art.bootWave, wf * 96, 0, 96, 96, 1120f, 400f, 4)
        ink.text(c, "đang khởi động…", 960f, Ink.baseline(736f, 48f), 48f, Pal.S, align = Paint.Align.CENTER)
    }

    private fun drawLaunch(c: Canvas, f: Frame) {
        val now = f.now
        if (launchCloseAt >= 0) {
            val q = f.steps(launchCloseAt, 180, 3)
            if (q < 1f) {
                c.saveLayerAlpha(stageRect, ((1 - q) * 255).toInt())
                ink.rect(c, 0f, 0f, STAGE_W, STAGE_H, launchColor)
                c.restore()
            }
        }
        val app = launchApp ?: return
        if (launchOpenAt < 0) return
        // the cartridge colour fills the screen from the cartridge's rectangle in 300 ms (6 steps)
        val p = f.steps(launchOpenAt, LAUNCH_FILL, 6)
        val b = launchBox
        ink.rect(c, b.left * (1 - p), b.top * (1 - p), b.width() + (STAGE_W - b.width()) * p, b.height() + (STAGE_H - b.height()) * p, launchColor)
        if (now - launchOpenAt < 160) { f.at(launchOpenAt + 160); return }
        val a = f.steps(launchOpenAt + 160, 160, 4)
        if (a < 1f) c.saveLayerAlpha(stageRect, (a * 255).toInt())
        drawIcon(c, app, 960f - 96, 332f, 192, 192, 12, fit = 192)
        ink.text(c, app.name, 960f, Ink.baseline(552f, 120f), 120f, Pal.W, 8f, Paint.Align.CENTER)
        val msg = "Đang mở…"
        val mw = 32 + 18 + ink.width(msg, 48f)
        val sx = 960f - mw / 2
        c.saveLayerAlpha(sx - 10, 690f, sx + mw + 10, 760f, 217)
        val blinkOn = (now - launchOpenAt) % 400 < 200
        f.at(now + 200 - (now - launchOpenAt) % 200)
        if (blinkOn) ink.rect(c, sx, 708f, 32f, 32f, Pal.W)
        ink.text(c, msg, sx + 50, Ink.baseline(700f, 48f), 48f, Pal.W)
        c.restore()
        if (a < 1f) c.restore()
    }

    private fun now() = SystemClock.uptimeMillis()

    companion object {
        const val STAGE_W = 1920f
        const val STAGE_H = 1080f

        private const val FRAME_GRID = 40L
        private const val CALM_MS = 5_000L
        private const val TRAVEL_MS = 3_000L
        private const val SAVER_MS = 5 * 60_000L
        private const val SAVER_FADE = 400L
        private const val LIGHTS_MS = 10_000L
        private const val SHOW_MS = 6_000L
        private const val SHOW_GAP = 120_000L
        private const val BOOT_MS = 1_800L
        private const val BOOT_LOGO_MS = 1_080L
        private const val BOOT_BLOCKS_MS = 1_050L
        private const val LEAD_IN_MS = 420L
        private const val READY_MS = 900L
        private const val LAUNCH_FILL = 300L
        private const val TAP_MS = 220L
        private const val OPEN_WAIT_MS = 2_000L
        private const val NAME_MS = 2_500L
        /** The dragon's head starts near x 1440; the box (from x 480) ends 160 px before it. */
        private const val NAME_MAX_W = 800f
        private const val DRIFT = 240
        private const val PANEL_ROWS = 4

        private val GLINT = longArrayOf(250, 250, 250)
        private val SHOW_FRAMES = longArrayOf(125, 125, 125, 125)
        private val BOOT_WAVE = LongArray(6) { 150 }
        private val OWN_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE,
        )
        private val DEBUG_KEYS = setOf(KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_R, KeyEvent.KEYCODE_T, KeyEvent.KEYCODE_F, KeyEvent.KEYCODE_G)
    }
}
