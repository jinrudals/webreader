package app.webreader.data

import androidx.room.Room
import app.webreader.data.db.AppDatabase
import app.webreader.data.db.Favorite
import app.webreader.data.db.ReadHistory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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

@RunWith(RobolectricTestRunner::class)
class SiteRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var cache: ResourceCache
    private lateinit var repo: SiteRepository
    private var clock = 0L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        cache = ResourceCache(tmp.root, { 1_000_000L }, { emptySet() })
        repo = SiteRepository(db.siteDao(), db.favoriteDao(), db.readHistoryDao(), cache) { ++clock }
    }

    @After
    fun tearDown() = db.close()

    private fun res() = CachedResource("text/html", null, byteArrayOf(1))

    @Test
    fun sameHostRejectedAcrossSites() = runTest {
        assertTrue(repo.add("A", "https://a.example.com", "", "", "").isSuccess)
        assertTrue(repo.add("B", "https://A.example.com/x", "", "", "").isFailure)
        assertEquals(1, repo.sites.first().size)
    }

    @Test
    fun aliasEqualToOtherBaseRejected() = runTest {
        repo.add("A", "https://a.example.com", "", "", "")
        assertTrue(repo.add("B", "https://b.example.com", "a.example.com", "", "").isFailure)
    }

    @Test
    fun invalidUrlAndRegexRejected() = runTest {
        assertTrue(repo.add("", "https://a.example.com", "", "", "").isFailure)
        assertTrue(repo.add("A", "ftp://a.example.com", "", "", "").isFailure)
        assertTrue(repo.add("A", "not a url", "", "", "").isFailure)
        assertTrue(repo.add("A", "https://a.example.com", "", "(", "").isFailure)
        assertTrue(repo.add("A", "https://a.example.com", "", "", "[").isFailure)
        assertTrue(repo.sites.first().isEmpty())
    }

    @Test
    fun addNormalizesAliases() = runTest {
        val s = repo.add("A", "HTTPS://A.Example.com/path", " M.Example.com, m.example.com a.example.com", "", "").getOrThrow()
        assertEquals("https://a.example.com/path", s.baseUrl)
        assertEquals(setOf("m.example.com"), s.aliasHosts)
    }

    @Test
    fun changingBaseHostAddsOldHostAndKeepsCache() = runTest {
        val s = repo.add("A", "https://a.example.com", "", "", "").getOrThrow()
        cache.put("${s.id}:/x", res())
        assertTrue(repo.update(s.id, "A", "https://new.example.com", "", "", "").isSuccess)
        val updated = repo.sites.first().single()
        assertEquals("https://new.example.com", updated.baseUrl)
        assertEquals(setOf("a.example.com"), updated.aliasHosts)
        assertNotNull(cache.get("${s.id}:/x"))
    }

    @Test
    fun deleteCascadesFavoritesHistoryAndCacheOfThatSiteOnly() = runTest {
        val a = repo.add("A", "https://a.example.com", "", "", "").getOrThrow()
        val b = repo.add("B", "https://b.example.com", "", "", "").getOrThrow()
        for (s in listOf(a, b)) {
            db.favoriteDao().upsert(Favorite(s.id, "/s/1", Favorite.TYPE_SERIES, "t", 1))
            db.readHistoryDao().upsert(ReadHistory(s.id, "/s/1", 5, 1))
            cache.put("${s.id}:/s/1", res())
        }
        repo.delete(a.id)
        assertEquals(listOf(b.id), repo.sites.first().map { it.id })
        assertTrue(db.favoriteDao().observeAll(a.id).first().isEmpty())
        assertNull(db.readHistoryDao().get(a.id, "/s/1"))
        assertNull(cache.get("${a.id}:/s/1"))
        assertEquals(1, db.favoriteDao().observeAll(b.id).first().size)
        assertNotNull(db.readHistoryDao().get(b.id, "/s/1"))
        assertNotNull(cache.get("${b.id}:/s/1"))
    }

    @Test
    fun samePathInTwoSitesDoesNotCollide() = runTest {
        db.favoriteDao().upsert(Favorite("a", "/s/1", Favorite.TYPE_SERIES, "A", 1))
        db.favoriteDao().upsert(Favorite("b", "/s/1", Favorite.TYPE_SERIES, "B", 2))
        db.readHistoryDao().upsert(ReadHistory("a", "/s/1", 1, 1))
        db.readHistoryDao().upsert(ReadHistory("b", "/s/1", 2, 2))
        assertEquals(listOf("A"), db.favoriteDao().observeAll("a").first().map { it.title })
        assertEquals(listOf("B"), db.favoriteDao().observeAll("b").first().map { it.title })
        assertEquals(1, db.readHistoryDao().get("a", "/s/1")?.lastPosition)
        assertEquals(2, db.readHistoryDao().get("b", "/s/1")?.lastPosition)
        assertEquals(2, db.favoriteDao().observeAllRefs().first().size)
    }
}
