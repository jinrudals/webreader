package app.webreader.data.db

import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DaosTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val ctx = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun favoriteToggle() = runTest {
        val dao = db.favoriteDao()
        assertFalse(dao.observeIsFavorite("/ep/1?a=1").first())
        dao.upsert(Favorite("/ep/1?a=1", Favorite.TYPE_EPISODE, "t", 1L))
        assertTrue(dao.observeIsFavorite("/ep/1?a=1").first())
        dao.delete("/ep/1?a=1")
        assertFalse(dao.observeIsFavorite("/ep/1?a=1").first())
    }

    @Test
    fun favoritesKeepBothTypes() = runTest {
        val dao = db.favoriteDao()
        dao.upsert(Favorite("/s/1", Favorite.TYPE_SERIES, "s", 1L))
        dao.upsert(Favorite("/ep/1", Favorite.TYPE_EPISODE, "e", 2L))
        val all = dao.observeAll().first()
        assertEquals(listOf("/ep/1", "/s/1"), all.map { it.key })
        assertEquals(setOf("/s/1", "/ep/1"), dao.allKeys().toSet())
        assertEquals(setOf("SERIES", "EPISODE"), all.map { it.type }.toSet())
    }

    @Test
    fun historyUpsertOverwritesPosition() = runTest {
        val dao = db.readHistoryDao()
        assertNull(dao.get("/ep/1"))
        dao.upsert(ReadHistory("/ep/1", 10, 1L))
        dao.upsert(ReadHistory("/ep/1", 99, 2L))
        assertEquals(99, dao.get("/ep/1")?.lastPosition)
    }

    @Test
    fun recentOrderedByUpdatedAtDesc() = runTest {
        val dao = db.readHistoryDao()
        dao.upsert(ReadHistory("a", 1, 1L))
        dao.upsert(ReadHistory("b", 1, 3L))
        dao.upsert(ReadHistory("c", 1, 2L))
        assertEquals(listOf("b", "c"), dao.observeRecent(2).first().map { it.key })
    }
}
