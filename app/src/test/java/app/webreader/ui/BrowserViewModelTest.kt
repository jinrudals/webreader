package app.webreader.ui

import androidx.room.Room
import app.webreader.data.db.AppDatabase
import app.webreader.data.db.Favorite
import app.webreader.domain.PageClassifier
import app.webreader.domain.SiteConfig
import app.webreader.domain.UrlKeyer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BrowserViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var vm: BrowserViewModel
    @Volatile private var config = SiteConfig("https://a.example.com", emptySet())
    private var now = 1000L

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val classifier = PageClassifier("^/series/\\d+", "^/ep/\\d+")
        vm = BrowserViewModel(
            db.favoriteDao(), db.readHistoryDao(), UrlKeyer { config }, { classifier }, { "s" }, { now },
        )
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun otherPagesNotFavoritable() = runTest {
        vm.toggleFavorite("https://a.example.com/about", "About").join()
        assertTrue(db.favoriteDao().observeAll("s").first().map { it.key }.isEmpty())
        assertFalse(vm.isFavorite("https://a.example.com/about").first())
    }

    @Test
    fun toggleStoresHostIndependentKey() = runTest {
        vm.toggleFavorite("https://a.example.com/series/5?b=2&a=1#x", "S").join()
        assertEquals(listOf("/series/5?a=1&b=2"), db.favoriteDao().observeAll("s").first().map { it.key })
    }

    @Test
    fun oldDomainUrlMapsToSameFavorite() = runTest {
        vm.toggleFavorite("https://a.example.com/series/5", "S").join()
        assertTrue(vm.isFavorite("https://b.example.org/series/5").first())
        config = SiteConfig("https://c.example.net", emptySet())
        assertTrue(vm.isFavorite("https://c.example.net/series/5").first())
    }

    @Test
    fun savePositionThenRestore() = runTest {
        val url = "https://a.example.com/ep/3"
        assertNull(vm.restoreTarget(url))
        vm.savePosition(url, 420).join()
        assertEquals(420, vm.restoreTarget("https://b.example.org/ep/3"))
        vm.savePosition("https://a.example.com/about", 99).join()
        assertNull(vm.restoreTarget("https://a.example.com/about"))
    }

    @Test
    fun onPageFinishedKeepsExistingPosition() = runTest {
        val url = "https://a.example.com/ep/3"
        vm.savePosition(url, 300).join()
        now = 2000L
        vm.onPageFinished(url, "T").join()
        val h = db.readHistoryDao().get("s", "/ep/3")!!
        assertEquals(300, h.lastPosition)
        assertEquals(2000L, h.updatedAt)
        vm.onPageFinished("https://a.example.com/about", "A").join()
        assertNull(db.readHistoryDao().get("s", "/about"))
    }

    @Test
    fun togglingTwiceRemovesFavorite() = runTest {
        val url = "https://a.example.com/series/5"
        vm.toggleFavorite(url, "S").join()
        vm.toggleFavorite(url, "S").join()
        assertFalse(vm.isFavorite(url).first())
    }

    @Test
    fun episodeAndSeriesTypesRecorded() = runTest {
        vm.toggleFavorite("https://a.example.com/series/5", "S").join()
        vm.toggleFavorite("https://a.example.com/ep/9", "").join()
        val all = db.favoriteDao().observeAll("s").first().associateBy { it.key }
        assertEquals(Favorite.TYPE_SERIES, all["/series/5"]!!.type)
        assertEquals(Favorite.TYPE_EPISODE, all["/ep/9"]!!.type)
        assertEquals("/ep/9", all["/ep/9"]!!.title)
    }
}
