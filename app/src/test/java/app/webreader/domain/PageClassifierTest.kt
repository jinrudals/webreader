package app.webreader.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class PageClassifierTest {
    @Test
    fun emptyPatternsAreOther() {
        assertEquals(PageType.OTHER, PageClassifier("", "").classify("/series/12"))
    }

    @Test
    fun episodeWinsWhenBothMatch() {
        val c = PageClassifier("^/series/", "/episode/")
        assertEquals(PageType.EPISODE, c.classify("/series/12/episode/3"))
    }

    @Test
    fun matchesAgainstKeyNotHost() {
        val c = PageClassifier("^/series/\\d+", "")
        assertEquals(PageType.SERIES, c.classify("/series/12"))
    }

    @Test
    fun invalidPatternNeverMatches() {
        val c = PageClassifier("([", "(")
        assertEquals(PageType.OTHER, c.classify("/series/12"))
    }
}
