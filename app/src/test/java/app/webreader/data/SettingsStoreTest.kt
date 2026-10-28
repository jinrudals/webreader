package app.webreader.data

import org.robolectric.RuntimeEnvironment
import app.webreader.domain.SiteConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class SettingsStoreTest {
    private fun newStore(): SettingsStore {
        val ctx = RuntimeEnvironment.getApplication()
        return SettingsStore(ctx, "test-" + UUID.randomUUID())
    }

    @Test
    fun defaults() = runTest {
        val s = newStore()
        assertEquals(SiteConfig("", emptySet()), s.siteConfig.first())
        assertEquals("", s.seriesPattern.first())
        assertEquals("", s.episodePattern.first())
        assertEquals(500, s.cacheLimitMb.first())
        assertTrue(s.protectFavorites.first())
    }

    @Test
    fun legacyKeysReadable() = runTest {
        val s = newStore()
        s.seedLegacySite("https://example.com", setOf("m.example.com"), "/series/\\d+", "/ep/\\d+")
        assertEquals(SiteConfig("https://example.com", setOf("m.example.com")), s.siteConfig.first())
        assertEquals("/series/\\d+", s.seriesPattern.first())
        assertEquals("/ep/\\d+", s.episodePattern.first())
    }
}
