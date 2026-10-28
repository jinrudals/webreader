package app.webreader.ui

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class BrowserLoadPolicyTest {
    private val req = OpenRequest("https://b.example/x", 1)

    @Test
    fun requestLoadsUrlAndSurvivesRecompositionBeforeSiteFlips() {
        val first = decideLoad(BrowserLoadState(null, "a"), req, "b", "a", "https://a.example")
        assertEquals(req.url, first.url)
        val again = decideLoad(first.state, req, "b", "a", "https://a.example")
        assertNull(again.url)
        val flipped = decideLoad(again.state, req, "b", "b", "https://b.example")
        assertNull(flipped.url)
        assertNull(decideLoad(flipped.state, req, "b", "b", "https://b.example").url)
    }

    @Test
    fun siteChangeLoadsBaseUrlAfterRequestSettled() {
        val s = BrowserLoadState(1, "b", null)
        assertEquals("https://c.example", decideLoad(s, req, "b", "c", "https://c.example").url)
    }

    @Test
    fun requestForCurrentSiteDoesNotSwallowLaterSwitch() {
        val first = decideLoad(BrowserLoadState(null, "a"), req, "a", "a", "https://a.example")
        assertEquals(req.url, first.url)
        val back = decideLoad(first.state.copy(seenSiteId = "c"), req, "a", "a", "https://a.example")
        assertEquals("https://a.example", back.url)
    }

    @Test
    fun noSiteLoadsNothingAndReaddedSiteLoadsBaseNotStaleRequest() {
        val s = BrowserLoadState(1, "a", null)
        val none = decideLoad(s, req, "b", null, null)
        assertNull(none.url)
        assertEquals("https://n.example", decideLoad(none.state, req, "b", "n", "https://n.example").url)
    }
}
