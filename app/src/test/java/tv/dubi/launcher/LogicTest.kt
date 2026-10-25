package tv.dubi.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class NavTest {
    // Home as laid out on the stage: 3 shortcut buttons, then 5 cartridges
    private val home = listOf(
        Box(504, 92, 186, 64), Box(730, 92, 200, 64), Box(970, 92, 230, 64),
        Box(288, 576, 224, 256), Box(568, 576, 224, 256), Box(848, 576, 224, 256), Box(1128, 576, 224, 256), Box(1408, 576, 224, 256),
    )

    @Test fun rightWalksTheRowAndStopsAtTheEnd() {
        assertEquals(4, nearest(home[3], Dir.RIGHT, home, 3))
        assertEquals(-1, nearest(home[7], Dir.RIGHT, home, 7))
        assertEquals(-1, nearest(home[3], Dir.LEFT, home, 3))
    }

    @Test fun downFromTheRowIsAnEdge() {
        assertEquals(-1, nearest(home[5], Dir.DOWN, home, 5)) // the launcher opens the grid here
    }

    @Test fun upFromACartridgeReachesAShortcut() {
        assertTrue(nearest(home[5], Dir.UP, home, 5) in 0..2)
        assertTrue(nearest(home[0], Dir.DOWN, home, 0) >= 3)
    }

    @Test fun gridDownFallsIntoAShortLastRow() {
        val grid = (0 until 6).map { Box(216 + (it % 4) * 378, 264 + (it / 4) * 192, 354, 168) }
        assertEquals(5, nearest(grid[3], Dir.DOWN, grid, 3)) // nothing below column 4: lands on the last row
        assertEquals(-1, nearest(grid[1], Dir.UP, grid, 1))
        assertEquals(-1, nearest(grid[3], Dir.RIGHT, grid, 3)) // never wraps rows
    }
}

class ClockTest {
    @Test fun timeOfDayBoundaries() {
        assertEquals(Tod.NIGHT, Clock.tod(5))
        assertEquals(Tod.DAY, Clock.tod(6))
        assertEquals(Tod.DAY, Clock.tod(16))
        assertEquals(Tod.DUSK, Clock.tod(17))
        assertEquals(Tod.DUSK, Clock.tod(18))
        assertEquals(Tod.NIGHT, Clock.tod(19))
    }

    @Test fun dragonShowOnlyWeekendNineToQuarterPast() {
        assertTrue(Clock.showTime(Calendar.SATURDAY, 21, 0))
        assertTrue(Clock.showTime(Calendar.SUNDAY, 21, 14))
        assertFalse(Clock.showTime(Calendar.SUNDAY, 21, 15))
        assertFalse(Clock.showTime(Calendar.FRIDAY, 21, 5))
        assertFalse(Clock.showTime(Calendar.SATURDAY, 20, 59))
    }

    @Test fun vietnameseDate() {
        assertEquals("Chủ nhật, 25/10", Clock.date(Calendar.SUNDAY, 25, 10))
        assertEquals("Thứ bảy, 1/3", Clock.date(Calendar.SATURDAY, 1, 3))
        assertEquals("09:05", Clock.time(9, 5))
    }
}

class StepsTest {
    @Test fun stepsJumpAtTheEndOfEachInterval() {
        val f = Frame()
        f.begin(1000); assertEquals(0f, f.steps(1000, 120, 2)); assertEquals(1060L, f.wake)
        f.begin(1059); assertEquals(0f, f.steps(1000, 120, 2))
        f.begin(1060); assertEquals(0.5f, f.steps(1000, 120, 2)); assertEquals(1120L, f.wake)
        f.begin(1120); assertEquals(1f, f.steps(1000, 120, 2)); assertEquals(Long.MAX_VALUE, f.wake)
    }

    @Test fun aDelayedStepWakesOnceAtItsStartThenNeverAgain() {
        // the name box: held for 2.5 s after a key, then a 200 ms fade in 4 steps
        val f = Frame()
        f.begin(1000); assertEquals(0f, f.steps(3500, 200, 4)); assertEquals(3500L, f.wake)
        f.begin(3550); assertEquals(0.25f, f.steps(3500, 200, 4)); assertEquals(3600L, f.wake)
        f.begin(3700); assertEquals(1f, f.steps(3500, 200, 4)); assertEquals(Long.MAX_VALUE, f.wake)
    }

    @Test fun loopPicksFrameAndNextChange() {
        val f = Frame()
        f.begin(5000)
        assertEquals(1, f.loop(800, longArrayOf(700, 500, 700, 140)))
        assertEquals(5000L + 400, f.wake)
        f.begin(5000)
        assertEquals(1, f.loop(800, longArrayOf(700, 500, 700, 140), running = false))
        assertEquals(Long.MAX_VALUE, f.wake) // a paused loop never wakes the view
    }

    @Test fun onceHoldsTheLastFrame() {
        val f = Frame()
        f.begin(10_000)
        assertEquals(4, f.once(0, longArrayOf(150, 150, 150, 600, 900)))
        assertEquals(Long.MAX_VALUE, f.wake)
    }

    @Test fun steppedMatchesFrame() {
        assertEquals(0.75f, stepped(130, 160, 4))
        assertEquals(1f, stepped(500, 160, 4))
        assertEquals(0f, stepped(-5, 160, 4))
    }
}

class AppKeyTest {
    @Test fun labelsNormaliseForDockMatching() {
        assertEquals("fptplay", Apps.key("FPT Play"))
        assertEquals("vtvgo", Apps.key("VTV Go"))
        assertEquals("vieon", Apps.key("VieON"))
        assertEquals("dientoan", Apps.key("Điện toán"))
    }
}
