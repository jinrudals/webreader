package app.webreader.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteConfigTest {
    @Test
    fun malformedBaseUrlNeverThrows() {
        val c = SiteConfig("not a url ::", setOf("alias.com"))
        assertFalse(c.isKnownHost("x.com"))
        assertTrue(c.isKnownHost("ALIAS.com"))
    }
}
