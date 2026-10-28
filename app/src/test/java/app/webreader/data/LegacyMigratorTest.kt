package app.webreader.data

import androidx.room.Room
import app.webreader.data.db.AppDatabase
import app.webreader.data.db.Favorite
import app.webreader.data.db.ReadHistory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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

@RunWith(RobolectricTestRunner::class)
class LegacyMigratorTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsStore
    private lateinit var cache: ResourceCache
    private lateinit var repo: SiteRepository
    private lateinit var migrator: LegacyMigrator

    @Before
    fun setUp() {
        val ctx = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        settings = SettingsStore(ctx, "test-" + UUID.randomUUID())
        cache = ResourceCache(tmp.root, { 1_000_000L }, { emptySet() })
        repo = SiteRepository(db.siteDao(), db.favoriteDao(), db.readHistoryDao(), cache)
        migrator = LegacyMigrator(settings, repo, db.favoriteDao(), db.readHistoryDao(), cache)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun legacySettingsBecomeDefaultSiteAndRecordsMoved() = runTest {
        settings.setBaseUrl("https://a.example.com")
        settings.setAliasHosts(setOf("m.example.com"))
        settings.setPatterns("^/series/\\d+", "^/ep/\\d+")
        db.favoriteDao().upsert(Favorite("", "/series/1", Favorite.TYPE_SERIES, "S", 1))
        db.readHistoryDao().upsert(ReadHistory("", "/ep/1", 7, 1))
        cache.put("/series/1", CachedResource("text/html", null, byteArrayOf(1)))

        migrator.run()

        val site = repo.sites.first().single()
        assertEquals("Default", site.name)
        assertEquals("https://a.example.com", site.baseUrl)
        assertEquals(setOf("m.example.com"), site.aliasHosts)
        assertEquals("^/series/\\d+", site.seriesPattern)
        assertEquals("^/ep/\\d+", site.episodePattern)
        assertEquals(site.id, settings.currentSiteId.first())
        assertEquals(1, db.favoriteDao().observeAll(site.id).first().size)
        assertEquals(7, db.readHistoryDao().get(site.id, "/ep/1")?.lastPosition)
        assertNull(cache.get("/series/1"))
        assertTrue(settings.legacyMigrated.first())
    }

    @Test
    fun runsOnlyOnce() = runTest {
        settings.setBaseUrl("https://a.example.com")
        migrator.run()
        db.siteDao().delete(repo.sites.first().single().id)
        migrator.run()
        assertTrue(repo.sites.first().isEmpty())
    }

    @Test
    fun blankLegacyBaseCreatesNothing() = runTest {
        migrator.run()
        assertTrue(repo.sites.first().isEmpty())
        assertNull(settings.currentSiteId.first())
        assertTrue(settings.legacyMigrated.first())
    }
}
