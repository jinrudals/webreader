package app.webreader.ui

import app.webreader.data.CachedResource
import app.webreader.data.ResourceCache
import app.webreader.data.SettingsStore
import app.webreader.data.SiteRepository
import app.webreader.data.db.AppDatabase
import app.webreader.data.db.Favorite
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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
    private lateinit var db: AppDatabase
    private lateinit var vm: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        settings = SettingsStore(RuntimeEnvironment.getApplication(), "test-" + UUID.randomUUID())
        cache = ResourceCache(tmp.newFolder(), { 10_000_000L }, { emptySet() })
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val repo = SiteRepository(db.siteDao(), db.favoriteDao(), db.readHistoryDao(), cache)
        vm = SettingsViewModel(settings, cache, repo)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun res() = CachedResource("text/html", "utf-8", byteArrayOf(1, 2, 3))

    @Test
    fun clearCacheEmptiesIt() = runTest {
        cache.put("/a", res())
        vm.clearCache().join()
        assertNull(cache.get("/a"))
        assertEquals(0L, cache.sizeBytes())
    }

    @Test
    fun cacheLimitStillGlobalAndAtLeastOne() = runTest {
        vm.saveCache(0, false)
        assertEquals(1, settings.cacheLimitMb.first())
        assertEquals(false, settings.protectFavorites.first())
        vm.saveCache(-5, true)
        assertEquals(1, settings.cacheLimitMb.first())
    }

    @Test
    fun addSiteDuplicateHostSurfacedAsFailure() = runTest {
        assertTrue(vm.addSite("A", "https://a.example.com", "", "", "").isSuccess)
        assertTrue(vm.addSite("B", "https://a.example.com", "", "", "").isFailure)
        assertTrue(vm.addSite("C", "https://c.example.com", "a.example.com", "", "").isFailure)
        assertEquals(1, vm.sites.first().size)
    }

    @Test
    fun deleteSiteRemovesItsRecords() = runTest {
        val site = vm.addSite("A", "https://a.example.com", "", "", "").getOrThrow()
        db.favoriteDao().upsert(Favorite(site.id, "/series/1", Favorite.TYPE_SERIES, "S", 1))
        cache.put("${site.id}:/a", res())
        vm.deleteSite(site.id)
        assertTrue(vm.sites.first().isEmpty())
        assertTrue(db.favoriteDao().observeAll(site.id).first().isEmpty())
        assertNull(cache.get("${site.id}:/a"))
    }

    @Test
    fun updateSiteWorksAndRejectsBadPattern() = runTest {
        val site = vm.addSite("A", "https://a.example.com", "", "", "").getOrThrow()
        assertTrue(vm.updateSite(site.id, "A2", "https://a.example.com", "", "(", "").isFailure)
        assertTrue(vm.updateSite(site.id, "A2", "https://a.example.com", "", "/s/\\d+", "").isSuccess)
        assertEquals("A2", vm.sites.first().single().name)
    }
}
