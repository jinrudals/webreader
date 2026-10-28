package app.webreader.ui

import app.webreader.data.CachedResource
import app.webreader.data.ResourceCache
import app.webreader.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var settings: SettingsStore
    private lateinit var cache: ResourceCache
    private lateinit var vm: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        settings = SettingsStore(RuntimeEnvironment.getApplication(), "test-" + UUID.randomUUID())
        cache = ResourceCache(tmp.newFolder(), { 10_000_000L }, { emptySet() })
        vm = SettingsViewModel(settings, cache)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun res() = CachedResource("text/html", "utf-8", byteArrayOf(1, 2, 3))

    @Test
    fun nonHttpBaseUrlRejected() = runTest {
        listOf("ftp://example.com", "example.com", "", "   ", "http://", "javascript:alert(1)").forEach {
            assertTrue(it, vm.saveBaseUrl(it).isFailure)
        }
        assertEquals("", settings.siteConfig.first().baseUrl)
        assertTrue(vm.saveBaseUrl("  https://example.com  ").isSuccess)
        assertEquals("https://example.com", settings.siteConfig.first().baseUrl)
    }

    @Test
    fun invalidRegexSurfacedAsFailure() = runTest {
        assertTrue(vm.savePatterns("/series/\\d+", "/ep/\\d+").isSuccess)
        assertTrue(vm.savePatterns("(", "/x").isFailure)
        assertEquals("/series/\\d+", vm.seriesPattern.first())
        assertEquals("/ep/\\d+", vm.episodePattern.first())
    }

    @Test
    fun changingBaseUrlDoesNotClearCache() = runTest {
        cache.put("/a", res())
        assertTrue(vm.saveBaseUrl("https://new.example.org").isSuccess)
        assertNotNull(cache.get("/a"))
    }

    @Test
    fun clearCacheEmptiesIt() = runTest {
        cache.put("/a", res())
        vm.clearCache().join()
        assertNull(cache.get("/a"))
        assertEquals(0L, cache.sizeBytes())
    }

    @Test
    fun aliasHostsNormalized() = runTest {
        vm.saveAliasHosts(" M.Example.com, b.example.com\nm.example.com  ,, ")
        assertEquals(setOf("m.example.com", "b.example.com"), settings.siteConfig.first().aliasHosts)
    }

    @Test
    fun cacheLimitNeverBelowOne() = runTest {
        vm.saveCache(0, false)
        assertEquals(1, settings.cacheLimitMb.first())
        assertEquals(false, settings.protectFavorites.first())
        vm.saveCache(-5, true)
        assertEquals(1, settings.cacheLimitMb.first())
    }
}
