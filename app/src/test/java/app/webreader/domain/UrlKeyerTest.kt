package app.webreader.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlKeyerTest {
    private fun keyer(base: String = "https://a.com") =
        UrlKeyer { SiteConfig(base, emptySet()) }

    @Test
    fun sameKeyAcrossHosts() {
        val k = keyer()
        assertEquals(k.key("https://a.com/ep/1"), k.key("https://b.net/ep/1"))
    }

    @Test
    fun queryOrderIgnored() {
        val k = keyer()
        assertEquals(k.key("https://a.com/x?b=2&a=1"), k.key("https://a.com/x?a=1&b=2"))
        assertEquals("/x?a=1&b=2", k.key("https://a.com/x?b=2&a=1"))
    }

    @Test
    fun trailingSlashAndFragmentNormalized() {
        val k = keyer()
        assertEquals(k.key("https://a.com/x"), k.key("https://a.com/x/#top"))
    }

    @Test
    fun resolveUsesCurrentBase() {
        assertEquals("https://new.com/ep/1", keyer("https://new.com").resolve("/ep/1"))
    }

    @Test
    fun malformedUrlDoesNotThrow() {
        val k = keyer()
        assertEquals(k.key("https://b.net/a b|c{1}?q=x y"), k.key("https://a.com/a b|c{1}?q=x y"))
        assertEquals("/a b|c?q=x y", k.key("https://a.com/a b|c?q=x y"))
    }

    @Test
    fun pathOnlyAndRoot() {
        val k = keyer()
        assertEquals("/ep/1", k.key("/ep/1"))
        assertEquals("/", k.key("/"))
        assertEquals("/", k.key("https://a.com"))
    }

    @Test
    fun resolvePrefixesSlash() {
        assertEquals("https://a.com/ep/1", keyer().resolve("ep/1"))
    }
}
