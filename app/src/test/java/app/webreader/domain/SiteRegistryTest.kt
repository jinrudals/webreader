package app.webreader.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SiteRegistryTest {
    private fun site(id: String, base: String, aliases: Set<String> = emptySet()) =
        Site(id, id, base, aliases, "", "")

    private val a = site("a", "https://Example.com", setOf("m.Example.com"))
    private val b = site("b", "https://other.org")
    private val registry = SiteRegistry(listOf(a, b))

    @Test
    fun forHostMatchesBaseAndAliasCaseInsensitive() {
        assertEquals(a, registry.forHost("EXAMPLE.com"))
        assertEquals(a, registry.forHost("m.example.com"))
        assertEquals(b, registry.forUrl("https://OTHER.org/x"))
    }

    @Test
    fun unknownHostAndMalformedUrlReturnNull() {
        assertNull(registry.forHost("nope.net"))
        assertNull(registry.forUrl("not a url"))
    }

    @Test
    fun conflictDetectedAgainstOtherSiteBaseOrAlias() {
        assertEquals("other.org", registry.conflictingHost(site("c", "https://other.org")))
        assertEquals("m.example.com", registry.conflictingHost(site("c", "https://new.io", setOf("M.example.com"))))
        assertNull(registry.conflictingHost(site("c", "https://new.io")))
    }

    @Test
    fun siteDoesNotConflictWithItself() {
        assertNull(registry.conflictingHost(a))
    }

    @Test
    fun resolveCurrentFallsBackToFirstThenNull() {
        assertEquals(b, SiteRegistry.resolveCurrent(listOf(a, b), "b"))
        assertEquals(a, SiteRegistry.resolveCurrent(listOf(a, b), "zzz"))
        assertEquals(a, SiteRegistry.resolveCurrent(listOf(a, b), null))
        assertNull(SiteRegistry.resolveCurrent(emptyList(), "a"))
    }
}
