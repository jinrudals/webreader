package app.webreader.ui

import androidx.room.Room
import app.webreader.data.db.AppDatabase
import app.webreader.data.db.Favorite
import app.webreader.data.db.ReadHistory
import app.webreader.domain.Site
import app.webreader.domain.SiteRegistry
import app.webreader.domain.UrlKeyer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LibraryViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var vm: LibraryViewModel
    private val currentId = MutableStateFlow("s")
    @Volatile private var registry = SiteRegistry(
        listOf(
            Site("s", "S", "https://a.example.com", emptySet(), "", ""),
            Site("t", "T", "https://b.example.org", emptySet(), "", ""),
        ),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        vm = LibraryViewModel(db.favoriteDao(), db.readHistoryDao(), UrlKeyer(), { registry }, currentId)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun urlForUsesCurrentSiteBase() {
        assertEquals("https://a.example.com/series/5", vm.urlFor("/series/5"))
        currentId.value = "t"
        assertEquals("https://b.example.org/series/5", vm.urlFor("/series/5"))
    }

    @Test
    fun libraryFollowsCurrentSite() = runTest {
        db.favoriteDao().upsert(Favorite("s", "/series/1", Favorite.TYPE_SERIES, "S", 1))
        db.favoriteDao().upsert(Favorite("t", "/series/9", Favorite.TYPE_SERIES, "T", 2))
        db.readHistoryDao().upsert(ReadHistory("s", "/ep/1", 10, 100))
        db.readHistoryDao().upsert(ReadHistory("t", "/ep/9", 10, 100))
        assertEquals(listOf("/series/1"), vm.favorites.first().map { it.key })
        assertEquals(listOf("/ep/1"), vm.recent.first().map { it.key })
        currentId.value = "t"
        assertEquals(listOf("/series/9"), vm.favorites.first().map { it.key })
        assertEquals(listOf("/series/9"), vm.favoritesOfType(Favorite.TYPE_SERIES).first().map { it.key })
        assertEquals(listOf("/ep/9"), vm.recent.first().map { it.key })
    }

    @Test
    fun favoritesCanBeFilteredByType() = runTest {
        db.favoriteDao().upsert(Favorite("s", "/series/1", Favorite.TYPE_SERIES, "S", 1))
        db.favoriteDao().upsert(Favorite("s", "/ep/2", Favorite.TYPE_EPISODE, "E", 2))
        assertEquals(2, vm.favorites.first().size)
        assertEquals(listOf("/series/1"), vm.favoritesOfType(Favorite.TYPE_SERIES).first().map { it.key })
        assertEquals(listOf("/ep/2"), vm.favoritesOfType(Favorite.TYPE_EPISODE).first().map { it.key })
    }

    @Test
    fun removeFavoriteDeletes() = runTest {
        db.favoriteDao().upsert(Favorite("s", "/series/1", Favorite.TYPE_SERIES, "S", 1))
        vm.removeFavorite("/series/1").join()
        assertEquals(emptyList<Favorite>(), vm.favorites.first())
    }

    @Test
    fun recentOrderedByUpdatedAtDesc() = runTest {
        db.readHistoryDao().upsert(ReadHistory("s", "/ep/1", 10, 100))
        db.readHistoryDao().upsert(ReadHistory("s", "/ep/2", 20, 300))
        db.readHistoryDao().upsert(ReadHistory("s", "/ep/3", 30, 200))
        assertEquals(listOf("/ep/2", "/ep/3", "/ep/1"), vm.recent.first().map { it.key })
    }
}
