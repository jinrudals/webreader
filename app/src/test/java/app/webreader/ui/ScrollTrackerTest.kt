package app.webreader.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollTrackerTest {
    private val saved = mutableListOf<Pair<String, Int>>()
    private val tracker = ScrollTracker { u, y -> saved += u to y }

    @Test
    fun navigationAwayFlushesOldPageLastPosition() {
        tracker.onPageFinished("A", 0)
        tracker.onScroll(500)
        tracker.onPageStarted("B")
        tracker.onScroll(0)
        assertEquals(listOf("A" to 500), saved)
    }

    @Test
    fun scrollWhileLoadingIgnored() {
        tracker.onPageFinished("A", 0)
        tracker.onPageStarted("B")
        tracker.onScroll(900)
        tracker.onPageFinished("B", 0)
        tracker.onPause()
        assertEquals(listOf("A" to 0, "B" to 0), saved)
    }

    @Test
    fun pauseWhileLoadingDoesNotOverwrite() {
        tracker.onPageFinished("A", 0)
        tracker.onScroll(500)
        tracker.onPageStarted("A")
        tracker.onPause()
        assertEquals(listOf("A" to 500), saved)
    }

    @Test
    fun pauseAfterFinishSavesRestoredPositionNotZero() {
        tracker.onPageFinished("A", 300)
        tracker.onPause()
        assertEquals(listOf("A" to 300), saved)
    }

    @Test
    fun scrollThenPauseSavesLastY() {
        tracker.onPageFinished("A", 0)
        tracker.onScroll(120)
        tracker.onScroll(240)
        tracker.onPause()
        assertEquals(listOf("A" to 240), saved)
    }

    @Test
    fun pageFinishedWithoutStartResetsCurrent() {
        tracker.onPageFinished("A", 0)
        tracker.onScroll(500)
        tracker.onPageFinished("B", 10)
        assertTrue(saved.isEmpty())
        tracker.onPause()
        assertEquals(listOf("B" to 10), saved)
    }
}
